package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Cross-editor Runtime ownership, using dsh-ide's kernel gate and atomic file guard protocol. */
final class DshRuntimeLock {
    private static final Logger LOG = Logger.getInstance(DshRuntimeLock.class);
    private final Path path;
    private final Path legacyPath;
    private final String ownerId = UUID.randomUUID().toString();
    private volatile boolean held;
    private JsonObject owned;
    private Object ownedFileKey;

    DshRuntimeLock() {
        this(temporaryDirectory());
    }

    DshRuntimeLock(Path directory) {
        path = directory.resolve("dsh-runtime.lock");
        legacyPath = directory.resolve("dsh-vscode-runtime.lock");
    }

    Path getPath() {
        return path;
    }

    boolean isHeld() {
        return held;
    }

    synchronized boolean acquire() {
        if (held) return true;
        try (Mutation ignored = mutation()) {
            for (Path candidate : new Path[] {legacyPath, path}) {
                Snapshot existing = read(candidate);
                if (existing != null) {
                    if (!reclaimable(existing.record) || !remove(existing)) return false;
                }
            }
            JsonObject record = new JsonObject();
            record.addProperty("pid", ProcessHandle.current().pid());
            record.addProperty("createdAt", System.currentTimeMillis());
            record.addProperty("ownerId", ownerId);
            atomicCreate(path, record.toString());
            owned = record;
            ownedFileKey = attributes(path).fileKey();
            held = true;
            return true;
        } catch (IOException error) {
            LOG.debug("Unable to acquire DSH runtime lock", error);
            return false;
        }
    }

    synchronized void publishProcess(Process process, String version, boolean wrapper) {
        if (!held) return;
        owned.addProperty("runtimePid", process.pid());
        owned.addProperty("runtimeVersion", version);
        owned.addProperty("runtimeProcess", wrapper ? "wrapper" : "direct");
        process.info()
                .startInstant()
                .ifPresent(time -> owned.addProperty("runtimeStartedAt", time.toString()));
        writeOwned();
    }

    synchronized void publishHelperProcess(long pid, String version, Long group) {
        if (!held || pid <= 0 || !DshRuntimeVersion.compatible(version)) return;
        owned.addProperty("runtimePid", pid);
        owned.addProperty("runtimeVersion", version);
        owned.addProperty("runtimeProcess", "wrapper");
        owned.remove("runtimeStartedAt");
        ProcessHandle.of(pid)
                .flatMap(handle -> handle.info().startInstant())
                .ifPresent(time -> owned.addProperty("runtimeStartedAt", time.toString()));
        if (group != null && group == pid) owned.addProperty("runtimeProcessGroup", group);
        writeOwned();
    }

    void publishUrl(String url) {
        publishUrl(url, null);
    }

    synchronized void publishUrl(String url, String launchUrl) {
        if (!held || loopbackUrl(url) == null) return;
        owned.addProperty("url", url);
        String launch = loopbackLaunchUrl(launchUrl, url);
        if (launch != null) owned.addProperty("launchUrl", launch);
        writeOwned();
    }

    private void writeOwned() {
        try (Mutation ignored = mutation()) {
            Snapshot current = read(path);
            if (!isOwned(current)) {
                held = false;
                return;
            }
            Path staging = stage(path, owned.toString());
            try {
                Files.move(
                        staging,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staging);
            }
            ownedFileKey = attributes(path).fileKey();
        } catch (IOException error) {
            LOG.debug("Unable to publish DSH Runtime ownership", error);
        }
    }

    synchronized void release() {
        if (!held) return;
        try (Mutation ignored = mutation()) {
            Snapshot current = read(path);
            if (isOwned(current) && !runtimeExited()) return;
            if (isOwned(current)) remove(current);
            held = false;
        } catch (IOException error) {
            LOG.debug("Unable to release DSH Runtime ownership", error);
        }
    }

    private boolean isOwned(Snapshot current) {
        return current != null
                && current.record != null
                && Objects.equals(current.key, ownedFileKey)
                && ownerId.equals(DshJson.string(current.record, "ownerId"));
    }

    String readAdvertisedUrl() {
        DshRuntimeEndpoint endpoint = readAdvertisedEndpoint();
        return endpoint == null ? null : endpoint.baseUrl;
    }

    DshRuntimeEndpoint readAdvertisedEndpoint() {
        for (Path candidate : new Path[] {path, legacyPath}) {
            Snapshot snapshot = read(candidate);
            if (snapshot == null || snapshot.record == null) continue;
            JsonObject record = snapshot.record;
            if (!DshRuntimeVersion.compatible(DshJson.string(record, "runtimeVersion"))) continue;
            DshRuntimeEndpoint base = DshRuntimeEndpoint.parse(DshJson.string(record, "url"), true);
            DshRuntimeEndpoint launch =
                    DshRuntimeEndpoint.parse(DshJson.string(record, "launchUrl"), true);
            if (launch != null && (base == null || base.baseUrl.equals(launch.baseUrl)))
                return launch;
            if (base != null) return base;
        }
        return null;
    }

    /** A readable lock remains evidence even if its Runtime is too old to attach. */
    JsonObject blockedRecord() {
        for (Path candidate : new Path[] {path, legacyPath}) {
            Snapshot snapshot = read(candidate);
            if (snapshot != null && snapshot.record != null) return snapshot.record.deepCopy();
        }
        return null;
    }

    private static boolean exited(JsonObject record, String key) {
        long pid = DshJson.longValue(record == null ? null : record.get(key), -1);
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
        } catch (SecurityException error) {
            return false;
        }
    }

    private static boolean reclaimable(JsonObject record) {
        if (record == null || !exited(record, "pid")) return false;
        if (record.has("runtimePid") && !exited(record, "runtimePid")) return false;
        // A surviving POSIX process group cannot be established as gone by a PID-only check.
        if (record.has("runtimeProcessGroup")
                && !groupExited(DshJson.longValue(record.get("runtimeProcessGroup"), -1)))
            return false;
        String address = DshJson.string(record, "url");
        if (address == null) address = DshJson.string(record, "launchUrl");
        if (address == null)
            return record.has("runtimePid")
                    && "direct".equals(DshJson.string(record, "runtimeProcess"));
        DshRuntimeEndpoint endpoint = DshRuntimeEndpoint.parse(address, true);
        if (endpoint == null) return false;
        URI uri = URI.create(endpoint.baseUrl);
        if ("localhost".equals(uri.getHost()) || uri.getPort() <= 0) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1000);
            return false;
        } catch (java.net.ConnectException refused) {
            return true;
        } catch (IOException unknown) {
            return false;
        }
    }

    static boolean groupExited(long group) {
        if (group <= 0) return false;
        try {
            Process ps = new ProcessBuilder("ps", "-eo", "pgid=,stat=").start();
            String output =
                    new String(ps.getInputStream().readNBytes(1024 * 1024), StandardCharsets.UTF_8);
            if (!ps.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || ps.exitValue() != 0)
                return false;
            for (String line : output.split("\\R")) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length >= 2
                        && fields[0].equals(Long.toString(group))
                        && !fields[1].startsWith("Z")) return false;
            }
            return true;
        } catch (IOException | InterruptedException error) {
            return false;
        }
    }

    synchronized boolean runtimeExited() {
        if (owned == null) return true;
        if (owned.has("runtimeProcessGroup"))
            return groupExited(DshJson.longValue(owned.get("runtimeProcessGroup"), -1));
        return !owned.has("runtimePid") || exited(owned, "runtimePid");
    }

    private record Snapshot(Path path, Object key, String text, JsonObject record) {}

    private static Snapshot read(Path path) {
        try {
            BasicFileAttributes before = attributes(path);
            if (!before.isRegularFile() || before.size() > 65536)
                return new Snapshot(path, before.fileKey(), "", null);
            String text = Files.readString(path);
            BasicFileAttributes after = attributes(path);
            if (!Objects.equals(before.fileKey(), after.fileKey()))
                return new Snapshot(path, after.fileKey(), "", null);
            JsonObject value = null;
            try {
                var parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) value = parsed.getAsJsonObject();
            } catch (RuntimeException invalid) {
                /* Unknown records are retained. */
            }
            return new Snapshot(path, after.fileKey(), text, value);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException unknown) {
            return new Snapshot(path, null, "", null);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean remove(Snapshot expected) throws IOException {
        Snapshot current = read(expected.path);
        if (current == null) return true;
        if (expected.key == null
                || !Objects.equals(expected.key, current.key)
                || !expected.text.equals(current.text)) return false;
        Files.delete(expected.path);
        return true;
    }

    private static Path stage(Path target, String contents) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createFile(
                    temp,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            Set.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException unsupported) {
            Files.createFile(temp);
        }
        Files.writeString(temp, contents, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        return temp;
    }

    private static void atomicCreate(Path target, String contents) throws IOException {
        Path temp = stage(target, contents);
        try {
            Files.createLink(target, temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Mutation mutation() throws IOException {
        String canonical = path.getParent().toRealPath().resolve(path.getFileName()).toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            canonical = canonical.toLowerCase(Locale.ROOT);
        byte[] digest;
        try {
            digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
        long hash = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(digest).getInt());
        int port = 16384 + (int) (hash % 16384);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        ServerSocket gate;
        while (true) {
            gate = new ServerSocket();
            gate.setReuseAddress(false);
            try {
                gate.bind(new InetSocketAddress("127.0.0.1", port));
                break;
            } catch (java.net.BindException busy) {
                gate.close();
                pause(deadline);
            } catch (IOException error) {
                gate.close();
                throw error;
            }
        }
        Path guard = path.resolveSibling(path.getFileName() + ".mutation");
        JsonObject record = new JsonObject();
        record.addProperty("pid", ProcessHandle.current().pid());
        record.addProperty("createdAt", System.currentTimeMillis());
        record.addProperty("ownerId", UUID.randomUUID().toString());
        try {
            while (true) {
                try {
                    atomicCreate(guard, record.toString());
                    return new Mutation(gate, read(guard));
                } catch (FileAlreadyExistsException busy) {
                    Snapshot stale = read(guard);
                    if (stale != null
                            && stale.record != null
                            && exited(stale.record, "pid")
                            && remove(stale)) continue;
                    pause(deadline);
                }
            }
        } catch (IOException error) {
            gate.close();
            throw error;
        }
    }

    private static void pause(long deadline) throws IOException {
        if (System.nanoTime() >= deadline)
            throw new IOException("DSH Runtime lock is busy; retry shortly.");
        try {
            Thread.sleep(25);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }

    private record Mutation(ServerSocket gate, Snapshot guard) implements AutoCloseable {
        public void close() throws IOException {
            try {
                if (guard != null) remove(guard);
            } finally {
                gate.close();
            }
        }
    }

    private static Path temporaryDirectory() {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] names =
                windows ? new String[] {"TEMP", "TMP"} : new String[] {"TMPDIR", "TMP", "TEMP"};
        String resolved = null;
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                resolved = value;
                break;
            }
        }
        if (resolved == null && windows) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) systemRoot = System.getenv("windir");
            resolved = systemRoot == null || systemRoot.isBlank() ? null : systemRoot + "\\temp";
        }
        if (resolved == null || resolved.isBlank()) {
            resolved = windows ? System.getProperty("java.io.tmpdir", "C:\\Windows\\temp") : "/tmp";
        }
        // Node strips one trailing separator, but never reduces the path to
        // nothing; Path.of normalizes the rest.
        char separator = windows ? '\\' : '/';
        if (resolved.length() > 1
                && (resolved.charAt(resolved.length() - 1) == separator
                        || resolved.charAt(resolved.length() - 1) == '/')) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        try {
            return Path.of(resolved);
        } catch (RuntimeException error) {
            LOG.debug(
                    "Unusable temporary directory "
                            + resolved
                            + "; falling back to the JVM default",
                    error);
            return Path.of(System.getProperty("java.io.tmpdir", "."));
        }
    }

    static String loopbackUrl(String value) {
        DshRuntimeEndpoint endpoint = DshRuntimeEndpoint.parse(value, true);
        return endpoint == null || endpoint.launchUrl != null ? null : endpoint.baseUrl;
    }

    private static String loopbackLaunchUrl(String value, String baseUrl) {
        if (value == null || value.isBlank()) return null;
        DshRuntimeEndpoint endpoint = DshRuntimeEndpoint.parse(value, true);
        return endpoint == null || endpoint.launchUrl == null || !baseUrl.equals(endpoint.baseUrl)
                ? null
                : endpoint.launchUrl;
    }
}
