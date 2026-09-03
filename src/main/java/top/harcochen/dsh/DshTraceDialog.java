package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.UIManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** IntelliJ host for the same Trace ledger webview and protocol used by dsh-ide. */
final class DshTraceDialog extends DialogWrapper {
    DshTraceDialog(
            @NotNull Project project,
            @NotNull String sessionId,
            @NotNull String sessionTitle,
            int selectedSeq) {
        super(project, false);
        setTitle(DshBundle.message("dsh.trace.dialog.title", abbreviate(sessionTitle, 80)));
        setModal(false);
        setResizable(true);
        TraceBrowser trace = new TraceBrowser(project, sessionId, sessionTitle, selectedSeq);
        Disposer.register(getDisposable(), trace);
        centerPanel = trace.component();
        init();
    }

    private final JComponent centerPanel;

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return centerPanel;
    }

    @Override
    protected JComponent createSouthPanel() {
        return null;
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static final class TraceBrowser implements Disposable {
        private static final Logger LOG = Logger.getInstance(TraceBrowser.class);
        private static final int PAGE_SIZE = 160;
        private static final int RAW_DETAIL_LIMIT = 65_536;
        private static final Pattern FILE_LOCATION =
                Pattern.compile(
                        "([^\\s<>\\\"'`()\\[\\]{}*,;]+):([1-9]\\d{0,7})(?::([1-9]\\d{0,7}))?(?!\\d|:\\d)");

        private final Project project;
        private final String sessionId;
        private final String sessionTitle;
        private final DshRuntimeService runtime;
        private final DshRpcClient client;
        private final JBCefBrowser browser;
        private final JBCefJSQuery actionQuery;
        private final ScheduledExecutorService refresher;
        private final AtomicBoolean refreshInFlight = new AtomicBoolean();
        private volatile DshTraceProjector.Projection projection =
                new DshTraceProjector.Projection(List.of(), List.of(), java.util.Map.of(), 0);
        private volatile JsonObject history = new JsonObject();
        private volatile boolean ready;
        private volatile boolean baselineLoaded;
        private volatile Integer lastTailHash;
        private volatile boolean disposed;
        private String query = "";
        private int offset;
        private boolean followLatest = true;
        private String selectedId;
        private String timelineMode = "sequence";
        private Long pendingSeq;
        private String error;

        TraceBrowser(Project project, String sessionId, String sessionTitle, int selectedSeq) {
            this.project = project;
            this.sessionId = sessionId;
            this.sessionTitle = sessionTitle;
            this.runtime = DshRuntimeService.getInstance(project);
            this.client = runtime.getClient();
            this.pendingSeq = selectedSeq < 0 ? null : (long) selectedSeq;
            this.browser = new JBCefBrowser();
            this.actionQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
            this.actionQuery.addHandler(
                    request -> {
                        try {
                            JsonElement parsed = JsonParser.parseString(request);
                            if (parsed.isJsonObject())
                                ApplicationManager.getApplication()
                                        .invokeLater(() -> receive(parsed.getAsJsonObject()));
                        } catch (RuntimeException malformed) {
                            LOG.warn("Ignoring malformed DSH Trace action", malformed);
                        }
                        return null;
                    });
            this.refresher =
                    Executors.newSingleThreadScheduledExecutor(
                            runnable -> {
                                Thread thread = new Thread(runnable, "dsh-intellij-trace-refresh");
                                thread.setDaemon(true);
                                return thread;
                            });
            browser.getComponent().setPreferredSize(new Dimension(1180, 760));
            browser.loadHTML(html());
            refresher.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.SECONDS);
        }

        JComponent component() {
            return browser.getComponent();
        }

        private void refresh() {
            if (disposed || !refreshInFlight.compareAndSet(false, true)) return;
            try {
                JsonObject next;
                if (baselineLoaded) {
                    JsonObject tail = client.history(sessionId, 1_000);
                    int tailHash = tail.hashCode();
                    if (lastTailHash != null && lastTailHash == tailHash) return;
                    lastTailHash = tailHash;
                    next = mergeHistory(history, tail);
                } else {
                    next = client.traceHistory(sessionId);
                    lastTailHash = next.hashCode();
                }
                DshTraceProjector.Projection nextProjection =
                        !baselineLoaded || !sameEvents(history, next)
                                ? DshTraceProjector.project(next)
                                : DshTraceProjector.withProjectionItems(projection, next);
                history = next;
                projection = nextProjection;
                baselineLoaded = true;
                error = null;
                ApplicationManager.getApplication()
                        .invokeLater(
                                () -> {
                                    applyPendingLocation();
                                    publish();
                                    refreshSelectedDetail();
                                });
            } catch (Exception failure) {
                error = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                ApplicationManager.getApplication().invokeLater(this::publish);
            } finally {
                refreshInFlight.set(false);
            }
        }

        private void receive(JsonObject action) {
            if (disposed) return;
            String type = string(action, "type");
            if (type == null) return;
            switch (type) {
                case "ready" -> {
                    if (!hasOnly(action, "type")) return;
                    ready = true;
                    publish();
                    refreshSelectedDetail();
                }
                case "selectRow" -> {
                    String rowId = string(action, "rowId");
                    if (!hasOnly(action, "type", "rowId")
                            || !bounded(rowId, 2_048)
                            || projection.rows().stream()
                                    .noneMatch(row -> rowId.equals(string(row.view(), "id"))))
                        return;
                    selectedId = rowId;
                    publish();
                    postRowDetail(rowId);
                }
                case "selectProjection" -> {
                    String key = string(action, "key");
                    if (!hasOnly(action, "type", "key") || !bounded(key, 1_024)) return;
                    DshTraceProjector.ProjectionItem item =
                            projection.projections().stream()
                                    .filter(candidate -> key.equals(candidate.key()))
                                    .findFirst()
                                    .orElse(null);
                    if (item == null) return;
                    selectedId = item.id();
                    publish();
                    postDetail(item.id(), item.key(), "Projection", item.raw(), item.fields());
                }
                case "setQuery" -> {
                    String next = string(action, "query");
                    if (!hasOnly(action, "type", "query")
                            || next == null
                            || next.length() > 500
                            || next.indexOf('\0') >= 0) return;
                    query = next;
                    followLatest = true;
                    selectedId = null;
                    publish();
                }
                case "page" -> {
                    String direction = string(action, "direction");
                    if (!hasOnly(action, "type", "direction") || direction == null) return;
                    page(direction);
                }
                case "setTimelineMode" -> {
                    String mode = string(action, "mode");
                    if (!hasOnly(action, "type", "mode")
                            || !("sequence".equals(mode) || "duration".equals(mode))) return;
                    timelineMode = mode;
                    publish();
                }
                case "clearSelection" -> {
                    if (!hasOnly(action, "type")) return;
                    selectedId = null;
                    publish();
                }
                case "openFileLocation" -> openFileLocation(action);
                default -> LOG.debug("Ignoring unsupported DSH Trace action: " + type);
            }
        }

        private void page(String direction) {
            List<DshTraceProjector.Row> filtered = filteredRows();
            int maximum = Math.max(0, filtered.size() - PAGE_SIZE);
            if ("older".equals(direction)) {
                followLatest = false;
                offset = Math.max(0, offset - PAGE_SIZE);
            } else if ("newer".equals(direction)) {
                offset = Math.min(maximum, offset + PAGE_SIZE);
                followLatest = offset >= maximum;
            } else if ("latest".equals(direction)) {
                followLatest = true;
                offset = maximum;
            } else return;
            publish();
        }

        private List<DshTraceProjector.Row> filteredRows() {
            String normalized = query.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) return projection.rows();
            return projection.rows().stream()
                    .filter(row -> row.searchText().contains(normalized))
                    .toList();
        }

        private void publish() {
            if (!ready || disposed) return;
            List<DshTraceProjector.Row> filtered = filteredRows();
            int maximum = Math.max(0, filtered.size() - PAGE_SIZE);
            offset = followLatest ? maximum : Math.min(Math.max(0, offset), maximum);
            int end = Math.min(filtered.size(), offset + PAGE_SIZE);
            List<DshTraceProjector.Row> page = filtered.subList(Math.min(offset, end), end);

            JsonObject state = new JsonObject();
            state.addProperty("sessionId", sessionId);
            state.addProperty("title", sessionTitle());
            JsonObject status = new JsonObject();
            DshRuntimeService.RuntimeStatus runtimeStatus = runtime.getStatus();
            status.addProperty(
                    "running", runtimeStatus.state == DshRuntimeService.RuntimeState.RUNNING);
            status.addProperty("attention", false);
            if (runtimeStatus.state == DshRuntimeService.RuntimeState.ERROR
                    && runtimeStatus.message != null) {
                status.addProperty("error", runtimeStatus.message);
            }
            state.add("status", status);
            state.addProperty("query", query);
            JsonArray rows = new JsonArray();
            for (DshTraceProjector.Row row : page) {
                JsonObject view = row.view().deepCopy();
                view.addProperty("summaryHtml", renderFileLocations(stringOr(view, "summary", "")));
                if (view.has("error"))
                    view.addProperty("errorHtml", renderFileLocations(stringOr(view, "error", "")));
                rows.add(view);
            }
            state.add("rows", rows);
            state.addProperty("totalEvents", projection.totalEvents());
            state.addProperty("totalRows", projection.rows().size());
            state.add("overview", overview(projection.rows()));
            addTimeline(state, projection.rows());
            state.addProperty("timelineMode", timelineMode);
            state.addProperty("filteredRows", filtered.size());
            state.addProperty("offset", offset);
            state.addProperty("pageSize", PAGE_SIZE);
            state.addProperty("hasOlder", offset > 0);
            state.addProperty("hasNewer", offset + page.size() < filtered.size());
            state.addProperty("followLatest", followLatest);
            JsonArray projections = new JsonArray();
            for (DshTraceProjector.ProjectionItem item : projection.projections()) {
                JsonObject value = new JsonObject();
                value.addProperty("id", item.id());
                value.addProperty("key", item.key());
                value.addProperty("seq", item.seq());
                value.addProperty("valuePreview", item.valuePreview());
                value.addProperty("valueHtml", renderFileLocations(item.valuePreview()));
                projections.add(value);
            }
            state.add("projections", projections);
            if (selectedId != null) state.addProperty("selectedId", selectedId);
            state.addProperty("needsHistoryBaseline", bool(history, "hasMore"));
            if (error != null) state.addProperty("error", error);
            JsonObject message = new JsonObject();
            message.addProperty("type", "state");
            message.add("state", state);
            post(message);
        }

        private JsonObject overview(List<DshTraceProjector.Row> rows) {
            JsonObject result = new JsonObject();
            Set<Long> turns = new HashSet<>();
            Set<String> calls = new HashSet<>();
            long errors = 0;
            long input = 0;
            long output = 0;
            long cacheRead = 0;
            long cacheWrite = 0;
            Long first = null;
            Long last = null;
            for (DshTraceProjector.Row row : rows) {
                JsonObject view = row.view();
                long time = longValue(view, "time", 0);
                if (first == null) first = time;
                last = time;
                if (view.has("turn")) turns.add(longValue(view, "turn", 0));
                String callId = string(view, "callId");
                if (callId != null) calls.add(callId);
                if (view.has("error") || "error".equals(string(view, "category"))) errors++;
                JsonObject tokens = object(view, "tokens");
                input += longValue(tokens, "inputTokens", 0);
                output += longValue(tokens, "outputTokens", 0);
                cacheRead += longValue(tokens, "cacheReadTokens", 0);
                cacheWrite += longValue(tokens, "cacheWriteTokens", 0);
            }
            if (first != null && last != null && last >= first)
                result.addProperty("durationMs", last - first);
            result.addProperty("turns", turns.size());
            result.addProperty("calls", calls.size());
            result.addProperty("errors", errors);
            result.addProperty("inputTokens", input);
            result.addProperty("outputTokens", output);
            result.addProperty("cacheReadTokens", cacheRead);
            result.addProperty("cacheWriteTokens", cacheWrite);
            return result;
        }

        private void addTimeline(JsonObject state, List<DshTraceProjector.Row> allRows) {
            List<DshTraceProjector.Row> timelineCandidates =
                    allRows.stream()
                            .filter(
                                    row -> {
                                        String category = string(row.view(), "category");
                                        return List.of(
                                                        "user",
                                                        "context",
                                                        "assistant",
                                                        "tool",
                                                        "subtool",
                                                        "compaction")
                                                .contains(category);
                                    })
                            .toList();
            List<DshTraceProjector.Row> timelineRows =
                    timelineCandidates.subList(
                            Math.max(0, timelineCandidates.size() - 180),
                            timelineCandidates.size());
            JsonArray timeline = new JsonArray();
            if (timelineRows.isEmpty()) {
                state.add("timeline", timeline);
                return;
            }
            long first = longValue(timelineRows.get(0).view(), "time", 0);
            long last = longValue(timelineRows.get(timelineRows.size() - 1).view(), "time", first);
            long span = Math.max(1, last - first);
            double blockWidth =
                    Math.max(0.8, Math.min(8, (100.0 / Math.max(1, timelineRows.size())) * 0.72));
            for (int index = 0; index < timelineRows.size(); index++) {
                JsonObject view = timelineRows.get(index).view();
                String category = stringOr(view, "category", "generic");
                JsonObject item = new JsonObject();
                item.addProperty("id", stringOr(view, "id", ""));
                item.addProperty(
                        "lane",
                        "assistant".equals(category) || "compaction".equals(category)
                                ? "model"
                                : "tool".equals(category) || "subtool".equals(category)
                                        ? "tools"
                                        : "input");
                item.addProperty("slot", index);
                item.addProperty("category", category);
                item.addProperty("eventType", stringOr(view, "eventType", ""));
                double left =
                        "sequence".equals(timelineMode)
                                ? (timelineRows.size() <= 1
                                        ? 0
                                        : (index * 100.0 / (timelineRows.size() - 1)))
                                : Math.max(
                                        0,
                                        Math.min(
                                                100,
                                                (longValue(view, "time", first) - first)
                                                        * 100.0
                                                        / span));
                double width =
                        "sequence".equals(timelineMode)
                                ? 100.0 / Math.max(1, timelineRows.size())
                                : view.has("durationMs")
                                        ? Math.max(
                                                blockWidth,
                                                Math.min(
                                                        100,
                                                        longValue(view, "durationMs", 0)
                                                                * 100.0
                                                                / span))
                                        : blockWidth;
                item.addProperty("left", left);
                item.addProperty("width", width);
                if (view.has("durationMs"))
                    item.add("durationMs", view.get("durationMs").deepCopy());
                item.addProperty("summary", stringOr(view, "summary", ""));
                timeline.add(item);
            }
            state.add("timeline", timeline);
            state.addProperty("timelineStart", first);
            state.addProperty("timelineEnd", last);
        }

        private void applyPendingLocation() {
            Long seq = pendingSeq;
            if (seq == null) return;
            String rowId = projection.seqToRowId().get(seq);
            if (rowId == null) return;
            pendingSeq = null;
            query = "";
            followLatest = false;
            selectedId = rowId;
            int index = -1;
            for (int i = 0; i < projection.rows().size(); i++) {
                if (rowId.equals(string(projection.rows().get(i).view(), "id"))) {
                    index = i;
                    break;
                }
            }
            offset = index < 0 ? 0 : (index / PAGE_SIZE) * PAGE_SIZE;
        }

        private void refreshSelectedDetail() {
            if (selectedId == null || !ready) return;
            DshTraceProjector.Row row =
                    projection.rows().stream()
                            .filter(candidate -> selectedId.equals(string(candidate.view(), "id")))
                            .findFirst()
                            .orElse(null);
            if (row != null) {
                postDetail(
                        selectedId,
                        stringOr(row.view(), "summary", ""),
                        "Event",
                        row.raw(),
                        row.fields());
                return;
            }
            DshTraceProjector.ProjectionItem item =
                    projection.projections().stream()
                            .filter(candidate -> selectedId.equals(candidate.id()))
                            .findFirst()
                            .orElse(null);
            if (item != null)
                postDetail(item.id(), item.key(), "Projection", item.raw(), item.fields());
        }

        private void postRowDetail(String rowId) {
            DshTraceProjector.Row row =
                    projection.rows().stream()
                            .filter(candidate -> rowId.equals(string(candidate.view(), "id")))
                            .findFirst()
                            .orElse(null);
            if (row != null)
                postDetail(
                        rowId,
                        stringOr(row.view(), "summary", ""),
                        "Event",
                        row.raw(),
                        row.fields());
        }

        private void postDetail(
                String id,
                String title,
                String kind,
                JsonElement raw,
                List<DshTraceProjector.Field> fields) {
            JsonObject detail = new JsonObject();
            detail.addProperty("id", id);
            detail.addProperty("title", title);
            detail.addProperty("kind", kind);
            JsonArray summary = new JsonArray();
            for (DshTraceProjector.Field field : fields) {
                JsonObject value = new JsonObject();
                value.addProperty("label", field.label());
                value.addProperty("value", field.value());
                value.addProperty("valueHtml", renderFileLocations(field.value()));
                summary.add(value);
            }
            detail.add("summary", summary);
            String rawText = DshTraceProjector.safeJson(raw, RAW_DETAIL_LIMIT);
            detail.addProperty("raw", rawText);
            detail.addProperty("rawHtml", renderFileLocations(rawText));
            JsonObject message = new JsonObject();
            message.addProperty("type", "detail");
            message.add("detail", detail);
            post(message);
        }

        private void openFileLocation(JsonObject action) {
            if (!hasOnly(action, "type", "path", "line", "column")) return;
            String value = string(action, "path");
            Long line = integer(action, "line");
            Long column = integer(action, "column");
            if (!bounded(value, 4_096)
                    || line == null
                    || line <= 0
                    || line > 99_999_999
                    || (column != null && (column <= 0 || column > 99_999_999))) return;
            String root = project.getBasePath();
            if (root == null) return;
            try {
                Path base = Path.of(root).toAbsolutePath().normalize();
                Path path = Path.of(value);
                Path resolved =
                        (path.isAbsolute() ? path : base.resolve(path))
                                .toAbsolutePath()
                                .normalize();
                if (!resolved.startsWith(base)) return;
                VirtualFile file =
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(resolved.toString());
                if (file != null)
                    new OpenFileDescriptor(
                                    project,
                                    file,
                                    Math.toIntExact(line - 1),
                                    column == null ? 0 : Math.toIntExact(column - 1))
                            .navigate(true);
            } catch (RuntimeException ignored) {
                // Treat malformed/untrusted webview paths as a no-op.
            }
        }

        private String sessionTitle() {
            return sessionTitle;
        }

        private static JsonObject mergeHistory(JsonObject baseline, JsonObject tail) {
            TreeMap<Long, JsonElement> merged = new TreeMap<>();
            addEvents(merged, baseline);
            addEvents(merged, tail);
            JsonObject result = tail.deepCopy();
            JsonArray events = new JsonArray();
            for (JsonElement event : merged.values()) events.add(event.deepCopy());
            result.add("events", events);
            result.addProperty("hasMore", false);
            return result;
        }

        /** Projection cells change independently of the durable event ledger. */
        private static boolean sameEvents(JsonObject left, JsonObject right) {
            return historyEvents(left).equals(historyEvents(right));
        }

        private static JsonArray historyEvents(JsonObject value) {
            return value != null && value.has("events") && value.get("events").isJsonArray()
                    ? value.getAsJsonArray("events")
                    : new JsonArray();
        }

        private static void addEvents(Map<Long, JsonElement> target, JsonObject page) {
            if (!page.has("events") || !page.get("events").isJsonArray()) return;
            for (JsonElement candidate : page.getAsJsonArray("events")) {
                if (!candidate.isJsonObject()) continue;
                JsonObject wrapper = candidate.getAsJsonObject();
                JsonObject event = object(wrapper, "event");
                if (event == null) event = wrapper;
                Long seq = integer(event, "seq");
                if (seq != null && seq >= 0) target.put(seq, candidate.deepCopy());
            }
        }

        private void post(JsonObject message) {
            if (!ready || disposed) return;
            String escaped = escapeJavaScriptString(message.toString());
            browser.getCefBrowser()
                    .executeJavaScript(
                            "window.postMessage(JSON.parse('" + escaped + "'),'*');",
                            browser.getCefBrowser().getURL(),
                            0);
        }

        private String html() {
            String css = readResource("/webview/trace.css");
            String script = readResource("/webview/trace.js");
            String injectedPost = actionQuery.inject("JSON.stringify(message)");
            JsonObject strings = strings();
            JsonObject bootstrap = new JsonObject();
            bootstrap.addProperty("sessionId", sessionId);
            bootstrap.add("strings", strings);
            String bootstrapJson = bootstrap.toString().replace("</", "<\\/");
            String languageTag = com.intellij.DynamicBundle.getLocale().toLanguageTag();
            return "<!doctype html><html lang=\""
                    + languageTag
                    + "\"><head><meta charset=\"UTF-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">"
                    + "<style>"
                    + themeCss()
                    + css
                    + "</style></head><body>"
                    + markup(strings)
                    + "<script type=\"application/json\" id=\"trace-bootstrap\">"
                    + bootstrapJson
                    + "</script>"
                    + "<script>window.__dshTraceState={};window.acquireVsCodeApi=function(){return {"
                    + "postMessage:function(message){"
                    + injectedPost
                    + "},"
                    + "getState:function(){return window.__dshTraceState;},"
                    + "setState:function(value){window.__dshTraceState=value;}}};</script>"
                    + "<script>"
                    + script
                    + "</script></body></html>";
        }

        private static JsonObject strings() {
            JsonObject value = new JsonObject();
            value.addProperty("loading", DshBundle.message("dsh.trace.string.loading"));
            value.addProperty(
                    "searchPlaceholder", DshBundle.message("dsh.trace.string.search.placeholder"));
            value.addProperty("older", DshBundle.message("dsh.trace.string.older"));
            value.addProperty("newer", DshBundle.message("dsh.trace.string.newer"));
            value.addProperty("followLatest", DshBundle.message("dsh.trace.string.follow.latest"));
            value.addProperty(
                    "projectionInspector",
                    DshBundle.message("dsh.trace.string.projection.inspector"));
            value.addProperty("selectRecord", DshBundle.message("dsh.trace.string.select.record"));
            value.addProperty(
                    "loadingFailed", DshBundle.message("dsh.trace.string.loading.failed"));
            value.addProperty("sessionError", DshBundle.message("dsh.trace.string.session.error"));
            value.addProperty(
                    "waitingForAction", DshBundle.message("dsh.trace.string.waiting.for.action"));
            value.addProperty("running", DshBundle.message("dsh.trace.string.running"));
            value.addProperty("idle", DshBundle.message("dsh.trace.string.idle"));
            value.addProperty(
                    "historySyncing", DshBundle.message("dsh.trace.string.history.syncing"));
            value.addProperty(
                    "noProjections", DshBundle.message("dsh.trace.string.no.projections"));
            value.addProperty("noRows", DshBundle.message("dsh.trace.string.no.rows"));
            value.addProperty(
                    "deferredDetail", DshBundle.message("dsh.trace.string.deferred.detail"));
            value.addProperty("event", DshBundle.message("dsh.trace.string.event"));
            value.addProperty("turnStep", DshBundle.message("dsh.trace.string.turn.step"));
            value.addProperty("summary", DshBundle.message("dsh.trace.string.summary"));
            value.addProperty("time", DshBundle.message("dsh.trace.string.time"));
            value.addProperty("rows", DshBundle.message("dsh.trace.string.rows"));
            value.addProperty("projected", DshBundle.message("dsh.trace.string.projected"));
            value.addProperty("raw", DshBundle.message("dsh.trace.string.raw"));
            value.addProperty("followLive", DshBundle.message("dsh.trace.string.follow.live"));
            value.addProperty("overview", DshBundle.message("dsh.trace.string.overview"));
            value.addProperty("timeline", DshBundle.message("dsh.trace.string.timeline"));
            value.addProperty("sequence", DshBundle.message("dsh.trace.string.sequence"));
            value.addProperty("duration", DshBundle.message("dsh.trace.string.duration"));
            value.addProperty("turns", DshBundle.message("dsh.trace.string.turns"));
            value.addProperty("calls", DshBundle.message("dsh.trace.string.calls"));
            value.addProperty("errors", DshBundle.message("dsh.trace.string.errors"));
            value.addProperty("inputTokens", DshBundle.message("dsh.trace.string.input.tokens"));
            value.addProperty("outputTokens", DshBundle.message("dsh.trace.string.output.tokens"));
            value.addProperty("cacheRead", DshBundle.message("dsh.trace.string.cache.read"));
            value.addProperty("cacheWrite", DshBundle.message("dsh.trace.string.cache.write"));
            value.addProperty("collapse", DshBundle.message("dsh.trace.string.collapse"));
            value.addProperty("expand", DshBundle.message("dsh.trace.string.expand"));
            return value;
        }

        private static String h(String key) {
            return escapeHtml(DshBundle.message(key));
        }

        private static String markup(JsonObject strings) {
            return "<div class=\"app\"><header><div id=\"title\" class=\"title\">"
                    + h("dsh.trace.markup.title")
                    + "</div>"
                    + "<div class=\"status\"><span id=\"dot\" class=\"dot\"></span><span id=\"statusText\">"
                    + h("dsh.trace.string.loading")
                    + "</span></div></header>"
                    + "<div class=\"toolbar\"><input id=\"search\" placeholder=\""
                    + h("dsh.trace.string.search.placeholder")
                    + "\">"
                    + "<span id=\"counts\" class=\"counts\"></span><button id=\"timelineMode\" class=\"secondary\">"
                    + h("dsh.trace.string.sequence")
                    + "</button>"
                    + "<button id=\"older\" class=\"secondary\">"
                    + h("dsh.trace.string.older")
                    + "</button><button id=\"newer\" class=\"secondary\">"
                    + h("dsh.trace.string.newer")
                    + "</button>"
                    + "<button id=\"latest\" class=\"secondary\">"
                    + h("dsh.trace.string.follow.latest")
                    + "</button></div>"
                    + "<section class=\"overview\">"
                    + metric(h("dsh.trace.string.duration"), "metricDuration")
                    + metric(h("dsh.trace.string.turns"), "metricTurns")
                    + metric(h("dsh.trace.string.calls"), "metricCalls")
                    + metric(h("dsh.trace.string.errors"), "metricErrors")
                    + metric(h("dsh.trace.string.input.tokens"), "metricInput")
                    + metric(h("dsh.trace.string.output.tokens"), "metricOutput")
                    + metric(h("dsh.trace.string.cache.read"), "metricCacheRead")
                    + metric(h("dsh.trace.string.cache.write"), "metricCacheWrite")
                    + "</section>"
                    + "<section class=\"timeline\"><div class=\"section-title\">"
                    + h("dsh.trace.string.timeline")
                    + "</div><div class=\"timeline-scale\">"
                    + "<span id=\"timelineStart\">—</span><span id=\"timelineEnd\">—</span></div>"
                    + "<div id=\"timelineLanes\" class=\"timeline-lanes\"></div></section>"
                    + "<div class=\"layout\"><div class=\"ledger-shell\"><section class=\"section projection-section\">"
                    + "<div class=\"section-title\">"
                    + h("dsh.trace.string.projection.inspector")
                    + "</div><div id=\"projections\" class=\"projections\"></div></section>"
                    + "<div class=\"ledger-head\"><div># / seq</div><div>"
                    + h("dsh.trace.string.event")
                    + "</div><div>"
                    + h("dsh.trace.string.turn.step")
                    + "</div><div>"
                    + h("dsh.trace.string.summary")
                    + "</div><div>"
                    + h("dsh.trace.string.time")
                    + "</div></div>"
                    + "<div id=\"ledger\" class=\"ledger\"></div></div><aside class=\"inspector\">"
                    + "<div class=\"inspector-head\">"
                    + "<div id=\"detailKind\" class=\"inspector-kind\">"
                    + h("dsh.trace.markup.inspector")
                    + "</div><div id=\"detailTitle\" class=\"inspector-title\">"
                    + h("dsh.trace.string.select.record")
                    + "</div></div>"
                    + "<div class=\"tabs\"><button data-tab=\"summary\" class=\"active\">"
                    + h("dsh.trace.string.summary")
                    + "</button><button data-tab=\"raw\">"
                    + h("dsh.trace.string.raw")
                    + "</button></div>"
                    + "<div class=\"detail\"><div id=\"summaryDetail\"></div>"
                    + "<pre id=\"rawDetail\" class=\"hidden\"></pre></div></aside></div></div>";
        }

        private static String metric(String label, String id) {
            return "<div class=\"metric\"><div class=\"metric-label\">"
                    + label
                    + "</div><div id=\""
                    + id
                    + "\" class=\"metric-value\">—</div></div>";
        }

        private static String themeCss() {
            Color background =
                    color("Editor.background", color("Panel.background", new Color(0x1E1F22)));
            Color foreground = color("Label.foreground", new Color(0xDFE1E5));
            Color muted = color("Label.disabledForeground", new Color(0x9DA3AD));
            Color border = color("Separator.separatorColor", new Color(0x454851));
            Color input = color("TextField.background", new Color(0x2B2D31));
            Color inputForeground = color("TextField.foreground", foreground);
            Color primary = color("Button.default.startBackground", new Color(0x3574F0));
            Color primaryForeground = color("Button.default.foreground", Color.WHITE);
            Color secondary = color("Button.background", new Color(0x393B40));
            Font font = UIManager.getFont("Label.font");
            Font editor = UIManager.getFont("TextArea.font");
            return ":root{"
                    + "--vscode-foreground:"
                    + hex(foreground)
                    + ";--vscode-editor-background:"
                    + hex(background)
                    + ";"
                    + "--vscode-descriptionForeground:"
                    + hex(muted)
                    + ";--vscode-panel-border:"
                    + hex(border)
                    + ";"
                    + "--vscode-button-foreground:"
                    + hex(primaryForeground)
                    + ";--vscode-button-background:"
                    + hex(primary)
                    + ";"
                    + "--vscode-button-secondaryBackground:"
                    + hex(secondary)
                    + ";--vscode-input-foreground:"
                    + hex(inputForeground)
                    + ";"
                    + "--vscode-input-background:"
                    + hex(input)
                    + ";--vscode-input-border:"
                    + hex(border)
                    + ";"
                    + "--vscode-focusBorder:"
                    + hex(primary)
                    + ";--vscode-list-hoverBackground:"
                    + hex(secondary)
                    + ";"
                    + "--vscode-list-activeSelectionForeground:"
                    + hex(primaryForeground)
                    + ";--vscode-list-activeSelectionBackground:"
                    + hex(primary)
                    + ";"
                    + "--vscode-toolbar-hoverBackground:"
                    + hex(secondary)
                    + ";--vscode-errorForeground:#f14c4c;"
                    + "--vscode-textLink-foreground:#6ea8fe;--vscode-textLink-activeForeground:#91bdff;"
                    + "--vscode-font-family:"
                    + cssFont(font)
                    + ";--vscode-editor-font-family:"
                    + cssFont(editor)
                    + ";}";
        }

        private static String renderFileLocations(String text) {
            if (text == null || text.isEmpty()) return "";
            Matcher matcher = FILE_LOCATION.matcher(text);
            StringBuilder output = new StringBuilder();
            int cursor = 0;
            while (matcher.find()) {
                String path = matcher.group(1);
                if (path.contains("://")
                        || !(path.contains("/") || path.contains("\\") || path.contains(".")))
                    continue;
                output.append(escapeHtml(text.substring(cursor, matcher.start())));
                output.append(
                                "<a class=\"file-location-link\" role=\"link\" tabindex=\"0\" data-file-path=\"")
                        .append(escapeHtml(path))
                        .append("\" data-file-line=\"")
                        .append(matcher.group(2))
                        .append("\"");
                if (matcher.group(3) != null)
                    output.append(" data-file-column=\"").append(matcher.group(3)).append("\"");
                output.append(">").append(escapeHtml(matcher.group())).append("</a>");
                cursor = matcher.end();
            }
            return output.append(escapeHtml(text.substring(cursor))).toString();
        }

        private static String readResource(String path) {
            try (InputStream stream = DshTraceDialog.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing DSH resource " + path);
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException("Unable to read DSH resource " + path, error);
            }
        }

        private static String escapeJavaScriptString(String value) {
            return value.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        }

        private static String escapeHtml(String value) {
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }

        private static Color color(String key, Color fallback) {
            Color value = UIManager.getColor(key);
            return value == null ? fallback : value;
        }

        private static String hex(Color value) {
            return String.format(
                    "#%02x%02x%02x", value.getRed(), value.getGreen(), value.getBlue());
        }

        private static String cssFont(Font value) {
            String family = value == null ? "sans-serif" : value.getFamily();
            return "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"") + "\",sans-serif";
        }

        private static boolean hasOnly(JsonObject value, String... names) {
            Set<String> allowed = Set.of(names);
            return value.keySet().stream().allMatch(allowed::contains);
        }

        private static boolean bounded(String value, int maximum) {
            return value != null
                    && !value.isEmpty()
                    && value.length() <= maximum
                    && value.indexOf('\0') < 0;
        }

        private static JsonObject object(JsonObject parent, String key) {
            return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                    ? parent.getAsJsonObject(key)
                    : null;
        }

        private static String string(JsonObject parent, String key) {
            try {
                return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
                        ? parent.get(key).getAsString()
                        : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static String stringOr(JsonObject parent, String key, String fallback) {
            String value = string(parent, key);
            return value == null ? fallback : value;
        }

        private static Long integer(JsonObject parent, String key) {
            try {
                return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
                        ? parent.get(key).getAsLong()
                        : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static long longValue(JsonObject parent, String key, long fallback) {
            Long value = integer(parent, key);
            return value == null ? fallback : value;
        }

        private static boolean bool(JsonObject parent, String key) {
            try {
                return parent != null
                        && parent.has(key)
                        && parent.get(key).isJsonPrimitive()
                        && parent.get(key).getAsBoolean();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        @Override
        public void dispose() {
            disposed = true;
            refresher.shutdownNow();
            Disposer.dispose(actionQuery);
            Disposer.dispose(browser);
        }
    }
}
