package top.harcochen.dsh.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import top.harcochen.dsh.DshJson;

/** UI authorization hints; the Host still authorizes each invocation. */
public final class DshRemoteAccess {
    private record Grant(String kind, String id, List<String> actions) {}

    private record State(boolean available, boolean owner, List<Grant> grants) {}

    private volatile State state = new State(false, false, List.of());
    private volatile long revision;

    public long revision() {
        return revision;
    }

    public boolean available() {
        return state.available;
    }

    public boolean owner() {
        return state.available && state.owner;
    }

    void reset() {
        revision++;
        state = new State(false, false, List.of());
    }

    void legacyOwner() {
        revision++;
        state = new State(true, true, List.of());
    }

    public boolean allows(String action) {
        return allows(action, "global", null);
    }

    public boolean allows(String action, String kind, String id) {
        State current = state;
        return current.available
                && (current.owner
                        || current.grants.stream()
                                .anyMatch(
                                        grant ->
                                                grant.kind.equals(kind)
                                                        && java.util.Objects.equals(grant.id, id)
                                                        && grant.actions.contains(action)));
    }

    void apply(JsonObject frame, boolean first) {
        revision++;
        String type = DshJson.string(frame, "type");
        if (first != "baseline".equals(type)) throw invalid();
        if ("baseline".equals(type)) {
            if (!frame.has("owner")
                    || !frame.get("owner").isJsonPrimitive()
                    || !frame.getAsJsonPrimitive("owner").isBoolean()) throw invalid();
            boolean owner = frame.get("owner").getAsBoolean();
            List<Grant> grants = new ArrayList<>();
            if (!owner) {
                if (!frame.has("grants") || !frame.get("grants").isJsonArray()) throw invalid();
                for (JsonElement value : frame.getAsJsonArray("grants")) grants.add(grant(value));
            }
            state = new State(true, owner, List.copyOf(grants));
        } else if (state.available
                && !state.owner
                && ("upsert".equals(type) || "remove".equals(type))) {
            Grant next =
                    "upsert".equals(type)
                            ? grant(frame.get("grant"))
                            : resource(frame.get("resource"), List.of());
            List<Grant> grants = new ArrayList<>(state.grants);
            grants.removeIf(
                    item ->
                            item.kind.equals(next.kind)
                                    && java.util.Objects.equals(item.id, next.id));
            if ("upsert".equals(type)) grants.add(next);
            state = new State(true, false, List.copyOf(grants));
        } else throw invalid();
    }

    private static Grant grant(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid();
        JsonObject row = value.getAsJsonObject();
        if (!row.has("actions") || !row.get("actions").isJsonArray()) throw invalid();
        List<String> actions = new ArrayList<>();
        for (JsonElement action : row.getAsJsonArray("actions")) {
            if (!action.isJsonPrimitive() || !action.getAsJsonPrimitive().isString())
                throw invalid();
            actions.add(action.getAsString());
        }
        return resource(row.get("resource"), List.copyOf(actions));
    }

    private static Grant resource(JsonElement value, List<String> actions) {
        if (value == null || !value.isJsonObject()) throw invalid();
        String kind = DshJson.strictString(value.getAsJsonObject(), "kind");
        String id = DshJson.strictString(value.getAsJsonObject(), "id");
        if (kind == null
                || kind.isBlank()
                || (!kind.equals("global") && (id == null || id.isBlank()))) throw invalid();
        return new Grant(kind, kind.equals("global") ? null : id, actions);
    }

    private static DshRemoteException invalid() {
        return DshRemoteException.protocol(
                "remoteAccess/follow", "Invalid Remote access frame", null);
    }
}
