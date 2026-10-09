/**
 * Redacts credential-bearing values before a request leaves the Runtime.
 * This is intentionally conservative: Jev receives bounded structure and
 * labels, never raw tool arguments, environment values, or authorization data.
 */
export declare function sanitizeForJev(value: unknown, depth?: number): unknown;
export declare function sanitizeFailureReason(value: unknown): string;
//# sourceMappingURL=sanitize.d.ts.map