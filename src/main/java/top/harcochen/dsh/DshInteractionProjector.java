package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Presents declared plan reviews while preserving the ordinary question answer protocol. */
final class DshInteractionProjector {
    private DshInteractionProjector() {}

    static JsonArray present(JsonArray interactions) {
        JsonArray result = interactions.deepCopy();
        for (JsonElement candidate : result) {
            if (!candidate.isJsonObject()) continue;
            JsonObject item = candidate.getAsJsonObject();
            if (!"question".equals(string(item, "kind"))) continue;
            JsonObject review = planReview(item.get("questions"));
            if (review == null) continue;
            item.addProperty("kind", "plan-review");
            item.add("review", review);
            item.addProperty("planHtml", DshMessageProjector.markdownHtml(string(review, "plan")));
        }
        return result;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    /** The approve label comes from intent, never from the option position or question title. */
    private static JsonObject planReview(JsonElement questions) {
        if (questions == null
                || !questions.isJsonArray()
                || questions.getAsJsonArray().size() != 1
                || !questions.getAsJsonArray().get(0).isJsonObject()) return null;
        JsonObject question = questions.getAsJsonArray().get(0).getAsJsonObject();
        if (!question.has("intent") || !question.get("intent").isJsonObject()) return null;
        JsonObject intent = question.getAsJsonObject("intent");
        String approve = string(intent, "approve");
        String id = string(question, "id");
        String title = string(question, "question");
        String plan = string(question, "detail");
        if (!"plan-review".equals(string(intent, "kind"))
                || approve == null
                || approve.isBlank()
                || id == null
                || id.isBlank()
                || title == null
                || plan == null
                || (question.has("multiSelect")
                        && (!question.get("multiSelect").isJsonPrimitive()
                                || !question.getAsJsonPrimitive("multiSelect").isBoolean()
                                || question.get("multiSelect").getAsBoolean()))
                || !question.has("options")
                || !question.get("options").isJsonArray()) return null;
        JsonArray options = question.getAsJsonArray("options");
        if (options.isEmpty() || options.size() > 2) return null;
        boolean foundApprove = false;
        String decline = null;
        for (JsonElement option : options) {
            if (!option.isJsonObject()) return null;
            String label = string(option.getAsJsonObject(), "label");
            if (label == null || label.isBlank()) return null;
            if (approve.equals(label)) {
                if (foundApprove) return null;
                foundApprove = true;
            } else {
                decline = label;
            }
        }
        if (!foundApprove) return null;
        JsonObject review = new JsonObject();
        review.addProperty("id", id);
        review.addProperty("question", title);
        review.addProperty("plan", plan);
        review.addProperty("approve", approve);
        if (decline != null) review.addProperty("decline", decline);
        return review;
    }
}
