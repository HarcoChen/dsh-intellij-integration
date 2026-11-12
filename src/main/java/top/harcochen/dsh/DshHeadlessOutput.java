package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Consumer;

/** Bounded decoding of the CLI's newline-delimited headless projection. */
final class DshHeadlessOutput {
    static final int MAX_CHARS = 512 * 1024;
    private static final int MAX_RECORD_CHARS = 2 * 1024 * 1024;
    private final StringBuilder pending = new StringBuilder();
    private boolean dropping;
    private boolean oversized;
    private boolean diagnosticTruncated;

    static final class Tail {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        void append(String value) {
            int from = Math.max(0, value.length() - MAX_CHARS);
            if (from > 0 && Character.isLowSurrogate(value.charAt(from))) from++;
            text.append(value, from, value.length());
            if (from > 0) truncated = true;
            int discard = text.length() - MAX_CHARS;
            if (discard > 0) {
                if (Character.isLowSurrogate(text.charAt(discard))) discard++;
                text.delete(0, discard);
                truncated = true;
            }
        }

        void replace(String value) {
            text.setLength(0);
            truncated = false;
            append(value);
        }

        String text() {
            return text.toString();
        }

        boolean truncated() {
            return truncated;
        }
    }

    void accept(String value, Consumer<JsonObject> event, Consumer<String> diagnostic) {
        int from = 0;
        while (from < value.length()) {
            int newline = value.indexOf('\n', from);
            int end = newline < 0 ? value.length() : newline;
            int length = end - from;
            if (!dropping && length <= MAX_RECORD_CHARS - pending.length())
                pending.append(value, from, end);
            else {
                dropping = true;
                oversized = true;
                pending.setLength(0);
            }
            if (newline < 0) return;
            if (!dropping) line(pending.toString(), event, diagnostic);
            pending.setLength(0);
            dropping = false;
            from = newline + 1;
        }
    }

    void finish(Consumer<JsonObject> event, Consumer<String> diagnostic) {
        if (!dropping && !pending.isEmpty()) line(pending.toString(), event, diagnostic);
        pending.setLength(0);
    }

    boolean oversized() {
        return oversized;
    }

    boolean diagnosticTruncated() {
        return diagnosticTruncated;
    }

    private void line(String line, Consumer<JsonObject> event, Consumer<String> diagnostic) {
        if (line.isBlank()) return;
        try {
            JsonElement value = JsonParser.parseString(line);
            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                String type = DshJson.strictString(object, "type");
                if (type != null && type.length() <= 64) {
                    event.accept(object);
                    return;
                }
            }
        } catch (RuntimeException invalid) {
            // CLI startup diagnostics can precede the event stream.
        }
        int end = Math.min(line.length(), 8192);
        if (end < line.length()) {
            if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
            diagnosticTruncated = true;
        }
        diagnostic.accept(line.substring(0, end) + (end < line.length() ? " [truncated]\n" : "\n"));
    }
}
