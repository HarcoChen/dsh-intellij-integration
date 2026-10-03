package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import org.jetbrains.annotations.NotNull;

/** Project-local, bounded question drafts, including across IDE restarts. */
@State(name = "DshChatDrafts", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DshChatDraftState implements PersistentStateComponent<DshChatDraftState.Data> {
    public static final class Data {
        public String drafts = "{}";
    }

    private volatile String drafts = "{}";

    public synchronized void save(JsonElement value) {
        if (value == null || !value.isJsonObject()) return;
        JsonElement questions = value.getAsJsonObject().get("questionDrafts");
        if (questions == null
                || !questions.isJsonObject()
                || questions.getAsJsonObject().size() > 100) return;
        String encoded = questions.toString();
        if (encoded.length() > 512_000) return;
        drafts = encoded;
    }

    public JsonObject view() {
        JsonObject state = new JsonObject();
        try {
            state.add("questionDrafts", JsonParser.parseString(drafts));
        } catch (RuntimeException invalid) {
            state.add("questionDrafts", new JsonObject());
        }
        return state;
    }

    /** Each view sends only edits/deletions, so an idle mirror cannot overwrite another draft. */
    public synchronized void merge(JsonElement input) {
        if (input == null || !input.isJsonObject()) return;
        JsonElement value = input.getAsJsonObject().get("changes");
        if (value == null || !value.isJsonObject() || value.getAsJsonObject().size() > 100) return;
        JsonObject current = view().getAsJsonObject("questionDrafts");
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (entry.getKey().length() > 1024) return;
            if (entry.getValue().isJsonNull()) current.remove(entry.getKey());
            else {
                current.remove(entry.getKey());
                current.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        while (current.size() > 100) current.remove(current.keySet().iterator().next());
        String encoded = current.toString();
        if (encoded.length() <= 512_000) drafts = encoded;
    }

    @Override
    public Data getState() {
        Data data = new Data();
        data.drafts = drafts;
        return data;
    }

    @Override
    public void loadState(@NotNull Data data) {
        if (data.drafts == null || data.drafts.length() > 512_000) return;
        try {
            JsonElement value = JsonParser.parseString(data.drafts);
            if (value.isJsonObject() && value.getAsJsonObject().size() <= 100)
                drafts = value.toString();
        } catch (RuntimeException ignored) {
        }
    }
}
