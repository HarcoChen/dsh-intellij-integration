package top.harcochen.dsh;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Companion-compatible best-effort startup gate: 250 ms wait, at most 500 ms hold. */
final class DshRuntimeStartupMutex {
    private DshRuntimeStartupMutex() {}

    static AutoCloseable acquire() {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    DshRuntimeAdvertisements.directory()
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8));
            int port =
                    16384
                            + (int)
                                    (Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt())
                                            % 16384);
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            do {
                ServerSocket server = new ServerSocket();
                try {
                    server.setReuseAddress(false);
                    server.bind(
                            new java.net.InetSocketAddress(
                                    InetAddress.getByName("127.0.0.1"), port));
                    var timer =
                            Executors.newSingleThreadScheduledExecutor(
                                    task -> {
                                        Thread thread = new Thread(task, "dsh-startup-mutex");
                                        thread.setDaemon(true);
                                        return thread;
                                    });
                    Runnable release =
                            () -> {
                                try {
                                    server.close();
                                } catch (Exception ignored) {
                                }
                                timer.shutdown();
                            };
                    timer.schedule(release, 500, TimeUnit.MILLISECONDS);
                    return release::run;
                } catch (java.io.IOException unavailable) {
                    server.close();
                }
                Thread.sleep(25);
            } while (System.nanoTime() < end);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            /* Discovery remains best effort. */
        }
        return () -> {};
    }
}
