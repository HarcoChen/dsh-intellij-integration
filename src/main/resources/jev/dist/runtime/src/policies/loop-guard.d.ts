import type { LoopGuardConfig } from '../../../protocol/src/index.js';
import type { LoopGuardOutcome, PolicyJevCall, PolicyToolExecution, PolicyToolResult } from './types.js';
export declare const STUCK_SEVERITY_CRITERIA: readonly ["Normal progress or healthy exploration", "Marginal repeat or stagnant exploration", "Definite dead loop, circular failures, or unrecoverable repetition"];
export interface LoopGuardThresholds {
    noProgressThreshold: number;
    pLoopThreshold: number;
    minConfidence: number;
}
export interface LoopGuardEvaluation {
    action: 'interrupt' | 'warn' | 'pass' | 'unknown';
    progress?: number;
    pLoop?: number;
    confidence?: number;
}
export declare function canonicalArgs(args: unknown): string;
export declare function extractResultText(content: unknown): string;
export declare function evaluateStuckTrajectory(answers: Record<string, unknown>, thresholds: LoopGuardThresholds): LoopGuardEvaluation;
export declare class LoopGuardPolicy {
    private readonly chains;
    reset(agent: unknown): void;
    inspect(exec: PolicyToolExecution, result: PolicyToolResult, config: LoopGuardConfig, call: PolicyJevCall): Promise<LoopGuardOutcome>;
}
//# sourceMappingURL=loop-guard.d.ts.map