package top.harcochen.dsh;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteService;

/** Explicit, bounded exports of a readable Session through the native save dialog. */
final class DshConversationExporter {
    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Consumer<String> notify;
    private final Consumer<Exception> failure;

    DshConversationExporter(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Consumer<String> notify,
            Consumer<Exception> failure) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.notify = notify;
        this.failure = failure;
    }

    void export(String session) {
        if (session == null) {
            notify.accept(DshBundle.message("dsh.review.no.session"));
            return;
        }
        long generation = remote.snapshot().generation;
        operations.execute(
                () -> {
                    try {
                        JsonObject history = remote.traceHistory(session, 160);
                        if (DshJson.bool(history, "hasMore", false))
                            throw new IllegalStateException(
                                    DshBundle.message("dsh.session.export.large"));
                        ApplicationManager.getApplication()
                                .invokeLater(() -> chooseDestination(session, generation, history));
                    } catch (Exception error) {
                        failure.accept(error);
                    }
                });
    }

    private boolean readable(String session, long generation) {
        return !project.isDisposed()
                && remote.snapshot().generation == generation
                && remote.access().allows("session/follow", "session", session);
    }

    private void chooseDestination(String session, long generation, JsonObject history) {
        if (!readable(session, generation)) return;
        String title = DshBundle.message("dsh.session.export");
        String[] formats = {"Markdown (.md)", "JSON (.json)"};
        int format =
                DshChoiceDialog.show(
                        project, title, title, Messages.getQuestionIcon(), formats, formats[0]);
        if (format < 0) return;
        String extension = format == 0 ? "md" : "json";
        FileSaverDescriptor descriptor = new FileSaverDescriptor(title, session, extension);
        String name = "dsh-" + session.replaceAll("[^A-Za-z0-9_-]", "-") + "." + extension;
        var saved =
                FileChooserFactory.getInstance()
                        .createSaveFileDialog(descriptor, project)
                        .save((VirtualFile) null, name);
        if (saved == null) return;
        Path target = saved.getFile().toPath();
        operations.execute(
                () -> {
                    try {
                        String content =
                                format == 0
                                        ? markdown(session, history)
                                        : new GsonBuilder()
                                                .setPrettyPrinting()
                                                .create()
                                                .toJson(history);
                        if (!readable(session, generation))
                            throw new IllegalStateException(
                                    DshBundle.message("dsh.remote.access.denied"));
                        write(target, content);
                        notify.accept(DshBundle.message("dsh.session.exported", target));
                    } catch (Exception error) {
                        failure.accept(error);
                    }
                });
    }

    static void write(Path target, String content) throws Exception {
        Path temporary = Files.createTempFile(target.getParent(), ".dsh-export-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String markdown(String session, JsonObject history) {
        StringBuilder text =
                new StringBuilder("# DSH conversation\n\nSession: " + session + "\n\n");
        for (JsonObject entry : DshToolDiff.entries(history)) {
            JsonObject event = DshWorkingDirectory.event(entry);
            JsonElement rawData = event.get("data");
            if (rawData == null || !rawData.isJsonObject()) continue;
            JsonElement rawMessage = rawData.getAsJsonObject().get("message");
            if (rawMessage == null || !rawMessage.isJsonObject()) continue;
            JsonObject message = rawMessage.getAsJsonObject();
            text.append("## ")
                    .append(DshJson.stringOr(message, "role", "message"))
                    .append(" · ")
                    .append(DshJson.longValue(event.get("seq"), 0))
                    .append("\n\n");
            appendText(message.get("content"), text);
            text.append("\n\n");
        }
        return text.toString();
    }

    private static void appendText(JsonElement value, StringBuilder text) {
        if (value == null) return;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
            text.append(value.getAsString());
        else if (value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray()) appendText(item, text);
        else if (value.isJsonObject()) {
            JsonObject block = value.getAsJsonObject();
            String content = DshJson.strictString(block, "text");
            if (content != null) text.append(content).append('\n');
            else
                text.append('[')
                        .append(DshJson.stringOr(block, "type", "attachment"))
                        .append("]\n");
        }
    }
}
