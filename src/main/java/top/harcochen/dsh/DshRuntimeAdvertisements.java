package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Interoperable dsh-ide discovery records; an editor writes and withdraws only its own file. */
final class DshRuntimeAdvertisements {
    private final String owner = UUID.randomUUID().toString();
    private final long createdAt = System.currentTimeMillis();

    static Path directory() throws Exception {
        String user =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                System.getProperty("user.home")
                                                        .getBytes(StandardCharsets.UTF_8)))
                        .substring(0, 16);
        return Path.of(System.getProperty("java.io.tmpdir"), "dsh-runtime-advertisements-" + user);
    }

    void publish(DshRuntimeEndpoint endpoint, String version, long runtimePid) {
        Path staging = null;
        try {
            Path dir = directory();
            if (Files.isSymbolicLink(dir)) return;
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.createDirectories(
                        dir,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectories(dir);
            if (!trustedDirectory(dir)) return;
            JsonObject value = new JsonObject();
            value.addProperty("ownerId", owner);
            value.addProperty("pid", ProcessHandle.current().pid());
            value.addProperty("runtimePid", runtimePid);
            value.addProperty("runtimeVersion", version);
            value.addProperty("createdAt", createdAt);
            value.addProperty("baseUrl", endpoint.baseUrl);
            if (endpoint.launchUrl != null) value.addProperty("launchUrl", endpoint.launchUrl);
            staging = Files.createTempFile(dir, owner + ".", ".tmp");
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(
                        staging, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(staging, value.toString(), StandardCharsets.UTF_8);
            Files.move(
                    staging,
                    dir.resolve(owner + ".json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unavailable) {
            /* Discovery failure must not prevent an owned launch. */
        } finally {
            if (staging != null)
                try {
                    Files.deleteIfExists(staging);
                } catch (Exception ignored) {
                }
        }
    }

    void withdraw() {
        try {
            Files.deleteIfExists(directory().resolve(owner + ".json"));
        } catch (Exception ignored) {
        }
    }

    List<DshRuntimeEndpoint> read() {
        List<DshRuntimeEndpoint> result = new ArrayList<>();
        try {
            Path dir = directory();
            List<Path> paths = new ArrayList<>();
            if (trustedDirectory(dir))
                try (var files = Files.list(dir)) {
                    paths.addAll(
                            files.filter(
                                            path ->
                                                    path.getFileName()
                                                            .toString()
                                                            .matches("[a-f0-9-]{36}\\.json"))
                                    .sorted(
                                            Comparator.comparingLong(
                                                            DshRuntimeAdvertisements::modified)
                                                    .reversed())
                                    .limit(16)
                                    .toList());
                }
            paths.add(Path.of(System.getProperty("java.io.tmpdir"), "dsh-runtime.lock"));
            paths.add(Path.of(System.getProperty("java.io.tmpdir"), "dsh-vscode-runtime.lock"));
            for (Path path : paths) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(path) > 64 * 1024) continue;
                try {
                    JsonObject record =
                            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    if (!DshRuntimeVersion.compatible(DshJson.string(record, "runtimeVersion")))
                        continue;
                    DshRuntimeEndpoint base =
                            DshRuntimeEndpoint.parse(
                                    DshJson.stringOr(
                                            record, "baseUrl", DshJson.string(record, "url")),
                                    true);
                    DshRuntimeEndpoint launch =
                            DshRuntimeEndpoint.parse(DshJson.string(record, "launchUrl"), true);
                    if (base != null && launch != null && base.baseUrl.equals(launch.baseUrl))
                        result.add(launch);
                } catch (RuntimeException ignored) {
                }
            }
        } catch (Exception unavailable) {
            /* Existing explicit server URLs remain usable. */
        }
        return result;
    }

    private static boolean trustedDirectory(Path dir) {
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false;
            var user =
                    dir.getFileSystem()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
            if (!Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).equals(user)) return false;
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) return false;
            }
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
