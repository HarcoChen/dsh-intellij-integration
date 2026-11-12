package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git-backed before/after snapshots of every file a turn changed.
 *
 * <p>Unlike {@link DshToolDiff}, which rebuilds one call's diff from the hunks the Runtime
 * recorded, this store answers the coarser question — what did the whole turn do to the working
 * tree — and it answers it with Git itself. The capture writes trees through a private index and
 * object directory, so it never touches the user's index, stash, HEAD, or reflog; the repository is
 * only ever read from, through {@code GIT_ALTERNATE_OBJECT_DIRECTORIES}.
 */
final class DshChangeReviewStore {
    private static final Logger LOG = Logger.getInstance(DshChangeReviewStore.class);
    private static final Set<String> REGULAR_MODES = Set.of("100644", "100755");
    private static final Pattern RAW_HEADER =
            Pattern.compile("^:(\\d{6}) (\\d{6}) ([0-9a-f]+) ([0-9a-f]+) ([A-Z])(\\d*)$");
    private static final Pattern NULL_BLOB = Pattern.compile("^0+$");

    /** The private index and object directory one review's Git commands run against. */
    private static final class GitContext {
        private final Path cwd;
        private String pathPrefix = "";
        private final Path tempRoot;
        private final Map<String, String> env;

        private GitContext(Path cwd, Path tempRoot, Map<String, String> env) {
            this.cwd = cwd;
            this.tempRoot = tempRoot;
            this.env = env;
        }
    }

    private static final class ChangeFile {
        private final String id = UUID.randomUUID().toString();
        private String status;
        private String path;
        private String oldPath;
        private String oldBlob;
        private String newBlob;
        private String oldMode;
        private String newMode;
        private boolean restorable;
        private boolean restored;
        private final Set<Integer> revertedHunks = new LinkedHashSet<>();

        /** Content hash sampled at capture; a later mismatch means the user edited the file. */
        private String newWorkingHash;
    }

    private static final class ChangeReview {
        private final int turn;
        private String state = "capturing";
        private String operationDirectory;
        private Long endSeq;
        private List<ChangeFile> files = new ArrayList<>();
        private boolean restored;
        private String error;
        private String beforeTree;
        private String afterTree;
        private GitContext git;
        private String archiveScope;

        private ChangeReview(int turn) {
            this.turn = turn;
        }
    }

    private static final class SessionReviews {
        private final String cwd;
        private final Set<String> seen = new LinkedHashSet<>();
        private final Map<Integer, ChangeReview> reviews = new LinkedHashMap<>();

        /**
         * Whether the first history page has been folded. Its turns are replay, not live: their
         * before-trees are long gone, so capturing against today's working tree would invent an
         * empty or bogus review. Only events that appear after this baseline are real turn
         * boundaries.
         */
        private boolean baselined;

        private SessionReviews(String cwd) {
            this.cwd = cwd;
        }
    }

    private final Map<String, SessionReviews> sessions = new LinkedHashMap<>();
    private final Set<Path> tempRoots = new LinkedHashSet<>();
    private final ExecutorService operations;
    private final Runnable onUpdate;
    private final DshReviewArchive archive;
    private final java.util.function.BooleanSupplier captureLocal;
    private final java.util.function.BooleanSupplier archiveAccess;
    private final Map<String, JsonArray> history = new LinkedHashMap<>();
    private String archiveScope;
    private final Set<String> historyLoading = new LinkedHashSet<>();
    private volatile boolean disposed;

    DshChangeReviewStore(
            String projectPath,
            java.util.function.Supplier<String> endpoint,
            java.util.function.BooleanSupplier captureLocal,
            java.util.function.BooleanSupplier archiveAccess,
            Runnable onUpdate) {
        this.archive = new DshReviewArchive(projectPath, endpoint);
        this.captureLocal = captureLocal;
        this.archiveAccess = archiveAccess;
        this.operations =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-change-capture");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.onUpdate = onUpdate;
    }

    /**
     * Fold one history page: a {@code turn/start} opens a capture and a {@code turn/end} closes it.
     * Each event is acted on once, keyed by seq, so a repeated poll of the same page cannot
     * re-snapshot a settled turn.
     */
    void observe(String sessionId, String cwd, JsonObject history) {
        if (disposed || sessionId == null || cwd == null || cwd.isBlank()) return;
        loadHistory(sessionId);
        if (!captureLocal.getAsBoolean() || !archiveAccess.getAsBoolean()) {
            pauseCapture();
            return;
        }
        SessionReviews session;
        synchronized (this) {
            session = sessions.computeIfAbsent(sessionId, ignored -> new SessionReviews(cwd));
            // A session that moved to another directory is not this store's.
            if (!session.cwd.equals(cwd)) return;
        }
        JsonArray events =
                history != null && history.has("events") && history.get("events").isJsonArray()
                        ? history.getAsJsonArray("events")
                        : new JsonArray();
        boolean wasBaselined;
        synchronized (this) {
            wasBaselined = session.baselined;
        }
        for (JsonElement candidate : events) {
            if (!candidate.isJsonObject()) continue;
            JsonObject entry = candidate.getAsJsonObject();
            JsonObject event =
                    entry.has("event") && entry.get("event").isJsonObject()
                            ? entry.getAsJsonObject("event")
                            : entry;
            String type = string(event, "type");
            if ("working-directory/change".equals(type)) {
                synchronized (this) {
                    if (!session.seen.add(number(event, "seq") + ":" + type) || !session.baselined)
                        continue;
                    String directory = DshWorkingDirectory.at(history, number(event, "seq"), cwd);
                    for (ChangeReview review : session.reviews.values()) {
                        if ("capturing".equals(review.state)
                                && review.endSeq == null
                                && !java.util.Objects.equals(
                                        directory, review.operationDirectory)) {
                            failReview(
                                    sessionId,
                                    review.turn,
                                    new IllegalStateException(
                                            DshBundle.message(
                                                    "dsh.change.review.directory.changed")));
                        }
                    }
                }
                continue;
            }
            if (!"turn/start".equals(type) && !"turn/end".equals(type)) continue;
            JsonObject data =
                    event.has("data") && event.get("data").isJsonObject()
                            ? event.getAsJsonObject("data")
                            : new JsonObject();
            int turn = integer(data, "turn");
            long seq = number(event, "seq");
            if (turn <= 0 || seq < 0) continue;
            String key = seq + ":" + type;
            boolean start;
            synchronized (this) {
                if (!session.seen.add(key)) continue;
                if (!session.baselined) continue;
                start = "turn/start".equals(type);
                if (start) {
                    ChangeReview review = new ChangeReview(turn);
                    review.archiveScope = archiveScope;
                    review.operationDirectory = DshWorkingDirectory.at(history, seq, cwd);
                    session.reviews.put(turn, review);
                } else if (session.reviews.containsKey(turn))
                    session.reviews.get(turn).endSeq = seq;
            }
            onUpdate.run();
            operations.execute(
                    () -> {
                        try {
                            if (start) captureBefore(sessionId, turn);
                            else captureAfter(sessionId, turn);
                        } catch (Exception error) {
                            failReview(sessionId, turn, error);
                        }
                    });
        }
        if (!wasBaselined) {
            synchronized (this) {
                session.baselined = true;
            }
        }
    }

    /** Resume from a fresh observation baseline after any gap in local capture. */
    synchronized void pauseCapture() {
        for (SessionReviews session : sessions.values()) {
            session.baselined = false;
            session.seen.clear();
            for (ChangeReview review : session.reviews.values())
                if ("capturing".equals(review.state)) {
                    review.state = "error";
                    review.error = DshBundle.message("dsh.review.capture.interrupted");
                }
        }
    }

    /** The client-visible rows, newest turn first. */
    synchronized JsonArray view(String sessionId) {
        JsonArray result = new JsonArray();
        if (!archiveAccess.getAsBoolean()) return result;
        SessionReviews session = sessionId == null ? null : sessions.get(sessionId);
        List<ChangeReview> reviews =
                session == null ? new ArrayList<>() : new ArrayList<>(session.reviews.values());
        reviews.sort((left, right) -> Integer.compare(right.turn, left.turn));
        for (ChangeReview review : reviews) {
            JsonObject row = new JsonObject();
            row.addProperty("turn", review.turn);
            row.addProperty("state", review.state);
            JsonArray files = new JsonArray();
            for (ChangeFile file : review.files) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", file.id);
                entry.addProperty("status", file.status);
                entry.addProperty("path", file.path);
                if (file.oldPath != null) entry.addProperty("oldPath", file.oldPath);
                entry.addProperty(
                        "restorable", file.restorable && !file.restored && !review.restored);
                entry.addProperty(
                        "hunkRestorable",
                        file.restorable
                                && !file.restored
                                && !review.restored
                                && supportsHunks(file));
                entry.addProperty("restored", file.restored);
                files.add(entry);
            }
            row.add("files", files);
            row.addProperty("restored", review.restored);
            if (review.error != null) row.addProperty("error", review.error);
            result.add(row);
        }
        JsonArray saved = history.get(sessionId);
        if (saved != null)
            for (JsonElement value : saved) {
                JsonObject row = value.getAsJsonObject();
                int turn = DshJson.integer(row, "turn", -1);
                if (session != null && session.reviews.containsKey(turn)) continue;
                JsonObject copy = row.deepCopy();
                copy.addProperty("archived", true);
                for (JsonElement file : copy.getAsJsonArray("files")) {
                    JsonObject item = file.getAsJsonObject();
                    item.remove("beforeText");
                    item.remove("afterText");
                    item.addProperty("restorable", false);
                    item.addProperty("hunkRestorable", false);
                }
                result.add(copy);
            }
        return result;
    }

    synchronized void loadHistory(String sessionId) {
        if (sessionId == null || disposed || !archiveAccess.getAsBoolean()) return;
        String scope = archive.scope();
        if (!scope.equals(archiveScope)) {
            if (archiveScope != null) retain(Set.of());
            archiveScope = scope;
            history.clear();
            historyLoading.clear();
        }
        if (history.containsKey(sessionId) || !historyLoading.add(sessionId)) return;
        operations.execute(
                () -> {
                    JsonArray saved = archive.load(scope, sessionId);
                    synchronized (this) {
                        if (!disposed && scope.equals(archiveScope)) {
                            history.put(sessionId, saved);
                            historyLoading.remove(sessionId);
                        }
                    }
                    onUpdate.run();
                });
    }

    void readSavedHistory(String sessionId) {
        if (sessionId == null || disposed || !archiveAccess.getAsBoolean()) return;
        String scope = archive.scope();
        JsonArray saved = archive.load(scope, sessionId);
        synchronized (this) {
            if (scope.equals(archiveScope)) history.put(sessionId, saved);
        }
    }

    private void persist(String sessionId, ChangeReview review) {
        if (disposed || !archiveAccess.getAsBoolean()) return;
        try {
            JsonObject row = new JsonObject();
            row.addProperty("turn", review.turn);
            row.addProperty("state", "ready");
            row.addProperty("restored", review.restored);
            row.addProperty("capturedAt", System.currentTimeMillis());
            row.addProperty("cwd", review.operationDirectory);
            JsonArray files = new JsonArray();
            for (ChangeFile file : review.files) {
                FileSides sides = sides(sessionId, review.turn, file.id);
                JsonObject item = new JsonObject();
                item.addProperty("id", file.id);
                item.addProperty("path", file.path);
                item.addProperty("status", file.status);
                item.addProperty("oldPath", file.oldPath);
                item.addProperty("binary", sides.binary());
                item.addProperty("restored", file.restored);
                item.addProperty("beforeText", sides.beforeText());
                item.addProperty("afterText", sides.afterText());
                files.add(item);
            }
            row.add("files", files);
            String scope = review.archiveScope;
            if (scope == null) return;
            archive.save(scope, sessionId, row);
            synchronized (this) {
                if (scope.equals(archiveScope))
                    history.put(sessionId, archive.load(scope, sessionId));
            }
        } catch (Exception error) {
            LOG.warn("Unable to persist DSH review", error);
            synchronized (this) {
                review.error = DshBundle.message("dsh.review.archive.failed", message(error));
            }
        }
    }

    /**
     * Both sides of one changed file, as text, or null when it is binary.
     *
     * <p>{@code title} is localized display text and is not a path; {@code path} is the repository
     * path the change lands on, which is what file-type detection needs.
     */
    record FileSides(
            String beforeText, String afterText, String title, String path, boolean binary) {}

    /**
     * Read both blobs of one changed file. A NUL byte on either side means the change is binary,
     * which no text diff can honestly display.
     */
    FileSides sides(String sessionId, int turn, String fileId) throws Exception {
        if (!archiveAccess.getAsBoolean())
            throw new IllegalStateException(DshBundle.message("dsh.remote.access.denied"));
        ChangeReview review;
        ChangeFile file = null;
        synchronized (this) {
            SessionReviews current = sessions.get(sessionId);
            if (current == null || !current.reviews.containsKey(turn)) {
                JsonArray saved = history.get(sessionId);
                if (saved != null)
                    for (JsonElement value : saved) {
                        JsonObject row = value.getAsJsonObject();
                        if (DshJson.integer(row, "turn", -1) != turn) continue;
                        for (JsonElement entry : row.getAsJsonArray("files")) {
                            JsonObject item = entry.getAsJsonObject();
                            if (!fileId.equals(DshJson.string(item, "id"))) continue;
                            String path = DshJson.string(item, "path");
                            return new FileSides(
                                    DshJson.string(item, "beforeText"),
                                    DshJson.string(item, "afterText"),
                                    DshBundle.message("dsh.change.review.turn.prefix", turn)
                                            + " "
                                            + path,
                                    path,
                                    DshJson.bool(item, "binary", false));
                        }
                    }
            }
            review = review(sessionId, turn);
            for (ChangeFile candidate : review.files) {
                if (candidate.id.equals(fileId)) {
                    file = candidate;
                    break;
                }
            }
            if (file == null || review.git == null || !"ready".equals(review.state)) {
                throw new IllegalStateException(
                        DshBundle.message("dsh.change.review.change.unavailable"));
            }
        }
        byte[] before = readBlob(review.git, file.oldBlob);
        byte[] after = readBlob(review.git, file.newBlob);
        String title =
                "renamed".equals(file.status)
                        ? DshBundle.message("dsh.change.review.turn.prefix", turn)
                                + " "
                                + file.oldPath
                                + " → "
                                + file.path
                        : DshBundle.message("dsh.change.review.turn.prefix", turn)
                                + " "
                                + file.path;
        if (hasNul(before) || hasNul(after)) {
            return new FileSides(null, null, title, file.path, true);
        }
        return new FileSides(
                new String(before, StandardCharsets.UTF_8),
                new String(after, StandardCharsets.UTF_8),
                title,
                file.path,
                false);
    }

    /** Whether a turn is in a state where restore is even offered. */
    synchronized boolean isRestorable(String sessionId, int turn) {
        if (!captureLocal.getAsBoolean() || !archiveAccess.getAsBoolean()) return false;
        SessionReviews session = sessionId == null ? null : sessions.get(sessionId);
        ChangeReview review = session == null ? null : session.reviews.get(turn);
        if (review == null
                || !"ready".equals(review.state)
                || review.restored
                || review.files.isEmpty()) return false;
        for (ChangeFile file : review.files) {
            if (!file.restored && !file.restorable) return false;
        }
        return true;
    }

    /** The count a confirmation prompt should quote before anything is written. */
    synchronized int restorableFileCount(String sessionId, int turn) {
        SessionReviews session = sessionId == null ? null : sessions.get(sessionId);
        ChangeReview review = session == null ? null : session.reviews.get(turn);
        return review == null
                ? 0
                : (int) review.files.stream().filter(file -> !file.restored).count();
    }

    /**
     * Put every file this turn changed back to its before-image.
     *
     * <p>The working tree is checked for drift twice — once before the blobs are read and again
     * immediately before anything is written — so a file edited while the confirmation dialog was
     * open is never silently overwritten.
     */
    void restore(String sessionId, int turn) throws Exception {
        restore(sessionId, turn, () -> {});
    }

    void restore(String sessionId, int turn, Runnable assertWritable) throws Exception {
        ChangeReview review;
        synchronized (this) {
            review = review(sessionId, turn);
        }
        synchronized (review) {
            if (!isRestorable(sessionId, turn))
                throw new IllegalStateException(
                        DshBundle.message("dsh.change.review.cannot.restore"));
            ChangeReview subset =
                    subset(review, review.files.stream().filter(file -> !file.restored).toList());
            restoreFiles(subset, assertWritable);
            synchronized (this) {
                for (ChangeFile file : subset.files) file.restored = true;
                review.restored = true;
            }
            operations.execute(() -> persist(sessionId, review));
        }
        onUpdate.run();
    }

    synchronized List<Path> restorePaths(String sessionId, int turn) {
        ChangeReview review = review(sessionId, turn);
        List<Path> paths = new ArrayList<>();
        for (ChangeFile file : review.files) {
            if (file.restored) continue;
            paths.add(resolvePath(review, file.path));
            if (file.oldPath != null) paths.add(resolvePath(review, file.oldPath));
        }
        return List.copyOf(paths);
    }

    record HunkPreview(String revision, List<DshReviewHunks.Block> blocks) {}

    HunkPreview revertHunks(String sessionId, int turn, String fileId) throws Exception {
        ChangeReview review;
        synchronized (this) {
            review = review(sessionId, turn);
        }
        synchronized (review) {
            ChangeFile file = revertFile(review, fileId);
            DshReviewHunks.Plan plan = hunkPlan(review, file);
            return new HunkPreview(file.newWorkingHash, plan.remaining(file.revertedHunks));
        }
    }

    List<Path> revertPaths(String sessionId, int turn, String fileId) {
        synchronized (this) {
            ChangeReview review = review(sessionId, turn);
            ChangeFile file = revertFile(review, fileId);
            List<Path> paths = new ArrayList<>();
            paths.add(resolvePath(review, file.path));
            if (file.oldPath != null) paths.add(resolvePath(review, file.oldPath));
            return List.copyOf(paths);
        }
    }

    boolean revert(
            String sessionId,
            int turn,
            String fileId,
            Integer block,
            String revision,
            Runnable assertWritable)
            throws Exception {
        ChangeReview review;
        synchronized (this) {
            review = review(sessionId, turn);
        }
        synchronized (review) {
            ChangeFile file = revertFile(review, fileId);
            assertWritable.run();
            if (block == null) {
                restoreFiles(subset(review, List.of(file)), assertWritable);
                synchronized (this) {
                    file.restored = true;
                }
            } else {
                if (!java.util.Objects.equals(revision, file.newWorkingHash))
                    throw new IllegalStateException(DshBundle.message("dsh.files.changed"));
                DshReviewHunks.Plan plan = hunkPlan(review, file);
                if (block < 0
                        || block >= plan.fragments().size()
                        || file.revertedHunks.contains(block))
                    throw new IllegalStateException(DshBundle.message("dsh.files.changed"));
                Set<Integer> reverted = new LinkedHashSet<>(file.revertedHunks);
                reverted.add(block);
                byte[] next = plan.content(reverted);
                assertWritable.run();
                List<String> conflicts = conflicts(subset(review, List.of(file)));
                if (!conflicts.isEmpty())
                    throw new IllegalStateException(conflictMessage(conflicts));
                Path target = resolvePath(review, file.path);
                Path temporary = Files.createTempFile(target.getParent(), ".dsh-revert-", ".tmp");
                try {
                    Files.write(temporary, next);
                    if (target.getFileSystem().supportedFileAttributeViews().contains("posix"))
                        Files.setPosixFilePermissions(
                                temporary,
                                Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
                    try {
                        Files.move(
                                temporary,
                                target,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(
                                temporary,
                                target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
                synchronized (this) {
                    file.revertedHunks.add(block);
                    file.newWorkingHash = workingHash(next);
                    file.restored = reverted.size() == plan.fragments().size();
                }
            }
            synchronized (this) {
                review.restored = review.files.stream().allMatch(fileEntry -> fileEntry.restored);
            }
            operations.execute(() -> persist(sessionId, review));
        }
        onUpdate.run();
        synchronized (this) {
            return review.files.stream()
                    .filter(file -> file.id.equals(fileId))
                    .findFirst()
                    .orElseThrow()
                    .restored;
        }
    }

    private ChangeFile revertFile(ChangeReview review, String fileId) {
        if (!captureLocal.getAsBoolean()
                || !archiveAccess.getAsBoolean()
                || review.restored
                || !"ready".equals(review.state))
            throw new IllegalStateException(DshBundle.message("dsh.change.review.cannot.restore"));
        return review.files.stream()
                .filter(file -> file.id.equals(fileId) && file.restorable && !file.restored)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        DshBundle.message("dsh.change.review.cannot.restore")));
    }

    private DshReviewHunks.Plan hunkPlan(ChangeReview review, ChangeFile file) throws Exception {
        if (!supportsHunks(file))
            throw new IllegalStateException(DshBundle.message("dsh.review.hunks.unsupported"));
        List<String> conflicts = conflicts(subset(review, List.of(file)));
        if (!conflicts.isEmpty()) throw new IllegalStateException(conflictMessage(conflicts));
        byte[] before = prepareRestore(subset(review, List.of(file))).get(file.id);
        byte[] after =
                runGit(
                        review.git,
                        List.of(
                                "cat-file",
                                "--filters",
                                "--path=" + review.git.pathPrefix + file.path,
                                file.newBlob));
        DshReviewHunks.Plan plan = DshReviewHunks.compare(before, after);
        if (!file.newWorkingHash.equals(workingHash(plan.content(file.revertedHunks))))
            throw new IllegalStateException(DshBundle.message("dsh.files.changed"));
        return plan;
    }

    private static boolean supportsHunks(ChangeFile file) {
        return "modified".equals(file.status)
                && REGULAR_MODES.contains(file.oldMode)
                && java.util.Objects.equals(file.oldMode, file.newMode);
    }

    private void restoreFiles(ChangeReview review, Runnable assertWritable) throws Exception {
        List<String> conflicts = conflicts(review);
        if (!conflicts.isEmpty()) throw new IllegalStateException(conflictMessage(conflicts));
        Map<String, byte[]> contents = prepareRestore(review);
        assertWritable.run();
        List<String> latest = conflicts(review);
        if (!latest.isEmpty()) throw new IllegalStateException(conflictMessage(latest));
        applyRestore(review, contents);
    }

    private static ChangeReview subset(ChangeReview source, List<ChangeFile> files) {
        ChangeReview result = new ChangeReview(source.turn);
        result.git = source.git;
        result.files = files;
        return result;
    }

    void dispose() {
        operations.shutdownNow();
        disposed = true;
        Set<Path> roots;
        synchronized (this) {
            sessions.clear();
            roots = new LinkedHashSet<>(tempRoots);
            tempRoots.clear();
        }
        for (Path root : roots) deleteRecursively(root);
    }

    /** Forget every review for sessions the catalog no longer lists. */
    void retain(Set<String> live) {
        List<Path> orphaned = new ArrayList<>();
        synchronized (this) {
            var iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, SessionReviews> entry = iterator.next();
                if (live.contains(entry.getKey())) continue;
                for (ChangeReview review : entry.getValue().reviews.values()) {
                    if (review.git != null && tempRoots.remove(review.git.tempRoot))
                        orphaned.add(review.git.tempRoot);
                }
                iterator.remove();
            }
        }
        for (Path root : orphaned) deleteRecursively(root);
    }

    private void captureBefore(String sessionId, int turn) throws Exception {
        if (disposed || !captureLocal.getAsBoolean() || !archiveAccess.getAsBoolean()) return;
        SessionReviews session;
        ChangeReview review;
        synchronized (this) {
            session = sessions.get(sessionId);
            review = session == null ? null : session.reviews.get(turn);
            if (session == null || review == null || !"capturing".equals(review.state)) return;
        }
        GitContext git =
                createGitContext(
                        review.operationDirectory == null || review.operationDirectory.isBlank()
                                ? session.cwd
                                : review.operationDirectory);
        if (git == null) {
            // Not a repository: there is nothing to diff against, and an empty
            // review would claim the turn changed nothing.
            synchronized (this) {
                session.reviews.remove(turn);
            }
            onUpdate.run();
            return;
        }
        String beforeTree = captureTree(git);
        synchronized (this) {
            if (!"capturing".equals(review.state)) return;
            review.git = git;
            review.beforeTree = beforeTree;
        }
    }

    private void captureAfter(String sessionId, int turn) throws Exception {
        if (disposed || !captureLocal.getAsBoolean() || !archiveAccess.getAsBoolean()) return;
        ChangeReview review;
        synchronized (this) {
            SessionReviews session = sessions.get(sessionId);
            review = session == null ? null : session.reviews.get(turn);
            if (review == null
                    || !"capturing".equals(review.state)
                    || review.git == null
                    || review.beforeTree == null) return;
        }
        String afterTree = captureTree(review.git);
        byte[] raw =
                runGit(
                        review.git,
                        List.of(
                                "diff",
                                "--raw",
                                "--relative",
                                "-z",
                                "-M",
                                "--no-abbrev",
                                review.beforeTree,
                                afterTree,
                                "--",
                                "."));
        List<ChangeFile> files = parseRawDiff(raw);
        synchronized (this) {
            if (!"capturing".equals(review.state)
                    || !captureLocal.getAsBoolean()
                    || !archiveAccess.getAsBoolean()) return;
            review.afterTree = afterTree;
            review.files = files;
        }
        captureWorkingHashes(review);
        synchronized (this) {
            if (!"capturing".equals(review.state)
                    || !captureLocal.getAsBoolean()
                    || !archiveAccess.getAsBoolean()) return;
            review.state = "ready";
        }
        persist(sessionId, review);
        onUpdate.run();
    }

    /**
     * Parse {@code git diff --raw -z -M}. The NUL-separated stream carries one header per change,
     * followed by one path — or two for a rename.
     */
    private static List<ChangeFile> parseRawDiff(byte[] output) {
        String[] fields = new String(output, StandardCharsets.UTF_8).split("\0", -1);
        List<ChangeFile> files = new ArrayList<>();
        int index = 0;
        while (index < fields.length) {
            String header = fields[index++];
            if (header == null || header.isEmpty()) continue;
            Matcher match = RAW_HEADER.matcher(header);
            if (!match.matches())
                throw new IllegalStateException(
                        DshBundle.message("dsh.change.review.unsupported.record"));
            String oldMode = match.group(1);
            String newMode = match.group(2);
            String oldBlob = match.group(3);
            String newBlob = match.group(4);
            String kind = match.group(5);
            if (index >= fields.length)
                throw new IllegalStateException(DshBundle.message("dsh.change.review.no.path"));
            String firstPath = fields[index++];
            if (firstPath.isEmpty())
                throw new IllegalStateException(DshBundle.message("dsh.change.review.no.path"));
            String secondPath = null;
            if ("R".equals(kind)) {
                if (index >= fields.length || fields[index].isEmpty()) {
                    throw new IllegalStateException(
                            DshBundle.message("dsh.change.review.invalid.rename"));
                }
                secondPath = fields[index++];
            }
            String status =
                    switch (kind) {
                        case "A" -> "added";
                        case "M", "T" -> "modified";
                        case "D" -> "deleted";
                        case "R" -> "renamed";
                        default ->
                                throw new IllegalStateException(
                                        DshBundle.message(
                                                "dsh.change.review.unsupported.type", kind));
                    };
            ChangeFile file = new ChangeFile();
            file.status = status;
            file.path = secondPath == null ? firstPath : secondPath;
            file.oldPath = secondPath == null ? null : firstPath;
            file.oldBlob = oldBlob;
            file.newBlob = newBlob;
            file.oldMode = oldMode;
            file.newMode = newMode;
            // A symlink or gitlink on either side cannot be restored by writing
            // a regular file, so it is shown but never offered for restore.
            file.restorable =
                    (isNullBlob(oldBlob) || REGULAR_MODES.contains(oldMode))
                            && (isNullBlob(newBlob) || REGULAR_MODES.contains(newMode));
            files.add(file);
        }
        return files;
    }

    /**
     * Snapshot the working tree as a Git tree object. The private index means {@code add -A} never
     * touches the user's staged state, and a repository without a HEAD (a fresh {@code git init})
     * starts from the empty tree.
     */
    private String captureTree(GitContext git) throws Exception {
        try {
            runGit(git, List.of("rev-parse", "--verify", "HEAD"));
            runGit(git, List.of("read-tree", "HEAD"));
        } catch (Exception ignored) {
            runGit(git, List.of("read-tree", "--empty"));
        }
        runGit(git, List.of("add", "-A", "--", "."));
        return new String(runGit(git, List.of("write-tree")), StandardCharsets.UTF_8).trim();
    }

    private GitContext createGitContext(String cwd) throws Exception {
        Path realCwd = Path.of(cwd).toRealPath();
        String root;
        String objectPath;
        try {
            root =
                    new String(
                                    runGitIn(
                                            realCwd,
                                            Map.of(),
                                            List.of("rev-parse", "--show-toplevel")),
                                    StandardCharsets.UTF_8)
                            .trim();
            objectPath =
                    new String(
                                    runGitIn(
                                            realCwd,
                                            Map.of(),
                                            List.of("rev-parse", "--git-path", "objects")),
                                    StandardCharsets.UTF_8)
                            .trim();
        } catch (Exception notARepository) {
            return null;
        }
        Path tempRoot = Files.createTempDirectory("dsh-changes-");
        synchronized (this) {
            if (disposed) {
                deleteRecursively(tempRoot);
                throw new IllegalStateException(
                        DshBundle.message("dsh.change.review.review.unavailable"));
            }
            tempRoots.add(tempRoot);
        }
        Path alternateObjects = Path.of(root).resolve(objectPath).normalize();
        Path objectDirectory = tempRoot.resolve("objects");
        Files.createDirectories(objectDirectory);
        Map<String, String> env = new HashMap<>();
        env.put("GIT_INDEX_FILE", tempRoot.resolve("index").toString());
        env.put("GIT_OBJECT_DIRECTORY", objectDirectory.toString());
        env.put("GIT_ALTERNATE_OBJECT_DIRECTORIES", alternateObjects.toString());
        GitContext result = new GitContext(realCwd, tempRoot, env);
        result.pathPrefix =
                new String(
                                runGitIn(realCwd, Map.of(), List.of("rev-parse", "--show-prefix")),
                                StandardCharsets.UTF_8)
                        .trim();
        return result;
    }

    private void failReview(String sessionId, int turn, Exception error) {
        synchronized (this) {
            SessionReviews session = sessions.get(sessionId);
            ChangeReview review = session == null ? null : session.reviews.get(turn);
            if (review == null) return;
            review.state = "error";
            review.error = DshBundle.message("dsh.change.review.capture.failed", message(error));
        }
        LOG.debug("DSH change capture failed for turn " + turn, error);
        onUpdate.run();
    }

    private ChangeReview review(String sessionId, int turn) {
        SessionReviews session = sessionId == null ? null : sessions.get(sessionId);
        ChangeReview review = session == null ? null : session.reviews.get(turn);
        if (review == null)
            throw new IllegalStateException(
                    DshBundle.message("dsh.change.review.review.unavailable"));
        return review;
    }

    private Path resolvePath(ChangeReview review, String relativePath) {
        if (review.git == null || Path.of(relativePath).isAbsolute()) {
            throw new IllegalStateException(DshBundle.message("dsh.change.review.invalid.path"));
        }
        Path candidate = review.git.cwd.resolve(relativePath).normalize();
        if (!candidate.startsWith(review.git.cwd)) {
            throw new IllegalStateException(
                    DshBundle.message("dsh.change.review.outside.workspace"));
        }
        return candidate;
    }

    /**
     * Refuse to follow a symlinked or non-directory parent. Restore writes files, and a swapped
     * parent directory would redirect that write outside the workspace even though the resolved
     * path looked contained.
     */
    private void assertSafeParents(ChangeReview review, String relativePath) throws IOException {
        Path target = resolvePath(review, relativePath);
        Path current = target.getParent();
        while (current != null && !current.equals(review.git.cwd)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                            DshBundle.message(
                                    "dsh.change.review.not.real.directory", relativePath));
                }
            }
            current = current.getParent();
        }
    }

    private boolean matchesFile(ChangeReview review, String relativePath, String blob, String mode)
            throws Exception {
        assertSafeParents(review, relativePath);
        Path target = resolvePath(review, relativePath);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            return false;
        if (isExecutableMode(mode) != isExecutable(target)) return false;
        String expected = null;
        for (ChangeFile file : review.files) {
            if (file.path.equals(relativePath) && file.newBlob.equals(blob))
                expected = file.newWorkingHash;
        }
        return expected != null && expected.equals(workingHash(Files.readAllBytes(target)));
    }

    private boolean isAbsent(ChangeReview review, String relativePath) throws IOException {
        assertSafeParents(review, relativePath);
        return !Files.exists(resolvePath(review, relativePath), LinkOption.NOFOLLOW_LINKS);
    }

    private List<String> conflicts(ChangeReview review) throws Exception {
        List<String> conflicts = new ArrayList<>();
        for (ChangeFile file : review.files) {
            boolean valid;
            if ("deleted".equals(file.status)) {
                valid = isAbsent(review, file.path);
            } else if ("renamed".equals(file.status)) {
                valid =
                        file.oldPath != null
                                && isAbsent(review, file.oldPath)
                                && matchesFile(review, file.path, file.newBlob, file.newMode);
            } else {
                valid = matchesFile(review, file.path, file.newBlob, file.newMode);
            }
            if (!valid) {
                conflicts.add(
                        "renamed".equals(file.status) && file.oldPath != null
                                ? file.oldPath + " → " + file.path
                                : file.path);
            }
        }
        return conflicts;
    }

    /**
     * Sample each changed file at capture time. A blob that no longer matches means the workspace
     * moved under the capture, which invalidates the whole review rather than half of it.
     */
    private void captureWorkingHashes(ChangeReview review) throws Exception {
        if (review.git == null)
            throw new IllegalStateException(
                    DshBundle.message("dsh.change.review.review.unavailable"));
        for (ChangeFile file : review.files) {
            if (isNullBlob(file.newBlob) || !REGULAR_MODES.contains(file.newMode)) continue;
            assertSafeParents(review, file.path);
            Path target = resolvePath(review, file.path);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target)) {
                throw new IllegalStateException(
                        DshBundle.message("dsh.change.review.not.regular.file", file.path));
            }
            byte[] content = Files.readAllBytes(target);
            String currentBlob =
                    new String(
                                    runGit(
                                            review.git,
                                            List.of(
                                                    "hash-object",
                                                    "--path=" + file.path,
                                                    "--",
                                                    file.path)),
                                    StandardCharsets.UTF_8)
                            .trim();
            if (!currentBlob.equals(file.newBlob)) {
                throw new IllegalStateException(
                        DshBundle.message("dsh.change.review.workspace.changed", review.turn));
            }
            file.newWorkingHash = workingHash(content);
        }
    }

    private static String conflictMessage(List<String> conflicts) {
        List<String> shown = conflicts.subList(0, Math.min(5, conflicts.size()));
        String remaining =
                conflicts.size() > 5
                        ? " "
                                + DshBundle.message(
                                        "dsh.change.review.restore.conflict.more",
                                        conflicts.size() - 5)
                        : "";
        return DshBundle.message("dsh.change.review.restore.conflict")
                + " "
                + String.join(", ", shown)
                + remaining;
    }

    private Map<String, byte[]> prepareRestore(ChangeReview review) throws Exception {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (ChangeFile file : review.files) {
            if ("added".equals(file.status)) continue;
            // --filters replays the repository's clean/smudge rules, so the
            // restored bytes match what a checkout of that blob would produce.
            contents.put(
                    file.id,
                    runGit(
                            review.git,
                            List.of(
                                    "cat-file",
                                    "--filters",
                                    "--path="
                                            + review.git.pathPrefix
                                            + (file.oldPath == null ? file.path : file.oldPath),
                                    file.oldBlob)));
        }
        return contents;
    }

    private void applyRestore(ChangeReview review, Map<String, byte[]> contents) throws Exception {
        for (ChangeFile file : review.files) {
            if ("renamed".equals(file.status)) {
                writeOldFile(review, file.oldPath, file, contents.get(file.id));
            } else if (!"added".equals(file.status)) {
                writeOldFile(review, file.path, file, contents.get(file.id));
            }
        }
        // Removals run last: a rename's new path must not be deleted before its
        // old path has been written back.
        for (ChangeFile file : review.files) {
            if ("added".equals(file.status) || "renamed".equals(file.status)) {
                Files.deleteIfExists(resolvePath(review, file.path));
            }
        }
    }

    private void writeOldFile(
            ChangeReview review, String relativePath, ChangeFile file, byte[] content)
            throws Exception {
        if (content == null)
            throw new IllegalStateException(
                    DshBundle.message("dsh.change.review.restore.unavailable"));
        assertSafeParents(review, relativePath);
        Path target = resolvePath(review, relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        applyMode(target, file.oldMode);
    }

    private static void applyMode(Path target, String mode) {
        try {
            Set<PosixFilePermission> permissions =
                    new LinkedHashSet<>(Files.getPosixFilePermissions(target));
            Set<PosixFilePermission> executable =
                    Set.of(
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_EXECUTE);
            if (isExecutableMode(mode)) permissions.addAll(executable);
            else permissions.removeAll(executable);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // A non-POSIX filesystem carries no executable bit to restore.
        }
    }

    private static boolean isExecutableMode(String mode) {
        return "100755".equals(mode);
    }

    private static boolean isExecutable(Path target) {
        try {
            return Files.getPosixFilePermissions(target)
                    .contains(PosixFilePermission.OWNER_EXECUTE);
        } catch (UnsupportedOperationException | IOException ignored) {
            return false;
        }
    }

    private byte[] readBlob(GitContext git, String hash) throws Exception {
        if (isNullBlob(hash)) return new byte[0];
        return runGit(git, List.of("cat-file", "blob", hash));
    }

    private static boolean isNullBlob(String hash) {
        return NULL_BLOB.matcher(hash).matches();
    }

    private static boolean hasNul(byte[] content) {
        for (byte value : content) {
            if (value == 0) return true;
        }
        return false;
    }

    private static String workingHash(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest(content)) result.append(String.format("%02x", value));
        return result.toString();
    }

    private byte[] runGit(GitContext git, List<String> args) throws Exception {
        return runGitIn(git.cwd, git.env, args);
    }

    private static byte[] runGitIn(Path cwd, Map<String, String> env, List<String> args)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.environment().putAll(env);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        byte[] stdout;
        String stderr;
        try (InputStream out = process.getInputStream();
                InputStream err = process.getErrorStream()) {
            stdout = readAll(out, 32 * 1024 * 1024);
            stderr = new String(readAll(err, 1024 * 1024), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git " + args.get(0) + " timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "git "
                            + args.get(0)
                            + " failed: "
                            + (stderr.isBlank()
                                    ? "exit code " + process.exitValue()
                                    : stderr.trim()));
        }
        return stdout;
    }

    private static byte[] readAll(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            if (buffer.size() + read > limit)
                throw new IOException("git produced more output than DSH can buffer");
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Best effort: a leftover temp directory is harmless.
                                }
                            });
        } catch (IOException error) {
            LOG.debug("DSH change review cleanup failed", error);
        }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : null;
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object != null
                            && object.has(key)
                            && object.get(key).isJsonPrimitive()
                            && object.get(key).getAsJsonPrimitive().isNumber()
                    ? object.get(key).getAsInt()
                    : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static long number(JsonObject object, String key) {
        try {
            return object != null
                            && object.has(key)
                            && object.get(key).isJsonPrimitive()
                            && object.get(key).getAsJsonPrimitive().isNumber()
                    ? object.get(key).getAsLong()
                    : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String message(Exception error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.toString() : text;
    }
}
