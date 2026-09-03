package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the code payloads referenced by webview code-action buttons.
 *
 * <p>The webview never sends code text back to the host. It sends a short-lived
 * render id and block id, and this cache resolves that pair to host-owned text.
 * Re-rendering changed Markdown invalidates the old ids.</p>
 */
final class DshMarkdownRenderCache {
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_CODE_BYTES = 65_536;
    private static final Pattern FENCE = Pattern.compile("(?ms)^ {0,3}(`{3,}|~{3,})([^\\n]*)\\n(.*?)^ {0,3}\\1[ \\t]*$");

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> codeByRenderId = new LinkedHashMap<>();

    synchronized JsonArray render(JsonArray source, String scope) {
        JsonArray result = source.deepCopy();
        for (JsonElement element : result) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            String id = string(message, "id");
            String role = string(message, "role");
            String text = string(message, "text");
            if (id == null || role == null || text == null) continue;
            String key = scope + ":" + role + ":" + id;
            Entry cached = entries.get(key);
            String reasoning = "assistant".equals(role) ? string(message, "reasoning") : null;
            if (cached == null || !cached.source.equals(text) || !equals(cached.reasoningSource, reasoning)) {
                cached = renderFresh(key, text, reasoning, cached);
            }
            message.addProperty("renderedHtml", cached.html);
            message.addProperty("renderId", cached.renderId);
            if (cached.reasoningHtml != null && cached.reasoningRenderId != null) {
                message.addProperty("renderedReasoningHtml", cached.reasoningHtml);
                message.addProperty("reasoningRenderId", cached.reasoningRenderId);
            }
        }
        return result;
    }

    synchronized String codeBlockText(String renderId, String codeBlockId) {
        String text = codeByRenderId.getOrDefault(renderId, Map.of()).get(codeBlockId);
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new IllegalArgumentException("The code block is no longer available or exceeds the 64 KiB limit.");
        }
        return text;
    }

    private Entry renderFresh(String key, String source, String reasoning, Entry previous) {
        if (previous != null) discard(previous);
        while (entries.size() >= MAX_ENTRIES) {
            String oldest = entries.keySet().iterator().next();
            discard(entries.remove(oldest));
        }
        Rendered body = renderMarkdown(source);
        Rendered thought = reasoning == null ? null : renderMarkdown(reasoning);
        Entry entry = new Entry(source, reasoning, body.html, randomId(), body.blocks,
                thought == null ? null : thought.html, thought == null ? null : randomId(),
                thought == null ? Map.of() : thought.blocks);
        entries.put(key, entry);
        codeByRenderId.put(entry.renderId, entry.blocks);
        if (entry.reasoningRenderId != null) codeByRenderId.put(entry.reasoningRenderId, entry.reasoningBlocks);
        return entry;
    }

    private static Rendered renderMarkdown(String source) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        Matcher matcher = FENCE.matcher(normalized);
        StringBuilder html = new StringBuilder();
        Map<String, String> blocks = new LinkedHashMap<>();
        int cursor = 0;
        while (matcher.find()) {
            html.append(DshMessageProjector.markdownHtml(normalized.substring(cursor, matcher.start())));
            String language = safeLanguage(matcher.group(2));
            String code = matcher.group(3);
            String blockId = "code-" + blocks.size();
            boolean copyable = code.getBytes(StandardCharsets.UTF_8).length <= MAX_CODE_BYTES;
            if (copyable) blocks.put(blockId, code);
            html.append(codeBlockHtml(code, language, copyable ? blockId : null));
            cursor = matcher.end();
        }
        html.append(DshMessageProjector.markdownHtml(normalized.substring(cursor)));
        return new Rendered(html.toString(), blocks);
    }

    private static String codeBlockHtml(String code, String language, String id) {
        String label = language == null ? "Code" : escape(language);
        StringBuilder result = new StringBuilder("<div class=\"markdown-code-block\"><div class=\"markdown-code-head\"><span>")
                .append(label).append("</span>");
        if (id == null) {
            result.append("<button type=\"button\" class=\"markdown-code-action markdown-code-copy\" disabled title=\"").append(escape(DshBundle.message("dsh.code.block.exceeds.copy.limit"))).append("\">").append(escape(DshBundle.message("dsh.code.block.too.large"))).append("</button>");
        } else {
            result.append("<div class=\"markdown-code-actions\">")
                    .append(action("copyCode", DshBundle.message("dsh.code.block.action.copy"), id, language, true))
                    .append(action("insertCode", DshBundle.message("dsh.code.block.action.insert"), id, language, false))
                    .append(action("openCode", DshBundle.message("dsh.code.block.action.open"), id, language, false))
                    .append(action("applyCode", DshBundle.message("dsh.code.block.action.apply"), id, language, false))
                    .append("</div>");
        }
        return result.append("</div><pre><code>").append(escape(code)).append("</code></pre></div>").toString();
    }

    private static String action(String type, String label, String id, String language, boolean copy) {
        return "<button type=\"button\" class=\"markdown-code-action" + (copy ? " markdown-code-copy" : "")
                + "\" data-code-action=\"" + type + "\" data-code-block-id=\"" + id + "\""
                + (copy ? " data-copy-code-id=\"" + id + "\"" : "")
                + (language == null ? "" : " data-code-language=\"" + escape(language) + "\"")
                + ">" + escape(label) + "</button>";
    }

    private void discard(Entry entry) {
        if (entry == null) return;
        codeByRenderId.remove(entry.renderId);
        if (entry.reasoningRenderId != null) codeByRenderId.remove(entry.reasoningRenderId);
    }

    private static String safeLanguage(String raw) {
        if (raw == null) return null;
        String candidate = raw.trim().split("\\s+", 2)[0];
        return candidate.matches("[\\p{L}\\p{N}_+.#-]{1,40}") ? candidate : null;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private record Rendered(String html, Map<String, String> blocks) {}

    private record Entry(String source, String reasoningSource, String html, String renderId,
                         Map<String, String> blocks, String reasoningHtml, String reasoningRenderId,
                         Map<String, String> reasoningBlocks) {}
}
