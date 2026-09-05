package top.harcochen.dsh;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two URLs emitted by an authenticated {@code dsh web} Runtime.
 *
 * <p>The launch URL is deliberately kept separate from the base URL. The former is used once to
 * exchange the printed token for a Runtime-owned session cookie; the latter is used for all API and
 * WebSocket requests after that exchange. Keeping the token out of the base URL also prevents it
 * from being copied into arbitrary API paths or diagnostics.
 */
final class DshRuntimeEndpoint {
    private static final Pattern AUTH_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern OUTPUT_URL =
            Pattern.compile(
                    "https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1\\]):\\d+(?:/\\?token=[A-Za-z0-9_-]+)?",
                    Pattern.CASE_INSENSITIVE);

    final String baseUrl;
    final String launchUrl;

    private DshRuntimeEndpoint(String baseUrl, String launchUrl) {
        this.baseUrl = baseUrl;
        this.launchUrl = launchUrl;
    }

    static DshRuntimeEndpoint ofBase(String baseUrl) {
        return new DshRuntimeEndpoint(baseUrl, null);
    }

    /**
     * Parse a user/process URL while rejecting credentials, fragments, paths, and unknown query
     * keys.
     */
    static DshRuntimeEndpoint parse(String value, boolean loopbackOnly) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getHost() == null
                    || uri.getPort() <= 0) return null;
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) return null;

            String host = uri.getHost();
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (loopbackOnly
                    && (!("http".equalsIgnoreCase(scheme))
                            || !("127.0.0.1".equals(lowerHost)
                                    || "localhost".equals(lowerHost)
                                    || "0.0.0.0".equals(lowerHost)
                                    || "[::1]".equals(lowerHost)
                                    || "::1".equals(lowerHost)))) return null;

            String token = null;
            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                String[] pairs = query.split("&", -1);
                if (pairs.length != 1) return null;
                String[] keyValue = pairs[0].split("=", 2);
                if (keyValue.length != 2 || !"token".equals(keyValue[0])) return null;
                token = keyValue[1];
                if (!AUTH_TOKEN.matcher(token).matches()) return null;
            } else if (query != null) {
                return null;
            }

            String canonicalHost;
            if (loopbackOnly) {
                canonicalHost =
                        ("::1".equals(lowerHost) || "[::1]".equals(lowerHost))
                                ? "[::1]"
                                : "127.0.0.1";
            } else {
                canonicalHost = uri.getRawAuthority();
                int at = canonicalHost == null ? -1 : canonicalHost.lastIndexOf('@');
                if (at >= 0) return null;
                if (canonicalHost == null || canonicalHost.isBlank()) return null;
                // URI#getRawAuthority contains the explicit port and preserves an IPv6 bracket.
                String prefix = scheme.toLowerCase(Locale.ROOT) + "://";
                String base = prefix + canonicalHost;
                return token == null
                        ? new DshRuntimeEndpoint(base, null)
                        : new DshRuntimeEndpoint(base, base + "?token=" + token);
            }

            String base =
                    scheme.toLowerCase(Locale.ROOT) + "://" + canonicalHost + ":" + uri.getPort();
            return token == null
                    ? new DshRuntimeEndpoint(base, null)
                    : new DshRuntimeEndpoint(base, base + "?token=" + token);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** Extract one loopback Runtime URL from process output, preserving its token if present. */
    static DshRuntimeEndpoint extract(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = OUTPUT_URL.matcher(value);
        return matcher.find() ? parse(matcher.group(), true) : null;
    }
}
