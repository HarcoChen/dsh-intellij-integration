package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Private Java/Node bridge for the loopback MCP server; bearer secret only travels in memory/env.
 */
final class DshDebugBridge implements AutoCloseable {
    static final String TOKEN_ENV = "DSH_IDE_DEBUG_TOKEN";
    private final String token = UUID.randomUUID().toString();
    private final DshDebugTools tools;
    private final Process process;
    private final ExecutorService reader =
            Executors.newSingleThreadExecutor(
                    task -> {
                        Thread thread = new Thread(task, "dsh-debug-mcp");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final CompletableFuture<String> ready = new CompletableFuture<>();
    private volatile boolean closed;
    private Path patch;

    DshDebugBridge(Project project, List<String> helperCommand, Map<String, String> environment)
            throws Exception {
        tools = new DshDebugTools(project);
        ProcessBuilder builder = new ProcessBuilder(helperCommand).redirectErrorStream(true);
        builder.environment().putAll(environment);
        process = builder.start();
        reader.execute(this::readFrames);
        try {
            JsonObject config = new JsonObject();
            config.addProperty("operation", "debug-server");
            config.addProperty("token", token);
            config.add("tools", DshDebugTools.definitions());
            DshRuntimeHelper.send(process, config);
            String url = ready.get(8, TimeUnit.SECONDS);
            java.net.URI uri = java.net.URI.create(url);
            if (!"http".equals(uri.getScheme())
                    || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() <= 0
                    || !"/mcp".equals(uri.getPath()))
                throw new IllegalArgumentException("Invalid debugger bridge endpoint");
            patch =
                    Files.createTempFile(
                            "dsh-intellij-debug-" + ProcessHandle.current().pid() + "-",
                            ".patch.yml");
            Files.writeString(patch, patchBody(url), StandardCharsets.UTF_8);
            patch.toFile().deleteOnExit();
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    Path patch() {
        return patch;
    }

    Map<String, String> environment() {
        return Map.of(TOKEN_ENV, token);
    }

    String redact(String value) {
        return value.replace(token, "<redacted>");
    }

    static String patchBody(String url) {
        return """
                - insert:
                    - id: dsh-intellij-debug
                      name: '@deepseek-ai/dsh-mcp-client'
                      config:
                        serverName: debug
                        transport: streamable-http
                        url: %s
                        headers:
                          Authorization: !!js '`Bearer ${process.env.DSH_IDE_DEBUG_TOKEN}`'
                        failOnStartupError: false
                """
                .formatted(url);
    }

    private void readFrames() {
        try (BufferedReader input =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = input.readLine()) != null) {
                if (!line.startsWith("DSH_INTELLIJ_HELPER ") || line.length() > 300_000) continue;
                JsonObject frame = JsonParser.parseString(line.substring(20)).getAsJsonObject();
                JsonObject value = frame.getAsJsonObject("value");
                String event = DshJson.strictString(frame, "event");
                if ("debug-ready".equals(event)) ready.complete(DshJson.strictString(value, "url"));
                else if ("debug-call".equals(event)) {
                    String id = DshJson.strictString(value, "id"),
                            name = DshJson.strictString(value, "name");
                    if (id == null
                            || name == null
                            || !value.has("args")
                            || !value.get("args").isJsonObject()) continue;
                    tools.execute(name, value.getAsJsonObject("args"))
                            .whenComplete(
                                    (text, error) -> {
                                        if (closed) return;
                                        JsonObject reply = new JsonObject();
                                        reply.addProperty("type", "debug-result");
                                        reply.addProperty("id", id);
                                        String result =
                                                error == null ? text : DshJson.message(error);
                                        if (result != null && result.length() > 180_000)
                                            result = result.substring(0, 180_000) + "\n[truncated]";
                                        reply.addProperty("text", result);
                                        reply.addProperty("isError", error != null);
                                        try {
                                            DshRuntimeHelper.send(process, reply);
                                        } catch (Exception stopped) {
                                        }
                                    });
                }
            }
        } catch (Exception unavailable) {
            ready.completeExceptionally(
                    new IllegalStateException("Debugger bridge startup failed"));
        } finally {
            ready.completeExceptionally(new IllegalStateException("Debugger bridge exited"));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        tools.close();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (Exception ignored) {
            process.destroyForcibly();
        }
        reader.shutdownNow();
        if (patch != null)
            try {
                Files.deleteIfExists(patch);
            } catch (Exception ignored) {
            }
    }
}
