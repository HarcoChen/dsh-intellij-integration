import type { StatisticsSnapshot } from '../../protocol/src/index.js';
import type { IntegrationPersistence } from './persistence.js';
export interface CallResultMeasurement {
    ok: boolean;
    latencyMs: number;
    requestBytes: number;
    responseBytes: number;
    timedOut?: boolean;
    cancelled?: boolean;
}
export declare class StatisticsStore {
    private readonly persistence;
    private readonly scopes;
    private persistQueue;
    private onChanged?;
    private onPersistenceError?;
    readonly ready: Promise<void>;
    constructor(persistence: IntegrationPersistence);
    setOnChanged(callback: (snapshot: StatisticsSnapshot) => void): void;
    setOnPersistenceError(callback: (error: unknown) => void): void;
    private load;
    private getOrCreate;
    private persist;
    private changed;
    get(workspaceId: string, sessionId?: string): StatisticsSnapshot;
    begin(workspaceId: string, sessionId: string | undefined, requestBytes: number): () => void;
    recordCall(workspaceId: string, sessionId: string | undefined, measurement: CallResultMeasurement, decision?: 'allow' | 'ask' | 'deny'): void;
    recordSkipped(workspaceId: string, sessionId?: string): void;
    recordLoopGuard(workspaceId: string, sessionId: string | undefined, outcome: 'warning' | 'interrupt' | 'uncertain'): void;
    recordDoneGate(workspaceId: string, sessionId: string | undefined, outcome: 'intervention' | 'uncertain' | 'pass'): void;
    recordResultShape(workspaceId: string, sessionId: string | undefined, charsRemoved: number): void;
    recordToolPruner(workspaceId: string, sessionId: string | undefined, outcome: 'call' | 'skipped' | 'low-confidence' | 'fail-open', seen?: number, kept?: number, removedChars?: number, latencyMs?: number): void;
    recordSkillRouter(workspaceId: string, sessionId: string | undefined, outcome: 'call' | 'skipped' | 'low-confidence' | 'fail-open', adviceCount?: number, adviceChars?: number, latencyMs?: number): void;
    recordDecisionTool(workspaceId: string, sessionId: string | undefined, outcome: 'call' | 'unknown' | 'fail-open', latencyMs?: number): void;
    recordDeterministicGuard(workspaceId: string, sessionId: string | undefined, denied: boolean): void;
    reset(workspaceId: string, sessionId?: string): Promise<StatisticsSnapshot>;
    flush(): Promise<void>;
}
//# sourceMappingURL=stats.d.ts.map