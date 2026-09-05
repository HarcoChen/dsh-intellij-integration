package top.harcochen.dsh.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Authority-bound session authentication for the RC Remote API.
 *
 * <p>The Runtime prints a one-time {@code /?token=...} launch URL. That URL may only be used
 * against the root path, where the Runtime answers with an HttpOnly session cookie bound to the
 * scheme/host/port. Every unary RPC and the {@code /api/remote.mux} upgrade then reuse that cookie.
 * The launch token never reaches an API path, a log line, or a diagnostic.
 *
 * <p>Runtimes before 0.1.2 are rejected here on purpose: a server that answers the root path
 * without setting a cookie does not speak the RC Remote protocol, and silently passing it through
 * would surface later as confusing missing-endpoint failures.
 */
public final class DshRemoteAuth {
    private static final int AUTH_TIMEOUT_MS = 3_000;
    private static final HttpClient AUTH_HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(AUTH_TIMEOUT_MS))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private final Supplier<String> baseUrlSupplier;
    private final Supplier<String> launchUrlSupplier;
    private final Object lock = new Object();

    private String cookie;
    private String cookieLaunchUrl;
    private String failedLaunchUrl;

    public DshRemoteAuth(Supplier<String> baseUrlSupplier, Supplier<String> launchUrlSupplier) {
        this.baseUrlSupplier = baseUrlSupplier;
        this.launchUrlSupplier = launchUrlSupplier;
    }

    /**
     * The cookie value to present to the configured authority, exchanging the launch token once
     * when needed. Callers must treat the result as opaque; it is never logged.
     */
    public String cookie() throws DshRemoteException {
        synchronized (lock) {
            String launch = launchUrlSupplier.get();
            if (launch == null || launch.isBlank()) {
                throw DshRemoteException.auth(
                        "runtime.auth",
                        "no-launch-url",
                        "DSH Runtime did not advertise a launch URL",
                        null);
            }
            if (cookie != null && Objects.equals(cookieLaunchUrl, launch)) {
                return cookie;
            }
            if (launch.equals(failedLaunchUrl)) {
                throw DshRemoteException.auth(
                        "runtime.auth",
                        "launch-exchange-failed",
                        "DSH Runtime launch-token exchange failed; restart the Runtime for a new token",
                        null);
            }
            try {
                cookie = exchange(launch);
            } catch (DshRemoteException error) {
                // Transport-level failures are transient (the Runtime may still
                // be starting); semantic failures mean the token is unusable.
                if (!"transport-error".equals(error.code())
                        && !"interrupted".equals(error.code())) {
                    failedLaunchUrl = launch;
                }
                throw error;
            }
            cookieLaunchUrl = launch;
            return cookie;
        }
    }

    /** Headers carrying the current session cookie; empty until authentication succeeded. */
    public java.util.Map<String, String> headers() {
        String current = cookie;
        return current == null || current.isBlank()
                ? java.util.Map.of()
                : java.util.Map.of("Cookie", current);
    }

    /** True when a cookie for the currently configured launch URL is already established. */
    public boolean isEstablished() {
        synchronized (lock) {
            return cookie != null && Objects.equals(cookieLaunchUrl, launchUrlSupplier.get());
        }
    }

    /**
     * Drop the cookie after an HTTP 401/403 or an authority change. The failed launch URL is
     * remembered so the same one-time token is never replayed in a loop; a Runtime restart prints a
     * fresh launch URL and automatically re-enables the exchange.
     */
    public void invalidate() {
        synchronized (lock) {
            String launch = cookieLaunchUrl;
            if (cookie != null && launch != null) {
                failedLaunchUrl = launch;
            }
            cookie = null;
            cookieLaunchUrl = null;
        }
    }

    /** Best-effort authentication probe for readiness checks. */
    public boolean tryEstablish() {
        try {
            cookie();
            return true;
        } catch (DshRemoteException ignored) {
            return false;
        }
    }

    private String exchange(String launch) throws DshRemoteException {
        HttpRequest request;
        try {
            request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(launch))
                            .timeout(Duration.ofMillis(AUTH_TIMEOUT_MS))
                            .header("accept", "text/html,text/plain")
                            .GET()
                            .build();
        } catch (IllegalArgumentException error) {
            throw DshRemoteException.auth(
                    "runtime.auth",
                    "invalid-launch-url",
                    "DSH Runtime launch URL is invalid",
                    error);
        }
        HttpResponse<String> response;
        try {
            response = AUTH_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw DshRemoteException.auth(
                    "runtime.auth",
                    "interrupted",
                    "DSH Runtime authentication was interrupted",
                    error);
        } catch (Exception error) {
            throw DshRemoteException.auth(
                    "runtime.auth",
                    "transport-error",
                    error.getMessage() == null
                            ? "DSH Runtime authentication failed"
                            : error.getMessage(),
                    error);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw DshRemoteException.auth("runtime.auth", response.statusCode());
        }
        if (response.statusCode() != 303
                && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            throw DshRemoteException.auth(
                    "runtime.auth",
                    "http-" + response.statusCode(),
                    "DSH Runtime authentication returned HTTP " + response.statusCode(),
                    null);
        }
        String setCookie = response.headers().firstValue("set-cookie").orElse(null);
        String value = setCookie == null ? null : setCookie.split(";", 2)[0].trim();
        if (value == null || !value.matches("^[^=;\\r\\n]+=[^;\\r\\n]*$")) {
            throw DshRemoteException.auth(
                    "runtime.auth",
                    "missing-cookie",
                    "DSH Runtime authentication did not return a session cookie; "
                            + "this Runtime version is not supported",
                    null);
        }
        return value;
    }

    /** True when the base URL still matches the authority this instance authenticates against. */
    public boolean matchesBaseUrl(String baseUrl) {
        String current = baseUrlSupplier.get();
        return Objects.equals(current, baseUrl);
    }
}
