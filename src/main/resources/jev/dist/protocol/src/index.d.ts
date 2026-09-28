/**
 * Language-neutral protocol model for the DSH/Jev Runtime integration.
 *
 * The JSON Schema in ../schema is the normative wire contract. These TypeScript
 * declarations deliberately contain no Runtime or IDE dependencies so they can
 * be copied/generated into either host.
 */
export declare const INTEGRATION_VERSION: "0.1.0";
export declare const PROTOCOL_VERSION: "1.1";
export declare const MIN_DSH_RUNTIME_VERSION: "0.1.5-rc.1";
export declare const DEFAULT_JEV_BASE_URL: "https://api.typesafe.ai/v1/systemone";
export declare const DEFAULT_JEV_MODEL: "jev-latest";
export declare const DEFAULT_GUARDED_TOOLS: readonly ["bash", "pwsh", "terminal", "run_command", "execute_command", "run_code", "write_to_file", "replace_file_content"];
export interface LoopGuardConfig {
    enabled: boolean;
    triggerThreshold: number;
    noProgressThreshold: number;
    pLoopThreshold: number;
    minConfidence: number;
    cooldownSteps: number;
    maxHistory: number;
    deferExactRepeats: boolean;
    requestTimeoutMs: number;
    include: string[];
    exclude: string[];
}
export interface ResultShaperConfig {
    enabled: boolean;
    shapeTools: string[];
    thresholdChars: number;
    maxPerTurn: number;
    keepKinds: string[];
    minKindConfidence: number;
    maxClusters: number;
    sampleChars: number;
    requestTimeoutMs: number;
}
export interface DoneGateConfig {
    enabled: boolean;
    blockThreshold: number;
    minEvidenceItems: number;
    requestTimeoutMs: number;
    maxClaimChars: number;
    cooldownTurns: number;
}
export interface ToolPrunerConfig {
    enabled: boolean;
    maxTools: number;
    minScoreThreshold: number;
    minConfidence: number;
    minIntentChars: number;
    minKeep: number;
    maxCandidates: number;
    requestTimeoutMs: number;
    alwaysRetain: string[];
}
export interface SkillRouterConfig {
    enabled: boolean;
    minCandidates: number;
    minIntentChars: number;
    maxSkills: number;
    maxCandidates: number;
    minScore: number;
    minConfidence: number;
    nameMatchBoost: number;
    maxAdviceChars: number;
    requestTimeoutMs: number;
}
export interface DecisionToolsConfig {
    enabled: boolean;
    requestTimeoutMs: number;
    maxStateChars: number;
    maxQuestionChars: number;
    maxQuestions: number;
    maxCandidates: number;
    maxCandidateChars: number;
    maxResultChars: number;
}
export interface DeterministicSafetyGuardConfig {
    enabled: boolean;
    maxArgumentChars: number;
}
export declare const DEFAULT_LOOP_GUARD: LoopGuardConfig;
export declare const DEFAULT_RESULT_SHAPER: ResultShaperConfig;
export declare const DEFAULT_DONE_GATE: DoneGateConfig;
export declare const DEFAULT_TOOL_PRUNER: ToolPrunerConfig;
export declare const DEFAULT_SKILL_ROUTER: SkillRouterConfig;
export declare const DEFAULT_DECISION_TOOLS: DecisionToolsConfig;
export declare const DEFAULT_DETERMINISTIC_SAFETY_GUARD: DeterministicSafetyGuardConfig;
export declare const ERROR_CODES: readonly ["DJE-0001", "DJE-0002", "DJE-0003", "DJE-0004", "DJE-0005", "DJE-0006", "DJE-0007", "DJE-0008", "DJE-0009", "DJE-0010", "DJE-0011"];
export type ErrorCode = (typeof ERROR_CODES)[number];
export declare const ERROR_CODE_NAMES: Record<ErrorCode, string>;
export type ConfigScope = 'global' | 'workspace' | 'session' | 'next-turn';
export type ConfigSource = 'default' | 'startup' | 'global' | 'workspace' | 'session' | 'next-turn';
export type StateEventType = 'state.changed' | 'config.changed' | 'stats.changed';
export type JevDecision = 'allow' | 'ask' | 'deny' | 'skipped';
export interface IntegrationConfig {
    enabled: boolean;
    baseUrl: string;
    model: string;
    timeoutMs: number;
    advisoryTimeoutMs: number;
    askThreshold: number;
    blockThreshold: number;
    guardedTools: string[];
    loopGuard: LoopGuardConfig;
    resultShaper: ResultShaperConfig;
    doneGate: DoneGateConfig;
    toolPruner: ToolPrunerConfig;
    skillRouter: SkillRouterConfig;
    decisionTools: DecisionToolsConfig;
    deterministicSafetyGuard: DeterministicSafetyGuardConfig;
}
export type IntegrationConfigPatch = Partial<Omit<IntegrationConfig, 'loopGuard' | 'resultShaper' | 'doneGate' | 'toolPruner' | 'skillRouter' | 'decisionTools' | 'deterministicSafetyGuard'>> & {
    loopGuard?: Partial<LoopGuardConfig>;
    resultShaper?: Partial<ResultShaperConfig>;
    doneGate?: Partial<DoneGateConfig>;
    toolPruner?: Partial<ToolPrunerConfig>;
    skillRouter?: Partial<SkillRouterConfig>;
    decisionTools?: Partial<DecisionToolsConfig>;
    deterministicSafetyGuard?: Partial<DeterministicSafetyGuardConfig>;
};
export declare const DEFAULT_CONFIG: IntegrationConfig;
export interface EffectiveConfig {
    enabled: boolean;
    active: boolean;
    values: IntegrationConfig;
    source: ConfigSource;
}
export interface UserRequestedConfig {
    enabled: boolean;
    source: ConfigSource;
}
export interface RuntimeState {
    workspaceId: string;
    sessionId?: string;
    userRequested: UserRequestedConfig;
    effective: EffectiveConfig;
    available: boolean;
    availabilityReason?: string;
    executing: boolean;
    activeRequests: number;
    lastDecision?: JevDecision;
    lastErrorCode?: ErrorCode;
    lastErrorMessage?: string;
    updatedAt: string;
}
export interface StatisticsSnapshot {
    workspaceId: string;
    sessionId?: string;
    since: string;
    resetAt: string;
    totalCalls: number;
    successfulCalls: number;
    failedCalls: number;
    timeoutCalls: number;
    cancelledCalls: number;
    skippedCalls: number;
    allowDecisions: number;
    askDecisions: number;
    denyDecisions: number;
    totalLatencyMs: number;
    maxLatencyMs: number;
    totalRequestBytes: number;
    totalResponseBytes: number;
    activeCalls: number;
    loopGuardChecks: number;
    loopGuardWarnings: number;
    loopGuardInterruptions: number;
    loopGuardUncertain: number;
    doneGateChecks: number;
    doneGateInterventions: number;
    doneGateUncertain: number;
    resultShaperCalls: number;
    resultShaperCharsRemoved: number;
    toolPrunerCalls: number;
    toolPrunerSkipped: number;
    toolPrunerLowConfidence: number;
    toolPrunerFailOpen: number;
    toolPrunerToolsSeen: number;
    toolPrunerToolsKept: number;
    toolPrunerRemovedChars: number;
    toolPrunerLatencyMs: number;
    skillRouterCalls: number;
    skillRouterSkipped: number;
    skillRouterLowConfidence: number;
    skillRouterFailOpen: number;
    skillRouterAdviceCount: number;
    skillRouterAdviceChars: number;
    skillRouterLatencyMs: number;
    decisionToolCalls: number;
    decisionToolUnknown: number;
    decisionToolFailOpen: number;
    decisionToolLatencyMs: number;
    deterministicGuardChecks: number;
    deterministicGuardDenies: number;
}
export interface RuntimeCompatibility {
    minimumDshRuntime: string;
    testedDshRuntime: string;
    requiredFeatures: string[];
}
export interface CapabilitiesResponse {
    integrationVersion: string;
    integrationCommit: string;
    protocolVersion: string;
    runtimeCompatibility: RuntimeCompatibility;
    pluginMounted: boolean;
    endpointAvailable: boolean;
    endpointReason?: string;
    capabilities: {
        configRead: boolean;
        configWrite: boolean;
        stateRead: boolean;
        statsRead: boolean;
        statsReset: boolean;
        stateEvents: boolean;
        preExecuteHook: boolean;
        postExecuteHook: boolean;
        loopGuard: boolean;
        resultShaper: boolean;
        doneEvidenceGate: boolean;
        turnStoppingHook: boolean;
        nextTurnOverride: boolean;
        toolPruner: boolean;
        skillRouter: boolean;
        decisionTools: boolean;
        deterministicSafetyGuard: boolean;
    };
    jev: {
        baseUrl: string;
        model: string;
        available: boolean;
        reason?: string;
    };
}
export interface ConfigUpdateRequest {
    workspaceId: string;
    sessionId?: string;
    scope: ConfigScope;
    patch: IntegrationConfigPatch;
}
export interface ConfigResponse {
    workspaceId: string;
    sessionId?: string;
    persisted: boolean;
    scope: ConfigScope;
    source: ConfigSource;
    values: IntegrationConfig;
}
export interface StatisticsResetRequest {
    workspaceId: string;
    sessionId?: string;
}
export interface ProtocolError {
    code: ErrorCode;
    name: string;
    message: string;
    details?: Record<string, unknown>;
    retryable: boolean;
}
export interface ErrorResponse {
    ok: false;
    error: ProtocolError;
}
export interface StateEvent {
    id: string;
    type: StateEventType;
    at: string;
    workspaceId: string;
    sessionId?: string;
    state?: RuntimeState;
    stats?: StatisticsSnapshot;
}
export interface SuccessResponse<T> {
    ok: true;
    data: T;
}
export type ProtocolResponse<T> = SuccessResponse<T> | ErrorResponse;
export declare function isErrorCode(value: unknown): value is ErrorCode;
export declare function makeProtocolError(code: ErrorCode, message: string, details?: Record<string, unknown>, retryable?: boolean): ProtocolError;
//# sourceMappingURL=index.d.ts.map