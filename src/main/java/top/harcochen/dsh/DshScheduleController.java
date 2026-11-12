package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;

/** Host-wide task management with legacy reminder compatibility. */
final class DshScheduleController implements AutoCloseable {
    private final Project project;
    private final DshRemoteService remote;
    private final DshRuntimeService runtime;
    private final ExecutorService operations;
    private final Supplier<String> sessionId;
    private final Function<String, DshRemoteState.ProjectionCell> scheduleCell;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final Runnable stateChanged;
    private volatile boolean closed;

    private record ScheduleSnapshot(
            String session,
            JsonArray schedules,
            DshRemoteState.ProjectionCell cell,
            long generation) {}

    private volatile ScheduleSnapshot currentSchedules;
    private volatile String unavailableSession;
    private final java.util.concurrent.atomic.AtomicLong refreshGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    JsonObject availability(String selected) {
        if (selected == null || !selected.equals(unavailableSession)) return null;
        JsonObject value = new JsonObject();
        value.addProperty("status", "unavailable");
        value.add("records", new JsonArray());
        return value;
    }

    DshScheduleController(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            ExecutorService operations,
            Supplier<String> sessionId,
            Function<String, DshRemoteState.ProjectionCell> scheduleCell,
            Consumer<String> notifier,
            Consumer<String> errorSink,
            Runnable stateChanged) {
        this.project = project;
        this.runtime = runtime;
        this.remote = remote;
        this.operations = operations;
        this.sessionId = sessionId;
        this.scheduleCell = scheduleCell;
        this.notifier = notifier;
        this.errorSink = errorSink;
        this.stateChanged = stateChanged;
    }

    void refreshCurrent(String selected) {
        if (closed || selected == null || selected.isBlank()) return;
        long token = refreshGeneration.incrementAndGet();
        long connectionGeneration = remote.snapshot().generation;
        unavailableSession = null;
        DshRemoteState.ProjectionCell cell = scheduleCell.apply(selected);
        DshRemoteState.ProjectionCell snapshot =
                cell == null
                        ? null
                        : new DshRemoteState.ProjectionCell(cell.value().deepCopy(), cell.seq());
        operations.execute(
                () -> {
                    try {
                        JsonElement value = remote.listSchedules(selected);
                        if (!value.isJsonArray()
                                || !selected.equals(sessionId.get())
                                || token != refreshGeneration.get()
                                || remote.snapshot().generation != connectionGeneration) return;
                        JsonArray normalized = DshSessionStateStore.schedule(value);
                        if (normalized == null)
                            throw new IllegalStateException("Invalid Schedule list");
                        currentSchedules =
                                new ScheduleSnapshot(
                                        selected, normalized, snapshot, connectionGeneration);
                        stateChanged.run();
                    } catch (DshRemoteException missing) {
                        if (missing.isCapabilityMissing() && token == refreshGeneration.get()) {
                            unavailableSession = selected;
                            currentSchedules = null;
                            stateChanged.run();
                        } else errorSink.accept(DshJson.message(missing));
                    } catch (Exception error) {
                        errorSink.accept(DshJson.message(error));
                    }
                });
    }

    JsonArray current(String selected, DshRemoteState.ProjectionCell cell) {
        ScheduleSnapshot cached = currentSchedules;
        return selected != null
                        && cached != null
                        && selected.equals(cached.session())
                        && cached.generation() == remote.snapshot().generation
                        && Objects.equals(cell, cached.cell())
                ? cached.schedules().deepCopy()
                : null;
    }

    void manage() {
        if (closed) return;
        operations.execute(
                () -> {
                    try {
                        runtime.startAsync().join();
                        long generation = remote.snapshot().generation;
                        JsonElement value;
                        try {
                            value = remote.scheduleCatalog();
                        } catch (DshRemoteException missing) {
                            if (!"http-404".equals(missing.code()) || sessionId.get() == null) {
                                throw missing;
                            }
                            value = remote.listSchedules(sessionId.get());
                        }
                        if (!value.isJsonArray()) {
                            throw new IllegalStateException(
                                    "Invalid Schedule catalog returned by Runtime");
                        }
                        List<JsonObject> records = new ArrayList<>();
                        for (JsonElement candidate : value.getAsJsonArray()) {
                            if (!candidate.isJsonObject()) continue;
                            JsonObject record = candidate.getAsJsonObject();
                            if (DshJson.string(record, "id") == null) continue;
                            JsonObject copy = record.deepCopy();
                            if (!copy.has("target")
                                    && !copy.has("sessionId")
                                    && sessionId.get() != null)
                                copy.addProperty("sessionId", sessionId.get());
                            records.add(copy);
                        }
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!closed
                                                    && !project.isDisposed()
                                                    && remote.snapshot().generation == generation)
                                                chooseRecord(records, generation);
                                        });
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void chooseRecord(List<JsonObject> records, long generation) {
        if (closed || project.isDisposed() || remote.snapshot().generation != generation) return;
        List<String> labels = new ArrayList<>();
        labels.add(DshBundle.message("dsh.schedule.create"));
        for (JsonObject record : records) {
            String title = DshJson.stringOr(record, "title", DshJson.stringOr(record, "id", ""));
            String owner = targetLabel(record);
            String status = DshJson.stringOr(record, "status", "active");
            labels.add(title + "  ·  " + status + "  ·  " + owner);
        }
        int selected =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.schedule.choose"),
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected == 0) create(generation);
        else if (selected > 0) {
            JsonObject record = records.get(selected - 1);
            if (record.has("target")) chooseModernAction(record, generation);
            else chooseAction(record);
        }
    }

    private void chooseModernAction(JsonObject record, long generation) {
        String[] keys = {
            "history",
            "edit.title",
            "edit.prompt",
            "edit.rule",
            "target",
            "pause.resume",
            "run",
            "delete"
        };
        String[] labels =
                java.util.Arrays.stream(keys)
                        .map(key -> DshBundle.message("dsh.schedule." + key))
                        .toArray(String[]::new);
        int choice =
                DshChoiceDialog.show(
                        project,
                        DshJson.stringOr(record, "title", "")
                                + "\n"
                                + DshJson.stringOr(record, "prompt", "")
                                + "\n"
                                + DshJson.stringOr(record, "status", "")
                                + " · "
                                + targetLabel(record),
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        labels,
                        labels[0]);
        String id = DshJson.string(record, "id");
        if (choice < 0) return;
        if (choice == 0) {
            operations.execute(() -> showHistory(null, id));
            return;
        }
        JsonObject patch = new JsonObject();
        if (choice == 1 || choice == 2) {
            String field = choice == 1 ? "title" : "prompt";
            String text =
                    multiline("dsh.schedule.edit." + field, DshJson.stringOr(record, field, ""));
            if (text == null || text.isBlank()) return;
            patch.addProperty(field, text.strip());
        } else if (choice == 3) {
            JsonObject timing = chooseRule();
            if (timing == null) return;
            patch.add("timing", timing);
        } else if (choice == 4) {
            JsonObject target = chooseTarget();
            if (target == null) return;
            patch.add("target", target);
        } else if (choice == 5) {
            patch.addProperty("active", !"active".equals(DshJson.string(record, "status")));
        }
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        String action = choice == 6 ? "run" : "edit";
        if (choice == 6 || choice == 7) {
            if (Messages.showYesNoDialog(
                            project,
                            DshBundle.message(
                                    choice == 6
                                            ? "dsh.schedule.run.confirm"
                                            : "dsh.schedule.delete.confirm"),
                            DshBundle.message("dsh.schedule.title"),
                            Messages.getWarningIcon())
                    != Messages.YES) return;
        }
        if (choice != 6) {
            request.addProperty("action", choice == 7 ? "delete" : "update");
            request.add(
                    "expectedUpdatedAt",
                    record.has("updatedAt")
                            ? record.get("updatedAt")
                            : com.google.gson.JsonNull.INSTANCE);
            if (choice != 7) request.add("patch", patch);
        }
        modernMutation(action, request, generation);
    }

    private void create(long generation) {
        String title = input("dsh.schedule.edit.title", "");
        if (title == null) return;
        String prompt = multiline("dsh.schedule.edit.prompt", "");
        if (prompt == null || prompt.isBlank()) return;
        JsonObject timing = chooseRule();
        if (timing == null) return;
        JsonObject target = chooseTarget();
        if (target == null) return;
        JsonObject request = new JsonObject();
        request.addProperty("title", title);
        request.addProperty("prompt", prompt.strip());
        request.add("timing", timing);
        request.add("target", target);
        if (Messages.showYesNoDialog(
                        project,
                        title + "\n" + prompt + "\n" + timing + "\n" + targetLabel(request),
                        DshBundle.message("dsh.schedule.create"),
                        Messages.getQuestionIcon())
                == Messages.YES) modernMutation("create", request, generation);
    }

    private JsonObject chooseTarget() {
        String[] kinds = {"session", "new_once", "new"};
        String[] labels =
                java.util.Arrays.stream(kinds)
                        .map(kind -> DshBundle.message("dsh.schedule.target." + kind))
                        .toArray(String[]::new);
        int selected =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.schedule.target"),
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        labels,
                        labels[0]);
        if (selected < 0) return null;
        JsonObject target = new JsonObject();
        target.addProperty("kind", kinds[selected]);
        if (selected == 0) {
            String session = sessionId.get();
            if (session == null) {
                notifier.accept(DshBundle.message("dsh.review.no.session"));
                return null;
            }
            target.addProperty("sessionId", session);
        }
        return target;
    }

    private static String targetLabel(JsonObject record) {
        JsonElement raw = record.get("target");
        if (raw == null || !raw.isJsonObject()) return DshJson.stringOr(record, "sessionId", "");
        JsonObject target = raw.getAsJsonObject();
        String kind = DshJson.stringOr(target, "kind", "");
        String label =
                switch (kind) {
                    case "session", "new_once", "new" ->
                            DshBundle.message("dsh.schedule.target." + kind);
                    default -> kind;
                };
        return label + " " + DshJson.stringOr(target, "sessionId", "");
    }

    private void modernMutation(String action, JsonObject request, long generation) {
        if (closed) return;
        operations.execute(
                () -> {
                    boolean submitted = false;
                    boolean knownRefusal = false;
                    try {
                        if (closed) return;
                        if (remote.snapshot().generation != generation)
                            throw new IllegalStateException(
                                    DshBundle.message("dsh.runtime.changed"));
                        submitted = true;
                        JsonElement raw = remote.scheduleRequest(action, request);
                        if (!raw.isJsonObject())
                            throw new IllegalStateException("Invalid Schedule result");
                        JsonObject result = raw.getAsJsonObject();
                        if (result.has("code")) {
                            knownRefusal = true;
                            throw new IllegalStateException(
                                    DshBundle.message(
                                            "dsh.schedule.refused",
                                            DshJson.string(result, "code")));
                        }
                        if (remote.snapshot().generation != generation) return;
                        stateChanged.run();
                        refreshCurrent(sessionId.get());
                        manage();
                    } catch (Exception error) {
                        boolean unknown =
                                submitted
                                        && !knownRefusal
                                        && (action.equals("create") || action.equals("run"))
                                        && (!(error instanceof DshRemoteException remoteError)
                                                || remoteError.layer()
                                                        == DshRemoteException.Layer.CARRIER
                                                || remoteError.layer()
                                                        == DshRemoteException.Layer.PROTOCOL
                                                || remoteError.httpStatus() >= 500);
                        report(
                                unknown
                                        ? new IllegalStateException(
                                                DshBundle.message("dsh.schedule.unknown")
                                                        + "\n"
                                                        + DshJson.message(error))
                                        : error);
                    }
                });
    }

    private String multiline(String key, String initial) {
        javax.swing.JTextArea text = new javax.swing.JTextArea(initial, 10, 65);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        com.intellij.openapi.ui.DialogWrapper dialog =
                new com.intellij.openapi.ui.DialogWrapper(project) {
                    {
                        setTitle(DshBundle.message(key));
                        init();
                    }

                    @Override
                    protected javax.swing.JComponent createCenterPanel() {
                        return new javax.swing.JScrollPane(text);
                    }

                    @Override
                    public javax.swing.JComponent getPreferredFocusedComponent() {
                        return text;
                    }
                };
        return dialog.showAndGet() ? text.getText() : null;
    }

    private void chooseAction(JsonObject record) {
        String owner = DshJson.string(record, "sessionId");
        String id = DshJson.string(record, "id");
        boolean editable =
                owner != null
                        && owner.equals(sessionId.get())
                        && !"inactive".equals(DshJson.string(record, "status"));
        String[] actions =
                editable
                        ? new String[] {
                            DshBundle.message("dsh.schedule.history"),
                            DshBundle.message("dsh.schedule.edit.title"),
                            DshBundle.message("dsh.schedule.edit.prompt"),
                            DshBundle.message("dsh.schedule.edit.rule"),
                            DshBundle.message("dsh.schedule.delete")
                        }
                        : new String[] {DshBundle.message("dsh.schedule.history")};
        String prompt =
                DshJson.stringOr(record, "prompt", "")
                        + "\n"
                        + DshJson.stringOr(record, "kind", "")
                        + "  ·  "
                        + DshJson.stringOr(record, "scheduledAt", "");
        int selected =
                DshChoiceDialog.show(
                        project,
                        prompt,
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        actions,
                        actions[0]);
        if (selected < 0) return;
        if (selected == 0) {
            operations.execute(() -> showHistory(owner, id));
        } else if (selected == 1) {
            String title =
                    Messages.showInputDialog(
                            project,
                            DshBundle.message("dsh.schedule.edit.title"),
                            DshBundle.message("dsh.schedule.title"),
                            Messages.getQuestionIcon(),
                            DshJson.stringOr(record, "title", ""),
                            null);
            if (title != null && !title.isBlank()) update(record, "title", title.trim(), null);
        } else if (selected == 2) {
            String text =
                    Messages.showInputDialog(
                            project,
                            DshBundle.message("dsh.schedule.edit.prompt"),
                            DshBundle.message("dsh.schedule.title"),
                            Messages.getQuestionIcon(),
                            DshJson.stringOr(record, "prompt", ""),
                            null);
            if (text != null && !text.isBlank()) update(record, "prompt", text.trim(), null);
        } else if (selected == 3) {
            JsonObject change = chooseRule();
            if (change != null) update(record, null, null, change);
        } else if (selected == 4) {
            int confirmed =
                    Messages.showYesNoDialog(
                            project,
                            DshBundle.message("dsh.schedule.delete.confirm"),
                            DshBundle.message("dsh.schedule.title"),
                            Messages.getWarningIcon());
            if (confirmed == Messages.YES) {
                operations.execute(
                        () -> {
                            try {
                                if (closed) return;
                                JsonElement result = remote.deleteSchedule(owner, id);
                                if (!result.isJsonObject()
                                        || !id.equals(
                                                DshJson.string(result.getAsJsonObject(), "id"))) {
                                    throw new IllegalStateException(
                                            "Invalid Schedule delete result");
                                }
                                manage();
                                refreshCurrent(sessionId.get());
                            } catch (Exception error) {
                                report(error);
                            }
                        });
            }
        }
    }

    private JsonObject chooseRule() {
        String[] kinds = {"at", "after", "every", "daily", "weekly", "cron", "rrule"};
        int selected =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.schedule.rule.choose"),
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        kinds,
                        kinds[0]);
        if (selected < 0) return null;
        String kind = kinds[selected];
        JsonObject change = new JsonObject();
        change.addProperty("kind", kind);
        if ("rrule".equals(kind)) {
            String rule =
                    multiline(
                            "dsh.schedule.rule.rrule",
                            "DTSTART;TZID="
                                    + java.time.ZoneId.systemDefault().getId()
                                    + ":"
                                    + java.time.LocalDate.now()
                                            .plusDays(1)
                                            .format(
                                                    java.time.format.DateTimeFormatter
                                                            .BASIC_ISO_DATE)
                                    + "T090000\nRRULE:FREQ=DAILY");
            if (rule == null || rule.isBlank()) return null;
            change.addProperty("rrule", rule.strip());
        } else if ("after".equals(kind)) {
            String seconds = input("dsh.schedule.rule.after", "3600");
            if (seconds == null) return null;
            try {
                long value = Long.parseLong(seconds);
                if (value <= 0) throw new NumberFormatException();
                change.addProperty("after_seconds", value);
            } catch (NumberFormatException invalid) {
                notifier.accept(DshBundle.message("dsh.schedule.rule.invalid"));
                return null;
            }
        } else if ("at".equals(kind)) {
            String value = input("dsh.schedule.rule.at", "");
            if (value == null) return null;
            change.addProperty("at", value);
        } else if ("every".equals(kind)) {
            String value = input("dsh.schedule.rule.every", "60");
            if (value == null) return null;
            try {
                long seconds = Long.parseLong(value);
                if (seconds < 60) throw new NumberFormatException();
                change.addProperty("every_seconds", seconds);
            } catch (NumberFormatException invalid) {
                notifier.accept(DshBundle.message("dsh.schedule.rule.invalid"));
                return null;
            }
        } else if ("daily".equals(kind) || "weekly".equals(kind)) {
            String time = input("dsh.schedule.rule.time", "09:00:00");
            String zone = time == null ? null : input("dsh.schedule.rule.zone", "Asia/Shanghai");
            if (time == null || zone == null) return null;
            JsonObject value = new JsonObject();
            value.addProperty("time", time);
            value.addProperty("time_zone", zone);
            if ("weekly".equals(kind)) {
                String days = input("dsh.schedule.rule.weekdays", "1,2,3,4,5");
                if (days == null) return null;
                JsonArray weekdays = new JsonArray();
                try {
                    java.util.TreeSet<Integer> parsed = new java.util.TreeSet<>();
                    for (String day : days.split(",")) {
                        int weekday = Integer.parseInt(day.trim());
                        if (weekday < 1 || weekday > 7 || !parsed.add(weekday)) {
                            throw new NumberFormatException("Invalid weekday");
                        }
                    }
                    for (int weekday : parsed) weekdays.add(weekday);
                } catch (NumberFormatException invalid) {
                    notifier.accept(DshBundle.message("dsh.schedule.rule.invalid"));
                    return null;
                }
                value.add("weekdays", weekdays);
            }
            change.add(kind, value);
        } else {
            String expression = input("dsh.schedule.rule.cron", "0 9 * * *");
            String zone =
                    expression == null ? null : input("dsh.schedule.rule.zone", "Asia/Shanghai");
            if (expression == null || zone == null) return null;
            JsonObject value = new JsonObject();
            value.addProperty("expression", expression);
            value.addProperty("time_zone", zone);
            change.add("cron", value);
        }
        return change;
    }

    private String input(String key, String initial) {
        String result =
                Messages.showInputDialog(
                        project,
                        DshBundle.message(key),
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        initial,
                        null);
        return result == null || result.isBlank() ? null : result.trim();
    }

    private void update(JsonObject record, String field, String value, JsonObject change) {
        JsonObject expected = record.deepCopy();
        expected.remove("sessionId");
        expected.remove("status");
        expected.remove("lastDelivery");
        if (!expected.has("title")) expected.addProperty("title", "");
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", DshJson.string(record, "sessionId"));
        request.addProperty("id", DshJson.string(record, "id"));
        request.add("expected", expected);
        if (field != null) request.addProperty(field, value);
        if (change != null) request.add("change", change);
        operations.execute(
                () -> {
                    try {
                        if (closed) return;
                        JsonElement result = remote.updateSchedule(request);
                        if (!result.isJsonObject()
                                || !DshJson.bool(result.getAsJsonObject(), "updated", false)) {
                            throw new IllegalStateException(
                                    "Schedule was not updated; refresh the catalog and try again");
                        }
                        manage();
                        refreshCurrent(sessionId.get());
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void showHistory(String owner, String id) {
        long generation = remote.snapshot().generation;
        try {
            JsonElement value = remote.scheduleHistory(owner, id, 50, null);
            String detail =
                    value.isJsonObject()
                            ? value.getAsJsonObject().toString()
                            : DshBundle.message("dsh.schedule.history.empty");
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                if (!project.isDisposed()
                                        && remote.snapshot().generation == generation)
                                    DshTextDialog.show(
                                            project,
                                            DshBundle.message("dsh.schedule.history"),
                                            detail);
                            });
        } catch (Exception error) {
            report(error);
        }
    }

    private void report(Exception error) {
        errorSink.accept(DshJson.message(error));
        notifier.accept(DshBundle.message("dsh.schedule.failed", DshJson.message(error)));
    }

    @Override
    public void close() {
        closed = true;
        refreshGeneration.incrementAndGet();
        currentSchedules = null;
    }
}
