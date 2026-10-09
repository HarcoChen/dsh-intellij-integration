/**
 * Language-neutral protocol model for the DSH/Jev Runtime integration.
 *
 * The JSON Schema in ../schema is the normative wire contract. These TypeScript
 * declarations deliberately contain no Runtime or IDE dependencies so they can
 * be copied/generated into either host.
 */
export const INTEGRATION_VERSION = '0.1.0';
export const PROTOCOL_VERSION = '1.1';
export const MIN_DSH_RUNTIME_VERSION = '0.1.5-rc.1';
export const DEFAULT_JEV_BASE_URL = 'https://api.typesafe.ai/v1/systemone';
export const DEFAULT_JEV_MODEL = 'jev-latest';
export const DEFAULT_GUARDED_TOOLS = [
    'bash',
    'pwsh',
    'terminal',
    'run_command',
    'execute_command',
    'run_code',
    'write_to_file',
    'replace_file_content',
];
export const DEFAULT_LOOP_GUARD = {
    // Semantic post-execute calls are opt-in because tool output and trajectory
    // samples leave the Runtime for the configured Jev endpoint.
    enabled: false,
    triggerThreshold: 2,
    noProgressThreshold: 0.3,
    pLoopThreshold: 0.6,
    minConfidence: 0.5,
    cooldownSteps: 3,
    maxHistory: 8,
    deferExactRepeats: true,
    requestTimeoutMs: 3_500,
    include: [],
    exclude: [],
};
export const DEFAULT_RESULT_SHAPER = {
    // Result shaping changes model-visible content and is deliberately opt-in.
    enabled: false,
    shapeTools: ['bash', 'pwsh', 'terminal', 'run_command', 'execute_command'],
    thresholdChars: 8_000,
    maxPerTurn: 2,
    keepKinds: ['warning', 'failure', 'error', 'summary'],
    minKindConfidence: 0.6,
    maxClusters: 24,
    sampleChars: 400,
    requestTimeoutMs: 4_000,
};
export const DEFAULT_TOKEN_OPTIMIZATION = {
    // The coordinator is opt-in. A host can enable deterministic pruning and
    // semantic fallback independently without changing the default DSH loop.
    enabled: false,
    deterministicFirst: true,
    minInputTokens: 256,
    minEstimatedSavingsTokens: 64,
    semanticFallback: false,
    maxDecisionLatencyMs: 1_500,
};
export const DEFAULT_DONE_GATE = {
    // Completion/evidence checking is experimental and must be explicitly enabled
    // by the host after explaining that the latest assistant claim is sent to Jev.
    enabled: false,
    blockThreshold: 0.75,
    minEvidenceItems: 1,
    requestTimeoutMs: 3_500,
    maxClaimChars: 4_000,
    cooldownTurns: 1,
};
export const DEFAULT_TOOL_PRUNER = {
    // Tool schemas are sent to Jev only when a host explicitly enables this
    // model-visible optimization and the integration itself is enabled.
    enabled: false,
    maxTools: 8,
    minScoreThreshold: 2,
    minConfidence: 0.5,
    minIntentChars: 8,
    minKeep: 3,
    maxCandidates: 50,
    requestTimeoutMs: 4_000,
    alwaysRetain: [
        'run_code', 'skill', 'jev_ask', 'jev_rank', 'jev_check',
        'read_file', 'write_to_file', 'write_file', 'edit_file',
        'str_replace_editor', 'bash', 'terminal', 'pwsh', 'run_command',
        'execute_command', 'grep', 'glob', 'find_by_name', 'view_file',
        'replace_file_content',
    ],
};
export const DEFAULT_SKILL_ROUTER = {
    // Routing advice is model-visible and therefore remains opt-in.
    enabled: false,
    minCandidates: 8,
    minIntentChars: 12,
    maxSkills: 2,
    maxCandidates: 32,
    minScore: 1.5,
    minConfidence: 0.5,
    nameMatchBoost: 0.6,
    maxAdviceChars: 1_200,
    requestTimeoutMs: 4_000,
};
export const DEFAULT_DECISION_TOOLS = {
    // These tools send model-provided state to Jev and are never model-visible
    // until explicitly enabled by the host.
    enabled: false,
    requestTimeoutMs: 3_500,
    maxStateChars: 12_000,
    maxQuestionChars: 1_500,
    maxQuestions: 16,
    maxCandidates: 50,
    maxCandidateChars: 600,
    maxResultChars: 4_000,
};
export const DEFAULT_DETERMINISTIC_SAFETY_GUARD = {
    // This guard is local, synchronous, and does not send content to Jev. Keep
    // the baseline protection active whenever DSH exposes tools.guard().
    enabled: true,
    maxArgumentChars: 32_000,
    customRules: [],
};
export const ERROR_CODES = [
    'DJE-0001',
    'DJE-0002',
    'DJE-0003',
    'DJE-0004',
    'DJE-0005',
    'DJE-0006',
    'DJE-0007',
    'DJE-0008',
    'DJE-0009',
    'DJE-0010',
    'DJE-0011',
];
export const ERROR_CODE_NAMES = {
    'DJE-0001': 'INVALID_REQUEST',
    'DJE-0002': 'WORKSPACE_REQUIRED',
    'DJE-0003': 'SESSION_REQUIRED',
    'DJE-0004': 'UNSUPPORTED_SCOPE',
    'DJE-0005': 'INTEGRATION_UNAVAILABLE',
    'DJE-0006': 'RUNTIME_INCOMPATIBLE',
    'DJE-0007': 'JEV_UNAVAILABLE',
    'DJE-0008': 'JEV_TIMEOUT',
    'DJE-0009': 'JEV_CANCELLED',
    'DJE-0010': 'CONFIG_PERSISTENCE_FAILED',
    'DJE-0011': 'STATS_PERSISTENCE_FAILED',
};
export const DEFAULT_CONFIG = {
    // Jev is an optional, privacy-sensitive advisory layer. Hosts must explicitly
    // enable it from their settings or a scoped Runtime override.
    enabled: false,
    baseUrl: DEFAULT_JEV_BASE_URL,
    model: DEFAULT_JEV_MODEL,
    timeoutMs: 2_000,
    // Match dsh-jev's semantic safety inspection budget. The Runtime also makes
    // one bounded retry for transient timeout/network/429/5xx failures.
    advisoryTimeoutMs: 3_500,
    askThreshold: 0.5,
    blockThreshold: 0.85,
    guardedTools: [...DEFAULT_GUARDED_TOOLS],
    loopGuard: { ...DEFAULT_LOOP_GUARD, include: [], exclude: [] },
    resultShaper: { ...DEFAULT_RESULT_SHAPER, shapeTools: [...DEFAULT_RESULT_SHAPER.shapeTools], keepKinds: [...DEFAULT_RESULT_SHAPER.keepKinds] },
    tokenOptimization: { ...DEFAULT_TOKEN_OPTIMIZATION },
    doneGate: { ...DEFAULT_DONE_GATE },
    toolPruner: { ...DEFAULT_TOOL_PRUNER, alwaysRetain: [...DEFAULT_TOOL_PRUNER.alwaysRetain] },
    skillRouter: { ...DEFAULT_SKILL_ROUTER },
    decisionTools: { ...DEFAULT_DECISION_TOOLS },
    deterministicSafetyGuard: { ...DEFAULT_DETERMINISTIC_SAFETY_GUARD, customRules: [] },
};
export function isErrorCode(value) {
    return typeof value === 'string' && ERROR_CODES.includes(value);
}
export function makeProtocolError(code, message, details, retryable = false) {
    const error = {
        code,
        name: ERROR_CODE_NAMES[code],
        message,
        retryable,
    };
    if (details !== undefined)
        error.details = details;
    return error;
}
//# sourceMappingURL=index.js.map