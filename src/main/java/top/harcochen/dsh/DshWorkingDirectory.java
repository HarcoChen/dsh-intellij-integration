package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import top.harcochen.dsh.remote.DshRemoteState;

/** Operation directories are independent of the session identity and permission root. */
public final class DshWorkingDirectory {
    private DshWorkingDirectory() {}

    public static String current(DshRemoteState.SessionView view, JsonObject summary) {
        DshRemoteState.ProjectionCell cell =
                view == null ? null : view.projections.get("workingDirectory");
        String value = cell == null ? null : text(cell.value());
        JsonObject baseline = object(summary, "projections");
        if (baseline != null
                && (cell == null || DshJson.longValue(baseline.get("asOfSeq"), -1) > cell.seq())) {
            value = DshJson.string(object(baseline, "values"), "workingDirectory");
        }
        return value == null || value.isBlank() ? original(summary, null) : value;
    }

    public static String original(JsonObject summary, String fallback) {
        String cwd = DshJson.string(summary, "cwd");
        return cwd == null || cwd.isBlank() ? fallback : cwd;
    }

    public static String at(JsonObject history, long seq, String original) {
        String cwd = original;
        long latest = -1;
        if (history != null && history.has("events") && history.get("events").isJsonArray()) {
            for (JsonElement raw : history.getAsJsonArray("events")) {
                if (!raw.isJsonObject()) continue;
                JsonObject event = event(raw.getAsJsonObject());
                long cut = DshJson.longValue(event.get("seq"), -1);
                if (cut < latest
                        || cut > seq
                        || !"working-directory/change".equals(DshJson.string(event, "type")))
                    continue;
                String next = DshJson.string(object(event, "data"), "cwd");
                if (next != null && !next.isBlank()) {
                    cwd = next;
                    latest = cut;
                }
            }
        }
        return cwd;
    }

    public static JsonObject event(JsonObject entry) {
        JsonObject nested = object(entry, "event");
        return nested == null ? entry : nested;
    }

    /** Resolve using the Host's path syntax, even on an IDE running another OS. */
    public static String resolve(String path, String directory) {
        if (directory == null || directory.isBlank()) return path;
        boolean windows = directory.matches("^[A-Za-z]:[\\\\/].*") || directory.startsWith("\\\\");
        if (!windows) return Path.of(directory).resolve(path).normalize().toString();
        String base = directory.replace('\\', '/');
        String value = path.replace('\\', '/');
        if (value.matches("^[A-Za-z]:(?:[^/].*)?"))
            throw new IllegalArgumentException("Drive-relative Host paths are unsupported");
        if (!value.matches("^[A-Za-z]:/.*") && !value.startsWith("//")) {
            if (value.startsWith("/")) {
                String root = windowsRoot(base);
                value = root + (root.endsWith("/") ? "" : "/") + value.substring(1);
            } else value = base + "/" + value;
        }
        String prefix = windowsRoot(value);
        String tail = value.substring(prefix.length());
        Deque<String> parts = new ArrayDeque<>();
        for (String part : tail.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else parts.addLast(part);
        }
        return (prefix + String.join("/", parts)).replace('/', '\\');
    }

    private static String windowsRoot(String path) {
        if (!path.startsWith("//")) return path.substring(0, 3);
        int server = path.indexOf('/', 2);
        int share = server < 0 ? -1 : path.indexOf('/', server + 1);
        if (server < 0) throw new IllegalArgumentException("Invalid UNC Host path");
        return share < 0 ? path : path.substring(0, share + 1);
    }

    private static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject()
                ? value.getAsJsonObject(key)
                : null;
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }
}
