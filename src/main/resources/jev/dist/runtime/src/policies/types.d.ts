import type { DoneGateConfig, LoopGuardConfig, ResultShaperConfig } from '../../../protocol/src/index.js';
import type { JevQuestion, JevQuestionResult } from '../jev-client.js';
/** Structural views of the DSH values used by the policy hooks. */
export interface PolicyToolExecution {
    name?: string;
    arguments?: unknown;
    args?: unknown;
    signal?: AbortSignal;
    agent?: unknown;
    [key: string]: unknown;
}
export interface PolicyToolResult {
    isError?: boolean;
    content?: unknown;
    value?: unknown;
    error?: unknown;
    additionalContexts?: unknown[];
    [key: string]: unknown;
}
export type PolicyPostDecision = {
    kind: 'accept';
    action?: 'accept';
    content?: unknown;
    value?: never;
    additionalContexts?: unknown[];
    [key: string]: unknown;
} | {
    kind: 'accept';
    action?: 'accept';
    value?: unknown;
    content?: never;
    additionalContexts?: unknown[];
    [key: string]: unknown;
} | {
    kind: 'block';
    action?: 'block';
    feedback: unknown;
    additionalContexts?: unknown[];
    [key: string]: unknown;
};
export interface PolicyCall {
    answers: Record<string, JevQuestionResult>;
    latencyMs: number;
    requestBytes: number;
    responseBytes: number;
}
export type PolicyJevCall = (request: {
    state: string | Record<string, unknown>;
    questions: Record<string, JevQuestion>;
}, options: {
    timeoutMs: number;
    signal?: AbortSignal;
}) => Promise<PolicyCall>;
export interface LoopGuardOutcome {
    action: 'pass' | 'warning' | 'interrupt' | 'uncertain' | 'skipped';
    context?: Record<string, unknown>;
    latencyMs?: number;
}
export interface ShapeOutcome {
    text: string;
    keptClusters: number;
    droppedClusters: number;
    droppedLines: number;
    latencyMs: number;
}
export interface DoneGateOutcome {
    action: 'pass' | 'intervene' | 'uncertain' | 'skipped';
    context?: Record<string, unknown>;
    latencyMs?: number;
}
export type { DoneGateConfig, LoopGuardConfig, ResultShaperConfig };
//# sourceMappingURL=types.d.ts.map