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

/** RC.2 Schedule catalog and current-session reminder management. */
final class DshScheduleController {
    private final Project project;
    private final DshRemoteService remote;
    private final DshRuntimeService runtime;
    private final ExecutorService operations;
    private final Supplier<String> sessionId;
    private final Function<String, DshRemoteState.ProjectionCell> scheduleCell;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final Runnable stateChanged;

    private record ScheduleSnapshot(
            String session, JsonArray schedules, DshRemoteState.ProjectionCell cell) {}

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
        if (selected == null || selected.isBlank()) return;
        long token = refreshGeneration.incrementAndGet();
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
                                || token != refreshGeneration.get()) return;
                        currentSchedules =
                                new ScheduleSnapshot(
                                        selected, value.getAsJsonArray().deepCopy(), snapshot);
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
                        && Objects.equals(cell, cached.cell())
                ? cached.schedules().deepCopy()
                : null;
    }

    void manage() {
        operations.execute(
                () -> {
                    try {
                        runtime.startAsync().join();
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
                            if (!copy.has("sessionId") && sessionId.get() != null)
                                copy.addProperty("sessionId", sessionId.get());
                            records.add(copy);
                        }
                        ApplicationManager.getApplication()
                                .invokeLater(() -> chooseRecord(records));
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void chooseRecord(List<JsonObject> records) {
        if (records.isEmpty()) {
            notifier.accept(DshBundle.message("dsh.schedule.empty"));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (JsonObject record : records) {
            String title = DshJson.stringOr(record, "title", DshJson.stringOr(record, "id", ""));
            String owner = DshJson.stringOr(record, "sessionId", "");
            String status = DshJson.stringOr(record, "status", "active");
            labels.add(title + "  ·  " + status + "  ·  " + owner);
        }
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.schedule.choose"),
                        DshBundle.message("dsh.schedule.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected >= 0) chooseAction(records.get(selected));
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
                Messages.showChooseDialog(
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
        String[] kinds = {"at", "every", "daily", "weekly", "cron"};
        int selected =
                Messages.showChooseDialog(
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
        if ("at".equals(kind)) {
            String value = input("dsh.schedule.rule.at", "");
            if (value == null) return null;
            change.addProperty("at", value);
        } else if ("every".equals(kind)) {
            String value = input("dsh.schedule.rule.every", "60");
            if (value == null) return null;
            try {
                change.addProperty("every_seconds", Long.parseLong(value));
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
        try {
            JsonElement value = remote.scheduleHistory(owner, id, 50, null);
            String detail =
                    value.isJsonObject()
                            ? value.getAsJsonObject().toString()
                            : DshBundle.message("dsh.schedule.history.empty");
            ApplicationManager.getApplication()
                    .invokeLater(
                            () ->
                                    DshTextDialog.show(
                                            project,
                                            DshBundle.message("dsh.schedule.history"),
                                            detail));
        } catch (Exception error) {
            report(error);
        }
    }

    private void report(Exception error) {
        errorSink.accept(DshJson.message(error));
        notifier.accept(DshBundle.message("dsh.schedule.failed", DshJson.message(error)));
    }
}
