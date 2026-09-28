import type { SkillRouterConfig } from '../../../protocol/src/index.js';
import type { SkillsServiceLike } from '../types.js';
import type { PolicyJevCall } from './types.js';
export type SkillRouterOutcome = 'call' | 'skipped' | 'low-confidence' | 'fail-open';
export interface SkillRouteResult {
    advice: string[];
    outcome: SkillRouterOutcome;
    adviceCount: number;
    adviceChars: number;
    latencyMs: number;
}
export interface SkillRouteOptions {
    config: SkillRouterConfig;
    service: SkillsServiceLike;
    cwd?: string;
    scope?: unknown;
    signal?: AbortSignal;
    call: PolicyJevCall;
}
/**
 * Selects skill advice without replacing the user's prompt or deleting skill
 * definitions. The actual skill body is fetched only after a confident rank;
 * the returned value is an additive context for system-prompt assembly.
 */
export declare class JevSkillRouterPolicy {
    route(intent: string, options: SkillRouteOptions): Promise<SkillRouteResult>;
}
//# sourceMappingURL=skill-router.d.ts.map