package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class DshBalanceService implements Disposable {
    private static final Logger LOG = Logger.getInstance(DshBalanceService.class);
    private static final String ENDPOINT = "https://api.deepseek.com/user/balance";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final double LOW_BALANCE_THRESHOLD = 10.0;

    record BalanceInfo(String currency, double total, double granted, double toppedUp) {}

    enum State { NO_KEY, CHECKING, OK, LOW, ERROR, UNAVAILABLE }

    record Snapshot(State state, String text, String tooltip, DshDeepSeekPricing.Period pricing) {}

    private final Project project;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final List<Consumer<Snapshot>> listeners = new ArrayList<>();
    private volatile Snapshot latest;
    private volatile ScheduledFuture<?> timer;
    private volatile boolean disposed;

    DshBalanceService(Project project) {
        this.project = project;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsh-balance-refresh");
            t.setDaemon(true);
            return t;
        });
        this.latest = new Snapshot(State.NO_KEY, DshBundle.message("dsh.balance.set.key"), DshBundle.message("dsh.balance.set.key.tooltip"), DshDeepSeekPricing.current());
    }

    void start() {
        int interval = Math.max(10_000, Math.min(3_600_000, DshSettingsState.getInstance(project).balanceRefreshIntervalMs));
        timer = scheduler.scheduleWithFixedDelay(this::refresh, 0, interval, TimeUnit.MILLISECONDS);
    }

    Snapshot snapshot() { return latest; }

    void addListener(Consumer<Snapshot> listener) { synchronized (listeners) { listeners.add(listener); } }
    void removeListener(Consumer<Snapshot> listener) { synchronized (listeners) { listeners.remove(listener); } }

    void refresh() {
        if (disposed || !refreshing.compareAndSet(false, true)) return;
        try {
            String apiKey = resolveApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                publish(new Snapshot(State.NO_KEY, DshBundle.message("dsh.balance.set.key"),
                        DshBundle.message("dsh.balance.set.key.tooltip"), DshDeepSeekPricing.current()));
                return;
            }
            publish(new Snapshot(State.CHECKING, DshBundle.message("dsh.balance.checking"),
                    DshBundle.message("dsh.balance.checking.tooltip"), DshDeepSeekPricing.current()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                publish(new Snapshot(State.ERROR, DshBundle.message("dsh.balance.error"),
                        DshBundle.message("dsh.balance.error.http", response.statusCode()), DshDeepSeekPricing.current()));
                return;
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            boolean available = body.has("is_available") && body.get("is_available").getAsBoolean();
            if (!available) {
                publish(new Snapshot(State.UNAVAILABLE, DshBundle.message("dsh.balance.unavailable"),
                        DshBundle.message("dsh.balance.unavailable.tooltip"), DshDeepSeekPricing.current()));
                return;
            }

            if (!body.has("balance_infos") || !body.get("balance_infos").isJsonArray()) {
                publish(new Snapshot(State.ERROR, DshBundle.message("dsh.balance.error"),
                        DshBundle.message("dsh.balance.error.invalid"), DshDeepSeekPricing.current()));
                return;
            }
            JsonArray infos = body.getAsJsonArray("balance_infos");
            List<BalanceInfo> balances = new ArrayList<>();
            for (JsonElement e : infos) {
                if (!e.isJsonObject()) continue;
                JsonObject info = e.getAsJsonObject();
                if (!info.has("currency") || !info.has("total_balance")
                        || !info.has("granted_balance") || !info.has("topped_up_balance")) continue;
                balances.add(new BalanceInfo(
                        info.get("currency").getAsString(),
                        Double.parseDouble(info.get("total_balance").getAsString()),
                        Double.parseDouble(info.get("granted_balance").getAsString()),
                        Double.parseDouble(info.get("topped_up_balance").getAsString())));
            }

            BalanceInfo preferred = preferredBalance(balances);
            DshDeepSeekPricing.Period pricing = DshDeepSeekPricing.current();
            boolean low = preferred != null && preferred.total < LOW_BALANCE_THRESHOLD;
            String pricingMark = pricing == DshDeepSeekPricing.Period.PEAK ? "↑" : "↓";
            String text = preferred == null ? DshBundle.message("dsh.balance.unavailable")
                    : DshBundle.message("dsh.balance.status", formatAmount(preferred.total), preferred.currency, pricingMark);

            StringBuilder tip = new StringBuilder();
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.getDefault());
            fmt.setMinimumFractionDigits(2);
            fmt.setMaximumFractionDigits(2);
            for (BalanceInfo b : balances) {
                tip.append(DshBundle.message("dsh.balance.detail",
                        b.currency, fmt.format(b.total), fmt.format(b.granted), fmt.format(b.toppedUp))).append("\n");
            }
            tip.append("\n");
            if (pricing == DshDeepSeekPricing.Period.PEAK) {
                tip.append(DshBundle.message("dsh.balance.pricing.peak"));
            } else {
                tip.append(DshBundle.message("dsh.balance.pricing.off.peak"));
            }
            tip.append("\n").append(DshBundle.message("dsh.balance.pricing.hours"));
            tip.append("\n\n").append(DshBundle.message("dsh.balance.click.refresh"));

            publish(new Snapshot(low ? State.LOW : State.OK, text, tip.toString(), pricing));
        } catch (Exception error) {
            String message = error.getMessage();
            if (error instanceof java.net.http.HttpTimeoutException) {
                message = DshBundle.message("dsh.balance.error.timeout");
            }
            publish(new Snapshot(State.ERROR, DshBundle.message("dsh.balance.error"),
                    message != null ? message : error.toString(), DshDeepSeekPricing.current()));
        } finally {
            refreshing.set(false);
        }
    }

    private String resolveApiKey() {
        String key = DshCredentials.read(project);
        if (key != null && !key.isBlank()) return key;
        String envName = DshSettingsState.getInstance(project).apiKeyEnv;
        if (envName != null && !envName.isBlank()) {
            String envValue = System.getenv(envName.trim());
            if (envValue != null && !envValue.isBlank()) return envValue;
        }
        return null;
    }

    private static BalanceInfo preferredBalance(List<BalanceInfo> balances) {
        BalanceInfo usd = null, cny = null;
        for (BalanceInfo b : balances) {
            if ("USD".equalsIgnoreCase(b.currency)) usd = b;
            if ("CNY".equalsIgnoreCase(b.currency)) cny = b;
        }
        if (usd != null) return usd;
        if (cny != null) return cny;
        return balances.isEmpty() ? null : balances.get(0);
    }

    private static String formatAmount(double amount) {
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.getDefault());
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
        return fmt.format(amount);
    }

    private void publish(Snapshot snapshot) {
        latest = snapshot;
        List<Consumer<Snapshot>> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Consumer<Snapshot> listener : copy) {
            try { listener.accept(snapshot); } catch (Exception error) { LOG.debug("Balance listener failed", error); }
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        ScheduledFuture<?> t = timer;
        if (t != null) t.cancel(false);
        scheduler.shutdownNow();
    }
}
