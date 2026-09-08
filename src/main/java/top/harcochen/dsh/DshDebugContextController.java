package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.Icon;

/**
 * Captures a bounded, read-only snapshot of the suspended debugger session for the next prompt,
 * mirroring dsh-ide's {@code dsh.explainDebugState}: call stack, focused-frame locals, and a source
 * excerpt, with sensitive variable names redacted before anything leaves the IDE.
 *
 * <p>Debugger values resolve asynchronously, so the capture runs on the EDT, waits for the
 * callbacks on a bounded latch, and only then attaches the one-shot context chip.
 */
final class DshDebugContextController {
    private static final Logger LOG = Logger.getInstance(DshDebugContextController.class);
    private static final int MAX_STACK_FRAMES = 10;
    private static final int MAX_VARIABLES = 60;
    private static final int MAX_VARIABLE_CHARS = 1_200;
    private static final int SOURCE_CONTEXT_LINES = 12;
    private static final int MAX_SOURCE_LINE_CHARS = 600;
    private static final int MAX_CONTENT_BYTES = 180_000;
    private static final long CAPTURE_TIMEOUT_SECONDS = 6;
    private static final Pattern SENSITIVE_VARIABLE_NAME =
            Pattern.compile(
                    "(?:password|passwd|secret|token|api[-_ ]?key|access[-_ ]?key|private[-_ ]?key"
                            + "|credential|authorization|cookie|session)",
                    Pattern.CASE_INSENSITIVE);

    private final Project project;
    private final Consumer<JsonObject> chipSink;
    private final Consumer<JsonObject> webviewMessage;
    private final Consumer<String> notifier;

    DshDebugContextController(
            Project project,
            Consumer<JsonObject> chipSink,
            Consumer<JsonObject> webviewMessage,
            Consumer<String> notifier) {
        this.project = project;
        this.chipSink = chipSink;
        this.webviewMessage = webviewMessage;
        this.notifier = notifier;
    }

    /** Capture the suspended session and prefill the composer with the explanation prompt. */
    void explainDebugState() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            XDebugSession session =
                                    XDebuggerManager.getInstance(project).getCurrentSession();
                            if (session == null || !session.isSuspended()) {
                                notifier.accept(
                                        DshBundle.message("dsh.debug.no.suspended.session"));
                                return;
                            }
                            Capture capture = new Capture(session);
                            capture.start();
                            java.util.concurrent.CompletableFuture.runAsync(
                                    () -> {
                                        String content = capture.awaitContent();
                                        ApplicationManager.getApplication()
                                                .invokeLater(
                                                        () -> {
                                                            if (content == null) return;
                                                            attach(content);
                                                        });
                                    });
                        });
    }

    private void attach(String content) {
        JsonObject item = new JsonObject();
        item.addProperty("id", java.util.UUID.randomUUID().toString());
        item.addProperty("kind", "debug");
        item.addProperty("label", DshBundle.message("dsh.debug.chip.label"));
        item.addProperty("content", content);
        item.addProperty("byteLength", content.getBytes(StandardCharsets.UTF_8).length);
        item.addProperty(
                "truncated", content.getBytes(StandardCharsets.UTF_8).length >= MAX_CONTENT_BYTES);
        chipSink.accept(item);
        webviewMessage.accept(
                setComposerTextMessage(DshBundle.message("dsh.debug.explain.prompt")));
    }

    private JsonObject setComposerTextMessage(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "setText");
        message.addProperty("text", text);
        return message;
    }

    // ---------------------------------------------------------------------------
    // capture machinery
    // ---------------------------------------------------------------------------

    private final class Capture {
        private final XDebugSession session;
        private final CountDownLatch done = new CountDownLatch(2);
        private final List<String> frameLines = new ArrayList<>();
        private final List<String> variableLines = new ArrayList<>();
        private volatile String selectedFrameLabel;
        private volatile String excerpt;

        Capture(XDebugSession session) {
            this.session = session;
        }

        void start() {
            XStackFrame current = session.getCurrentStackFrame();
            captureFrames();
            if (current != null) {
                captureVariables(current);
                excerpt = sourceExcerpt(current);
            } else {
                done.countDown();
                done.countDown();
            }
        }

        private void captureFrames() {
            XExecutionStack stack = activeStack();
            if (stack == null) {
                frameLines.add("(unavailable)");
                done.countDown();
                return;
            }
            try {
                stack.computeStackFrames(
                        0,
                        new XExecutionStack.XStackFrameContainer() {
                            @Override
                            public void addStackFrames(
                                    java.util.List<? extends XStackFrame> frames, boolean last) {
                                XStackFrame current = session.getCurrentStackFrame();
                                int count = Math.min(frames.size(), MAX_STACK_FRAMES);
                                if (count == 0) {
                                    frameLines.add("(unavailable)");
                                }
                                for (int index = 0; index < count; index++) {
                                    XStackFrame frame = frames.get(index);
                                    String location = locationOf(frame);
                                    String marker = frame == current ? "*" : " ";
                                    frameLines.add(
                                            marker
                                                    + " #"
                                                    + index
                                                    + (location.isEmpty() ? "" : " — " + location));
                                    if (frame == current) {
                                        selectedFrameLabel =
                                                "#"
                                                        + index
                                                        + (location.isEmpty()
                                                                ? ""
                                                                : " " + location);
                                    }
                                }
                                if (last) done.countDown();
                            }

                            @Override
                            public void errorOccurred(String errorMessage) {
                                synchronized (frameLines) {
                                    frameLines.add("(unavailable: " + errorMessage + ")");
                                }
                            }

                            @Override
                            public boolean isObsolete() {
                                return false;
                            }
                        });
            } catch (RuntimeException error) {
                LOG.debug("Unable to read debug stack frames", error);
                frameLines.add("(unavailable: " + error.getMessage() + ")");
                done.countDown();
            }
        }

        private void captureVariables(XStackFrame frame) {
            try {
                frame.computeChildren(new CollectingNode(variableLines, done));
            } catch (RuntimeException error) {
                LOG.debug("Unable to read debug variables", error);
                variableLines.add("(unavailable: " + error.getMessage() + ")");
                done.countDown();
            }
        }

        /** Assemble the snapshot; runs off the EDT after the latch settles. */
        String awaitContent() {
            boolean complete = false;
            try {
                complete = done.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            StringBuilder builder = new StringBuilder();
            builder.append("Debug context (untrusted, read-only IDE snapshot):\n");
            builder.append("Session: ").append(session.getSessionName()).append('\n');
            builder.append("Stop reason: not reported\n");
            builder.append('\n');
            builder.append("Call stack (top first):\n");
            synchronized (frameLines) {
                for (String line : frameLines) builder.append(line).append('\n');
            }
            builder.append('\n');
            builder.append("Focused frame: ")
                    .append(selectedFrameLabel == null ? "(unavailable)" : selectedFrameLabel)
                    .append('\n');
            builder.append('\n');
            builder.append("Focused frame locals and arguments:\n");
            synchronized (variableLines) {
                if (variableLines.isEmpty()) {
                    builder.append("(unavailable)\n");
                } else {
                    for (String line : variableLines) builder.append(line).append('\n');
                }
            }
            builder.append('\n');
            builder.append("Current source excerpt:\n");
            if (excerpt != null) {
                builder.append(excerpt).append('\n');
            } else {
                builder.append("(unavailable)\n");
            }
            if (!complete) {
                builder.append('\n')
                        .append(
                                "Capture notes:\n- capture timed out; some debugger data is missing\n");
            }
            return truncate(builder.toString(), MAX_CONTENT_BYTES);
        }

        private XExecutionStack activeStack() {
            var context = session.getSuspendContext();
            return context == null ? null : context.getActiveExecutionStack();
        }

        private String locationOf(XStackFrame frame) {
            try {
                XSourcePosition position = frame.getSourcePosition();
                if (position == null || position.getFile() == null) return "";
                return position.getFile().getPath() + ":" + (position.getLine() + 1);
            } catch (RuntimeException error) {
                return "";
            }
        }

        private String sourceExcerpt(XStackFrame frame) {
            XSourcePosition position;
            try {
                position = frame.getSourcePosition();
            } catch (RuntimeException error) {
                return null;
            }
            if (position == null || position.getFile() == null) return null;
            VirtualFile file = position.getFile();
            int line = position.getLine();
            return com.intellij.openapi.application.ReadAction.compute(
                    () -> {
                        var document =
                                com.intellij
                                        .openapi
                                        .fileEditor
                                        .FileDocumentManager
                                        .getInstance()
                                        .getDocument(file);
                        if (document == null) return null;
                        StringBuilder builder = new StringBuilder();
                        builder.append("File: ").append(file.getPath()).append('\n');
                        builder.append("Stopped near line ").append(line + 1).append('\n');
                        int first = Math.max(0, line - SOURCE_CONTEXT_LINES);
                        int last =
                                Math.min(document.getLineCount() - 1, line + SOURCE_CONTEXT_LINES);
                        for (int index = first; index <= last; index++) {
                            String text =
                                    document.getText(
                                            com.intellij.openapi.util.TextRange.from(
                                                    document.getLineStartOffset(index),
                                                    document.getLineEndOffset(index)
                                                            - document.getLineStartOffset(index)));
                            if (text.length() > MAX_SOURCE_LINE_CHARS) {
                                text = text.substring(0, MAX_SOURCE_LINE_CHARS) + "…";
                            }
                            builder.append(index == line ? "> " : "  ");
                            builder.append(index + 1).append("  ").append(text).append('\n');
                        }
                        return builder.toString();
                    });
        }
    }

    /**
     * Collects one level of visible variables. Values render through a child node; children are
     * never expanded, matching the bounded snapshot contract.
     */
    private static final class CollectingNode implements XCompositeNode, XValueNode {
        private final List<String> lines;
        private final CountDownLatch done;
        private int collected;

        CollectingNode(List<String> lines, CountDownLatch done) {
            this.lines = lines;
            this.done = done;
        }

        @Override
        public void setAlreadySorted(boolean alreadySorted) {
            // Ordering does not matter for the bounded snapshot.
        }

        @Override
        public void addChildren(XValueChildrenList children, boolean last) {
            int remaining = MAX_VARIABLES - collected;
            int count = Math.min(children.size(), remaining);
            for (int index = 0; index < count; index++) {
                collect(children.getName(index), children.getValue(index));
            }
            collected += count;
            if (last || collected >= MAX_VARIABLES) {
                done.countDown();
            }
        }

        private void collect(String name, XValue value) {
            if (value == null) return;
            if (name != null && SENSITIVE_VARIABLE_NAME.matcher(name).find()) {
                synchronized (lines) {
                    lines.add("  " + name + " = <redacted>");
                }
                return;
            }
            value.computePresentation(
                    new XValueNode() {
                        @Override
                        public void setPresentation(
                                Icon icon, String type, String value, boolean hasChildren) {
                            recordValue(name, type, value);
                        }

                        @Override
                        public void setPresentation(
                                Icon icon,
                                com.intellij.xdebugger.frame.presentation.XValuePresentation
                                        presentation,
                                boolean hasChildren) {
                            presentation.renderValue(
                                    new com.intellij.xdebugger.frame.presentation.XValuePresentation
                                            .XValueTextRenderer() {
                                        @Override
                                        public void renderValue(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderStringValue(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderNumericValue(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderKeywordValue(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderValue(
                                                String rendered,
                                                com.intellij.openapi.editor.colors.TextAttributesKey
                                                        attributes) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderStringValue(
                                                String rendered, String extraClass, int maxLength) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderComment(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderSpecialSymbol(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }

                                        @Override
                                        public void renderError(String rendered) {
                                            recordValue(name, presentation.getType(), rendered);
                                        }
                                    });
                        }

                        @Override
                        public void setFullValueEvaluator(
                                com.intellij.xdebugger.frame.XFullValueEvaluator evaluator) {
                            // The bounded snapshot never fetches full values.
                        }

                        @Override
                        public boolean isObsolete() {
                            return false;
                        }
                    },
                    XValuePlace.TREE);
        }

        private void recordValue(String name, String type, String value) {
            String text = value == null ? "" : value;
            if (text.length() > MAX_VARIABLE_CHARS)
                text = text.substring(0, MAX_VARIABLE_CHARS) + "…";
            synchronized (lines) {
                lines.add(
                        "  "
                                + name
                                + (type == null || type.isBlank() ? " = " : ": " + type + " = ")
                                + text);
            }
        }

        @Override
        public void tooManyChildren(int remaining) {
            synchronized (lines) {
                lines.add("  … " + remaining + " more (not shown)");
            }
        }

        @Override
        public void setErrorMessage(String errorMessage) {
            synchronized (lines) {
                lines.add("  (unavailable: " + errorMessage + ")");
            }
            done.countDown();
        }

        @Override
        public void setErrorMessage(
                String errorMessage, com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink link) {
            setErrorMessage(errorMessage);
        }

        @Override
        public void setMessage(
                String text,
                Icon icon,
                com.intellij.ui.SimpleTextAttributes attributes,
                com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink link) {
            synchronized (lines) {
                lines.add("  " + text);
            }
            done.countDown();
        }

        @Override
        public void setPresentation(Icon icon, String type, String value, boolean hasChildren) {
            // Top-level node is never presented directly.
        }

        @Override
        public void setPresentation(
                Icon icon,
                com.intellij.xdebugger.frame.presentation.XValuePresentation presentation,
                boolean hasChildren) {
            // Top-level node is never presented directly.
        }

        @Override
        public void setFullValueEvaluator(
                com.intellij.xdebugger.frame.XFullValueEvaluator evaluator) {
            // Top-level node is never presented directly.
        }

        @Override
        public boolean isObsolete() {
            return false;
        }
    }

    private static String truncate(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int width = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + width > maxBytes) break;
            builder.appendCodePoint(codePoint);
            bytes += width;
            offset += Character.charCount(codePoint);
        }
        return builder.toString();
    }
}
