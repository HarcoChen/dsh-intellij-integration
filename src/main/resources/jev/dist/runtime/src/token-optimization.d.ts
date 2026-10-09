import type { TokenOptimizationFailureType } from '../../protocol/src/index.js';
import type { JevCallRequest } from './jev-client.js';
import type { TokenMeterServiceLike, ToolResultPrunerServiceLike, ToolExecutionLike } from './types.js';
export interface NativePruneOutcome {
    content: unknown;
    changed: boolean;
    charsBefore: number | null;
    charsAfter: number | null;
    charsRemoved: number;
}
export interface TokenMeasurementPair {
    before: number | null;
    after: number | null;
    removed: number | null;
}
export declare function estimateContentTokens(meter: TokenMeterServiceLike | undefined, content: unknown, isError: boolean, callId?: string): number | null;
export declare function estimateTextTokens(meter: TokenMeterServiceLike | undefined, text: string): number | null;
export declare function measureSessionTokens(meter: TokenMeterServiceLike | undefined, session: unknown): number | null;
export declare function preserveCriticalContent(original: unknown, pruned: readonly unknown[]): unknown[];
export declare function applyNativeResultPruner(pruner: ToolResultPrunerServiceLike | undefined, content: unknown, isError: boolean): NativePruneOutcome;
export declare function estimatePotentialSavings(meter: TokenMeterServiceLike | undefined, text: string): number | null;
export declare function estimateDecisionCost(meter: TokenMeterServiceLike | undefined, request: JevCallRequest): number | null;
export declare function tokenPair(before: number | null, after: number | null): TokenMeasurementPair;
export declare function failureType(error: unknown): TokenOptimizationFailureType | undefined;
export declare function executionCallId(exec: ToolExecutionLike): string | undefined;
//# sourceMappingURL=token-optimization.d.ts.map