package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.PathManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Private stdin/stdout bridge to the bundled, editor-independent recovery engine. */
final class DshRuntimeHelper {
    private static Path helper;

    private DshRuntimeHelper() {}

    static synchronized Path executable() throws IOException {
        if (helper != null && Files.isRegularFile(helper)) return helper;
        helper = Files.createTempFile("dsh-intellij-helper-", ".cjs");
        try (var source = DshRuntimeHelper.class.getResourceAsStream("/runtime/helper.cjs")) {
            if (source == null)
                throw new IOException("DSH recovery helper is missing from the plugin");
            Files.copy(source, helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        helper.toFile().deleteOnExit();
        return helper;
    }

    static JsonObject configuration(
            List<String> command,
            String cwd,
            String version,
            DshSettingsState settings,
            String overlay) {
        JsonObject config = new JsonObject();
        config.addProperty("command", command.get(0));
        JsonArray args = new JsonArray();
        for (String arg : command.subList(1, command.size())) args.add(arg);
        config.add("args", args);
        config.addProperty("cwd", cwd);
        config.addProperty("version", version);
        config.addProperty("storage", storage(cwd).toString());
        config.addProperty("enabled", settings.recoveryEnabled);
        config.addProperty("isolate", settings.recoveryAutoPersistBundleIsolation);
        JsonArray overlays = new JsonArray();
        if (overlay != null) overlays.add(overlay);
        config.add("overlays", overlays);
        return config;
    }

    static Path storage(String cwd) {
        try {
            String key =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(cwd.getBytes(StandardCharsets.UTF_8)))
                            .substring(0, 24);
            return Path.of(PathManager.getSystemPath(), "dsh", "runtime", key);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void send(Process helper, JsonObject message) throws IOException {
        synchronized (helper) {
            helper.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
            helper.getOutputStream().flush();
        }
    }
}
