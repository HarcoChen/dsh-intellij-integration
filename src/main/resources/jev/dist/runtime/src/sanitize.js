/**
 * Redacts credential-bearing values before a request leaves the Runtime.
 * This is intentionally conservative: Jev receives bounded structure and
 * labels, never raw tool arguments, environment values, or authorization data.
 */
const SENSITIVE_KEY = /(?:api[_-]?key|token|secret|password|credential|authorization|private[_-]?key|arguments?|args|env)/i;
const PRIVATE_KEY = /-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?-----END [^-]*PRIVATE KEY-----/gi;
const BEARER = /\bBearer\s+[A-Za-z0-9._~+/=-]+/gi;
const JWT = /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9._-]{8,}\.[A-Za-z0-9._-]{8,}\b/g;
const LONG_SECRET_ASSIGNMENT = /((?:api[_-]?key|token|secret|password|credential|authorization)\s*[:=]\s*["']?)[^\s,"'}]+/gi;
const ENV_REFERENCE = /\$\{?[A-Z][A-Z0-9_]*(?:\}?)/g;
function redactString(value) {
    return value
        .replace(PRIVATE_KEY, '[REDACTED_PRIVATE_KEY]')
        .replace(BEARER, 'Bearer [REDACTED]')
        .replace(JWT, '[REDACTED_JWT]')
        .replace(LONG_SECRET_ASSIGNMENT, '$1[REDACTED]')
        .replace(ENV_REFERENCE, '[REDACTED_ENV]');
}
export function sanitizeForJev(value, depth = 0) {
    if (depth > 8)
        return '[REDACTED_DEPTH]';
    if (typeof value === 'string')
        return redactString(value);
    if (typeof value === 'number' || typeof value === 'boolean' || value === null)
        return value;
    if (Array.isArray(value))
        return value.slice(0, 128).map(item => sanitizeForJev(item, depth + 1));
    if (typeof value !== 'object')
        return String(value);
    const output = {};
    for (const [key, entry] of Object.entries(value).slice(0, 128)) {
        if (SENSITIVE_KEY.test(key))
            output[key] = '[REDACTED]';
        else
            output[key] = sanitizeForJev(entry, depth + 1);
    }
    return output;
}
export function sanitizeFailureReason(value) {
    const text = value instanceof Error ? value.message : typeof value === 'string' ? value : String(value);
    return redactString(text).replace(/\s+/g, ' ').slice(0, 200);
}
//# sourceMappingURL=sanitize.js.map