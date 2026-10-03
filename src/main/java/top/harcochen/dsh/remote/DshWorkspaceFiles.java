package top.harcochen.dsh.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import top.harcochen.dsh.DshJson;

/** Host Workspace paths and exact UTF-8 previews, independent of the editor filesystem. */
public final class DshWorkspaceFiles {
    private static final int MAX_BYTES = 1024 * 1024;
    private final DshRemoteUnaryClient unary;

    DshWorkspaceFiles(DshRemoteUnaryClient unary) {
        this.unary = unary;
    }

    public record Preview(String absolutePath, String text) {}

    public static JsonObject args(String session, String path) {
        JsonObject args = new JsonObject();
        args.addProperty("workspaceFileScopeId", session);
        args.addProperty("path", path == null || path.isEmpty() ? "." : path);
        return args;
    }

    public JsonObject list(String session, String path) {
        JsonElement result = unary.call("workspaceFiles/list", args(session, path));
        if (!result.isJsonObject()) throw invalid("list");
        JsonObject listing = result.getAsJsonObject();
        String listed = DshJson.strictString(listing, "path");
        if (listed == null
                || !listing.has("entries")
                || !listing.get("entries").isJsonArray()
                || !listing.has("truncated")
                || !listing.get("truncated").isJsonPrimitive()
                || !listing.getAsJsonPrimitive("truncated").isBoolean()) throw invalid("list");
        if (!listed.isEmpty())
            for (String segment : listed.split("/", -1))
                if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                    throw invalid("list");
        Set<String> seen = new HashSet<>();
        for (JsonElement value : listing.getAsJsonArray("entries")) {
            if (!value.isJsonObject()) throw invalid("list");
            JsonObject entry = value.getAsJsonObject();
            String name = DshJson.strictString(entry, "name");
            if (name == null
                    || name.isBlank()
                    || name.contains("/")
                    || name.contains("\\")
                    || name.contains("\0")
                    || name.equals(".")
                    || name.equals("..")
                    || !seen.add(name)
                    || !Set.of("file", "directory", "other")
                            .contains(DshJson.stringOr(entry, "type", ""))) throw invalid("list");
        }
        return listing.deepCopy();
    }

    private JsonObject stat(String session, String path) {
        JsonElement result = unary.call("workspaceFiles/stat", args(session, path));
        if (!result.isJsonObject()) throw invalid("stat");
        JsonObject stat = result.getAsJsonObject();
        if (DshJson.strictString(stat, "absolutePath") == null
                || DshJson.strictString(stat, "version") == null) throw invalid("stat");
        return stat;
    }

    public Preview preview(String session, String path, Runnable assertEndpoint) {
        assertEndpoint.run();
        JsonObject before = stat(session, path);
        assertEndpoint.run();
        long bytes = DshJson.longValue(before.get("bytes"), -1);
        if (bytes > MAX_BYTES)
            throw new IllegalArgumentException(
                    top.harcochen.dsh.DshBundle.message("dsh.files.too.large"));
        JsonObject options = new JsonObject();
        JsonObject range = new JsonObject();
        range.addProperty("length", bytes < 0 ? MAX_BYTES + 1 : Math.max(1, bytes));
        options.add("range", range);
        JsonObject args = args(session, path);
        args.add("options", options);
        assertEndpoint.run();
        DshRemoteBinaryResponse.FileBytes file = unary.callFileBytes(args);
        assertEndpoint.run();
        if (!DshJson.bool(file.value(), "eof", false) || file.data().length > MAX_BYTES)
            throw new IllegalArgumentException(
                    top.harcochen.dsh.DshBundle.message("dsh.files.too.large"));
        JsonObject after = stat(session, path);
        assertEndpoint.run();
        String absolute = DshJson.strictString(before, "absolutePath");
        String version = DshJson.strictString(before, "version");
        if (!version.equals(DshJson.strictString(file.value(), "version"))
                || !version.equals(DshJson.strictString(after, "version"))
                || !absolute.equals(DshJson.strictString(file.value(), "absolutePath"))
                || !absolute.equals(DshJson.strictString(after, "absolutePath")))
            throw new IllegalArgumentException(
                    top.harcochen.dsh.DshBundle.message("dsh.files.changed"));
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(file.data()))
                            .toString();
            if (text.contains("\0"))
                throw new IllegalArgumentException(
                        top.harcochen.dsh.DshBundle.message("dsh.files.binary"));
            return new Preview(absolute, text);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException(
                    top.harcochen.dsh.DshBundle.message("dsh.files.binary"));
        }
    }

    private static DshRemoteException invalid(String endpoint) {
        return DshRemoteException.protocol(
                "workspaceFiles/" + endpoint, "Invalid Workspace Files response", null);
    }
}
