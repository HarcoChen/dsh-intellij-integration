package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native diff for one file-editing tool call, reconstructed without Git.
 *
 * <p>The Runtime already ships everything needed. A {@code write} / {@code edit} / {@code
 * str_replace_editor} call carries a {@code card: 'diff'} presentation whose {@code diffs} are the
 * applied contextual hunks, and that payload is persisted with the session log, so it survives
 * replay and cold session loads.
 *
 * <p>What the wire does NOT carry is a before-image of the whole file, or line numbers for the
 * hunks. This class recovers the before-image by walking the hunks backwards out of the file that
 * is on disk right now: each hunk's {@code newText} is located in the current content and put back
 * to its {@code oldText}. The context lines are what make that anchor findable, and a hunk that
 * does not appear exactly once fails the reconstruction rather than guessing — a wrong diff is
 * worse than no diff.
 */
final class DshToolDiff {

    private DshToolDiff() {}

    /** One applied hunk: content after the change, and what it replaced. */
    static final class FileDiff {
        final String path;

        /** Prior content, or null for a pure insertion / a file with no before-image. */
        final String oldText;

        final String newText;

        private FileDiff(String path, String oldText, String newText) {
            this.path = path;
            this.oldText = oldText;
            this.newText = newText;
        }
    }

    /** A parsed {@code card: 'diff'} tool presentation. */
    static final class DiffView {
        final String title;
        final List<FileDiff> diffs;

        private DiffView(String title, List<FileDiff> diffs) {
            this.title = title;
            this.diffs = diffs;
        }
    }

    /** The diff card a call proposed, paired with whether that call has settled. */
    static final class CallDiffState {
        final DiffView view;
        final boolean settled;

        private CallDiffState(DiffView view, boolean settled) {
            this.view = view;
            this.settled = settled;
        }
    }

    /** One call's hunks for one file, oldest call first. */
    static final class CallHunks {
        final String callId;
        final List<FileDiff> hunks;

        private CallHunks(String callId, List<FileDiff> hunks) {
            this.callId = callId;
            this.hunks = hunks;
        }
    }

    /** A file rewound to both sides of one call. */
    static final class Rewind {
        final String before;
        final String after;

        /** Whether nothing followed this call, so the working copy still is its result. */
        final boolean afterIsCurrent;

        private Rewind(String before, String after, boolean afterIsCurrent) {
            this.before = before;
            this.after = after;
            this.afterIsCurrent = afterIsCurrent;
        }
    }

    private static FileDiff fileDiff(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        JsonElement path = source.get("path");
        JsonElement oldText = source.get("oldText");
        JsonElement newText = source.get("newText");
        if (path == null || !path.isJsonPrimitive() || path.getAsString().isEmpty()) return null;
        if (newText == null || !newText.isJsonPrimitive()) return null;
        if (oldText != null && !oldText.isJsonNull() && !oldText.isJsonPrimitive()) return null;
        return new FileDiff(
                path.getAsString(),
                oldText == null || oldText.isJsonNull() ? null : oldText.getAsString(),
                newText.getAsString());
    }

    /**
     * Narrows an already-unwrapped tool presentation to a diff card. Anything that is not a
     * well-formed diff card returns null so the caller keeps its generic rendering, matching how
     * the Runtime's own bridges degrade.
     */
    static DiffView parseDiffView(JsonElement view) {
        if (view == null || !view.isJsonObject()) return null;
        JsonObject source = view.getAsJsonObject();
        JsonElement card = source.get("card");
        if (card == null || !card.isJsonPrimitive() || !"diff".equals(card.getAsString()))
            return null;
        if (!source.has("diffs") || !source.get("diffs").isJsonArray()) return null;
        List<FileDiff> diffs = new ArrayList<>();
        for (JsonElement entry : source.getAsJsonArray("diffs")) {
            FileDiff parsed = fileDiff(entry);
            if (parsed == null) return null;
            diffs.add(parsed);
        }
        if (diffs.isEmpty()) return null;
        JsonElement title = source.get("title");
        return new DiffView(
                title != null && title.isJsonPrimitive() ? title.getAsString() : null, diffs);
    }

    /** The distinct file paths a diff card touches, in first-seen order. */
    static List<String> diffViewPaths(DiffView view) {
        Set<String> seen = new LinkedHashSet<>();
        if (view != null) {
            for (FileDiff diff : view.diffs) seen.add(diff.path);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Hunks are computed on an LF-normalized basis upstream, so a CRLF working copy would never
     * match its own anchors. Both sides of the presented diff use this normalization, which makes
     * the comparison about content rather than line endings.
     */
    static String normalizeNewlines(String text) {
        return text == null ? "" : text.replace("\r\n", "\n");
    }

    /**
     * Whether these hunks describe a file that had no before-image at all — the {@code write}
     * tool's replay-safe fallback, which reports one whole-content hunk with no prior text.
     * Reconstruction for that case is the empty file.
     */
    private static boolean isWholeFileCreate(List<FileDiff> hunks) {
        return hunks.size() == 1 && hunks.get(0).oldText == null;
    }

    /**
     * Undo one call's hunks, returning the content as it was before them.
     *
     * <p>Hunks arrive in file order, so they are undone last-first: replacing a later hunk cannot
     * move an earlier one's anchor. Returns null when any anchor is missing or ambiguous, which is
     * the honest answer whenever the file has drifted from what the Runtime recorded.
     */
    static String reverseApplyHunks(String content, List<FileDiff> hunks) {
        if (isWholeFileCreate(hunks)) {
            return normalizeNewlines(hunks.get(0).newText).equals(content) ? "" : null;
        }
        String result = content;
        for (int index = hunks.size() - 1; index >= 0; index--) {
            FileDiff hunk = hunks.get(index);
            String after = normalizeNewlines(hunk.newText);
            // An empty anchor cannot be located; it also cannot have been
            // produced by a contextual hunk, so this is malformed rather than a
            // hard case.
            if (after.isEmpty()) return null;
            int at = result.indexOf(after);
            if (at < 0 || result.indexOf(after, at + 1) >= 0) return null;
            result =
                    result.substring(0, at)
                            + normalizeNewlines(hunk.oldText)
                            + result.substring(at + after.length());
        }
        return result;
    }

    /**
     * Applies a pending call's proposed change to the file as it stands now.
     *
     * <p>A call awaiting approval has not run, so what is on disk IS its before-image — no
     * reconstruction needed. Returns null when an anchor is missing or ambiguous, which is also how
     * the tool itself would fail, so refusing to preview is the honest answer.
     */
    static String applyProposedHunks(String current, List<FileDiff> hunks) {
        if (isWholeFileCreate(hunks)) return normalizeNewlines(hunks.get(0).newText);
        String result = current;
        for (FileDiff hunk : hunks) {
            String before = normalizeNewlines(hunk.oldText);
            if (before.isEmpty()) return null;
            int at = result.indexOf(before);
            if (at < 0 || result.indexOf(before, at + 1) >= 0) return null;
            result =
                    result.substring(0, at)
                            + normalizeNewlines(hunk.newText)
                            + result.substring(at + before.length());
        }
        return result;
    }

    /**
     * Rewinds a file to its state on either side of one call.
     *
     * <p>{@code history} is every diff-producing call for this path in the session, oldest first;
     * {@code current} is what is on disk now. Later calls are undone first so the requested call is
     * compared against the file as it actually stood then, not against today's content.
     */
    static Rewind rewindAround(String current, List<CallHunks> history, String callId) {
        int index = -1;
        for (int cursor = 0; cursor < history.size(); cursor++) {
            if (history.get(cursor).callId.equals(callId)) {
                index = cursor;
                break;
            }
        }
        if (index < 0) return null;
        String after = normalizeNewlines(current);
        for (int cursor = history.size() - 1; cursor > index; cursor--) {
            String rewound = reverseApplyHunks(after, history.get(cursor).hunks);
            if (rewound == null) return null;
            after = rewound;
        }
        String before = reverseApplyHunks(after, history.get(index).hunks);
        if (before == null) return null;
        return new Rewind(before, after, index == history.size() - 1);
    }

    /** Unwraps the {@code { for, view }} envelope the Runtime puts on a tool event. */
    private static JsonElement toolPresentation(JsonObject entry, String target) {
        if (entry == null || !entry.has("view") || !entry.get("view").isJsonObject()) return null;
        JsonObject view = entry.getAsJsonObject("view");
        JsonElement forTarget = view.get("for");
        if (forTarget == null
                || !forTarget.isJsonPrimitive()
                || !target.equals(forTarget.getAsString())) return null;
        return view.get("view");
    }

    /**
     * The diff card for one stored tool event pair. The result view wins: its hunks are the change
     * that was actually applied, while the call view is only the model's proposal (for {@code
     * edit}, the bare {@code old_string} → {@code new_string} snippet, with no context lines to
     * anchor on).
     */
    static DiffView storedDiffView(JsonObject call, JsonObject result) {
        DiffView fromResult = parseDiffView(toolPresentation(result, "result"));
        return fromResult != null ? fromResult : parseDiffView(toolPresentation(call, "call"));
    }

    private static String eventType(JsonObject entry) {
        JsonObject event =
                entry.has("event") && entry.get("event").isJsonObject()
                        ? entry.getAsJsonObject("event")
                        : entry;
        JsonElement type = event.get("type");
        return type != null && type.isJsonPrimitive() ? type.getAsString() : null;
    }

    private static JsonObject eventData(JsonObject entry) {
        JsonObject event =
                entry.has("event") && entry.get("event").isJsonObject()
                        ? entry.getAsJsonObject("event")
                        : entry;
        return event.has("data") && event.get("data").isJsonObject()
                ? event.getAsJsonObject("data")
                : new JsonObject();
    }

    private static String callIdOf(JsonObject entry) {
        JsonObject data = eventData(entry);
        JsonElement callId = data.get("callId");
        if (callId != null && callId.isJsonPrimitive()) return callId.getAsString();
        // A tool/result may carry the id on its nested result block instead.
        JsonObject result =
                data.has("result") && data.get("result").isJsonObject()
                        ? data.getAsJsonObject("result")
                        : null;
        JsonElement nested = result == null ? null : result.get("callId");
        return nested != null && nested.isJsonPrimitive() ? nested.getAsString() : null;
    }

    private static List<JsonObject> entries(JsonObject history) {
        List<JsonObject> result = new ArrayList<>();
        JsonArray source =
                history != null && history.has("events") && history.get("events").isJsonArray()
                        ? history.getAsJsonArray("events")
                        : new JsonArray();
        for (JsonElement candidate : source) {
            if (candidate.isJsonObject()) result.add(candidate.getAsJsonObject());
        }
        return result;
    }

    /**
     * Every diff-producing call for one path in this session, oldest first. The order is the
     * session log's, which is the order the edits were applied, and that is what makes rewinding
     * one call at a time meaningful.
     */
    static List<CallHunks> collectCallHunks(JsonObject history, String path) {
        Map<String, JsonObject> calls = new LinkedHashMap<>();
        for (JsonObject entry : entries(history)) {
            if ("tool/call".equals(eventType(entry))) {
                String callId = callIdOf(entry);
                if (callId != null) calls.put(callId, entry);
            }
        }
        List<CallHunks> result = new ArrayList<>();
        for (JsonObject entry : entries(history)) {
            if (!"tool/result".equals(eventType(entry))) continue;
            String callId = callIdOf(entry);
            if (callId == null) continue;
            DiffView view = storedDiffView(calls.get(callId), entry);
            if (view == null) continue;
            List<FileDiff> hunks = new ArrayList<>();
            for (FileDiff diff : view.diffs) {
                if (diff.path.equals(path)) hunks.add(diff);
            }
            if (!hunks.isEmpty()) result.add(new CallHunks(callId, hunks));
        }
        return result;
    }

    /**
     * The diff card one call proposed, plus whether a result has settled it. An unsettled call is
     * previewed against the working copy; a settled one is rewound out of it.
     */
    static CallDiffState callDiffState(JsonObject history, String callId) {
        JsonObject call = null;
        JsonObject result = null;
        for (JsonObject entry : entries(history)) {
            String type = eventType(entry);
            if ("tool/call".equals(type) && callId.equals(callIdOf(entry))) call = entry;
            else if ("tool/result".equals(type) && callId.equals(callIdOf(entry))) result = entry;
        }
        DiffView view = storedDiffView(call, result);
        return view == null ? null : new CallDiffState(view, result != null);
    }
}
