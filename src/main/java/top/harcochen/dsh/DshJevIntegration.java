package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Extracts and mounts the IDE-neutral bundled Laya/Jev integration for owned Runtimes. */
final class DshJevIntegration {
    private static Path packageRoot;

    private DshJevIntegration() {}

    static synchronized String prepare(DshSettingsState settings) throws IOException {
        Path root = extract();
        Path entry = root.resolve("dist/runtime/src/index.js");
        if (!Files.isRegularFile(entry)) throw new IOException("Bundled Jev entry is missing");
        JsonObject config = new JsonObject();
        config.addProperty("enabled", settings.jevEnabled);
        config.addProperty("baseUrl", settings.jevBaseUrl);
        config.addProperty("model", settings.jevModel);
        config.add("loopGuard", enabled(settings.jevLoopGuard));
        config.add("resultShaper", enabled(settings.jevResultShaper));
        config.add("doneGate", enabled(settings.jevDoneGate));
        config.add("toolPruner", enabled(settings.jevToolPruner));
        config.add("skillRouter", enabled(settings.jevSkillRouter));
        config.add("decisionTools", enabled(settings.jevDecisionTools));
        JsonObject inserted = new JsonObject();
        inserted.addProperty("id", "dsh-jev-integration");
        inserted.addProperty("name", entry.toAbsolutePath().toString());
        inserted.add("config", config);
        JsonArray inserts = new JsonArray();
        inserts.add(inserted);
        JsonObject patch = new JsonObject();
        patch.add("insert", inserts);
        JsonArray patches = new JsonArray();
        patches.add(patch);
        Path overlay = Files.createTempFile(root, "jev-integration-", ".patch.yml");
        overlay.toFile().deleteOnExit();
        Files.writeString(
                overlay,
                patches.toString() + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        return overlay.toString();
    }

    private static JsonObject enabled(boolean value) {
        JsonObject config = new JsonObject();
        config.addProperty("enabled", value);
        return config;
    }

    private static Path extract() throws IOException {
        if (packageRoot != null && Files.isRegularFile(packageRoot.resolve("package.json"))) {
            return packageRoot;
        }
        Path root = Files.createTempDirectory("dsh-intellij-jev-");
        root.toFile().deleteOnExit();
        String manifest;
        try (InputStream source = DshJevIntegration.class.getResourceAsStream("/jev/files.txt")) {
            if (source == null) throw new IOException("Bundled Jev file list is missing");
            manifest = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String relative : manifest.lines().toList()) {
            if (relative.isBlank()) continue;
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IOException("Invalid bundled Jev path");
            Path parent = target.getParent();
            Path directory = root;
            for (Path segment : root.relativize(parent)) {
                directory = directory.resolve(segment);
                if (!Files.isDirectory(directory)) {
                    Files.createDirectory(directory);
                    directory.toFile().deleteOnExit();
                }
            }
            try (InputStream source =
                    DshJevIntegration.class.getResourceAsStream("/jev/" + relative)) {
                if (source == null)
                    throw new IOException("Bundled Jev file is missing: " + relative);
                Files.copy(source, target);
            }
            target.toFile().deleteOnExit();
        }
        packageRoot = root;
        return root;
    }
}
