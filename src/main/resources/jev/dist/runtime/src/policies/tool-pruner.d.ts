import type { ToolPrunerConfig } from '../../../protocol/src/index.js';
import type { ToolPrunerServiceLike, ToolSchemaLike } from '../types.js';
import type { PolicyJevCall } from './types.js';
export type ToolPrunerOutcome = 'call' | 'skipped' | 'low-confidence' | 'fail-open';
export interface ToolPruneResult {
    tools: readonly ToolSchemaLike[];
    outcome: ToolPrunerOutcome;
    seen: number;
    kept: number;
    removedChars: number;
    latencyMs: number;
}
export interface ToolPruneOptions {
    config: ToolPrunerConfig;
    signal?: AbortSignal;
    call: PolicyJevCall;
}
/**
 * Minimal Jev-backed implementation of the DSH `ctx.toolPruner` service.
 *
 * The service deliberately returns a subset of the input schemas in their
 * original order. It never changes the ToolRuntime registry or permissions;
 * it only changes the model-facing assembly that DSH is already preparing.
 */
export declare class JevToolPrunerService implements ToolPrunerServiceLike {
    pruneTools(intent: string, candidates: readonly ToolSchemaLike[], options?: Partial<ToolPruneOptions>): Promise<readonly ToolSchemaLike[]>;
    prune(intent: string, candidates: readonly ToolSchemaLike[], options: ToolPruneOptions): Promise<ToolPruneResult>;
}
//# sourceMappingURL=tool-pruner.d.ts.map