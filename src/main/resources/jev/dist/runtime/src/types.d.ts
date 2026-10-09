import type { DoneGateConfig, DecisionToolsConfig, DeterministicSafetyGuardConfig, DeterministicSafetyRuleConfig, IntegrationConfig, LoopGuardConfig, ProtocolError, ResultShaperConfig, RuntimeState, SkillRouterConfig, TokenOptimizationConfig, ToolPrunerConfig } from '../../protocol/src/index.js';
import type { JevCallResult, JevQuestion } from './jev-client.js';
export interface DshSessionHeader {
    cwd?: string;
}
export interface DshSessionLike {
    id?: string;
    header?: DshSessionHeader;
}
export interface ToolExecutionLike {
    name?: string;
    callId?: string;
    arguments?: unknown;
    args?: unknown;
    signal?: AbortSignal;
    agent?: {
        session?: DshSessionLike;
        [key: string]: unknown;
    };
    [key: string]: unknown;
}
export interface ToolSchemaLike {
    name: string;
    description?: string;
    parameters?: unknown;
    [key: string]: unknown;
}
export interface ToolPrunerServiceLike {
    pruneTools?(intent: string, candidates: readonly ToolSchemaLike[], options?: {
        signal?: AbortSignal;
        config?: ToolPrunerConfig;
        call?: (request: {
            state: string | Record<string, unknown>;
            questions: Record<string, JevQuestion>;
        }, options: {
            timeoutMs: number;
            signal?: AbortSignal;
        }) => Promise<JevCallResult>;
    }): Promise<readonly ToolSchemaLike[]> | readonly ToolSchemaLike[];
}
/** Structural view of DSH's optional replay token-meter service. */
export interface TokenMeterServiceLike {
    estimateMessage?(message: unknown): number;
    measure?(session: unknown, requestHeader?: unknown): {
        totalTokens?: number;
        [key: string]: unknown;
    };
}
/** Structural view of DSH's model-free current tool-result pruner. */
export interface ToolResultPrunerServiceLike {
    measureContent?(blocks: readonly unknown[]): number;
    pruneContent?(blocks: readonly unknown[]): readonly unknown[] | null;
    pruneSession?(session: unknown): {
        charsRemoved?: number;
        [key: string]: unknown;
    };
}
export interface SkillSummaryLike {
    name: string;
    description?: string;
    whenToUse?: string;
    invocation?: {
        modelInvocable?: boolean;
    };
}
export interface SkillSnapshotLike {
    skills: SkillSummaryLike[];
    complete: boolean;
}
export interface SkillsServiceLike {
    snapshot?(options?: {
        cwd?: string;
        signal?: AbortSignal;
        scope?: unknown;
    }): Promise<SkillSnapshotLike>;
    list?(options?: {
        cwd?: string;
        signal?: AbortSignal;
        scope?: unknown;
    }): Promise<SkillSummaryLike[]>;
    get?(name: string, options?: {
        cwd?: string;
        signal?: AbortSignal;
        scope?: unknown;
    }): Promise<{
        name: string;
        description?: string;
        content?: string;
    } | undefined>;
}
export interface ToolsServiceLike {
    register?(definition: unknown): unknown;
    guard?(guard: (execution: Readonly<ToolExecutionLike>) => string | undefined): unknown;
}
export interface ToolResultLike {
    isError?: boolean;
    content?: unknown;
    value?: unknown;
    error?: unknown;
    additionalContexts?: unknown[];
    [key: string]: unknown;
}
export type PreToolDecisionLike = {
    kind: 'allow';
    action?: 'allow';
} | {
    kind: 'deny';
    action?: 'deny';
    reason: string;
} | {
    kind: 'ask';
    action?: 'ask';
    prompt?: string;
    reason?: string;
    details?: Record<string, unknown>;
} | {
    kind: 'cancel';
    action?: 'cancel';
};
export type PreToolNext = () => Promise<PreToolDecisionLike> | PreToolDecisionLike;
export type PostToolDecisionLike = {
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
export type PostToolNext = () => Promise<PostToolDecisionLike> | PostToolDecisionLike;
export interface AgentTurnStoppingPayload {
    agent?: unknown;
    turn?: number;
    signal?: AbortSignal;
}
export interface WorkspaceLike {
    id: string;
}
export interface WorkspaceRegistryLike {
    resolveByPath(path: string): Promise<WorkspaceLike | undefined>;
}
export interface FetchRoute {
    path: string;
    methods: readonly ('GET' | 'HEAD' | 'POST')[];
    requestBody: 'buffered' | 'streaming';
    fetch(request: Request): Promise<Response>;
}
export interface ConnectionLike {
    fetch: {
        register(route: FetchRoute): Promise<() => Promise<void>> | (() => Promise<void>);
    };
}
export interface LoggerLike {
    debug?(message: string, ...args: unknown[]): void;
    info?(message: string, ...args: unknown[]): void;
    warn?(message: string, ...args: unknown[]): void;
    error?(message: string, ...args: unknown[]): void;
}
export interface CordisContextLike {
    get?(name: string): unknown;
    provide?(name: string, value: unknown): unknown;
    effect?(effect: () => void | (() => void | Promise<void>), label?: string): unknown;
    inject?(services: readonly string[], callback: (ctx: CordisContextLike) => void): unknown;
    on?(event: string, callback: (...args: unknown[]) => unknown): (() => void) | void;
    connection?: ConnectionLike;
    workspaceRegistry?: WorkspaceRegistryLike;
    logger?: LoggerLike;
    [key: string]: unknown;
}
export interface RuntimePluginOptions {
    enabled?: boolean;
    apiKey?: string;
    baseUrl?: string;
    model?: string;
    timeoutMs?: number;
    advisoryTimeoutMs?: number;
    askThreshold?: number;
    blockThreshold?: number;
    guardedTools?: string[];
    loopGuard?: Partial<LoopGuardConfig>;
    resultShaper?: Partial<ResultShaperConfig>;
    tokenOptimization?: Partial<TokenOptimizationConfig>;
    doneGate?: Partial<DoneGateConfig>;
    toolPruner?: Partial<ToolPrunerConfig>;
    skillRouter?: Partial<SkillRouterConfig>;
    decisionTools?: Partial<DecisionToolsConfig>;
    deterministicSafetyGuard?: Partial<Omit<DeterministicSafetyGuardConfig, 'customRules'>> & {
        customRules?: Array<Partial<DeterministicSafetyRuleConfig>>;
    };
    persistenceRootDir?: string;
    integrationCommit?: string;
    dshRuntimeVersion?: string;
    fetch?: typeof globalThis.fetch;
}
export interface IntegrationStatus {
    state: RuntimeState;
    config: IntegrationConfig;
}
export interface RouteErrorResponse {
    ok: false;
    error: ProtocolError;
}
//# sourceMappingURL=types.d.ts.map