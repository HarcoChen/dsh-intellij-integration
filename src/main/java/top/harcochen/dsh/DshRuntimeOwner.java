package top.harcochen.dsh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** In-process identity of this editor's child; discovery records never confer process ownership. */
final class DshRuntimeOwner {
    private long pid;
    private Long group;
    private Instant startedAt;

    synchronized void record(long processId, Long processGroup) {
        if (processId <= 1 || (processGroup != null && processGroup != processId)) return;
        pid = processId;
        group = processGroup;
        startedAt =
                ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant()).orElse(null);
    }

    synchronized long pid() {
        return pid;
    }

    synchronized void clear() {
        pid = 0;
        group = null;
        startedAt = null;
    }

    synchronized boolean exited() {
        if (pid == 0) return true;
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        if (process != null
                && startedAt != null
                && !startedAt.equals(process.info().startInstant().orElse(null))) return true;
        if (group == null) return process == null || !process.isAlive();
        Process probe = null;
        try {
            probe = new ProcessBuilder("ps", "-eo", "pgid=,stat=").start();
            Process current = probe;
            var output =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return new String(
                                            current.getInputStream().readNBytes(1024 * 1024),
                                            StandardCharsets.UTF_8);
                                } catch (IOException error) {
                                    return null;
                                }
                            });
            if (!probe.waitFor(2, TimeUnit.SECONDS) || probe.exitValue() != 0) return false;
            String rows = output.get(2, TimeUnit.SECONDS);
            if (rows == null) return false;
            for (String line : rows.split("\\R")) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length >= 2
                        && fields[0].equals(Long.toString(group))
                        && !fields[1].startsWith("Z")) return false;
            }
            return true;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unknown) {
            return false;
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
        }
    }
}
