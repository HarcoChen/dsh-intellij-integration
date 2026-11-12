package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Bounded, atomic, read-only review history scoped to project and Runtime endpoint. */
final class DshReviewArchive {
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FILES = 1000;
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final long MAX_AGE = java.time.Duration.ofDays(30).toMillis();
    private final Path root;
    private final Supplier<String> endpoint;
    private final Map<String, JsonArray> loaded = new ConcurrentHashMap<>();

    DshReviewArchive(String projectPath, Supplier<String> endpoint) {
        this.root = Path.of(PathManager.getSystemPath(), "dsh-review-history", hash(projectPath));
        this.endpoint = endpoint;
    }

    String scope() {
        return hash(endpoint.get());
    }

    JsonArray load(String scope, String session) {
        String key = scope + "/" + hash(session);
        return loaded.computeIfAbsent(key, ignored -> read(root.resolve(key + ".json"))).deepCopy();
    }

    void save(String scope, String session, JsonObject review) throws Exception {
        if (!review.has("files")
                || !review.get("files").isJsonArray()
                || review.getAsJsonArray("files").size() > MAX_FILES)
            throw new java.io.IOException("Review exceeds archive file limit (1000)");
        JsonArray rows = load(scope, session);
        JsonArray next = new JsonArray();
        next.add(review);
        for (JsonElement row : rows) {
            if (next.size() == 20) break;
            if (DshJson.integer(row.getAsJsonObject(), "turn", -1)
                    != DshJson.integer(review, "turn", -1)) next.add(row);
        }
        byte[] bytes = next.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            // Drop older turns before refusing an individually oversized review.
            next = new JsonArray();
            next.add(review);
            bytes = next.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES)
                throw new java.io.IOException("Review exceeds archive limit (8 MiB)");
        }
        Path directory = root.resolve(scope);
        Files.createDirectories(directory);
        privatePermissions(root, true);
        privatePermissions(directory, true);
        Path target = directory.resolve(hash(session) + ".json");
        Path temp = Files.createTempFile(directory, "review-", ".tmp");
        try {
            privatePermissions(temp, false);
            Files.write(temp, bytes);
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            loaded.put(scope + "/" + hash(session), next.deepCopy());
        } finally {
            Files.deleteIfExists(temp);
        }
        prune();
    }

    private static JsonArray read(Path path) {
        try {
            if (!Files.isRegularFile(path)
                    || Files.size(path) > MAX_FILE_BYTES
                    || System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis()
                            > MAX_AGE) return new JsonArray();
            JsonElement value = JsonParser.parseString(Files.readString(path));
            if (!value.isJsonArray() || value.getAsJsonArray().size() > 20) return new JsonArray();
            JsonArray rows = value.getAsJsonArray();
            for (JsonElement entry : rows) {
                if (!entry.isJsonObject()) return new JsonArray();
                JsonObject row = entry.getAsJsonObject();
                if (DshJson.integer(row, "turn", -1) <= 0
                        || !row.has("files")
                        || !row.get("files").isJsonArray()
                        || row.getAsJsonArray("files").size() > MAX_FILES) return new JsonArray();
                for (JsonElement raw : row.getAsJsonArray("files")) {
                    if (!raw.isJsonObject()) return new JsonArray();
                    JsonObject file = raw.getAsJsonObject();
                    String id = DshJson.strictString(file, "id");
                    String name = DshJson.strictString(file, "path");
                    if (id == null
                            || id.length() > 512
                            || name == null
                            || name.length() > 4096
                            || !java.util.Set.of("added", "modified", "deleted", "renamed")
                                    .contains(DshJson.stringOr(file, "status", "")))
                        return new JsonArray();
                    if (!DshJson.bool(file, "binary", false)
                            && (DshJson.strictString(file, "beforeText") == null
                                    || DshJson.strictString(file, "afterText") == null))
                        return new JsonArray();
                }
            }
            return rows;
        } catch (Exception invalid) {
            return new JsonArray();
        }
    }

    private void prune() throws Exception {
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root, 2)) {
            walk.filter(p -> p.toString().endsWith(".json")).forEach(paths::add);
        }
        paths.sort(Comparator.comparingLong(DshReviewArchive::modified).reversed());
        long total = 0;
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            total += Files.size(path);
            if (i >= 100
                    || total > MAX_BYTES
                    || System.currentTimeMillis() - modified(path) > MAX_AGE)
                Files.deleteIfExists(path);
        }
        loaded.clear();
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception missing) {
            return 0;
        }
    }

    private static void privatePermissions(Path path, boolean directory) throws Exception {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.setPosixFilePermissions(
                    path,
                    java.nio.file.attribute.PosixFilePermissions.fromString(
                            directory ? "rwx------" : "rw-------"));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            String.valueOf(value)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
