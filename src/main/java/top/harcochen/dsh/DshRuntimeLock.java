package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

/**
 * The start lock the VS Code extension and this plugin share, so one dsh Runtime serves every
 * editor on the machine instead of one per IDE.
 *
 * <p>The file and its record are dsh-ide's ({@code src/dshRuntime.ts}): a JSON object {@code {pid,
 * createdAt, url?, launchUrl?}} at {@code <tmp>/dsh-runtime.lock}, created with an exclusive open
 * so two starters cannot both win. Whoever wins spawns the Runtime and publishes its URL into the
 * record; whoever loses polls that URL and attaches to the Runtime already coming up. A lock whose
 * owning process is gone is stale and is reclaimed rather than obeyed.
 *
 * <p>The temporary directory is resolved the way Node's {@code os.tmpdir()} resolves it, NOT
 * through {@code java.io.tmpdir}. The two agree on macOS and Windows but diverge on Linux, where
 * Node honours {@code $TMPDIR} and the JVM always answers {@code /tmp} — and a lock in two
 * different directories is not a shared lock at all. For the same reason the environment is read
 * straight from the process ({@link System#getenv}) rather than through IntelliJ's login-shell
 * environment helper: VS Code's Node sees the process environment, so this must too.
 */
final class DshRuntimeLock {
    private static final Logger LOG = Logger.getInstance(DshRuntimeLock.class);

    /** The editor-neutral address both integrations agree on. */
    private static final String LOCK_FILE_NAME = "dsh-runtime.lock";

    /**
     * The name dsh-ide used before the lock was shared. A VS Code build that has not updated yet
     * still owns that file, so it is read for an advertised URL and deferred to while its owner
     * lives — otherwise the rename would reintroduce exactly the double-spawn the lock exists to
     * prevent.
     */
    private static final String LEGACY_LOCK_FILE_NAME = "dsh-vscode-runtime.lock";

    private static final int MAX_RECLAIM_ATTEMPTS = 3;
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path path;
    private final Path legacyPath;
    private long createdAt;
    private volatile boolean held;

    DshRuntimeLock() {
        Path temporary = temporaryDirectory();
        this.path = temporary.resolve(LOCK_FILE_NAME);
        this.legacyPath = temporary.resolve(LEGACY_LOCK_FILE_NAME);
    }

    Path getPath() {
        return path;
    }

    boolean isHeld() {
        return held;
    }

    /**
     * Node's {@code os.tmpdir()}: the first of TMPDIR/TMP/TEMP that is set, falling back to {@code
     * /tmp}, with one trailing separator stripped.
     */
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

    /**
     * Take the lock, or report that another starter holds it.
     *
     * @return true when this process now owns the lock and must spawn the Runtime; false when a
     *     live owner is already starting one.
     */
    boolean acquire() {
        // A VS Code build on the pre-rename lock cannot see ours, so check its
        // file first: deferring to a live legacy owner is what keeps the
        // transition from spawning two Runtimes.
        if (ownerIsAlive(legacyPath)) return false;
        for (int attempt = 0; attempt < MAX_RECLAIM_ATTEMPTS; attempt++) {
            long stamp = System.currentTimeMillis();
            try {
                // CREATE_NEW is O_EXCL, the same exclusivity Node's "wx" gives
                // dsh-ide: the loser of the race gets a failure, never a handle.
                Files.write(
                        path,
                        record(stamp, null, null).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                restrictPermissions();
                createdAt = stamp;
                held = true;
                return true;
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                if (ownerIsAlive(path)) return false;
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    LOG.debug("Unable to reclaim the stale DSH runtime lock", error);
                    return false;
                }
            } catch (IOException error) {
                LOG.debug("Unable to create the DSH runtime lock", error);
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the recorded owner is still running. An unreadable or pid-less record is treated as
     * stale — it cannot be waited on — while a live pid means another starter genuinely holds the
     * lock.
     */
    private boolean ownerIsAlive(Path candidate) {
        JsonObject stored = read(candidate);
        if (stored == null) return false;
        JsonElement pid = stored.get("pid");
        if (pid == null || !pid.isJsonPrimitive() || !pid.getAsJsonPrimitive().isNumber())
            return false;
        try {
            long value = pid.getAsLong();
            if (value <= 0 || value == ProcessHandle.current().pid()) return false;
            return ProcessHandle.of(value).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException error) {
            return false;
        }
    }

    /**
     * Advertise the started Runtime's URL, so a peer that lost the race — or one that starts later
     * — attaches instead of spawning a second Runtime. Only the owner writes; a non-owner would be
     * corrupting someone's record.
     */
    void publishUrl(String url) {
        publishUrl(url, null);
    }

    /** Publish the base URL and, when present, the token-bearing launch URL for peer auth. */
    void publishUrl(String url, String launchUrl) {
        if (!held) return;
        String advertised = loopbackUrl(url);
        if (advertised == null) return;
        String advertisedLaunch = loopbackLaunchUrl(launchUrl, advertised);
        try {
            restrictPermissions();
            Files.write(
                    path,
                    record(createdAt, advertised, advertisedLaunch)
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            LOG.debug("Unable to publish the DSH runtime URL", error);
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some mounted filesystems do not expose POSIX permissions. The lock is
            // still created in the per-user temporary directory and the token is never logged.
        }
    }

    /** Release an owned lock. A lock this process never took is left alone. */
    void release() {
        if (!held) return;
        held = false;
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            LOG.debug("Unable to release the DSH runtime lock", error);
        }
    }

    /**
     * The URL a live owner has advertised, or null when the lock is absent, unreadable, or has not
     * reached the publishing step yet.
     */
    String readAdvertisedUrl() {
        DshRuntimeEndpoint endpoint = readAdvertisedEndpoint();
        return endpoint == null ? null : endpoint.baseUrl;
    }

    /** Read a trusted loopback endpoint, retaining a separately advertised launch URL. */
    DshRuntimeEndpoint readAdvertisedEndpoint() {
        // The shared lock wins; the legacy one still answers for a VS Code
        // build that has not updated yet.
        for (Path candidate : new Path[] {path, legacyPath}) {
            JsonObject stored = read(candidate);
            if (stored == null) continue;
            JsonElement url = stored.get("url");
            DshRuntimeEndpoint advertised =
                    url != null && url.isJsonPrimitive()
                            ? DshRuntimeEndpoint.parse(url.getAsString(), true)
                            : null;
            JsonElement launch = stored.get("launchUrl");
            String launchValue =
                    launch != null && launch.isJsonPrimitive() ? launch.getAsString() : null;
            DshRuntimeEndpoint launchEndpoint =
                    launchValue == null ? null : DshRuntimeEndpoint.parse(launchValue, true);
            String baseUrl =
                    advertised != null
                            ? advertised.baseUrl
                            : launchEndpoint == null ? null : launchEndpoint.baseUrl;
            if (baseUrl == null) continue;

            // Match dsh-ide's compatibility order: a valid dedicated launchUrl wins when it
            // belongs to the advertised base; otherwise a token-bearing legacy `url` is retained.
            // A mismatched launch URL is ignored rather than allowing a token to be sent to the
            // wrong Runtime.
            if (launchEndpoint != null && baseUrl.equals(launchEndpoint.baseUrl)) {
                return launchEndpoint.launchUrl == null
                        ? DshRuntimeEndpoint.ofBase(baseUrl)
                        : launchEndpoint;
            }
            if (advertised != null && baseUrl.equals(advertised.baseUrl)) {
                return advertised.launchUrl == null
                        ? DshRuntimeEndpoint.ofBase(baseUrl)
                        : advertised;
            }
            return DshRuntimeEndpoint.ofBase(baseUrl);
        }
        return null;
    }

    private JsonObject read(Path candidate) {
        try {
            String contents = Files.readString(candidate, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(contents);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException error) {
            // A missing, half-written, or legacy lock simply advertises nothing.
            return null;
        }
    }

    private static String record(long createdAt, String url, String launchUrl) {
        JsonObject value = new JsonObject();
        value.addProperty("pid", ProcessHandle.current().pid());
        value.addProperty("createdAt", createdAt);
        if (url != null) value.addProperty("url", url);
        if (launchUrl != null) value.addProperty("launchUrl", launchUrl);
        return value.toString();
    }

    /**
     * Accept only a bare loopback HTTP origin, mirroring dsh-ide's {@code loopbackRuntimeUrl}. The
     * lock is a world-writable file in a shared temporary directory, so the URL it carries is
     * untrusted input: anything with credentials, a path, an invalid token, or a non-local host is
     * refused rather than followed. The launch token is accepted only in the dedicated launch URL
     * field and is never returned by {@link #readAdvertisedUrl()}.
     */
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
