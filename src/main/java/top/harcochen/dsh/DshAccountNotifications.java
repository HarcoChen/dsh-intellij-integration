package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;

/** Generic, ordered account notices with credential and connection lifetime fences. */
final class DshAccountNotifications {
    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Consumer<String> errorSink;
    private final AtomicBoolean draining = new AtomicBoolean();

    DshAccountNotifications(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Consumer<String> errorSink) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.errorSink = errorSink;
    }

    /** False means the Runtime needs the legacy bonus API. Invoked off the EDT. */
    boolean show(JsonObject client) {
        if (!draining.compareAndSet(false, true)) return true;
        long generation = remote.snapshot().generation;
        AtomicLong identity = new AtomicLong();
        AtomicReference<NoticeDialog> active = new AtomicReference<>();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Runnable invalidate =
                () -> {
                    identity.incrementAndGet();
                    ApplicationManager.getApplication()
                            .invokeLater(
                                    () -> {
                                        NoticeDialog dialog = active.get();
                                        if (dialog != null) dialog.expire();
                                    });
                };
        try (AutoCloseable watch =
                remote.watchFeature(
                        "account/watch",
                        JsonObject::new,
                        frame -> {
                            if ("credentials-changed".equals(DshJson.string(frame, "type")))
                                invalidate.run();
                            ready.complete(null);
                        },
                        failure -> {
                            invalidate.run();
                            ready.completeExceptionally(
                                    new IllegalStateException("Account stream unavailable"));
                        })) {
            ready.get(10, TimeUnit.SECONDS);
            long epoch = identity.get();
            JsonElement raw = remote.accountNotifications(client);
            if (raw == null || raw.isJsonNull()) return true;
            if (!raw.isJsonObject())
                throw new IllegalArgumentException("Invalid account notifications");
            JsonObject batch = raw.getAsJsonObject();
            double delay =
                    batch.has("nextPollAfterSeconds")
                                    && batch.get("nextPollAfterSeconds").isJsonPrimitive()
                                    && batch.getAsJsonPrimitive("nextPollAfterSeconds").isNumber()
                            ? batch.get("nextPollAfterSeconds").getAsDouble()
                            : 0;
            if (!Double.isFinite(delay)
                    || delay <= 0
                    || delay > 2147483.647
                    || !batch.has("notifications")
                    || !batch.get("notifications").isJsonArray()
                    || batch.getAsJsonArray("notifications").size() > 1000)
                throw new IllegalArgumentException("Invalid account notification batch");
            Set<String> seen = new HashSet<>();
            for (JsonElement value : batch.getAsJsonArray("notifications")) {
                JsonObject notice = notice(value);
                if (notice == null || !seen.add(DshJson.string(notice, "id"))) continue;
                if (identity.get() != epoch || remote.snapshot().generation != generation) break;
                NoticeDialog shown =
                        DshPluginManagementController.ui(
                                () -> {
                                    if (project.isDisposed()
                                            || identity.get() != epoch
                                            || remote.snapshot().generation != generation)
                                        return null;
                                    NoticeDialog dialog =
                                            new NoticeDialog(
                                                    notice, client, generation, identity, epoch);
                                    active.set(dialog);
                                    dialog.show();
                                    active.compareAndSet(dialog, null);
                                    return dialog;
                                });
                if (shown != null) shown.reporting.get(10, TimeUnit.SECONDS);
                if (shown != null && shown.accountRequested) break;
            }
            return true;
        } catch (DshRemoteException missing) {
            if (missing.isCapabilityMissing()) return false;
            errorSink.accept(DshJson.message(missing));
            return true;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            errorSink.accept(DshJson.message(error));
            return true;
        } finally {
            draining.set(false);
        }
    }

    private static JsonObject notice(JsonElement value) {
        if (!value.isJsonObject()) return null;
        JsonObject row = value.getAsJsonObject();
        for (String key : new String[] {"id", "createdAt", "title", "body"})
            if (DshJson.strictString(row, key) == null
                    || DshJson.string(row, key).length() > 100000) return null;
        if (DshJson.string(row, "id").isBlank()
                || DshJson.string(row, "id").length() > 4096
                || !row.has("displayUntil")
                || !row.has("actions")
                || !row.get("actions").isJsonArray()
                || row.getAsJsonArray("actions").size() > 20) return null;
        try {
            Instant.parse(DshJson.string(row, "createdAt"));
            if (!row.get("displayUntil").isJsonNull()
                    && !Instant.parse(DshJson.strictString(row, "displayUntil"))
                            .isAfter(Instant.now())) return null;
        } catch (RuntimeException invalid) {
            return null;
        }
        for (JsonElement raw : row.getAsJsonArray("actions")) {
            if (!raw.isJsonObject()) return null;
            JsonObject action = raw.getAsJsonObject();
            if (DshJson.strictString(action, "label") == null
                    || !Set.of("primary", "secondary")
                            .contains(DshJson.stringOr(action, "style", ""))
                    || !action.has("target")
                    || !action.get("target").isJsonObject()) return null;
            JsonObject target = action.getAsJsonObject("target");
            String kind = DshJson.stringOr(target, "type", "");
            if (!"dismiss".equals(kind)
                    && !("app_route".equals(kind)
                            && "account".equals(DshJson.string(target, "name")))) return null;
        }
        return row.deepCopy();
    }

    private final class NoticeDialog extends DialogWrapper {
        private final JsonObject notice;
        private final JsonObject client;
        private final long generation;
        private final AtomicLong identity;
        private final long epoch;
        private final Set<String> reports = new HashSet<>();
        private volatile boolean expired;
        private boolean disposed;
        private javax.swing.Timer expiry;
        private CompletableFuture<Void> reporting = CompletableFuture.completedFuture(null);
        private boolean accountRequested;

        NoticeDialog(
                JsonObject notice,
                JsonObject client,
                long generation,
                AtomicLong identity,
                long epoch) {
            super(project);
            this.notice = notice;
            this.client = client;
            this.generation = generation;
            this.identity = identity;
            this.epoch = epoch;
            setTitle(DshJson.string(notice, "title"));
            setCancelButtonText(DshBundle.message("dsh.diff.approval.close"));
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JTextArea text = new JTextArea(DshJson.string(notice, "body"), 10, 60);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.addHierarchyListener(
                    event -> {
                        if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED)
                                        != 0
                                && text.isShowing()
                                && !disposed
                                && current()) report("displayed");
                    });
            return new JScrollPane(text);
        }

        @Override
        protected Action[] createActions() {
            java.util.List<Action> actions = new java.util.ArrayList<>();
            for (JsonElement raw : notice.getAsJsonArray("actions")) {
                JsonObject action = raw.getAsJsonObject();
                actions.add(
                        new DialogWrapperAction(DshJson.string(action, "label")) {
                            @Override
                            protected void doAction(java.awt.event.ActionEvent event) {
                                if (!current()) {
                                    expire();
                                    return;
                                }
                                if ("dismiss"
                                        .equals(
                                                DshJson.string(
                                                        action.getAsJsonObject("target"), "type")))
                                    doCancelAction();
                                else {
                                    report("cta_clicked");
                                    accountRequested = true;
                                    close(OK_EXIT_CODE);
                                }
                            }
                        });
            }
            actions.add(getCancelAction());
            return actions.toArray(Action[]::new);
        }

        @Override
        public void show() {
            expiry =
                    new javax.swing.Timer(
                            1000,
                            ignored -> {
                                if (!current()
                                        || (!notice.get("displayUntil").isJsonNull()
                                                && !Instant.parse(
                                                                DshJson.string(
                                                                        notice, "displayUntil"))
                                                        .isAfter(Instant.now()))) expire();
                            });
            expiry.start();
            super.show();
        }

        private boolean current() {
            return !expired
                    && !project.isDisposed()
                    && identity.get() == epoch
                    && (notice.get("displayUntil").isJsonNull()
                            || Instant.parse(DshJson.string(notice, "displayUntil"))
                                    .isAfter(Instant.now()))
                    && remote.snapshot().generation == generation;
        }

        private void report(String event) {
            if (!current() || !reports.add(event)) return;
            reporting =
                    reporting.thenRunAsync(
                            () -> {
                                if (!current()) return;
                                try {
                                    remote.reportAccountNotification(
                                            DshJson.string(notice, "id"), event, client);
                                } catch (RuntimeException error) {
                                    errorSink.accept(DshJson.message(error));
                                }
                            },
                            operations);
        }

        void expire() {
            expired = true;
            close(CANCEL_EXIT_CODE);
        }

        @Override
        public void doCancelAction() {
            if (!expired) report("dismissed");
            super.doCancelAction();
        }

        @Override
        protected void dispose() {
            disposed = true;
            if (expiry != null) expiry.stop();
            super.dispose();
        }
    }
}
