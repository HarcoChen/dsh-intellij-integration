import { ERROR_CODE_NAMES, MIN_DSH_RUNTIME_VERSION, PROTOCOL_VERSION, } from '../../protocol/src/index.js';
import { ConfigState, validateConfigPatch } from './config-state.js';
import { JevClient, JevClientError, noulProbability } from './jev-client.js';
import { IntegrationPersistence } from './persistence.js';
import { DoneEvidencePolicy } from './policies/done-gate.js';
import { LoopGuardPolicy } from './policies/loop-guard.js';
import { extractText as extractShaperText, replaceText } from './policies/result-shaper.js';
import { ResultShaperPolicy } from './policies/result-shaper.js';
import { JevSkillRouterPolicy } from './policies/skill-router.js';
import { JevToolPrunerService } from './policies/tool-pruner.js';
import { classifyCredential, evaluateDeterministicSafety } from './policies/deterministic-safety.js';
import { registerDecisionTools } from './decision-tools.js';
import { StatisticsStore } from './stats.js';
import { sanitizeFailureReason, sanitizeForJev } from './sanitize.js';
import { applyNativeResultPruner, estimateContentTokens, estimateDecisionCost, estimatePotentialSavings, executionCallId, failureType, measureSessionTokens, preserveCriticalContent, tokenPair, } from './token-optimization.js';
import { VERSION } from './version.js';
function scopeKey(identity) {
    return JSON.stringify([identity.workspaceId, identity.sessionId ?? null]);
}
function parseUrl(request) {
    return new URL(request.url, 'http://dsh-jev.local');
}
function jsonResponse(value, status = 200, headers) {
    return new Response(JSON.stringify(value), {
        status,
        headers: { 'content-type': 'application/json; charset=utf-8', ...headers },
    });
}
function success(data) {
    return jsonResponse({ ok: true, data });
}
function errorResponse(code, message, status, details, retryable = false) {
    const error = {
        code,
        name: ERROR_CODE_NAMES[code],
        message,
        retryable,
    };
    if (details !== undefined)
        error.details = details;
    return jsonResponse({ ok: false, error }, status);
}
function requiredQuery(url, name) {
    const value = url.searchParams.get(name)?.trim();
    return value ? value : errorResponse('DJE-0002', `${name} is required`, 400);
}
function optionalQuery(url, name) {
    const value = url.searchParams.get(name)?.trim();
    return value || undefined;
}
function isRecord(value) {
    return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}
function serviceFromContext(context, name) {
    try {
        const value = context.get?.(name);
        if (value !== undefined)
            return value;
    }
    catch {
        // Optional Cordis services may throw when the service is not injected.
    }
    try {
        const direct = context[name];
        return direct === undefined ? undefined : direct;
    }
    catch {
        return undefined;
    }
}
function extractPromptAgent(value) {
    if (!isRecord(value))
        return undefined;
    if (isRecord(value.agent))
        return value.agent;
    if (isRecord(value.scope) && isRecord(value.scope.session))
        return value.scope;
    return undefined;
}
function extractAgentIntent(agent, assembly) {
    if (isRecord(agent)) {
        const session = isRecord(agent.session) ? agent.session : undefined;
        const derive = session?.deriveMessages;
        if (typeof derive === 'function') {
            try {
                const messages = derive.call(session);
                if (Array.isArray(messages)) {
                    const userMessages = messages
                        .filter(message => isRecord(message) && isUserSource(message.source))
                        .map(message => extractTextFromMessage(message))
                        .filter(Boolean);
                    const last = userMessages.at(-1);
                    if (last)
                        return last.slice(-4_000);
                }
            }
            catch {
                // Fall back to the assembly when a host-provided session is partial.
            }
        }
    }
    const candidates = [];
    for (const section of assembly.sections ?? []) {
        if (isRecord(section) && typeof section.text === 'string')
            candidates.push(section.text);
    }
    for (const context of assembly.contexts ?? []) {
        if (isRecord(context) && typeof context.text === 'string')
            candidates.push(context.text);
    }
    return candidates.join('\n').slice(-4_000);
}
function toolSchemaNames(tools) {
    return new Set(tools.map(tool => tool.name).filter(Boolean));
}
async function parseBody(request) {
    const text = await request.text();
    if (!text.trim())
        return {};
    try {
        return JSON.parse(text);
    }
    catch {
        throw new Error('request body is not valid JSON');
    }
}
function eventMatches(subscriber, event) {
    return subscriber.workspaceId === event.workspaceId && (subscriber.sessionId === undefined || subscriber.sessionId === event.sessionId);
}
function extractSession(value) {
    if (!isRecord(value))
        return {};
    const header = isRecord(value.header) ? value.header : undefined;
    const id = typeof value.id === 'string' ? value.id : undefined;
    const cwd = header && typeof header.cwd === 'string' ? header.cwd : undefined;
    const result = {};
    if (id !== undefined)
        result.id = id;
    if (cwd !== undefined)
        result.cwd = cwd;
    return result;
}
function extractTurnEvent(value) {
    if (!isRecord(value))
        return {};
    const type = typeof value.type === 'string' ? value.type : undefined;
    return type === undefined ? {} : { type };
}
function extractEventData(value) {
    if (!isRecord(value) || !isRecord(value.data))
        return undefined;
    return value.data;
}
function extractTextFromMessage(value) {
    if (!isRecord(value))
        return '';
    const direct = extractShaperText(value.content);
    if (direct)
        return direct;
    if (!Array.isArray(value.content))
        return '';
    return value.content
        .filter(block => isRecord(block) && block.type === 'tool-result')
        .map(block => extractShaperText(block.content) ?? '')
        .filter(text => text.length > 0)
        .join('\n');
}
function isUserSource(value) {
    return isRecord(value) && value.kind === 'user';
}
function isCompletionAgent(value) {
    return Boolean(value) && typeof value === 'object';
}
function asNext(value) {
    return typeof value === 'function' ? value : async () => ({ kind: 'allow', action: 'allow' });
}
function responseScopeForSource(source) {
    if (source === 'workspace')
        return 'workspace';
    if (source === 'session')
        return 'session';
    if (source === 'next-turn')
        return 'next-turn';
    // `default` and the read-only `startup` composition layer are deployment-
    // wide values, so the response shape uses global as their writable scope.
    return 'global';
}
const ADVISORY_RETRIES = 1;
const ADVISORY_RETRY_DELAY_MS = 150;
function isRetryableAdvisoryError(error) {
    if (!(error instanceof JevClientError))
        return false;
    if (error.kind === 'timeout' || error.kind === 'network')
        return true;
    return error.kind === 'http' && (error.status === 429 || (error.status !== undefined && error.status >= 500));
}
function waitForRetry(delayMs, signal) {
    if (signal?.aborted)
        return Promise.reject(new JevClientError('cancelled', 'Jev call was cancelled before retry'));
    return new Promise((resolve, reject) => {
        let timer;
        const finish = () => {
            if (timer !== undefined)
                clearTimeout(timer);
            signal?.removeEventListener('abort', onAbort);
            resolve();
        };
        const onAbort = () => {
            if (timer !== undefined)
                clearTimeout(timer);
            signal?.removeEventListener('abort', onAbort);
            reject(new JevClientError('cancelled', 'Jev call was cancelled before retry'));
        };
        timer = setTimeout(finish, delayMs);
        signal?.addEventListener('abort', onAbort, { once: true });
    });
}
function createDecisionDeadline(parent, timeoutMs) {
    const controller = new AbortController();
    let timedOut = false;
    let timer;
    const onParentAbort = () => controller.abort();
    if (parent?.aborted)
        controller.abort();
    else
        parent?.addEventListener('abort', onParentAbort, { once: true });
    timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
    }, timeoutMs);
    return {
        signal: controller.signal,
        timedOut: () => timedOut,
        dispose: () => {
            if (timer !== undefined)
                clearTimeout(timer);
            parent?.removeEventListener('abort', onParentAbort);
        },
    };
}
async function callAdvisoryWithRetry(client, request, options, onRetry) {
    let retry = 0;
    for (;;) {
        try {
            return await client.systemOne(request, options);
        }
        catch (error) {
            if (!(error instanceof JevClientError) || !isRetryableAdvisoryError(error) || retry >= ADVISORY_RETRIES || options.signal?.aborted) {
                throw error;
            }
            retry += 1;
            onRetry(retry, error);
            await waitForRetry(ADVISORY_RETRY_DELAY_MS * retry, options.signal);
        }
    }
}
export class JevIntegration {
    name = 'dsh-jev-integration';
    persistence;
    configState;
    stats;
    ready;
    context;
    options;
    availability = new Map();
    activeRequests = new Map();
    lastDecisions = new Map();
    lastErrors = new Map();
    subscribers = new Set();
    loopGuard = new LoopGuardPolicy();
    resultShaper = new ResultShaperPolicy();
    doneGate = new DoneEvidencePolicy();
    skillRouter = new JevSkillRouterPolicy();
    ownToolPruner = new JevToolPrunerService();
    sessionTraces = new Map();
    sessionEventQueues = new Map();
    sessionScopes = new Map();
    sessionObjects = new Map();
    disposedSessions = new Set();
    alreadyPrunedContents = new WeakSet();
    hookDisposers = [];
    decisionToolDisposers = [];
    decisionToolNames = new Set();
    decisionToolsRegistered = false;
    toolPrunerService;
    toolPrunerProvidedDisposer;
    deterministicGuardDisposer;
    extensionsReady = false;
    routeDisposers = [];
    serviceDisposer;
    eventSequence = 0;
    mounted = false;
    endpointAvailable = false;
    endpointReason = 'connection-unavailable';
    connectionRegistrationStarted = false;
    routeReady = Promise.resolve();
    constructor(context, options = {}) {
        this.context = context;
        this.options = options;
        this.persistence = new IntegrationPersistence(options.persistenceRootDir);
        const startupPatch = {};
        if (options.enabled !== undefined)
            startupPatch.enabled = options.enabled;
        if (options.baseUrl !== undefined)
            startupPatch.baseUrl = options.baseUrl;
        if (options.model !== undefined)
            startupPatch.model = options.model;
        if (options.timeoutMs !== undefined)
            startupPatch.timeoutMs = options.timeoutMs;
        if (options.advisoryTimeoutMs !== undefined)
            startupPatch.advisoryTimeoutMs = options.advisoryTimeoutMs;
        if (options.askThreshold !== undefined)
            startupPatch.askThreshold = options.askThreshold;
        if (options.blockThreshold !== undefined)
            startupPatch.blockThreshold = options.blockThreshold;
        if (options.guardedTools !== undefined)
            startupPatch.guardedTools = [...options.guardedTools];
        if (options.loopGuard !== undefined)
            startupPatch.loopGuard = { ...options.loopGuard };
        if (options.resultShaper !== undefined)
            startupPatch.resultShaper = { ...options.resultShaper };
        if (options.tokenOptimization !== undefined)
            startupPatch.tokenOptimization = { ...options.tokenOptimization };
        if (options.doneGate !== undefined)
            startupPatch.doneGate = { ...options.doneGate };
        if (options.toolPruner !== undefined)
            startupPatch.toolPruner = { ...options.toolPruner };
        if (options.skillRouter !== undefined)
            startupPatch.skillRouter = { ...options.skillRouter };
        if (options.decisionTools !== undefined)
            startupPatch.decisionTools = { ...options.decisionTools };
        if (options.deterministicSafetyGuard !== undefined)
            startupPatch.deterministicSafetyGuard = { ...options.deterministicSafetyGuard };
        const validation = validateConfigPatch(startupPatch);
        this.configState = new ConfigState(this.persistence, validation.patch ?? {});
        this.stats = new StatisticsStore(this.persistence);
        this.stats.setOnChanged(snapshot => this.emitStatsChanged(snapshot));
        this.stats.setOnPersistenceError(error => this.context.logger?.warn?.(`dsh-jev-integration: stats persistence failed: ${String(error)}`));
        this.ready = Promise.all([this.configState.ready, this.stats.ready]).then(() => undefined);
    }
    async whenReady() {
        await this.ready;
        await this.routeReady;
    }
    mount() {
        if (this.mounted)
            return this;
        this.mounted = true;
        const provided = this.context.provide?.('jevIntegration', this);
        if (typeof provided === 'function')
            this.serviceDisposer = provided;
        this.registerHooks();
        void this.ready.then(() => this.installRuntimeExtensions()).catch(error => {
            this.context.logger?.warn?.(`dsh-jev-integration: optional Runtime extensions unavailable: ${String(error)}`);
        });
        this.routeReady = this.mountConnectionRoutes();
        this.context.effect?.(() => () => this.dispose());
        return this;
    }
    async dispose() {
        if (!this.mounted)
            return;
        this.mounted = false;
        for (const disposer of this.hookDisposers.splice(0))
            disposer();
        for (const disposer of this.decisionToolDisposers.splice(0))
            disposer();
        this.decisionToolNames.clear();
        this.toolPrunerProvidedDisposer?.();
        this.toolPrunerProvidedDisposer = undefined;
        this.deterministicGuardDisposer?.();
        this.deterministicGuardDisposer = undefined;
        this.toolPrunerService = undefined;
        this.extensionsReady = false;
        this.serviceDisposer?.();
        this.serviceDisposer = undefined;
        for (const disposer of this.routeDisposers.splice(0)) {
            try {
                await disposer();
            }
            catch (error) {
                this.context.logger?.warn?.(`dsh-jev-integration: route cleanup failed: ${String(error)}`);
            }
        }
        // Deliberately do not stop or reset a shared Jev client: another Connection
        // may still be using this Runtime. Only this integration's subscriptions go.
        this.subscribers.clear();
        this.sessionTraces.clear();
        this.sessionEventQueues.clear();
        this.sessionScopes.clear();
        this.sessionObjects.clear();
        this.disposedSessions.clear();
        this.endpointAvailable = false;
        this.endpointReason = 'plugin-unmounted';
        this.connectionRegistrationStarted = false;
    }
    registerHooks() {
        const preDisposer = this.context.on?.('tools/pre-execute', ((exec, next) => this.preExecute(exec, asNext(next))));
        if (typeof preDisposer === 'function')
            this.hookDisposers.push(preDisposer);
        const postDisposer = this.context.on?.('tools/post-execute', ((exec, result, next) => this.postExecute(exec, result, typeof next === 'function' ? next : async () => ({ kind: 'accept', action: 'accept' }))));
        if (typeof postDisposer === 'function')
            this.hookDisposers.push(postDisposer);
        const preStepDisposer = this.context.on?.('agent/pre-step', ((payload, next) => {
            this.resetPolicyForNewUserMessage(payload);
            return typeof next === 'function' ? next() : undefined;
        }));
        if (typeof preStepDisposer === 'function')
            this.hookDisposers.push(preStepDisposer);
        const stoppingDisposer = this.context.on?.('agent/turn-stopping', ((payload) => {
            return this.onTurnStopping(payload);
        }));
        if (typeof stoppingDisposer === 'function')
            this.hookDisposers.push(stoppingDisposer);
        const sessionDisposer = this.context.on?.('session/event', ((session, event) => {
            return this.enqueueSessionEvent(session, event);
        }));
        if (typeof sessionDisposer === 'function')
            this.hookDisposers.push(sessionDisposer);
        const sessionDisposedDisposer = this.context.on?.('session/disposed', ((session) => {
            return this.disposeSessionState(session);
        }));
        if (typeof sessionDisposedDisposer === 'function')
            this.hookDisposers.push(sessionDisposedDisposer);
        const promptDisposer = this.context.on?.('system-prompt/assemble', ((assembly, assembleContext, next) => this.onPromptAssemble(assembly, assembleContext, typeof next === 'function' ? next : undefined)));
        if (typeof promptDisposer === 'function')
            this.hookDisposers.push(promptDisposer);
    }
    getToolsService() {
        return serviceFromContext(this.context, 'tools');
    }
    getSkillsService() {
        return serviceFromContext(this.context, 'skills');
    }
    getTokenMeterService() {
        return serviceFromContext(this.context, 'tokenMeter');
    }
    getToolResultPrunerService() {
        return serviceFromContext(this.context, 'toolResultPruner');
    }
    executionIdentity(exec) {
        const session = exec.agent && isRecord(exec.agent) ? exec.agent.session : undefined;
        const sessionId = extractSession(session).id;
        if (sessionId !== undefined)
            return this.sessionScopes.get(sessionId);
        return undefined;
    }
    installRuntimeExtensions() {
        if (!this.mounted || this.extensionsReady)
            return;
        this.extensionsReady = true;
        const existingPruner = serviceFromContext(this.context, 'toolPruner');
        if (existingPruner?.pruneTools) {
            this.toolPrunerService = existingPruner;
        }
        else if (typeof this.context.provide === 'function') {
            try {
                // A bare DSH Runtime has no upstream `ctx.toolPruner`; expose this
                // Jev-backed implementation through the same service seam. If the
                // host refuses the optional provider, the prompt hook fails open.
                const provided = this.context.provide?.('toolPruner', this.ownToolPruner);
                this.toolPrunerService = this.ownToolPruner;
                if (typeof provided === 'function')
                    this.toolPrunerProvidedDisposer = provided;
            }
            catch (error) {
                this.context.logger?.debug?.(`dsh-jev-integration: ctx.toolPruner is not providable: ${String(error)}`);
            }
        }
        const tools = this.getToolsService();
        if (tools?.guard) {
            try {
                const registered = tools.guard((execution) => {
                    const identity = this.executionIdentity(execution);
                    const resolved = this.configState.resolve(identity?.workspaceId ?? '__deterministic-guard__', identity?.sessionId);
                    const match = evaluateDeterministicSafety(execution, resolved.values.guardedTools, resolved.values.deterministicSafetyGuard);
                    if (identity) {
                        this.stats.recordDeterministicGuard(identity.workspaceId, identity.sessionId, match !== undefined);
                        const raw = execution.arguments !== undefined ? execution.arguments : execution.args;
                        let serialized = '';
                        try {
                            serialized = JSON.stringify(raw) ?? String(raw);
                        }
                        catch {
                            serialized = String(raw);
                        }
                        const credentialClass = classifyCredential(serialized);
                        if (match !== undefined)
                            this.stats.recordDeterministicRuleHit(identity.workspaceId, identity.sessionId, match.ruleId, match.credentialClass ?? credentialClass);
                        else if (credentialClass !== undefined)
                            this.stats.recordDeterministicRuleHit(identity.workspaceId, identity.sessionId, `credential.${credentialClass}`, credentialClass);
                    }
                    return match === undefined ? undefined : `[DSH Jev deterministic safety] ${match.reason}. Review the command and request explicit approval.`;
                });
                if (typeof registered === 'function')
                    this.deterministicGuardDisposer = registered;
            }
            catch (error) {
                this.context.logger?.debug?.(`dsh-jev-integration: ctx.tools.guard is unavailable: ${String(error)}`);
            }
        }
        void this.refreshDecisionToolsRegistration();
    }
    async refreshDecisionToolsRegistration(workspaceId, sessionId) {
        if (!this.mounted || !this.extensionsReady)
            return;
        await this.ready;
        if (!this.mounted || !this.extensionsReady)
            return;
        const resolved = this.configState.resolve(workspaceId ?? '__decision-tools__', sessionId);
        const shouldRegister = resolved.values.enabled && resolved.values.decisionTools.enabled;
        if (!shouldRegister) {
            // Tool definitions live at the Runtime service scope, while enablement
            // may be workspace/session-specific. Keep a shared registration for
            // other clients and hide these names in the narrower prompt assembly;
            // dispose() is the lifecycle boundary that unregisters them.
            return;
        }
        if (this.decisionToolsRegistered)
            return;
        const tools = this.getToolsService();
        if (!tools?.register)
            return;
        const config = resolved.values.decisionTools;
        try {
            this.decisionToolNames.clear();
            this.decisionToolDisposers = registerDecisionTools(tools, {
                config,
                register: definition => tools.register?.(definition),
                onRegistered: name => this.decisionToolNames.add(name),
                resolveConfig: exec => {
                    const session = exec?.agent && isRecord(exec.agent) ? exec.agent.session : undefined;
                    const sessionIdForConfig = extractSession(session).id;
                    const identity = sessionIdForConfig === undefined ? undefined : this.sessionScopes.get(sessionIdForConfig);
                    return this.configState.resolve(identity?.workspaceId ?? workspaceId ?? '__decision-tools__', identity?.sessionId ?? sessionIdForConfig ?? sessionId).values.decisionTools;
                },
                call: async (request, options, exec) => {
                    const session = exec?.agent && isRecord(exec.agent) ? exec.agent.session : undefined;
                    const identity = await this.resolveWorkspace(session);
                    if (!identity)
                        throw new JevClientError('missing-api-key', 'workspace/session scope is unavailable');
                    const current = this.configState.resolve(identity.workspaceId, identity.sessionId);
                    if (!current.values.enabled || !current.values.decisionTools.enabled)
                        throw new JevClientError('missing-api-key', 'decision tools are disabled for this scope');
                    const client = new JevClient(current.values, this.options.apiKey, this.options.fetch);
                    return this.invokePolicy(identity, client, request, options.timeoutMs, options.signal);
                },
                record: (outcome, latencyMs, exec) => {
                    const identity = (exec ? this.executionIdentity(exec) : undefined)
                        ?? (workspaceId ? { workspaceId, ...(sessionId !== undefined ? { sessionId } : {}) } : undefined);
                    if (identity)
                        this.stats.recordDecisionTool(identity.workspaceId, identity.sessionId, outcome, latencyMs);
                },
            });
            this.decisionToolsRegistered = this.decisionToolNames.size > 0;
        }
        catch (error) {
            this.decisionToolDisposers = [];
            this.decisionToolNames.clear();
            this.context.logger?.warn?.(`dsh-jev-integration: decision tool registration failed: ${String(error)}`);
        }
    }
    async onPromptAssemble(assemblyValue, contextValue, next) {
        let assembledValue;
        try {
            assembledValue = next ? await next() : assemblyValue;
        }
        catch (error) {
            this.context.logger?.warn?.(`dsh-jev-integration: prompt assembly downstream failed: ${String(error)}`);
            return assemblyValue;
        }
        await this.ready;
        if (!this.mounted || !isRecord(assembledValue))
            return assembledValue;
        const assembly = assembledValue;
        const assembleContext = isRecord(contextValue) ? contextValue : {};
        const agent = extractPromptAgent(assembleContext);
        const session = agent && isRecord(agent) ? agent.session : undefined;
        const identity = await this.resolveWorkspace(session);
        if (!identity)
            return assembledValue;
        if (identity.sessionId !== undefined)
            this.sessionScopes.set(identity.sessionId, identity);
        const resolved = this.configState.resolve(identity.workspaceId, identity.sessionId);
        if (!resolved.values.enabled && Array.isArray(assembly.tools)) {
            return { ...assembly, tools: assembly.tools.filter(tool => !this.decisionToolNames.has(tool.name)) };
        }
        if (!resolved.values.enabled)
            return assembledValue;
        const intent = extractAgentIntent(agent, assembly);
        const client = new JevClient(resolved.values, this.options.apiKey, this.options.fetch);
        if (!(await client.hasApiKey())) {
            if (resolved.values.toolPruner.enabled)
                this.stats.recordToolPruner(identity.workspaceId, identity.sessionId, 'fail-open', assembly.tools?.length ?? 0, assembly.tools?.length ?? 0);
            if (resolved.values.skillRouter.enabled)
                this.stats.recordSkillRouter(identity.workspaceId, identity.sessionId, 'fail-open');
            if (!resolved.values.decisionTools.enabled && Array.isArray(assembly.tools)) {
                return { ...assembly, tools: assembly.tools.filter(tool => !this.decisionToolNames.has(tool.name)) };
            }
            return assembledValue;
        }
        let output = { ...assembly };
        if (!resolved.values.decisionTools.enabled && Array.isArray(output.tools)) {
            // Decision tools are registered at the Runtime service scope, while a
            // config can be narrower. Hide only our own schemas for disabled scopes;
            // all DSH-provided tools and permission rules remain untouched.
            output.tools = output.tools.filter(tool => !this.decisionToolNames.has(tool.name));
        }
        if (resolved.values.toolPruner.enabled && Array.isArray(output.tools) && this.toolPrunerService?.pruneTools) {
            // Prune the already-filtered model-facing list. This prevents a stale
            // decision-tool schema from being reintroduced when a narrower scope has
            // disabled this integration's registered tools.
            const candidates = output.tools;
            try {
                let pruneResult;
                if (this.toolPrunerService === this.ownToolPruner) {
                    pruneResult = await this.ownToolPruner.prune(intent, candidates, {
                        config: resolved.values.toolPruner,
                        call: (request, options) => this.invokePolicy(identity, client, request, options.timeoutMs, options.signal),
                        ...(assembleContext.signal !== undefined ? { signal: assembleContext.signal } : {}),
                    });
                }
                else {
                    const started = Date.now();
                    const returned = await this.toolPrunerService.pruneTools(intent, candidates, {
                        config: resolved.values.toolPruner,
                        call: (request, options) => this.invokePolicy(identity, client, request, options.timeoutMs, options.signal),
                        ...(assembleContext.signal !== undefined ? { signal: assembleContext.signal } : {}),
                    });
                    const names = toolSchemaNames(candidates);
                    const returnedNames = new Set(returned.map(tool => tool.name).filter(name => names.has(name)));
                    const mandatory = new Set(['run_code', 'skill', 'jev_ask', 'jev_rank', 'jev_check', ...resolved.values.toolPruner.alwaysRetain]);
                    const safeTools = candidates.filter(tool => returnedNames.has(tool.name) || mandatory.has(tool.name));
                    pruneResult = {
                        tools: safeTools.length > 0 ? safeTools : candidates,
                        outcome: 'call',
                        seen: candidates.length,
                        kept: safeTools.length > 0 ? safeTools.length : candidates.length,
                        removedChars: Math.max(0, JSON.stringify(candidates).length - JSON.stringify(safeTools.length > 0 ? safeTools : candidates).length),
                        latencyMs: Date.now() - started,
                    };
                }
                this.stats.recordToolPruner(identity.workspaceId, identity.sessionId, pruneResult.outcome, pruneResult.seen, pruneResult.kept, pruneResult.removedChars, pruneResult.latencyMs);
                output.tools = pruneResult.tools;
            }
            catch {
                this.stats.recordToolPruner(identity.workspaceId, identity.sessionId, 'fail-open', candidates.length, candidates.length);
            }
        }
        else if (resolved.values.toolPruner.enabled) {
            this.stats.recordToolPruner(identity.workspaceId, identity.sessionId, this.toolPrunerService === undefined ? 'fail-open' : 'skipped', assembly.tools?.length ?? 0, assembly.tools?.length ?? 0);
        }
        if (resolved.values.skillRouter.enabled) {
            const skills = this.getSkillsService();
            if (!skills) {
                this.stats.recordSkillRouter(identity.workspaceId, identity.sessionId, 'skipped');
            }
            else {
                const routed = await this.skillRouter.route(intent, {
                    config: resolved.values.skillRouter,
                    service: skills,
                    call: (request, options) => this.invokePolicy(identity, client, request, options.timeoutMs, options.signal),
                    ...(extractSession(session).cwd !== undefined ? { cwd: extractSession(session).cwd } : {}),
                    ...(assembleContext.scope !== undefined || agent !== undefined ? { scope: assembleContext.scope ?? agent } : {}),
                    ...(assembleContext.signal !== undefined ? { signal: assembleContext.signal } : {}),
                });
                this.stats.recordSkillRouter(identity.workspaceId, identity.sessionId, routed.outcome, routed.adviceCount, routed.adviceChars, routed.latencyMs);
                if (routed.advice.length > 0) {
                    const contexts = Array.isArray(output.contexts) ? output.contexts.filter(context => !(isRecord(context) && context.name === 'dsh-jev:skill-router')) : [];
                    contexts.push({ name: 'dsh-jev:skill-router', order: 7_500, text: routed.advice.join('\n\n') });
                    output.contexts = contexts;
                }
            }
        }
        return output;
    }
    getConnection(context = this.context) {
        // Cordis only exposes service properties on a context that declared the
        // service in `inject`. `ctx.get()` is safe for the optional connection;
        // keep the direct-property fallback for the small host/test contexts that
        // implement the service as a plain object.
        let viaGet;
        try {
            viaGet = context.get?.('connection');
        }
        catch {
            viaGet = undefined;
        }
        if (isRecord(viaGet) && isRecord(viaGet.fetch) && typeof viaGet.fetch.register === 'function')
            return viaGet;
        try {
            const direct = context.connection;
            if (direct && isRecord(direct.fetch) && typeof direct.fetch.register === 'function')
                return direct;
        }
        catch {
            // An uninjected Cordis property is intentionally ignored; the inject
            // callback below will retry when Connection becomes available.
        }
        return undefined;
    }
    async mountConnectionRoutes() {
        const immediate = this.getConnection();
        if (immediate) {
            await this.registerConnectionRoutes(immediate);
            return;
        }
        this.endpointAvailable = false;
        this.endpointReason = 'connection-unavailable';
        this.context.inject?.(['connection'], child => {
            const connection = this.getConnection(child);
            if (connection)
                void this.registerConnectionRoutes(connection);
        });
    }
    async registerConnectionRoutes(connection) {
        if (this.endpointAvailable || this.connectionRegistrationStarted)
            return;
        this.connectionRegistrationStarted = true;
        const routes = [
            { path: '/api/dsh-jev/capabilities', methods: ['GET'], requestBody: 'buffered', fetch: request => this.routeCapabilities(request) },
            { path: '/api/dsh-jev/config', methods: ['GET', 'POST'], requestBody: 'buffered', fetch: request => this.routeConfig(request) },
            { path: '/api/dsh-jev/state', methods: ['GET'], requestBody: 'buffered', fetch: request => this.routeState(request) },
            { path: '/api/dsh-jev/stats', methods: ['GET'], requestBody: 'buffered', fetch: request => this.routeStats(request) },
            { path: '/api/dsh-jev/stats/reset', methods: ['POST'], requestBody: 'buffered', fetch: request => this.routeStatsReset(request) },
            { path: '/api/dsh-jev/events', methods: ['GET'], requestBody: 'streaming', fetch: request => this.routeEvents(request) },
        ];
        try {
            for (const route of routes) {
                const disposer = await connection.fetch.register(route);
                this.routeDisposers.push(disposer);
            }
            this.endpointAvailable = true;
            this.endpointReason = 'ready';
            this.context.logger?.info?.('dsh-jev-integration: Connection endpoints mounted');
        }
        catch (error) {
            for (const disposer of this.routeDisposers.splice(0)) {
                try {
                    await disposer();
                }
                catch {
                    // The original registration already failed; keep the retry bounded.
                }
            }
            this.endpointAvailable = false;
            this.endpointReason = 'route-registration-failed';
            this.connectionRegistrationStarted = false;
            this.context.logger?.warn?.(`dsh-jev-integration: Connection endpoint registration failed: ${String(error)}`);
        }
    }
    async resolveWorkspace(session) {
        const identity = extractSession(session);
        if (!identity.id && !identity.cwd)
            return undefined;
        if (!identity.cwd)
            return identity.id ? undefined : undefined;
        let registry;
        try {
            registry = this.context.get?.('workspaceRegistry');
        }
        catch {
            registry = undefined;
        }
        if (registry === undefined) {
            try {
                registry = this.context.workspaceRegistry;
            }
            catch {
                registry = undefined;
            }
        }
        if (!registry?.resolveByPath)
            return undefined;
        try {
            const workspace = await registry.resolveByPath(identity.cwd);
            if (!workspace?.id)
                return undefined;
            const result = { workspaceId: workspace.id };
            if (identity.id !== undefined)
                result.sessionId = identity.id;
            return result;
        }
        catch {
            return undefined;
        }
    }
    availabilityFor(identity) {
        return this.availability.get(scopeKey(identity));
    }
    async getState(identity) {
        await this.ready;
        const resolved = this.configState.resolve(identity.workspaceId, identity.sessionId);
        const key = scopeKey(identity);
        const storedAvailability = this.availabilityFor(identity);
        let available = storedAvailability?.available;
        let reason = storedAvailability?.reason;
        if (available === undefined) {
            const client = new JevClient(resolved.values, this.options.apiKey, this.options.fetch);
            available = await client.hasApiKey();
            if (!available)
                reason = 'missing-api-key';
        }
        const state = {
            workspaceId: identity.workspaceId,
            userRequested: { enabled: resolved.requestedEnabled, source: resolved.requestedEnabledSource },
            effective: {
                enabled: resolved.values.enabled,
                active: resolved.values.enabled && available,
                values: resolved.values,
                source: resolved.source,
            },
            available,
            executing: (this.activeRequests.get(key) ?? 0) > 0,
            activeRequests: this.activeRequests.get(key) ?? 0,
            updatedAt: new Date().toISOString(),
        };
        if (identity.sessionId !== undefined)
            state.sessionId = identity.sessionId;
        if (reason !== undefined)
            state.availabilityReason = reason;
        const lastDecision = this.lastDecisions.get(key);
        if (lastDecision !== undefined)
            state.lastDecision = lastDecision;
        const lastError = this.lastErrors.get(key);
        if (lastError) {
            state.lastErrorCode = lastError.code;
            state.lastErrorMessage = lastError.message;
        }
        return state;
    }
    setAvailability(identity, available, reason, errorCode) {
        const value = { available };
        if (reason !== undefined)
            value.reason = reason;
        if (errorCode !== undefined)
            value.errorCode = errorCode;
        this.availability.set(scopeKey(identity), value);
        if (errorCode !== undefined && reason !== undefined)
            this.lastErrors.set(scopeKey(identity), { code: errorCode, message: reason });
        if (available)
            this.lastErrors.delete(scopeKey(identity));
        void this.publishState(identity);
    }
    setDecision(identity, decision) {
        this.lastDecisions.set(scopeKey(identity), decision);
        void this.publishState(identity);
    }
    incrementActive(identity) {
        const key = scopeKey(identity);
        this.activeRequests.set(key, (this.activeRequests.get(key) ?? 0) + 1);
        void this.publishState(identity);
    }
    decrementActive(identity) {
        const key = scopeKey(identity);
        const count = Math.max(0, (this.activeRequests.get(key) ?? 1) - 1);
        if (count === 0)
            this.activeRequests.delete(key);
        else
            this.activeRequests.set(key, count);
        void this.publishState(identity);
    }
    async invokePolicy(identity, client, request, timeoutMs, signal) {
        const safeRequest = {
            ...(request.model !== undefined ? { model: request.model } : {}),
            state: typeof request.state === 'string' ? sanitizeFailureReason(sanitizeForJev(request.state)) : sanitizeForJev(request.state),
            questions: sanitizeForJev(request.questions),
        };
        const requestBytesEstimate = new TextEncoder().encode(JSON.stringify({
            model: safeRequest.model ?? client.model,
            state: typeof safeRequest.state === 'string' ? safeRequest.state : JSON.stringify(safeRequest.state),
            questions: safeRequest.questions,
        })).byteLength;
        const finishActive = this.stats.begin(identity.workspaceId, identity.sessionId, requestBytesEstimate);
        this.incrementActive(identity);
        const started = Date.now();
        try {
            const callOptions = signal === undefined ? { timeoutMs } : { timeoutMs, signal };
            const result = await callAdvisoryWithRetry(client, safeRequest, callOptions, (attempt, error) => {
                this.context.logger?.debug?.(`dsh-jev-integration: retrying Jev policy (${attempt}/${ADVISORY_RETRIES}) after ${error.kind}`);
            });
            this.stats.recordCall(identity.workspaceId, identity.sessionId, {
                ok: true,
                latencyMs: Date.now() - started,
                requestBytes: result.requestBytes,
                responseBytes: result.responseBytes,
            });
            this.setAvailability(identity, true);
            return result;
        }
        catch (error) {
            const jevError = error instanceof JevClientError ? error : undefined;
            this.stats.recordCall(identity.workspaceId, identity.sessionId, {
                ok: false,
                latencyMs: Date.now() - started,
                requestBytes: requestBytesEstimate,
                responseBytes: 0,
                timedOut: jevError?.kind === 'timeout',
                cancelled: jevError?.kind === 'cancelled',
            });
            const code = jevError?.kind === 'timeout'
                ? 'DJE-0008'
                : jevError?.kind === 'cancelled'
                    ? 'DJE-0009'
                    : 'DJE-0007';
            const message = sanitizeFailureReason(jevError?.kind ?? (error instanceof Error ? error.message : String(error)));
            this.setAvailability(identity, false, message, code);
            throw error;
        }
        finally {
            finishActive();
            this.decrementActive(identity);
        }
    }
    async preExecute(execValue, next) {
        await this.ready;
        const exec = isRecord(execValue) ? execValue : {};
        const toolName = typeof exec.name === 'string' ? exec.name : '';
        const session = exec.agent && isRecord(exec.agent) ? exec.agent.session : undefined;
        const identity = await this.resolveWorkspace(session);
        if (!identity)
            return next();
        if (identity.sessionId !== undefined)
            this.sessionScopes.set(identity.sessionId, identity);
        const resolved = this.configState.resolve(identity.workspaceId, identity.sessionId);
        if (!resolved.values.enabled || !resolved.values.guardedTools.includes(toolName)) {
            this.stats.recordSkipped(identity.workspaceId, identity.sessionId);
            this.setDecision(identity, 'skipped');
            return next();
        }
        const client = new JevClient(resolved.values, this.options.apiKey, this.options.fetch);
        if (!(await client.hasApiKey())) {
            this.stats.recordSkipped(identity.workspaceId, identity.sessionId);
            this.setAvailability(identity, false, 'missing-api-key', 'DJE-0007');
            this.setDecision(identity, 'skipped');
            return next();
        }
        const state = {
            workspaceId: identity.workspaceId,
            sessionId: identity.sessionId,
            tool: toolName,
            arguments: exec.arguments ?? exec.args,
        };
        const questions = {
            is_destructive: { type: 'noul', instructions: 'Does this tool call attempt unrecoverable destruction such as rm -rf, dropping a database, or wiping git history?' },
            is_exfiltration: { type: 'noul', instructions: 'Does this tool call send private credentials, keys, or secrets to a remote endpoint?' },
            is_jailbreak: { type: 'noul', instructions: 'Does this tool call contain an injection payload, jailbreak attempt, or deliberate sandbox escape?' },
            risk_score: {
                type: 'score',
                instructions: 'Rate the overall risk and potential irreversible damage of executing this tool action.',
                criteria: [
                    'Low risk: standard development or read/write operations',
                    'Moderate risk: broad file modification, network egress, or elevated command',
                    'Critical risk: catastrophic deletion, privilege violation, or active credential leak',
                ],
            },
        };
        try {
            const result = await this.invokePolicy(identity, client, { state, questions }, resolved.values.advisoryTimeoutMs, exec.signal);
            const hazards = ['is_destructive', 'is_exfiltration', 'is_jailbreak']
                .map(key => noulProbability(result.answers[key]))
                .filter((value) => typeof value === 'number');
            const riskScore = result.answers.risk_score?.type === 'score' && !result.answers.risk_score.unknown && typeof result.answers.risk_score.score === 'number'
                ? result.answers.risk_score.score
                : undefined;
            const maxHazard = hazards.length > 0 ? Math.max(...hazards) : undefined;
            let decision = 'allow';
            if ((maxHazard !== undefined && maxHazard >= resolved.values.blockThreshold) || (riskScore !== undefined && riskScore >= 1.7))
                decision = 'deny';
            else if ((maxHazard !== undefined && maxHazard >= resolved.values.askThreshold) || (riskScore !== undefined && riskScore >= 0.7))
                decision = 'ask';
            this.stats.recordDecision(identity.workspaceId, identity.sessionId, decision);
            this.setAvailability(identity, true);
            this.setDecision(identity, decision);
            if (decision === 'deny') {
                return { kind: 'deny', action: 'deny', reason: `[DSH Jev] Execution blocked: hazard probability ${((maxHazard ?? 0) * 100).toFixed(0)}%.` };
            }
            if (decision === 'ask') {
                return {
                    kind: 'ask',
                    action: 'ask',
                    prompt: `[DSH Jev] Approval required: estimated hazard ${(Math.max(maxHazard ?? 0, (riskScore ?? 0) / 2) * 100).toFixed(0)}%.`,
                    reason: 'Potentially destructive or sensitive tool action',
                };
            }
            return next();
        }
        catch (error) {
            const jevError = error instanceof JevClientError ? error : undefined;
            const kind = jevError?.kind;
            const code = kind === 'timeout' ? 'DJE-0008' : kind === 'cancelled' ? 'DJE-0009' : 'DJE-0007';
            const message = sanitizeFailureReason(kind ?? (error instanceof Error ? error.message : String(error)));
            // Jev is an optional advisory layer: a timeout, missing key, cancellation,
            // or upstream failure never blocks the normal DSH tool pipeline.
            this.setAvailability(identity, false, message, code);
            this.setDecision(identity, 'allow');
            this.context.logger?.warn?.(`dsh-jev-integration: Jev advisory skipped for ${toolName}: ${message}`);
            return next();
        }
    }
    async postExecute(execValue, resultValue, next) {
        const baseDecision = await next();
        await this.ready;
        const exec = isRecord(execValue) ? execValue : {};
        const result = isRecord(resultValue) ? resultValue : { content: resultValue };
        const session = exec.agent && isRecord(exec.agent) ? exec.agent.session : undefined;
        const identity = await this.resolveWorkspace(session);
        if (!identity)
            return baseDecision;
        const resolved = this.configState.resolve(identity.workspaceId, identity.sessionId);
        if (!resolved.values.enabled)
            return baseDecision;
        const tokenConfig = resolved.values.tokenOptimization;
        const tokenEnabled = tokenConfig.enabled;
        const canRewrite = baseDecision.kind !== 'block' && !Object.hasOwn(baseDecision, 'content') && !Object.hasOwn(baseDecision, 'value');
        const tokenMeter = tokenEnabled ? this.getTokenMeterService() : undefined;
        const resultPruner = tokenEnabled ? this.getToolResultPrunerService() : undefined;
        const originalContent = result.content;
        let workingResult = result;
        const measuredSessionBefore = tokenEnabled ? measureSessionTokens(tokenMeter, session) : null;
        let inputTokensBefore = estimateContentTokens(tokenMeter, originalContent, result.isError === true, executionCallId(exec));
        if (inputTokensBefore === null)
            inputTokensBefore = measuredSessionBefore;
        let deterministicChanged = false;
        const alreadyMarkedPruned = typeof extractShaperText(originalContent) === 'string'
            && /(?:DSH native deterministic prune|lines dropped by DSH Jev result shaper|DSH Jev preserved critical lines)/.test(extractShaperText(originalContent) ?? '');
        if (tokenEnabled && tokenConfig.deterministicFirst && canRewrite && result.isError !== true && !alreadyMarkedPruned && resultPruner?.pruneContent) {
            const contentObject = originalContent && typeof originalContent === 'object' ? originalContent : undefined;
            if (contentObject && this.alreadyPrunedContents.has(contentObject)) {
                // The same result object can pass through more than one post-execute
                // listener. Avoid applying a second replacement to an already handled
                // content array.
            }
            else {
                const native = applyNativeResultPruner(resultPruner, originalContent, false);
                if (contentObject)
                    this.alreadyPrunedContents.add(contentObject);
                if (native.changed && native.content !== originalContent) {
                    deterministicChanged = true;
                    const replacement = { ...result, content: native.content };
                    workingResult = replacement;
                }
                if (native.content && typeof native.content === 'object')
                    this.alreadyPrunedContents.add(native.content);
            }
        }
        const afterDeterministicTokens = tokenEnabled
            ? estimateContentTokens(tokenMeter, workingResult.content, workingResult.isError === true, executionCallId(exec))
            : null;
        const pairAfterDeterministic = tokenPair(inputTokensBefore, afterDeterministicTokens);
        const wantsSemanticShaper = resolved.values.resultShaper.enabled
            && (!tokenEnabled || tokenConfig.semanticFallback);
        const needsJev = resolved.values.loopGuard.enabled || wantsSemanticShaper;
        const client = needsJev ? new JevClient(resolved.values, this.options.apiKey, this.options.fetch) : undefined;
        if (needsJev && client !== undefined && !(await client.hasApiKey())) {
            if (tokenEnabled && wantsSemanticShaper) {
                this.stats.recordTokenOptimization(identity.workspaceId, identity.sessionId, 'fail-open', {
                    inputTokensBefore,
                    inputTokensAfter: afterDeterministicTokens,
                    tokensRemoved: pairAfterDeterministic.removed,
                    netTokensSaved: pairAfterDeterministic.removed,
                    failureReason: 'missing-api-key',
                });
            }
            return deterministicChanged && canRewrite
                ? { ...baseDecision, kind: 'accept', action: 'accept', content: workingResult.content }
                : baseDecision;
        }
        let decision = deterministicChanged && canRewrite
            ? { ...baseDecision, kind: 'accept', action: 'accept', content: workingResult.content }
            : baseDecision;
        if (resolved.values.loopGuard.enabled) {
            const loopOutcome = await this.loopGuard.inspect(exec, workingResult, resolved.values.loopGuard, (request, options) => this.invokePolicy(identity, client, request, options.timeoutMs, options.signal));
            if (loopOutcome.action === 'warning' || loopOutcome.action === 'interrupt') {
                this.stats.recordLoopGuard(identity.workspaceId, identity.sessionId, loopOutcome.action);
                if (loopOutcome.context !== undefined && decision.kind !== 'block') {
                    decision = this.appendAdditionalContext(decision, loopOutcome.context);
                }
            }
            else if (loopOutcome.action === 'uncertain') {
                this.stats.recordLoopGuard(identity.workspaceId, identity.sessionId, 'uncertain');
            }
        }
        let tokenOutcome;
        let decisionLatencyMs = 0;
        let decisionCostTokens = null;
        let tokenFailure = {};
        if (wantsSemanticShaper
            && workingResult.isError !== true
            && canRewrite) {
            const originalText = extractShaperText(workingResult.content);
            const considered = originalText !== undefined && this.resultShaper.shouldConsider(exec, originalText, resolved.values.resultShaper);
            if (!considered) {
                if (tokenEnabled)
                    tokenOutcome = 'skipped-low-roi';
            }
            else {
                const expectedSavings = tokenEnabled ? estimatePotentialSavings(tokenMeter, originalText ?? '') : null;
                const decisionEstimateRequest = {
                    state: { tool: exec.name ?? '', outputSample: (originalText ?? '').slice(0, 1_500) },
                    questions: { kind_0: { type: 'choice', instructions: 'Classify repeated tool output lines.', criteria: { routine_progress: 'routine', warning: 'warning', failure: 'failure', error: 'error', summary: 'summary' } } },
                };
                decisionCostTokens = tokenEnabled ? estimateDecisionCost(tokenMeter, decisionEstimateRequest) : null;
                const belowInput = tokenEnabled && inputTokensBefore !== null && inputTokensBefore < tokenConfig.minInputTokens;
                const deterministicEnough = tokenEnabled && pairAfterDeterministic.removed !== null && pairAfterDeterministic.removed >= tokenConfig.minEstimatedSavingsTokens;
                const expectedBelowThreshold = tokenEnabled && expectedSavings !== null && expectedSavings < tokenConfig.minEstimatedSavingsTokens;
                const belowDecisionCost = tokenEnabled && expectedSavings !== null && decisionCostTokens !== null && expectedSavings <= decisionCostTokens;
                if (belowInput || deterministicEnough || expectedBelowThreshold || belowDecisionCost || !tokenConfig.semanticFallback && tokenEnabled) {
                    if (tokenEnabled)
                        tokenOutcome = 'skipped-low-roi';
                }
                else {
                    const semanticStarted = Date.now();
                    let semanticFailure;
                    const deadline = tokenEnabled ? createDecisionDeadline(exec.signal, tokenConfig.maxDecisionLatencyMs) : undefined;
                    const shaped = await (async () => {
                        try {
                            return await this.resultShaper.shape(originalText ?? '', exec.name ?? '', exec, tokenEnabled
                                ? { ...resolved.values.resultShaper, keepKinds: [...new Set([...resolved.values.resultShaper.keepKinds, 'warning', 'failure', 'error', 'summary'])] }
                                : resolved.values.resultShaper, (request, options) => {
                                const signal = deadline?.signal ?? options.signal;
                                const timeoutMs = deadline === undefined
                                    ? options.timeoutMs
                                    : Math.min(options.timeoutMs, tokenConfig.maxDecisionLatencyMs);
                                return this.invokePolicy(identity, client, request, timeoutMs, signal).then(response => {
                                    if (deadline?.timedOut())
                                        throw new JevClientError('timeout', 'token optimization decision latency exceeded');
                                    return response;
                                }).catch(error => {
                                    if (deadline?.timedOut() && !exec.signal?.aborted) {
                                        const timeout = new JevClientError('timeout', 'token optimization decision latency exceeded');
                                        semanticFailure = timeout;
                                        throw timeout;
                                    }
                                    semanticFailure = error;
                                    throw error;
                                });
                            });
                        }
                        finally {
                            deadline?.dispose();
                        }
                    })();
                    decisionLatencyMs = Date.now() - semanticStarted;
                    tokenOutcome = shaped === undefined && semanticFailure !== undefined ? 'failure' : 'decision';
                    if (semanticFailure !== undefined) {
                        tokenFailure = {
                            type: failureType(semanticFailure),
                            reason: sanitizeFailureReason(semanticFailure instanceof Error ? semanticFailure.message : 'semantic result shaper failed'),
                        };
                        tokenOutcome = 'failure';
                    }
                    if (shaped !== undefined) {
                        const shapedOutput = replaceText(workingResult.content, shaped.text);
                        const shapedContent = Array.isArray(shapedOutput)
                            ? preserveCriticalContent(workingResult.content, shapedOutput)
                            : shapedOutput;
                        this.stats.recordResultShape(identity.workspaceId, identity.sessionId, (originalText?.length ?? 0) - shaped.text.length);
                        workingResult = { ...workingResult, content: shapedContent };
                        decision = {
                            ...decision,
                            kind: 'accept',
                            action: 'accept',
                            content: shapedContent,
                        };
                    }
                }
            }
        }
        else if (tokenEnabled) {
            tokenOutcome = 'skipped-low-roi';
        }
        if (tokenEnabled) {
            const inputTokensAfter = estimateContentTokens(tokenMeter, workingResult.content, workingResult.isError === true, executionCallId(exec))
                ?? (workingResult === result ? afterDeterministicTokens : null);
            const pair = tokenPair(inputTokensBefore, inputTokensAfter);
            const netTokensSaved = pair.removed === null
                ? null
                : tokenOutcome === 'decision' && decisionCostTokens === null
                    ? null
                    : Math.max(0, pair.removed - (tokenOutcome === 'decision' ? decisionCostTokens ?? 0 : 0));
            this.stats.recordTokenOptimization(identity.workspaceId, identity.sessionId, tokenOutcome ?? 'skipped-low-roi', {
                inputTokensBefore,
                inputTokensAfter,
                tokensRemoved: pair.removed,
                netTokensSaved,
                decisionLatencyMs,
                ...(tokenFailure.type !== undefined ? { failureType: tokenFailure.type } : {}),
                ...(tokenFailure.reason !== undefined ? { failureReason: tokenFailure.reason } : {}),
            });
        }
        return decision;
    }
    appendAdditionalContext(decision, context) {
        const existing = Array.isArray(decision.additionalContexts) ? decision.additionalContexts : [];
        return { ...decision, additionalContexts: [...existing, context] };
    }
    resetPolicyForNewUserMessage(payloadValue) {
        if (!isRecord(payloadValue))
            return;
        const messages = Array.isArray(payloadValue.messages) ? payloadValue.messages : [];
        if (!messages.some(message => isRecord(message) && isUserSource(message.source)))
            return;
        const agent = payloadValue.agent;
        this.loopGuard.reset(agent);
        this.resultShaper.reset(agent);
        this.doneGate.reset(agent);
    }
    sessionQueueKey(sessionValue) {
        const session = extractSession(sessionValue);
        return session.id;
    }
    clearSessionTrace(sessionId) {
        for (const traceKey of this.sessionTraces.keys()) {
            try {
                const parsed = JSON.parse(traceKey);
                if (Array.isArray(parsed) && parsed[1] === sessionId)
                    this.sessionTraces.delete(traceKey);
            }
            catch {
                // The key is internal JSON; an invalid key cannot match a live trace.
            }
        }
    }
    async disposeSessionState(sessionValue) {
        const sessionId = this.sessionQueueKey(sessionValue);
        if (sessionId === undefined)
            return;
        const knownSession = this.sessionObjects.get(sessionId);
        if (knownSession !== undefined && knownSession !== sessionValue)
            return;
        this.sessionObjects.set(sessionId, sessionValue);
        // Mark first: an event already waiting on workspace resolution must not
        // recreate a trace after disposal has started.
        this.disposedSessions.add(sessionId);
        const pending = this.sessionEventQueues.get(sessionId);
        await pending?.catch(() => undefined);
        // A new Session object may have been opened with the same id while the
        // old queue was draining. The old disposal must not clear the new state.
        if (this.sessionObjects.get(sessionId) !== sessionValue)
            return;
        if (this.sessionEventQueues.get(sessionId) === pending)
            this.sessionEventQueues.delete(sessionId);
        this.clearSessionTrace(sessionId);
        await this.ready;
        const identity = this.sessionScopes.get(sessionId) ?? await this.resolveWorkspace(sessionValue);
        if (identity?.sessionId !== undefined) {
            this.configState.clearSession(identity.workspaceId, identity.sessionId);
            this.stats.clearSession(identity.workspaceId, identity.sessionId);
        }
        this.sessionScopes.delete(sessionId);
    }
    enqueueSessionEvent(sessionValue, eventValue) {
        const key = this.sessionQueueKey(sessionValue);
        if (key !== undefined) {
            const knownSession = this.sessionObjects.get(key);
            if (knownSession !== undefined && knownSession !== sessionValue)
                this.disposedSessions.delete(key);
            this.sessionObjects.set(key, sessionValue);
            if (this.disposedSessions.has(key))
                return Promise.resolve();
        }
        if (key === undefined)
            return this.onSessionEvent(sessionValue, eventValue);
        const previous = this.sessionEventQueues.get(key) ?? Promise.resolve();
        const current = previous
            .catch(() => undefined)
            .then(() => this.onSessionEvent(sessionValue, eventValue))
            .catch(error => {
            this.context.logger?.warn?.(`dsh-jev-integration: session event processing failed: ${String(error)}`);
        });
        this.sessionEventQueues.set(key, current);
        void current.finally(() => {
            if (this.sessionEventQueues.get(key) === current)
                this.sessionEventQueues.delete(key);
        }).catch(() => undefined);
        return current;
    }
    async waitForSessionEvents(sessionValue) {
        const key = this.sessionQueueKey(sessionValue);
        if (key === undefined)
            return;
        await this.sessionEventQueues.get(key);
    }
    async onSessionEvent(sessionValue, eventValue) {
        if (!this.mounted)
            return;
        const sessionId = this.sessionQueueKey(sessionValue);
        if (sessionId !== undefined && this.disposedSessions.has(sessionId))
            return;
        const event = extractTurnEvent(eventValue);
        const identity = await this.resolveWorkspace(sessionValue);
        if (!identity?.sessionId)
            return;
        if (!this.mounted || this.disposedSessions.has(identity.sessionId) || this.sessionObjects.get(identity.sessionId) !== sessionValue)
            return;
        this.sessionScopes.set(identity.sessionId, identity);
        const key = scopeKey(identity);
        const data = extractEventData(eventValue);
        if (event.type === 'turn/start') {
            const turn = data && typeof data.turn === 'number' ? data.turn : 0;
            this.sessionTraces.set(key, { turn, claim: '', evidence: [] });
            if (this.configState.consumeNextTurn(identity.workspaceId, identity.sessionId)) {
                void this.publishState(identity);
            }
            return;
        }
        const trace = this.sessionTraces.get(key);
        if (!trace || !data)
            return;
        if (event.type === 'user/message') {
            const source = isRecord(data.source) ? data.source : undefined;
            if (isUserSource(source)) {
                const text = extractTextFromMessage(data);
                if (text)
                    trace.userPrompt = text;
            }
        }
        else if (event.type === 'assistant/message') {
            const message = isRecord(data.message) ? data.message : data;
            const text = extractTextFromMessage(message);
            if (text)
                trace.claim = text;
        }
        else if (event.type === 'tool/result') {
            const message = isRecord(data.message) ? data.message : data;
            const content = extractTextFromMessage(message);
            const blocks = isRecord(message) && Array.isArray(message.content) ? message.content : [];
            const isError = blocks.some(block => isRecord(block) && block.type === 'tool-result' && block.isError === true);
            if (content)
                trace.evidence.push({ text: content, isError });
        }
    }
    async onTurnStopping(payloadValue) {
        if (!this.mounted)
            return;
        await this.ready;
        if (!isRecord(payloadValue))
            return;
        const payload = payloadValue;
        const agent = payload.agent;
        if (!isCompletionAgent(agent))
            return;
        const session = isRecord(agent) ? agent.session : undefined;
        const sessionId = this.sessionQueueKey(session);
        const knownSession = sessionId === undefined ? undefined : this.sessionObjects.get(sessionId);
        if (sessionId !== undefined && knownSession !== undefined && knownSession !== session)
            return;
        if (sessionId !== undefined && this.disposedSessions.has(sessionId))
            return;
        await this.waitForSessionEvents(session);
        if (sessionId !== undefined && this.disposedSessions.has(sessionId))
            return;
        const identity = await this.resolveWorkspace(session);
        if (!identity?.sessionId)
            return;
        const resolved = this.configState.resolve(identity.workspaceId, identity.sessionId);
        if (!resolved.values.enabled || !resolved.values.doneGate.enabled)
            return;
        const trace = this.sessionTraces.get(scopeKey(identity));
        if (!trace)
            return;
        const client = new JevClient(resolved.values, this.options.apiKey, this.options.fetch);
        if (!(await client.hasApiKey()))
            return;
        const outcome = await this.doneGate.inspect(agent, trace, resolved.values.doneGate, (request, options) => this.invokePolicy(identity, client, request, options.timeoutMs, options.signal), payload.signal);
        if (outcome.action === 'intervene' && outcome.context !== undefined) {
            this.stats.recordDoneGate(identity.workspaceId, identity.sessionId, 'intervention');
            const steer = agent.steer;
            if (typeof steer === 'function') {
                try {
                    steer(outcome.context);
                }
                catch (error) {
                    this.context.logger?.warn?.(`dsh-jev-integration: done/evidence steering failed: ${String(error)}`);
                }
            }
        }
        else if (outcome.action === 'uncertain') {
            this.stats.recordDoneGate(identity.workspaceId, identity.sessionId, 'uncertain');
        }
        else if (outcome.action === 'pass') {
            this.stats.recordDoneGate(identity.workspaceId, identity.sessionId, 'pass');
        }
    }
    emit(type, identity, state, stats) {
        const event = {
            id: `${Date.now()}-${++this.eventSequence}`,
            type,
            at: new Date().toISOString(),
            workspaceId: identity.workspaceId,
        };
        if (identity.sessionId !== undefined)
            event.sessionId = identity.sessionId;
        if (state !== undefined)
            event.state = state;
        if (stats !== undefined)
            event.stats = stats;
        for (const subscriber of [...this.subscribers]) {
            if (!eventMatches(subscriber, event))
                continue;
            try {
                subscriber.callback(event);
            }
            catch {
                this.subscribers.delete(subscriber);
            }
        }
    }
    async publishState(identity) {
        if (!this.mounted)
            return;
        try {
            this.emit('state.changed', identity, await this.getState(identity));
        }
        catch (error) {
            this.context.logger?.warn?.(`dsh-jev-integration: state publication failed: ${String(error)}`);
        }
    }
    emitStatsChanged(snapshot) {
        const identity = { workspaceId: snapshot.workspaceId };
        if (snapshot.sessionId !== undefined)
            identity.sessionId = snapshot.sessionId;
        this.emit('stats.changed', identity, undefined, snapshot);
    }
    subscribe(identity, callback) {
        const subscriber = { workspaceId: identity.workspaceId, callback };
        if (identity.sessionId !== undefined)
            subscriber.sessionId = identity.sessionId;
        this.subscribers.add(subscriber);
        return () => this.subscribers.delete(subscriber);
    }
    async capabilities(workspaceId, sessionId) {
        await this.ready;
        if (this.mounted && !this.extensionsReady)
            this.installRuntimeExtensions();
        const resolved = this.configState.resolve(workspaceId ?? '__capabilities__', sessionId);
        const client = new JevClient(resolved.values, this.options.apiKey, this.options.fetch);
        const stored = workspaceId === undefined ? undefined : this.availability.get(scopeKey({ workspaceId, ...(sessionId !== undefined ? { sessionId } : {}) }));
        const available = stored?.available ?? await client.hasApiKey();
        const result = {
            integrationVersion: VERSION.integration,
            integrationCommit: this.options.integrationCommit ?? VERSION.commit,
            protocolVersion: PROTOCOL_VERSION,
            runtimeCompatibility: {
                minimumDshRuntime: MIN_DSH_RUNTIME_VERSION,
                testedDshRuntime: this.options.dshRuntimeVersion ?? MIN_DSH_RUNTIME_VERSION,
                requiredFeatures: [
                    'connection.fetch',
                    'tools/pre-execute',
                    'tools/post-execute',
                    'agent/pre-step',
                    'agent/turn-stopping',
                    'session/event',
                    'system-prompt/assemble',
                    'tools.register',
                    'tools.guard',
                    'tokenMeter (optional)',
                    'toolResultPruner (optional)',
                ],
            },
            pluginMounted: this.mounted,
            endpointAvailable: this.endpointAvailable,
            capabilities: {
                configRead: true,
                configWrite: true,
                stateRead: true,
                statsRead: true,
                statsReset: true,
                stateEvents: true,
                preExecuteHook: this.mounted,
                postExecuteHook: this.mounted,
                loopGuard: this.mounted,
                resultShaper: this.mounted,
                doneEvidenceGate: this.mounted,
                turnStoppingHook: this.mounted,
                nextTurnOverride: this.mounted,
                toolPruner: this.mounted && this.toolPrunerService !== undefined,
                skillRouter: this.mounted && Boolean(this.getSkillsService()),
                decisionTools: this.mounted && Boolean(this.getToolsService()?.register),
                deterministicSafetyGuard: this.mounted && Boolean(this.getToolsService()?.guard),
                tokenMeter: this.mounted && Boolean(this.getTokenMeterService()?.estimateMessage || this.getTokenMeterService()?.measure),
                toolResultPruner: this.mounted && Boolean(this.getToolResultPrunerService()?.pruneContent),
                tokenOptimization: this.mounted,
            },
            jev: { baseUrl: resolved.values.baseUrl, model: resolved.values.model, available },
        };
        if (!this.endpointAvailable)
            result.endpointReason = this.endpointReason;
        if (stored?.reason !== undefined)
            result.jev.reason = stored.reason;
        else if (!available)
            result.jev.reason = 'missing-api-key';
        return result;
    }
    async routeCapabilities(request) {
        try {
            const url = parseUrl(request);
            return success(await this.capabilities(optionalQuery(url, 'workspaceId'), optionalQuery(url, 'sessionId')));
        }
        catch (error) {
            return errorResponse('DJE-0005', String(error), 503, undefined, true);
        }
    }
    async routeConfig(request) {
        try {
            const url = parseUrl(request);
            if (request.method === 'GET') {
                const workspaceValue = requiredQuery(url, 'workspaceId');
                if (workspaceValue instanceof Response)
                    return workspaceValue;
                const sessionId = optionalQuery(url, 'sessionId');
                const resolved = this.configState.resolve(workspaceValue, sessionId);
                const source = resolved.source;
                const response = {
                    workspaceId: workspaceValue,
                    persisted: source === 'global' || source === 'workspace',
                    scope: responseScopeForSource(source),
                    source,
                    values: resolved.values,
                };
                if (sessionId !== undefined)
                    response.sessionId = sessionId;
                return success(response);
            }
            const body = await parseBody(request);
            if (!isRecord(body))
                return errorResponse('DJE-0001', 'request body must be an object', 400);
            const workspaceId = typeof body.workspaceId === 'string' ? body.workspaceId.trim() : '';
            if (!workspaceId)
                return errorResponse('DJE-0002', 'workspaceId is required', 400);
            const sessionId = typeof body.sessionId === 'string' ? body.sessionId.trim() : undefined;
            const scope = body.scope;
            if (scope !== 'global' && scope !== 'workspace' && scope !== 'session' && scope !== 'next-turn') {
                return errorResponse('DJE-0004', 'scope must be global, workspace, session, or next-turn', 400);
            }
            if ((scope === 'session' || scope === 'next-turn') && !sessionId)
                return errorResponse('DJE-0003', 'sessionId is required for session and next-turn scopes', 400);
            const validated = validateConfigPatch(body.patch);
            if (!validated.patch)
                return errorResponse('DJE-0001', validated.error ?? 'invalid config patch', 400);
            if (scope === 'global' || scope === 'workspace') {
                await this.configState.setPersisted(scope, workspaceId, validated.patch);
            }
            else {
                this.configState.setEphemeral(scope, workspaceId, sessionId, validated.patch);
            }
            const resolved = this.configState.resolve(workspaceId, sessionId);
            const response = {
                workspaceId,
                persisted: scope === 'global' || scope === 'workspace',
                scope,
                source: resolved.source,
                values: resolved.values,
            };
            if (sessionId !== undefined)
                response.sessionId = sessionId;
            const identity = { workspaceId };
            if (sessionId !== undefined)
                identity.sessionId = sessionId;
            this.emit('config.changed', identity, await this.getState(identity));
            void this.refreshDecisionToolsRegistration(workspaceId, sessionId);
            return success(response);
        }
        catch (error) {
            this.context.logger?.warn?.(`dsh-jev-integration: config request failed: ${String(error)}`);
            if (error instanceof Error && error.message.includes('request body is not valid JSON')) {
                return errorResponse('DJE-0001', error.message, 400);
            }
            return errorResponse('DJE-0010', 'configuration could not be persisted', 500, undefined, true);
        }
    }
    async routeState(request) {
        const url = parseUrl(request);
        const workspaceValue = requiredQuery(url, 'workspaceId');
        if (workspaceValue instanceof Response)
            return workspaceValue;
        try {
            const identity = { workspaceId: workspaceValue };
            const sessionId = optionalQuery(url, 'sessionId');
            if (sessionId !== undefined)
                identity.sessionId = sessionId;
            return success(await this.getState(identity));
        }
        catch (error) {
            return errorResponse('DJE-0005', String(error), 503, undefined, true);
        }
    }
    async routeStats(request) {
        const url = parseUrl(request);
        const workspaceValue = requiredQuery(url, 'workspaceId');
        if (workspaceValue instanceof Response)
            return workspaceValue;
        const sessionId = optionalQuery(url, 'sessionId');
        await this.stats.ready;
        const snapshot = this.stats.get(workspaceValue, sessionId);
        return success(snapshot);
    }
    async routeStatsReset(request) {
        try {
            const url = parseUrl(request);
            const body = await parseBody(request);
            const workspaceId = isRecord(body) && typeof body.workspaceId === 'string' ? body.workspaceId.trim() : optionalQuery(url, 'workspaceId');
            if (!workspaceId)
                return errorResponse('DJE-0002', 'workspaceId is required', 400);
            const sessionId = isRecord(body) && typeof body.sessionId === 'string' ? body.sessionId.trim() : optionalQuery(url, 'sessionId');
            return success(await this.stats.reset(workspaceId, sessionId));
        }
        catch (error) {
            return errorResponse('DJE-0011', 'statistics could not be reset or persisted', 500, { reason: String(error) }, true);
        }
    }
    async routeEvents(request) {
        const url = parseUrl(request);
        const workspaceValue = requiredQuery(url, 'workspaceId');
        if (workspaceValue instanceof Response)
            return workspaceValue;
        const sessionId = optionalQuery(url, 'sessionId');
        const identity = { workspaceId: workspaceValue };
        if (sessionId !== undefined)
            identity.sessionId = sessionId;
        const encoder = new TextEncoder();
        let closed = false;
        let controllerRef;
        let unsubscribe = () => undefined;
        const close = () => {
            if (closed)
                return;
            closed = true;
            unsubscribe();
            try {
                controllerRef?.close();
            }
            catch {
                // A client can race cancellation with close; it is already detached.
            }
        };
        const stream = new ReadableStream({
            start: controller => {
                controllerRef = controller;
                unsubscribe = this.subscribe(identity, event => {
                    if (!closed)
                        controller.enqueue(encoder.encode(`id: ${event.id}\ndata: ${JSON.stringify(event)}\n\n`));
                });
                request.signal.addEventListener('abort', close, { once: true });
                void this.getState(identity).then(state => {
                    if (!closed) {
                        const initial = {
                            id: `${Date.now()}-${++this.eventSequence}`,
                            type: 'state.changed',
                            at: new Date().toISOString(),
                            workspaceId: identity.workspaceId,
                            state,
                        };
                        if (identity.sessionId !== undefined)
                            initial.sessionId = identity.sessionId;
                        controller.enqueue(encoder.encode(`id: ${initial.id}\ndata: ${JSON.stringify(initial)}\n\n`));
                    }
                }).catch(error => controller.error(error));
            },
            cancel: close,
        });
        return new Response(stream, {
            status: 200,
            headers: {
                'content-type': 'text/event-stream; charset=utf-8',
                'cache-control': 'no-cache',
                connection: 'keep-alive',
            },
        });
    }
}
export function apply(context, options = {}) {
    const existing = context.get?.('jevIntegration');
    if (existing instanceof JevIntegration)
        return () => undefined;
    const integration = new JevIntegration(context, options).mount();
    return () => {
        void integration.dispose();
    };
}
export function createIntegration(context, options = {}) {
    return new JevIntegration(context, options);
}
//# sourceMappingURL=integration.js.map