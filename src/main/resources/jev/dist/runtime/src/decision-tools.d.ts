import type { DecisionToolsConfig } from '../../protocol/src/index.js';
import { type JevCallResult, type JevQuestion } from './jev-client.js';
import type { ToolExecutionLike, ToolsServiceLike } from './types.js';
interface DecisionToolRuntime {
    config: DecisionToolsConfig;
    register: (definition: unknown) => unknown;
    onRegistered?: (name: string) => void;
    resolveConfig?: (exec?: ToolExecutionLike) => DecisionToolsConfig;
    call: (request: {
        state: string | Record<string, unknown>;
        questions: Record<string, JevQuestion>;
    }, options: {
        timeoutMs: number;
        signal?: AbortSignal;
    }, exec?: ToolExecutionLike) => Promise<JevCallResult>;
    record: (outcome: 'call' | 'unknown' | 'fail-open', latencyMs: number, exec?: ToolExecutionLike) => void;
}
export declare function registerDecisionTools(tools: ToolsServiceLike | undefined, runtime: DecisionToolRuntime): (() => void)[];
export {};
//# sourceMappingURL=decision-tools.d.ts.map