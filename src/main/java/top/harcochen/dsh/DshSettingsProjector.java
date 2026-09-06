package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Projects {@code settings.describe} into the settings cards the webview renders, and turns the
 * form's edits back into {@code settings.mutate} path operations.
 *
 * <p>The namespace view carries three layers — the resolved value, the composition base, and the
 * raw user section — plus a serialized schemastery schema. Fields are discovered from the schema
 * first, so a setting that has never been written still appears with its description, then from
 * each layer, so a value the schema does not describe is not hidden. Secret slots are listed but
 * their values never ride the wire and are never written from here.
 */
final class DshSettingsProjector {
    private static final Pattern SENSITIVE_LEAF =
            Pattern.compile(
                    "(?:api[_-]?key|token|password|secret|credential|private[_-]?key)$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    private DshSettingsProjector() {}

    /** The panel view for one {@code settings.describe} result. */
    static JsonObject presentPanel(JsonObject result) {
        boolean writable = bool(result, "writable");
        JsonObject panel = new JsonObject();
        panel.addProperty("open", true);
        panel.addProperty("writable", writable);
        panel.addProperty("hasDocument", bool(result, "hasDocument"));
        JsonArray cards = new JsonArray();
        JsonArray namespaces =
                result != null && result.has("namespaces") && result.get("namespaces").isJsonArray()
                        ? result.getAsJsonArray("namespaces")
                        : new JsonArray();
        for (JsonElement candidate : namespaces) {
            if (!candidate.isJsonObject()) continue;
            cards.add(presentCard(candidate.getAsJsonObject(), writable));
        }
        panel.add("cards", cards);
        return panel;
    }

    /** A panel that is open but carries only a failure, so the reader sees why. */
    static JsonObject failedPanel(String error) {
        JsonObject panel = new JsonObject();
        panel.addProperty("open", true);
        panel.addProperty("writable", false);
        panel.addProperty("hasDocument", false);
        panel.add("cards", new JsonArray());
        if (error != null) panel.addProperty("error", error);
        return panel;
    }

    static JsonObject loadingPanel() {
        JsonObject panel = new JsonObject();
        panel.addProperty("open", true);
        panel.addProperty("loading", true);
        panel.addProperty("writable", false);
        panel.addProperty("hasDocument", false);
        panel.add("cards", new JsonArray());
        return panel;
    }

    private static JsonObject presentCard(JsonObject namespace, boolean writable) {
        String ns = string(namespace, "ns", "");
        JsonObject card = new JsonObject();
        card.addProperty("ns", ns);
        card.addProperty("title", titleOf(ns));
        String applies = string(namespace, "applies", "live");
        card.addProperty("applies", "restart".equals(applies) ? "restart" : "live");
        card.addProperty("writable", writable);
        card.addProperty("revision", number(namespace, "revision"));
        card.add("fields", presentFields(namespace));
        return card;
    }

    private static String titleOf(String ns) {
        String spaced = ns.replaceAll("[-_]+", " ");
        return spaced.isEmpty()
                ? spaced
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** One discovered leaf setting, keyed by its path. */
    private static JsonArray presentFields(JsonObject namespace) {
        Map<String, JsonObject> fields = new LinkedHashMap<>();
        Map<String, Boolean> secrets = new LinkedHashMap<>();
        JsonArray secretList =
                namespace.has("secrets") && namespace.get("secrets").isJsonArray()
                        ? namespace.getAsJsonArray("secrets")
                        : new JsonArray();
        for (JsonElement candidate : secretList) {
            if (!candidate.isJsonObject()) continue;
            List<String> path = pathOf(candidate.getAsJsonObject().get("path"));
            if (path.isEmpty()) continue;
            secrets.put(key(path), bool(candidate.getAsJsonObject(), "set"));
        }
        JsonElement value = namespace.get("value");
        JsonElement base = namespace.get("base");
        JsonElement user = namespace.get("user");
        JsonObject schema = schemaRoot(namespace.get("schema"));

        visitSchema(schema, new ArrayList<>(), 0, fields, secrets, value, base, user);
        visitValue(value, new ArrayList<>(), 0, fields, secrets, value, base, user);
        visitValue(base, new ArrayList<>(), 0, fields, secrets, value, base, user);
        visitValue(user, new ArrayList<>(), 0, fields, secrets, value, base, user);
        for (String secretKey : secrets.keySet()) {
            addField(unkey(secretKey), null, fields, secrets, value, base, user);
        }

        List<JsonObject> ordered = new ArrayList<>(fields.values());
        ordered.sort(Comparator.comparing(field -> joinPath(field.get("path"))));
        JsonArray result = new JsonArray();
        for (JsonObject field : ordered) result.add(field);
        return result;
    }

    /**
     * Walk the schema so a never-written setting still shows up with its description. A dict node's
     * children come from the value instead, since the schema declares only the shape of one entry.
     */
    private static void visitSchema(
            JsonObject node,
            List<String> path,
            int depth,
            Map<String, JsonObject> fields,
            Map<String, Boolean> secrets,
            JsonElement value,
            JsonElement base,
            JsonElement user) {
        if (depth > 8) return;
        if (secrets.containsKey(key(path))) {
            addField(path, node, fields, secrets, value, base, user);
            return;
        }
        String type = node == null ? null : string(node, "type", null);
        List<String> childKeys = new ArrayList<>();
        Map<String, JsonObject> childNodes = new LinkedHashMap<>();
        if ("object".equals(type) && node.has("dict") && node.get("dict").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject("dict").entrySet()) {
                childKeys.add(entry.getKey());
                childNodes.put(
                        entry.getKey(),
                        entry.getValue().isJsonObject()
                                ? entry.getValue().getAsJsonObject()
                                : null);
            }
        } else if ("dict".equals(type) && node.has("inner") && node.get("inner").isJsonObject()) {
            JsonElement dictValue = valueAtPath(value, path);
            if (dictValue != null && dictValue.isJsonObject()) {
                for (String entryKey : dictValue.getAsJsonObject().keySet()) {
                    childKeys.add(entryKey);
                    childNodes.put(entryKey, node.getAsJsonObject("inner"));
                }
            }
        }
        if (!childKeys.isEmpty()) {
            for (String childKey : childKeys) {
                List<String> childPath = new ArrayList<>(path);
                childPath.add(childKey);
                visitSchema(
                        childNodes.get(childKey),
                        childPath,
                        depth + 1,
                        fields,
                        secrets,
                        value,
                        base,
                        user);
            }
            return;
        }
        addField(path, node, fields, secrets, value, base, user);
    }

    private static void visitValue(
            JsonElement node,
            List<String> path,
            int depth,
            Map<String, JsonObject> fields,
            Map<String, Boolean> secrets,
            JsonElement value,
            JsonElement base,
            JsonElement user) {
        if (depth > 8 || node == null || node.isJsonNull()) return;
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                List<String> childPath = new ArrayList<>(path);
                childPath.add(entry.getKey());
                visitValue(
                        entry.getValue(), childPath, depth + 1, fields, secrets, value, base, user);
            }
            return;
        }
        addField(path, null, fields, secrets, value, base, user);
    }

    private static void addField(
            List<String> path,
            JsonObject node,
            Map<String, JsonObject> fields,
            Map<String, Boolean> secrets,
            JsonElement value,
            JsonElement base,
            JsonElement user) {
        if (path.isEmpty()) return;
        String pathKey = key(path);
        if (fields.containsKey(pathKey)) return;
        Boolean secretSet = secrets.get(pathKey);
        boolean secret = secretSet != null || isSensitivePath(path);
        JsonElement resolved = valueAtPath(value, path);
        JsonElement fromBase = valueAtPath(base, path);
        JsonElement fromUser = valueAtPath(user, path);
        JsonObject field = new JsonObject();
        JsonArray pathArray = new JsonArray();
        for (String segment : path) pathArray.add(segment);
        field.add("path", pathArray);
        field.addProperty("label", labelOf(path));
        String description = descriptionOf(node);
        if (description != null) field.addProperty("description", description);
        JsonElement typeSource =
                resolved != null ? resolved : (fromBase != null ? fromBase : fromUser);
        field.addProperty("type", secret ? "string" : typeOf(typeSource, node));
        field.addProperty("value", secret ? "" : textOf(resolved));
        field.addProperty("overridden", hasPath(user, path));
        field.addProperty("secret", secret);
        // A resolved secret with no explicit slot state is still configured.
        field.addProperty(
                "secretSet", Boolean.TRUE.equals(secretSet) || (secret && resolved != null));
        fields.put(pathKey, field);
    }

    /**
     * Coerce the webview's edits into path operations. Every value arrives as a string from the
     * form, so the declared field type decides how it parses; a value that does not parse is
     * rejected rather than silently coerced. Secret fields stay with the credential provider and
     * are never written here.
     */
    static JsonArray mutationOps(JsonArray fields, JsonArray changes) {
        Map<String, JsonObject> byPath = new LinkedHashMap<>();
        for (JsonElement candidate : fields) {
            if (!candidate.isJsonObject()) continue;
            byPath.put(
                    joinPath(candidate.getAsJsonObject().get("path")), candidate.getAsJsonObject());
        }
        JsonArray ops = new JsonArray();
        for (JsonElement candidate : changes) {
            if (!candidate.isJsonObject()) continue;
            JsonObject change = candidate.getAsJsonObject();
            JsonObject field = byPath.get(joinPath(change.get("path")));
            if (field == null || bool(field, "secret")) continue;
            JsonObject op = new JsonObject();
            op.add("path", field.get("path").deepCopy());
            if (bool(change, "clear")) {
                op.addProperty("op", "unset");
            } else {
                op.addProperty("op", "set");
                op.add(
                        "value",
                        coerce(string(field, "type", "string"), string(change, "value", "")));
            }
            ops.add(op);
        }
        return ops;
    }

    private static JsonElement coerce(String type, String raw) {
        switch (type) {
            case "boolean" -> {
                if (!"true".equals(raw) && !"false".equals(raw)) {
                    throw new IllegalArgumentException(
                            DshBundle.message("dsh.settings.coerce.boolean"));
                }
                return new JsonPrimitive(Boolean.parseBoolean(raw));
            }
            case "number" -> {
                try {
                    double parsed = Double.parseDouble(raw.trim());
                    if (!Double.isFinite(parsed)) throw new NumberFormatException(raw);
                    return new JsonPrimitive(
                            parsed == Math.rint(parsed) && Math.abs(parsed) < 1e15
                                    ? (Number) (long) parsed
                                    : (Number) parsed);
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            DshBundle.message("dsh.settings.coerce.number"));
                }
            }
            case "json" -> {
                try {
                    return JsonParser.parseString(raw);
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            DshBundle.message("dsh.settings.coerce.json"));
                }
            }
            default -> {
                return new JsonPrimitive(raw);
            }
        }
    }

    private static JsonObject schemaRoot(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        // A schemastery envelope points at its root through `uid`/`refs`.
        if (source.has("uid")
                && source.get("uid").isJsonPrimitive()
                && source.has("refs")
                && source.get("refs").isJsonObject()) {
            JsonElement root = source.getAsJsonObject("refs").get(source.get("uid").getAsString());
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        }
        return source;
    }

    private static JsonElement valueAtPath(JsonElement root, List<String> path) {
        JsonElement current = root;
        for (String segment : path) {
            if (current == null
                    || !current.isJsonObject()
                    || !current.getAsJsonObject().has(segment)) return null;
            current = current.getAsJsonObject().get(segment);
        }
        return current == null || current.isJsonNull() ? null : current;
    }

    private static boolean hasPath(JsonElement root, List<String> path) {
        JsonElement current = root;
        for (String segment : path) {
            if (current == null
                    || !current.isJsonObject()
                    || !current.getAsJsonObject().has(segment)) return false;
            current = current.getAsJsonObject().get(segment);
        }
        return true;
    }

    private static String labelOf(List<String> path) {
        String leaf = path.isEmpty() ? "Setting" : path.get(path.size() - 1);
        String spaced = CAMEL_BOUNDARY.matcher(leaf).replaceAll("$1 $2").replaceAll("[_-]+", " ");
        return spaced.isEmpty()
                ? spaced
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String descriptionOf(JsonObject node) {
        if (node == null) return null;
        String direct = string(node, "description", null);
        if (direct != null && !direct.isBlank()) return direct.trim();
        JsonObject meta =
                node.has("meta") && node.get("meta").isJsonObject()
                        ? node.getAsJsonObject("meta")
                        : null;
        String nested = meta == null ? null : string(meta, "description", null);
        return nested != null && !nested.isBlank() ? nested.trim() : null;
    }

    private static String typeOf(JsonElement value, JsonObject node) {
        String declared = node == null ? null : string(node, "type", null);
        if (value != null && value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) return "boolean";
            if (primitive.isNumber()) return "number";
            if (primitive.isString()) return "string";
        }
        if ("boolean".equals(declared)) return "boolean";
        if ("number".equals(declared) || "integer".equals(declared)) return "number";
        if ("string".equals(declared)) return "string";
        return "json";
    }

    private static String textOf(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.toString();
    }

    private static boolean isSensitivePath(List<String> path) {
        return !path.isEmpty() && SENSITIVE_LEAF.matcher(path.get(path.size() - 1)).find();
    }

    private static List<String> pathOf(JsonElement value) {
        List<String> path = new ArrayList<>();
        if (value == null || !value.isJsonArray()) return path;
        for (JsonElement segment : value.getAsJsonArray()) {
            if (segment.isJsonPrimitive()) path.add(segment.getAsString());
        }
        return path;
    }

    private static String joinPath(JsonElement value) {
        return key(pathOf(value));
    }

    private static String key(List<String> path) {
        return String.join("\0", path);
    }

    private static List<String> unkey(String value) {
        List<String> path = new ArrayList<>();
        if (value.isEmpty()) return path;
        for (String segment : value.split("\0", -1)) path.add(segment);
        return path;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : fallback;
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object != null
                    && object.has(key)
                    && object.get(key).isJsonPrimitive()
                    && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long number(JsonObject object, String key) {
        try {
            return object != null
                            && object.has(key)
                            && object.get(key).isJsonPrimitive()
                            && object.get(key).getAsJsonPrimitive().isNumber()
                    ? object.get(key).getAsLong()
                    : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
