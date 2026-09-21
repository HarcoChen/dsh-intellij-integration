package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.PathManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Small, dependency-free managed Runtime installer.
 *
 * <p>The VS Code sidecar has the same cache contract. IntelliJ cannot reuse its Node installer, so
 * this class keeps the important boundary local: a pinned version, HTTPS manifest, exact SHA-256
 * and byte count, per-version lock, safe extraction, and an atomic final directory. A failed
 * download is never added to the launcher candidates; the existing package-manager fallback stays
 * available.
 */
final class DshManagedRuntime {
    private static final String RELEASE_ROOT =
            "https://cnb.cool/harcochen/dsh-runtime/-/releases/download/v";
    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    private DshManagedRuntime() {}

    @Nullable
    static Path ensure(String version, java.util.function.Consumer<String> log) {
        String target = target();
        if (target == null || DshRuntimeVersion.exact(version) == null) return null;
        Path root = Path.of(PathManager.getSystemPath(), "dsh", "runtime-managed");
        Path versionDir = root.resolve(target).resolve(version);
        try {
            Path cached = check(versionDir, target, version);
            if (cached != null) return cached;
            Files.createDirectories(root.resolve("locks"));
            Path lock =
                    root.resolve("locks").resolve("install-" + target + "-" + version + ".lock");
            String lockToken = acquire(lock);
            if (lockToken == null) return null;
            try {
                cached = check(versionDir, target, version);
                if (cached != null) return cached;
                return install(root, versionDir, target, version, log);
            } finally {
                release(lock, lockToken);
            }
        } catch (Exception error) {
            log.accept("[dsh:managed-runtime] unavailable: " + safeMessage(error));
            return null;
        }
    }

    @Nullable
    private static Path check(Path versionDir, String target, String version) throws IOException {
        Path metadata = versionDir.resolve("installed.json");
        Path launcher =
                versionDir
                        .resolve("dsh-runtime")
                        .resolve("bin")
                        .resolve(isWindows(target) ? "dsh.cmd" : "dsh");
        Path data = versionDir.resolve("dsh-runtime").resolve("app").resolve("node_modules");
        if (!Files.isRegularFile(metadata)
                || !Files.isRegularFile(launcher)
                || !Files.isDirectory(data)) return null;
        String raw = Files.readString(metadata);
        if (!raw.contains("\"version\":\"" + version + "\"")
                || !raw.contains("\"target\":\"" + target + "\"")) return null;
        if (!isWindows(target)) launcher.toFile().setExecutable(true, false);
        return launcher;
    }

    @Nullable
    private static String acquire(Path lock) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        String token = UUID.randomUUID().toString();
        while (System.nanoTime() < deadline) {
            try {
                Files.createFile(lock);
                Files.writeString(
                        lock, ProcessHandle.current().pid() + "\n" + token, StandardCharsets.UTF_8);
                return token;
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                if (lockOwnerAlive(lock)) {
                    Thread.sleep(500);
                    continue;
                }
                Files.deleteIfExists(lock);
            }
        }
        return null;
    }

    private static void release(Path lock, String token) {
        try {
            String[] lines = Files.readString(lock).split("\\R", -1);
            if (lines.length > 1 && token.equals(lines[1])) Files.deleteIfExists(lock);
        } catch (IOException ignored) {
        }
    }

    private static boolean lockOwnerAlive(Path lock) {
        try {
            String[] lines = Files.readString(lock).split("\\R", -1);
            if (lines.length == 0) return false;
            long pid = Long.parseLong(lines[0].trim());
            return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path install(
            Path root,
            Path versionDir,
            String target,
            String version,
            java.util.function.Consumer<String> log)
            throws Exception {
        String manifestUrl = RELEASE_ROOT + version + "/manifest.json";
        JsonObject manifest = getJson(manifestUrl, 1_048_576);
        if (!version.equals(DshJson.string(manifest, "version")))
            throw new IOException("managed Runtime manifest version mismatch");
        JsonObject platforms =
                manifest.get("platforms").isJsonObject()
                        ? manifest.getAsJsonObject("platforms")
                        : null;
        JsonObject asset = platforms == null ? null : platforms.getAsJsonObject(target);
        if (asset == null) throw new IOException("managed Runtime has no " + target + " asset");
        String filename = DshJson.string(asset, "filename");
        String sha256 = DshJson.string(asset, "sha256");
        long size = DshJson.longValue(asset.get("size"), -1);
        if (!safeFilename(filename)
                || sha256 == null
                || !sha256.matches("[0-9a-fA-F]{64}")
                || size <= 0) throw new IOException("managed Runtime manifest asset is invalid");
        Path downloadRoot = root.resolve("downloads");
        Files.createDirectories(downloadRoot);
        Path temp = downloadRoot.resolve("." + UUID.randomUUID());
        Files.createDirectories(temp);
        try {
            Path archive =
                    temp.resolve(filename.endsWith(".zip") ? "archive.zip" : "archive.tar.gz");
            download(RELEASE_ROOT + version + "/" + filename, archive);
            long actualSize = Files.size(archive);
            String actualHash = sha256(archive);
            if (actualSize != size || !actualHash.equalsIgnoreCase(sha256))
                throw new IOException("managed Runtime archive integrity check failed");
            Path staging = temp.resolve("staging");
            Files.createDirectories(staging);
            if (filename.endsWith(".zip")) extractZip(archive, staging);
            else extractTarGz(archive, staging);
            verify(staging, target);
            Path metadata = staging.resolve("installed.json");
            Files.writeString(
                    metadata,
                    "{\"version\":\""
                            + version
                            + "\",\"target\":\""
                            + target
                            + "\",\"filename\":\""
                            + filename
                            + "\",\"sha256\":\""
                            + actualHash
                            + "\",\"size\":"
                            + actualSize
                            + "}\n",
                    StandardCharsets.UTF_8);
            deleteTree(versionDir);
            Files.createDirectories(versionDir.getParent());
            try {
                Files.move(staging, versionDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, versionDir);
            }
            log.accept("[dsh:managed-runtime] installed " + version + " (" + target + ")");
            return check(versionDir, target, version);
        } finally {
            deleteTree(temp);
        }
    }

    private static JsonObject getJson(String url, int limit) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200
                || response.statusCode() >= 300
                || response.body().length() > limit)
            throw new IOException(
                    "managed Runtime manifest request failed (HTTP " + response.statusCode() + ")");
        JsonElement parsed = com.google.gson.JsonParser.parseString(response.body());
        if (!parsed.isJsonObject())
            throw new IOException("managed Runtime manifest is not an object");
        return parsed.getAsJsonObject();
    }

    private static void download(String url, Path destination) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();
        HttpResponse<Path> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException(
                    "managed Runtime download failed (HTTP " + response.statusCode() + ")");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static void extractZip(Path archive, Path staging) throws IOException {
        try (ZipInputStream input =
                new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = safePath(staging, entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (var output = new BufferedOutputStream(Files.newOutputStream(target))) {
                        input.transferTo(output);
                    }
                    target.toFile().setExecutable(true, false);
                }
            }
        }
    }

    private static void extractTarGz(Path archive, Path staging) throws IOException {
        Path tar = archive.resolveSibling("archive.tar");
        try (InputStream input =
                        new GZIPInputStream(
                                new BufferedInputStream(Files.newInputStream(archive)));
                var output = new BufferedOutputStream(Files.newOutputStream(tar))) {
            input.transferTo(output);
        }
        try (RandomAccessFile input = new RandomAccessFile(tar.toFile(), "r")) {
            byte[] header = new byte[512];
            String pendingLongName = null;
            Map<String, String> pendingPax = null;
            while (input.read(header) == header.length) {
                boolean zero = true;
                for (byte value : header)
                    if (value != 0) {
                        zero = false;
                        break;
                    }
                if (zero) break;
                String rawName = text(header, 0, 100);
                long entrySize = octal(header, 124, 12);
                long size = entrySize;
                int type = header[156] & 0xff;
                if (size < 0) throw new IOException("invalid Runtime archive entry size");
                if (type == 'L') {
                    pendingLongName = stripNul(readTarText(input, size));
                    skipPadding(input, size);
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    Map<String, String> parsed = parsePax(readTarText(input, size));
                    if (type == 'x') pendingPax = parsed;
                    skipPadding(input, size);
                    continue;
                }
                String name = pendingPax == null ? null : pendingPax.get("path");
                if (name == null || name.isBlank()) name = pendingLongName;
                if (name == null || name.isBlank()) {
                    name = rawName;
                    String prefix = text(header, 345, 155);
                    if (!prefix.isBlank()) name = prefix + "/" + name;
                }
                if (pendingPax != null && pendingPax.containsKey("size")) {
                    try {
                        size = Long.parseLong(pendingPax.get("size"));
                    } catch (NumberFormatException invalid) {
                        throw new IOException("invalid Runtime archive entry size", invalid);
                    }
                }
                if (size < 0) throw new IOException("invalid Runtime archive entry size");
                Path target = safePath(staging, name);
                if (type == '5') Files.createDirectories(target);
                else if (type == 0 || type == '0' || type == '7' || type == ' ') {
                    Files.createDirectories(target.getParent());
                    try (var output = new BufferedOutputStream(Files.newOutputStream(target))) {
                        copy(input, output, size);
                    }
                    int mode = (int) octal(header, 100, 8);
                    target.toFile().setExecutable((mode & 0100) != 0, false);
                } else skip(input, size);
                skipPadding(input, entrySize);
                pendingLongName = null;
                pendingPax = null;
            }
        } finally {
            Files.deleteIfExists(tar);
        }
    }

    private static void verify(Path staging, String target) throws IOException {
        int topLevelEntries = 0;
        try (var entries = Files.newDirectoryStream(staging)) {
            for (Path entry : entries) {
                topLevelEntries++;
                if (!entry.getFileName().toString().equals("dsh-runtime")
                        || !Files.isDirectory(entry))
                    throw new IOException("managed Runtime archive has an invalid top-level entry");
            }
        }
        if (topLevelEntries != 1)
            throw new IOException("managed Runtime archive must contain one dsh-runtime directory");
        Path root = staging.resolve("dsh-runtime");
        Path launcher = root.resolve("bin").resolve(isWindows(target) ? "dsh.cmd" : "dsh");
        if (!Files.isDirectory(root) || !Files.isRegularFile(launcher))
            throw new IOException("managed Runtime launcher missing");
        if (!Files.isDirectory(root.resolve("app").resolve("node_modules")))
            throw new IOException("managed Runtime data missing");
        if (!isWindows(target)) launcher.toFile().setExecutable(true, false);
    }

    private static Path safePath(Path root, String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\0"))
            throw new IOException("invalid archive entry");
        String normalized = raw.replace('\\', '/');
        Path result = root.resolve(normalized).normalize();
        if (!result.startsWith(root)
                || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*"))
            throw new IOException("archive entry escapes staging directory");
        return result;
    }

    private static void copy(RandomAccessFile input, BufferedOutputStream output, long size)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("truncated Runtime archive");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void skip(RandomAccessFile input, long size) throws IOException {
        input.seek(input.getFilePointer() + size);
    }

    private static void skipPadding(RandomAccessFile input, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        skip(input, padding);
    }

    private static String readTarText(RandomAccessFile input, long size) throws IOException {
        if (size > 4 * 1024 * 1024) throw new IOException("Runtime archive metadata is too large");
        byte[] bytes = new byte[(int) size];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) throw new IOException("truncated Runtime archive");
            offset += read;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String stripNul(String value) {
        return value.replaceFirst("\\0+$", "");
    }

    private static Map<String, String> parsePax(String value) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        int offset = 0;
        while (offset < value.length()) {
            int space = value.indexOf(' ', offset);
            if (space <= offset) throw new IOException("invalid Runtime PAX header");
            int length;
            try {
                length = Integer.parseInt(value.substring(offset, space));
            } catch (NumberFormatException invalid) {
                throw new IOException("invalid Runtime PAX header", invalid);
            }
            if (length <= 0 || offset + length > value.length())
                throw new IOException("invalid Runtime PAX header");
            String record = value.substring(space + 1, offset + length);
            if (!record.endsWith("\n")) throw new IOException("invalid Runtime PAX header");
            int equals = record.indexOf('=');
            if (equals > 0)
                result.put(
                        record.substring(0, equals),
                        record.substring(equals + 1, record.length() - 1));
            offset += length;
        }
        return result;
    }

    private static String text(byte[] source, int offset, int length) {
        return new String(source, offset, length, StandardCharsets.UTF_8).replace("\0", "").trim();
    }

    private static long octal(byte[] source, int offset, int length) {
        String value = text(source, offset, length);
        return value.isBlank() ? 0 : Long.parseLong(value, 8);
    }

    private static boolean safeFilename(String value) {
        return value != null
                && !value.isBlank()
                && !value.equals(".")
                && !value.equals("..")
                && !value.contains("/")
                && !value.contains("\\")
                && !value.contains("\0");
    }

    private static String target() {
        String platform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os =
                platform.contains("mac") || platform.contains("darwin")
                        ? "darwin"
                        : platform.contains("win")
                                ? "win32"
                                : platform.contains("linux") ? "linux" : null;
        if (os == null) return null;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu =
                arch.contains("aarch64") || arch.contains("arm64")
                        ? "arm64"
                        : arch.contains("amd64") || arch.contains("x86_64") ? "x64" : null;
        if (cpu == null) return null;
        if (os.equals("win32") && !cpu.equals("x64")) return null;
        return os + "-" + cpu;
    }

    private static boolean isWindows(String target) {
        return target.startsWith("win32-");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException error)
                                throws IOException {
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}
