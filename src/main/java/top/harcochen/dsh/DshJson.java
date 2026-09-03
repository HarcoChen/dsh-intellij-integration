package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Small, defensive JSON accessors shared by the webview-facing controllers. */
final class DshJson {
    private DshJson() {}

    static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : null;
    }

    static String stringOr(JsonObject object, String key, String fallback) {
        String value = string(object, key);
        return value == null ? fallback : value;
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static long longValue(JsonElement element, long fallback) {
        try {
            return element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isNumber()
                    ? element.getAsLong()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static void copyString(JsonObject source, JsonObject target, String key) {
        String value = string(source, key);
        if (value != null) {
            target.addProperty(key, value);
        }
    }

    static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null
                && (cause instanceof java.util.concurrent.CompletionException
                        || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
