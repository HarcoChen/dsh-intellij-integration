package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;

/** DeepSeek account actions through the public RC.2 Account RPC. */
final class DshAccountController {
    private final Project project;
    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    DshAccountController(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            ExecutorService operations,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.runtime = runtime;
        this.remote = remote;
        this.operations = operations;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void manage() {
        operations.execute(this::load);
    }

    private void load() {
        try {
            runtime.startAsync().join();
            JsonObject client = client();
            JsonObject state = remote.accountState();
            String status = DshJson.string(state, "status");
            if (!"signed-out".equals(status) && !"credential-stored".equals(status)) {
                throw new IllegalStateException("Invalid account state returned by Runtime");
            }
            JsonObject links =
                    state.has("links") && state.get("links").isJsonObject()
                            ? state.getAsJsonObject("links")
                            : new JsonObject();
            String summary =
                    "credential-stored".equals(status)
                            ? signedInSummary(client)
                            : DshBundle.message("dsh.account.signed.out");
            if ("credential-stored".equals(status)) showBonuses(client);
            ApplicationManager.getApplication()
                    .invokeLater(() -> choose(status, summary, links, client));
        } catch (Exception error) {
            errorSink.accept(DshJson.message(error));
            notifier.accept(DshBundle.message("dsh.account.failed", DshJson.message(error)));
        }
    }

    private String signedInSummary(JsonObject client) {
        StringBuilder summary = new StringBuilder(DshBundle.message("dsh.account.signed.in"));
        try {
            JsonElement profileValue = remote.accountProfile(client);
            if (profileValue.isJsonObject()) {
                JsonObject profile = profileValue.getAsJsonObject();
                if ("ready".equals(DshJson.string(profile, "status"))
                        && profile.has("value")
                        && profile.get("value").isJsonObject()) {
                    JsonObject value = profile.getAsJsonObject("value");
                    String name = DshJson.string(value, "name");
                    String contact = DshJson.string(value, "contact");
                    if (name != null) summary.append("\n").append(name);
                    if (contact != null) summary.append("  ").append(contact);
                }
            }
        } catch (Exception ignored) {
            // Account profile is optional while the credential is refreshing.
        }
        try {
            JsonElement balanceValue = remote.accountBalance(client);
            if (balanceValue.isJsonObject()) {
                JsonObject balance = balanceValue.getAsJsonObject();
                if ("ready".equals(DshJson.string(balance, "status"))) {
                    appendWallets(
                            summary,
                            DshBundle.message("dsh.account.balance"),
                            balance.get("value"));
                    appendWallets(
                            summary,
                            DshBundle.message("dsh.account.bonus"),
                            balance.get("bonusWallets"));
                }
            }
        } catch (Exception ignored) {
            // The menu remains usable while balance service is unavailable.
        }
        return summary.toString();
    }

    private static void appendWallets(StringBuilder summary, String label, JsonElement wallets) {
        if (wallets == null || !wallets.isJsonArray()) return;
        StringBuilder values = new StringBuilder();
        for (JsonElement candidate : wallets.getAsJsonArray()) {
            if (!candidate.isJsonObject()) continue;
            JsonObject wallet = candidate.getAsJsonObject();
            String currency = DshJson.string(wallet, "currency");
            String balance = DshJson.string(wallet, "balance");
            if (currency == null || balance == null) continue;
            if (!values.isEmpty()) values.append(" · ");
            values.append(currency).append(' ').append(balance);
        }
        if (!values.isEmpty()) summary.append("\n").append(label).append(": ").append(values);
    }

    private final java.util.concurrent.atomic.AtomicBoolean drainingBonuses =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void showBonuses(JsonObject client) {
        if (!drainingBonuses.compareAndSet(false, true)) return;
        String endpoint = runtime.getUrl();
        try {
            JsonObject batch = bonusBatch(remote.accountBonuses(client));
            if (batch == null) return;
            String accountId = DshJson.strictString(batch, "accountId");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonElement raw : batch.getAsJsonArray("bonuses")) {
                JsonObject original = raw.getAsJsonObject();
                String orderId = DshJson.strictString(original, "orderId");
                if (!seen.add(orderId) || !java.util.Objects.equals(endpoint, runtime.getUrl()))
                    continue;
                JsonObject current = bonusBatch(remote.accountBonuses(client));
                if (current == null
                        || !accountId.equals(DshJson.strictString(current, "accountId"))) return;
                JsonObject notice = null;
                for (JsonElement item : current.getAsJsonArray("bonuses"))
                    if (orderId.equals(DshJson.strictString(item.getAsJsonObject(), "orderId")))
                        notice = item.getAsJsonObject();
                if (notice == null) continue;
                String expiresAt = DshJson.strictString(notice, "expiresAt");
                try {
                    if (!expiresAt.isBlank()
                            && !java.time.Instant.parse(expiresAt).isAfter(java.time.Instant.now()))
                        continue;
                } catch (java.time.format.DateTimeParseException ignored) {
                    /* An unspecified expiration does not expire the notice. */
                }
                JsonObject displayed = notice;
                DshPluginManagementController.ui(
                        () -> {
                            if (java.util.Objects.equals(endpoint, runtime.getUrl()))
                                Messages.showInfoMessage(
                                        project,
                                        DshJson.strictString(displayed, "message"),
                                        DshJson.stringOr(
                                                displayed,
                                                "title",
                                                DshBundle.message("dsh.account.bonus")));
                            return null;
                        });
                if (!java.util.Objects.equals(endpoint, runtime.getUrl())) return;
                JsonObject latest = bonusBatch(remote.accountBonuses(client));
                if (latest == null || !accountId.equals(DshJson.strictString(latest, "accountId")))
                    return;
                JsonElement acknowledged =
                        remote.acknowledgeAccountBonus(accountId, orderId, client);
                if (!acknowledged.isJsonPrimitive()
                        || !acknowledged.getAsJsonPrimitive().isBoolean())
                    throw new IllegalStateException("Invalid bonus acknowledgement from Runtime");
                if (!acknowledged.getAsBoolean()) return;
            }
        } catch (DshRemoteException missing) {
            if (!missing.isCapabilityMissing()) errorSink.accept(DshJson.message(missing));
        } catch (RuntimeException error) {
            errorSink.accept(DshJson.message(error));
        } finally {
            drainingBonuses.set(false);
        }
    }

    private static JsonObject bonusBatch(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject())
            throw new IllegalArgumentException("Invalid account notifications");
        JsonObject batch = value.getAsJsonObject();
        if (DshJson.strictString(batch, "accountId") == null
                || !batch.has("bonuses")
                || !batch.get("bonuses").isJsonArray()
                || batch.getAsJsonArray("bonuses").size() > 1000)
            throw new IllegalArgumentException("Invalid account notifications");
        for (JsonElement raw : batch.getAsJsonArray("bonuses")) {
            if (!raw.isJsonObject())
                throw new IllegalArgumentException("Invalid account notification");
            JsonObject item = raw.getAsJsonObject();
            if (DshJson.strictString(item, "orderId") == null
                    || DshJson.string(item, "orderId").isBlank()
                    || DshJson.strictString(item, "message") == null
                    || DshJson.strictString(item, "expiresAt") == null
                    || (item.has("title") && DshJson.strictString(item, "title") == null))
                throw new IllegalArgumentException("Invalid account notification");
        }
        return batch;
    }

    private void choose(String status, String summary, JsonObject links, JsonObject client) {
        boolean signedIn = "credential-stored".equals(status);
        String[] actions =
                signedIn
                        ? new String[] {
                            DshBundle.message("dsh.account.refresh"),
                            DshBundle.message("dsh.account.sign.out"),
                            DshBundle.message("dsh.account.usage"),
                            DshBundle.message("dsh.account.top.up")
                        }
                        : new String[] {
                            DshBundle.message("dsh.account.refresh"),
                            DshBundle.message("dsh.account.sign.in")
                        };
        int action =
                DshChoiceDialog.show(
                        project,
                        summary,
                        DshBundle.message("dsh.account.title"),
                        Messages.getQuestionIcon(),
                        actions,
                        actions[0]);
        if (action == 0) manage();
        else if (!signedIn && action == 1) operations.execute(() -> signIn(client));
        else if (signedIn && action == 1) operations.execute(() -> confirmSignOut(client));
        else if (signedIn && action == 2) openLink(DshJson.string(links, "usageUrl"));
        else if (signedIn && action == 3) openLink(DshJson.string(links, "topUpUrl"));
    }

    private void signIn(JsonObject client) {
        try {
            String origin = callbackOrigin(runtime.getUrl());
            if (origin == null) {
                throw new IllegalStateException(DshBundle.message("dsh.account.local.runtime"));
            }
            JsonObject started = remote.startAccountSignIn(client, origin);
            if ("credential-stored".equals(DshJson.string(started, "status"))) {
                remote.initializeDefaultModel();
                notifier.accept(DshBundle.message("dsh.account.sign.in.complete"));
                return;
            }
            JsonObject attempt =
                    started.has("attempt") && started.get("attempt").isJsonObject()
                            ? started.getAsJsonObject("attempt")
                            : null;
            String url = attempt == null ? null : DshJson.string(attempt, "authorizeUrl");
            String attemptId = attempt == null ? null : DshJson.string(attempt, "id");
            if (attemptId == null) {
                throw new IllegalStateException(
                        "Runtime did not return an account sign-in attempt");
            }
            AppExecutorUtil.getAppExecutorService().execute(() -> watchAttempt(attemptId, url));
        } catch (Exception error) {
            errorSink.accept(DshJson.message(error));
            notifier.accept(DshBundle.message("dsh.account.failed", DshJson.message(error)));
        }
    }

    private void watchAttempt(String attemptId, String initialUrl) {
        String openedUrl = null;
        String candidateUrl = initialUrl;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        while (!project.isDisposed() && System.nanoTime() < deadline) {
            try {
                if (candidateUrl != null && !candidateUrl.equals(openedUrl)) {
                    URI authorization = safeExternalUrl(candidateUrl);
                    if (authorization == null) {
                        throw new IllegalStateException(
                                DshBundle.message("dsh.account.invalid.url"));
                    }
                    String opened = candidateUrl;
                    ApplicationManager.getApplication()
                            .invokeLater(() -> BrowserUtil.browse(opened));
                    notifier.accept(DshBundle.message("dsh.account.browser.opened"));
                    openedUrl = candidateUrl;
                }
                JsonObject state = remote.accountState();
                if ("credential-stored".equals(DshJson.string(state, "status"))) {
                    try {
                        remote.initializeDefaultModel();
                    } catch (DshRemoteException ignored) {
                        // Older compatible Runtimes do not expose this optional initializer.
                    }
                    notifier.accept(DshBundle.message("dsh.account.sign.in.complete"));
                    return;
                }
                JsonObject attempt =
                        state.has("attempt") && state.get("attempt").isJsonObject()
                                ? state.getAsJsonObject("attempt")
                                : null;
                if (attempt == null || !attemptId.equals(DshJson.string(attempt, "id"))) return;
                String phase = DshJson.string(attempt, "phase");
                if ("cancelled".equals(phase)
                        || "expired".equals(phase)
                        || "failed".equals(phase)) {
                    notifier.accept(DshBundle.message("dsh.account.sign.in.failed"));
                    return;
                }
                candidateUrl = DshJson.string(attempt, "authorizeUrl");
                Thread.sleep(1000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                errorSink.accept(DshJson.message(error));
                notifier.accept(DshBundle.message("dsh.account.failed", DshJson.message(error)));
                return;
            }
        }
    }

    private void confirmSignOut(JsonObject client) {
        try {
            JsonElement running = remote.hasRunningAccountTasks();
            if (!running.isJsonPrimitive() || !running.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalStateException("Invalid account task status returned by Runtime");
            }
            String message =
                    DshBundle.message(
                            running.getAsBoolean()
                                    ? "dsh.account.sign.out.running"
                                    : "dsh.account.sign.out.confirm");
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                int answer =
                                        Messages.showYesNoDialog(
                                                project,
                                                message,
                                                DshBundle.message("dsh.account.sign.out"),
                                                Messages.getWarningIcon());
                                if (answer == Messages.YES) {
                                    operations.execute(
                                            () -> {
                                                try {
                                                    remote.signOutAccount(client);
                                                    manage();
                                                } catch (Exception error) {
                                                    errorSink.accept(DshJson.message(error));
                                                    notifier.accept(
                                                            DshBundle.message(
                                                                    "dsh.account.failed",
                                                                    DshJson.message(error)));
                                                }
                                            });
                                }
                            });
        } catch (Exception error) {
            errorSink.accept(DshJson.message(error));
        }
    }

    private void openLink(String value) {
        URI uri = safeExternalUrl(value);
        if (uri == null) {
            notifier.accept(DshBundle.message("dsh.account.invalid.url"));
            return;
        }
        BrowserUtil.browse(uri.toString());
    }

    private static JsonObject client() {
        JsonObject metadata = new JsonObject();
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("top.harcochen.dsh"));
        metadata.addProperty("version", plugin == null ? "0.0.0" : plugin.getVersion());
        metadata.addProperty("locale", Locale.getDefault().toLanguageTag());
        metadata.addProperty(
                "timezoneOffsetSeconds", OffsetDateTime.now().getOffset().getTotalSeconds());
        return metadata;
    }

    private static String callbackOrigin(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"http".equals(uri.getScheme())
                    || !isLoopback(host)
                    || uri.getPort() <= 0
                    || uri.getUserInfo() != null) return null;
            return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + uri.getPort();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static URI safeExternalUrl(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || !("https".equals(uri.getScheme())
                            || ("http".equals(uri.getScheme()) && isLoopback(uri.getHost())))) {
                return null;
            }
            return uri;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
