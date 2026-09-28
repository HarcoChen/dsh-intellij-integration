import type { DoneGateConfig } from '../../../protocol/src/index.js';
import type { DoneGateOutcome, PolicyJevCall } from './types.js';
export interface EvidenceItem {
    text: string;
    isError?: boolean;
}
export interface CompletionTrace {
    turn: number;
    claim: string;
    evidence: EvidenceItem[];
    userPrompt?: string;
}
export declare function looksLikeCompletionClaim(text: string): boolean;
export declare class DoneEvidencePolicy {
    private readonly lastIntervention;
    reset(agent: unknown): void;
    inspect(agent: unknown, trace: CompletionTrace | undefined, config: DoneGateConfig, call: PolicyJevCall, signal?: AbortSignal): Promise<DoneGateOutcome>;
}
//# sourceMappingURL=done-gate.d.ts.map