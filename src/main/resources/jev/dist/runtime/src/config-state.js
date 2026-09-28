import { DEFAULT_CONFIG, } from '../../protocol/src/index.js';
function scopeKey(workspaceId, sessionId) {
    return `${workspaceId}\u0000${sessionId}`;
}
function hasOwn(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
}
function mergePatch(base, patch) {
    const next = { ...base, ...patch };
    if (base.loopGuard !== undefined || patch.loopGuard !== undefined) {
        next.loopGuard = {
            ...(base.loopGuard ?? {}),
            ...(patch.loopGuard ?? {}),
            ...(patch.loopGuard?.include !== undefined ? { include: [...patch.loopGuard.include] } : {}),
            ...(patch.loopGuard?.exclude !== undefined ? { exclude: [...patch.loopGuard.exclude] } : {}),
        };
    }
    if (base.resultShaper !== undefined || patch.resultShaper !== undefined) {
        next.resultShaper = {
            ...(base.resultShaper ?? {}),
            ...(patch.resultShaper ?? {}),
            ...(patch.resultShaper?.shapeTools !== undefined ? { shapeTools: [...patch.resultShaper.shapeTools] } : {}),
            ...(patch.resultShaper?.keepKinds !== undefined ? { keepKinds: [...patch.resultShaper.keepKinds] } : {}),
        };
    }
    if (base.doneGate !== undefined || patch.doneGate !== undefined) {
        next.doneGate = { ...(base.doneGate ?? {}), ...(patch.doneGate ?? {}) };
    }
    if (base.toolPruner !== undefined || patch.toolPruner !== undefined) {
        next.toolPruner = {
            ...(base.toolPruner ?? {}),
            ...(patch.toolPruner ?? {}),
            ...(patch.toolPruner?.alwaysRetain !== undefined ? { alwaysRetain: [...patch.toolPruner.alwaysRetain] } : {}),
        };
    }
    if (base.skillRouter !== undefined || patch.skillRouter !== undefined) {
        next.skillRouter = { ...(base.skillRouter ?? {}), ...(patch.skillRouter ?? {}) };
    }
    if (base.decisionTools !== undefined || patch.decisionTools !== undefined) {
        next.decisionTools = { ...(base.decisionTools ?? {}), ...(patch.decisionTools ?? {}) };
    }
    if (base.deterministicSafetyGuard !== undefined || patch.deterministicSafetyGuard !== undefined) {
        next.deterministicSafetyGuard = { ...(base.deterministicSafetyGuard ?? {}), ...(patch.deterministicSafetyGuard ?? {}) };
    }
    if (patch.guardedTools !== undefined)
        next.guardedTools = [...patch.guardedTools];
    return next;
}
function mergeConfig(base, patch) {
    const next = {
        ...base,
        guardedTools: [...base.guardedTools],
        loopGuard: { ...base.loopGuard, include: [...base.loopGuard.include], exclude: [...base.loopGuard.exclude] },
        resultShaper: { ...base.resultShaper, shapeTools: [...base.resultShaper.shapeTools], keepKinds: [...base.resultShaper.keepKinds] },
        doneGate: { ...base.doneGate },
        toolPruner: { ...base.toolPruner, alwaysRetain: [...base.toolPruner.alwaysRetain] },
        skillRouter: { ...base.skillRouter },
        decisionTools: { ...base.decisionTools },
        deterministicSafetyGuard: { ...base.deterministicSafetyGuard },
    };
    if (patch.enabled !== undefined)
        next.enabled = patch.enabled;
    if (patch.baseUrl !== undefined)
        next.baseUrl = patch.baseUrl;
    if (patch.model !== undefined)
        next.model = patch.model;
    if (patch.timeoutMs !== undefined)
        next.timeoutMs = patch.timeoutMs;
    if (patch.advisoryTimeoutMs !== undefined)
        next.advisoryTimeoutMs = patch.advisoryTimeoutMs;
    if (patch.askThreshold !== undefined)
        next.askThreshold = patch.askThreshold;
    if (patch.blockThreshold !== undefined)
        next.blockThreshold = patch.blockThreshold;
    if (patch.guardedTools !== undefined)
        next.guardedTools = [...patch.guardedTools];
    if (patch.loopGuard !== undefined) {
        next.loopGuard = {
            ...next.loopGuard,
            ...patch.loopGuard,
            ...(patch.loopGuard.include !== undefined ? { include: [...patch.loopGuard.include] } : {}),
            ...(patch.loopGuard.exclude !== undefined ? { exclude: [...patch.loopGuard.exclude] } : {}),
        };
    }
    if (patch.resultShaper !== undefined) {
        next.resultShaper = {
            ...next.resultShaper,
            ...patch.resultShaper,
            ...(patch.resultShaper.shapeTools !== undefined ? { shapeTools: [...patch.resultShaper.shapeTools] } : {}),
            ...(patch.resultShaper.keepKinds !== undefined ? { keepKinds: [...patch.resultShaper.keepKinds] } : {}),
        };
    }
    if (patch.doneGate !== undefined)
        next.doneGate = { ...next.doneGate, ...patch.doneGate };
    if (patch.toolPruner !== undefined) {
        next.toolPruner = {
            ...next.toolPruner,
            ...patch.toolPruner,
            ...(patch.toolPruner.alwaysRetain !== undefined ? { alwaysRetain: [...patch.toolPruner.alwaysRetain] } : {}),
        };
    }
    if (patch.skillRouter !== undefined)
        next.skillRouter = { ...next.skillRouter, ...patch.skillRouter };
    if (patch.decisionTools !== undefined)
        next.decisionTools = { ...next.decisionTools, ...patch.decisionTools };
    if (patch.deterministicSafetyGuard !== undefined)
        next.deterministicSafetyGuard = { ...next.deterministicSafetyGuard, ...patch.deterministicSafetyGuard };
    return next;
}
function patchWithoutUnknown(value) {
    const allowed = {};
    if (value.enabled !== undefined)
        allowed.enabled = value.enabled;
    if (value.baseUrl !== undefined)
        allowed.baseUrl = value.baseUrl;
    if (value.model !== undefined)
        allowed.model = value.model;
    if (value.timeoutMs !== undefined)
        allowed.timeoutMs = value.timeoutMs;
    if (value.advisoryTimeoutMs !== undefined)
        allowed.advisoryTimeoutMs = value.advisoryTimeoutMs;
    if (value.askThreshold !== undefined)
        allowed.askThreshold = value.askThreshold;
    if (value.blockThreshold !== undefined)
        allowed.blockThreshold = value.blockThreshold;
    if (value.guardedTools !== undefined)
        allowed.guardedTools = [...value.guardedTools];
    if (value.loopGuard !== undefined) {
        allowed.loopGuard = {
            ...value.loopGuard,
            ...(value.loopGuard.include !== undefined ? { include: [...value.loopGuard.include] } : {}),
            ...(value.loopGuard.exclude !== undefined ? { exclude: [...value.loopGuard.exclude] } : {}),
        };
    }
    if (value.resultShaper !== undefined) {
        allowed.resultShaper = {
            ...value.resultShaper,
            ...(value.resultShaper.shapeTools !== undefined ? { shapeTools: [...value.resultShaper.shapeTools] } : {}),
            ...(value.resultShaper.keepKinds !== undefined ? { keepKinds: [...value.resultShaper.keepKinds] } : {}),
        };
    }
    if (value.doneGate !== undefined)
        allowed.doneGate = { ...value.doneGate };
    if (value.toolPruner !== undefined) {
        allowed.toolPruner = {
            ...value.toolPruner,
            ...(value.toolPruner.alwaysRetain !== undefined ? { alwaysRetain: [...value.toolPruner.alwaysRetain] } : {}),
        };
    }
    if (value.skillRouter !== undefined)
        allowed.skillRouter = { ...value.skillRouter };
    if (value.decisionTools !== undefined)
        allowed.decisionTools = { ...value.decisionTools };
    if (value.deterministicSafetyGuard !== undefined)
        allowed.deterministicSafetyGuard = { ...value.deterministicSafetyGuard };
    return allowed;
}
function isRecord(value) {
    return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}
function validateNumber(value, name, min, max, integer = false) {
    if (typeof value !== 'number' || !Number.isFinite(value) || (integer && !Number.isInteger(value)) || value < min || value > max) {
        return `${name} must be a ${integer ? 'integer' : 'number'} from ${min} to ${max}`;
    }
    return undefined;
}
function validateStringArray(value, name, allowEmpty = true) {
    if (!Array.isArray(value) || (!allowEmpty && value.length === 0) || value.some(item => typeof item !== 'string' || item.length === 0)) {
        return `${name} must be a ${allowEmpty ? '' : 'non-empty-'}string array`;
    }
    if (new Set(value).size !== value.length)
        return `${name} must be unique`;
    return undefined;
}
function validateNestedPatch(value, name, allowedKeys) {
    if (!isRecord(value))
        return `${name} must be an object`;
    for (const key of Object.keys(value))
        if (!allowedKeys.includes(key))
            return `unsupported config field: ${name}.${key}`;
    return undefined;
}
export function validateConfigPatch(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value))
        return { error: 'patch must be an object' };
    const candidate = value;
    const allowedKeys = new Set([
        'enabled',
        'baseUrl',
        'model',
        'timeoutMs',
        'advisoryTimeoutMs',
        'askThreshold',
        'blockThreshold',
        'guardedTools',
        'loopGuard',
        'resultShaper',
        'doneGate',
        'toolPruner',
        'skillRouter',
        'decisionTools',
        'deterministicSafetyGuard',
    ]);
    for (const key of Object.keys(candidate)) {
        if (!allowedKeys.has(key))
            return { error: `unsupported config field: ${key}` };
    }
    if (candidate.enabled !== undefined && typeof candidate.enabled !== 'boolean')
        return { error: 'enabled must be boolean' };
    if (candidate.baseUrl !== undefined) {
        if (typeof candidate.baseUrl !== 'string' || candidate.baseUrl.length === 0)
            return { error: 'baseUrl must be a non-empty URL' };
        try {
            const url = new URL(candidate.baseUrl);
            if (url.protocol !== 'https:' && url.protocol !== 'http:')
                return { error: 'baseUrl must use http or https' };
        }
        catch {
            return { error: 'baseUrl must be a valid URL' };
        }
    }
    if (candidate.model !== undefined && (typeof candidate.model !== 'string' || candidate.model.length === 0)) {
        return { error: 'model must be a non-empty string' };
    }
    for (const key of ['timeoutMs', 'advisoryTimeoutMs']) {
        const number = candidate[key];
        if (number !== undefined && (!Number.isInteger(number) || Number(number) < 1 || Number(number) > 120_000)) {
            return { error: `${key} must be an integer from 1 to 120000` };
        }
    }
    for (const key of ['askThreshold', 'blockThreshold']) {
        const number = candidate[key];
        if (number !== undefined && (typeof number !== 'number' || !Number.isFinite(number) || number < 0 || number > 1)) {
            return { error: `${key} must be a number from 0 to 1` };
        }
    }
    if (candidate.guardedTools !== undefined) {
        if (!Array.isArray(candidate.guardedTools) || candidate.guardedTools.some((tool) => typeof tool !== 'string' || tool.length === 0)) {
            return { error: 'guardedTools must be a non-empty-string array' };
        }
        if (new Set(candidate.guardedTools).size !== candidate.guardedTools.length)
            return { error: 'guardedTools must be unique' };
    }
    if (candidate.loopGuard !== undefined) {
        const nestedError = validateNestedPatch(candidate.loopGuard, 'loopGuard', [
            'enabled', 'triggerThreshold', 'noProgressThreshold', 'pLoopThreshold', 'minConfidence',
            'cooldownSteps', 'maxHistory', 'deferExactRepeats', 'requestTimeoutMs', 'include', 'exclude',
        ]);
        if (nestedError)
            return { error: nestedError };
        const loopGuard = candidate.loopGuard;
        if (loopGuard.enabled !== undefined && typeof loopGuard.enabled !== 'boolean')
            return { error: 'loopGuard.enabled must be boolean' };
        for (const key of ['triggerThreshold', 'cooldownSteps', 'maxHistory']) {
            if (loopGuard[key] !== undefined) {
                const error = validateNumber(loopGuard[key], `loopGuard.${key}`, 1, 120, true);
                if (error)
                    return { error };
            }
        }
        if (loopGuard.requestTimeoutMs !== undefined) {
            const error = validateNumber(loopGuard.requestTimeoutMs, 'loopGuard.requestTimeoutMs', 1, 120_000, true);
            if (error)
                return { error };
        }
        for (const key of ['noProgressThreshold', 'pLoopThreshold', 'minConfidence']) {
            if (loopGuard[key] !== undefined) {
                const error = validateNumber(loopGuard[key], `loopGuard.${key}`, 0, 1);
                if (error)
                    return { error };
            }
        }
        if (loopGuard.deferExactRepeats !== undefined && typeof loopGuard.deferExactRepeats !== 'boolean')
            return { error: 'loopGuard.deferExactRepeats must be boolean' };
        for (const key of ['include', 'exclude']) {
            if (loopGuard[key] !== undefined) {
                const error = validateStringArray(loopGuard[key], `loopGuard.${key}`);
                if (error)
                    return { error };
            }
        }
    }
    if (candidate.resultShaper !== undefined) {
        const nestedError = validateNestedPatch(candidate.resultShaper, 'resultShaper', [
            'enabled', 'shapeTools', 'thresholdChars', 'maxPerTurn', 'keepKinds', 'minKindConfidence',
            'maxClusters', 'sampleChars', 'requestTimeoutMs',
        ]);
        if (nestedError)
            return { error: nestedError };
        const shaper = candidate.resultShaper;
        if (shaper.enabled !== undefined && typeof shaper.enabled !== 'boolean')
            return { error: 'resultShaper.enabled must be boolean' };
        for (const key of ['thresholdChars', 'maxPerTurn', 'maxClusters', 'sampleChars', 'requestTimeoutMs']) {
            if (shaper[key] !== undefined) {
                const error = validateNumber(shaper[key], `resultShaper.${key}`, 1, 120_000, true);
                if (error)
                    return { error };
            }
        }
        if (shaper.minKindConfidence !== undefined) {
            const error = validateNumber(shaper.minKindConfidence, 'resultShaper.minKindConfidence', 0, 1);
            if (error)
                return { error };
        }
        for (const key of ['shapeTools', 'keepKinds']) {
            if (shaper[key] !== undefined) {
                const error = validateStringArray(shaper[key], `resultShaper.${key}`, false);
                if (error)
                    return { error };
            }
        }
    }
    if (candidate.doneGate !== undefined) {
        const nestedError = validateNestedPatch(candidate.doneGate, 'doneGate', [
            'enabled', 'blockThreshold', 'minEvidenceItems', 'requestTimeoutMs', 'maxClaimChars', 'cooldownTurns',
        ]);
        if (nestedError)
            return { error: nestedError };
        const doneGate = candidate.doneGate;
        if (doneGate.enabled !== undefined && typeof doneGate.enabled !== 'boolean')
            return { error: 'doneGate.enabled must be boolean' };
        for (const key of ['minEvidenceItems', 'requestTimeoutMs', 'maxClaimChars', 'cooldownTurns']) {
            if (doneGate[key] !== undefined) {
                const error = validateNumber(doneGate[key], `doneGate.${key}`, 1, 120_000, true);
                if (error)
                    return { error };
            }
        }
        if (doneGate.blockThreshold !== undefined) {
            const error = validateNumber(doneGate.blockThreshold, 'doneGate.blockThreshold', 0, 1);
            if (error)
                return { error };
        }
    }
    if (candidate.toolPruner !== undefined) {
        const nestedError = validateNestedPatch(candidate.toolPruner, 'toolPruner', [
            'enabled', 'maxTools', 'minScoreThreshold', 'minConfidence', 'minIntentChars', 'minKeep',
            'maxCandidates', 'requestTimeoutMs', 'alwaysRetain',
        ]);
        if (nestedError)
            return { error: nestedError };
        const pruner = candidate.toolPruner;
        if (pruner.enabled !== undefined && typeof pruner.enabled !== 'boolean')
            return { error: 'toolPruner.enabled must be boolean' };
        for (const key of ['maxTools', 'minKeep', 'maxCandidates']) {
            if (pruner[key] !== undefined) {
                const error = validateNumber(pruner[key], `toolPruner.${key}`, 1, 120, true);
                if (error)
                    return { error };
            }
        }
        for (const key of ['minIntentChars', 'requestTimeoutMs']) {
            if (pruner[key] !== undefined) {
                const error = validateNumber(pruner[key], `toolPruner.${key}`, 1, 120_000, true);
                if (error)
                    return { error };
            }
        }
        for (const key of ['minScoreThreshold', 'minConfidence']) {
            if (pruner[key] !== undefined) {
                const error = validateNumber(pruner[key], `toolPruner.${key}`, 0, key === 'minScoreThreshold' ? 2 : 1);
                if (error)
                    return { error };
            }
        }
        if (pruner.alwaysRetain !== undefined) {
            const error = validateStringArray(pruner.alwaysRetain, 'toolPruner.alwaysRetain');
            if (error)
                return { error };
        }
    }
    if (candidate.skillRouter !== undefined) {
        const nestedError = validateNestedPatch(candidate.skillRouter, 'skillRouter', [
            'enabled', 'minCandidates', 'minIntentChars', 'maxSkills', 'maxCandidates', 'minScore',
            'minConfidence', 'nameMatchBoost', 'maxAdviceChars', 'requestTimeoutMs',
        ]);
        if (nestedError)
            return { error: nestedError };
        const router = candidate.skillRouter;
        if (router.enabled !== undefined && typeof router.enabled !== 'boolean')
            return { error: 'skillRouter.enabled must be boolean' };
        for (const key of ['minCandidates', 'maxCandidates']) {
            if (router[key] !== undefined) {
                const error = validateNumber(router[key], `skillRouter.${key}`, 1, 120, true);
                if (error)
                    return { error };
            }
        }
        if (router.maxSkills !== undefined) {
            const error = validateNumber(router.maxSkills, 'skillRouter.maxSkills', 1, 20, true);
            if (error)
                return { error };
        }
        for (const key of ['minIntentChars', 'maxAdviceChars', 'requestTimeoutMs']) {
            if (router[key] !== undefined) {
                const error = validateNumber(router[key], `skillRouter.${key}`, 1, 120_000, true);
                if (error)
                    return { error };
            }
        }
        for (const key of ['minScore', 'minConfidence', 'nameMatchBoost']) {
            if (router[key] !== undefined) {
                const error = validateNumber(router[key], `skillRouter.${key}`, 0, key === 'minScore' || key === 'nameMatchBoost' ? 2 : 1);
                if (error)
                    return { error };
            }
        }
    }
    if (candidate.decisionTools !== undefined) {
        const nestedError = validateNestedPatch(candidate.decisionTools, 'decisionTools', [
            'enabled', 'requestTimeoutMs', 'maxStateChars', 'maxQuestionChars', 'maxQuestions',
            'maxCandidates', 'maxCandidateChars', 'maxResultChars',
        ]);
        if (nestedError)
            return { error: nestedError };
        const tools = candidate.decisionTools;
        if (tools.enabled !== undefined && typeof tools.enabled !== 'boolean')
            return { error: 'decisionTools.enabled must be boolean' };
        for (const key of ['maxQuestions', 'maxCandidates']) {
            if (tools[key] !== undefined) {
                const error = validateNumber(tools[key], `decisionTools.${key}`, 1, 120, true);
                if (error)
                    return { error };
            }
        }
        for (const key of ['requestTimeoutMs', 'maxStateChars', 'maxQuestionChars', 'maxCandidateChars', 'maxResultChars']) {
            if (tools[key] !== undefined) {
                const error = validateNumber(tools[key], `decisionTools.${key}`, 1, 120_000, true);
                if (error)
                    return { error };
            }
        }
    }
    if (candidate.deterministicSafetyGuard !== undefined) {
        const nestedError = validateNestedPatch(candidate.deterministicSafetyGuard, 'deterministicSafetyGuard', [
            'enabled', 'maxArgumentChars',
        ]);
        if (nestedError)
            return { error: nestedError };
        const guard = candidate.deterministicSafetyGuard;
        if (guard.enabled !== undefined && typeof guard.enabled !== 'boolean')
            return { error: 'deterministicSafetyGuard.enabled must be boolean' };
        if (guard.maxArgumentChars !== undefined) {
            const error = validateNumber(guard.maxArgumentChars, 'deterministicSafetyGuard.maxArgumentChars', 1, 1_000_000, true);
            if (error)
                return { error };
        }
    }
    const patch = patchWithoutUnknown(candidate);
    const merged = mergeConfig(DEFAULT_CONFIG, patch);
    if (merged.blockThreshold < merged.askThreshold)
        return { error: 'blockThreshold must be at least askThreshold' };
    return { patch };
}
export class ConfigState {
    persistence;
    persistedGlobal = {};
    persistedWorkspaces = {};
    sessionOverrides = new Map();
    nextTurnOverrides = new Map();
    startupPatch;
    ready;
    constructor(persistence, startupPatch = {}) {
        this.persistence = persistence;
        this.startupPatch = patchWithoutUnknown(startupPatch);
        this.ready = this.load();
    }
    async load() {
        const persisted = await this.persistence.loadConfig();
        this.persistedGlobal = patchWithoutUnknown(persisted.global);
        this.persistedWorkspaces = {};
        for (const [workspaceId, patch] of Object.entries(persisted.workspaces)) {
            this.persistedWorkspaces[workspaceId] = patchWithoutUnknown(patch);
        }
    }
    layers(workspaceId, sessionId) {
        const layers = [
            { source: 'default', patch: {} },
            { source: 'global', patch: this.persistedGlobal },
            { source: 'workspace', patch: this.persistedWorkspaces[workspaceId] ?? {} },
        ];
        if (sessionId) {
            layers.push({ source: 'session', patch: this.sessionOverrides.get(scopeKey(workspaceId, sessionId)) ?? {} });
            layers.push({ source: 'next-turn', patch: this.nextTurnOverrides.get(scopeKey(workspaceId, sessionId)) ?? {} });
        }
        // The startup patch is the runtime composition layer. It intentionally sits
        // above persisted defaults so an operator can pin a deployment-wide value.
        layers.splice(1, 0, { source: 'startup', patch: this.startupPatch });
        return layers;
    }
    resolve(workspaceId, sessionId) {
        const layers = this.layers(workspaceId, sessionId);
        let values = { ...DEFAULT_CONFIG, guardedTools: [...DEFAULT_CONFIG.guardedTools] };
        let source = 'default';
        let requestedEnabled = values.enabled;
        let requestedEnabledSource = 'default';
        for (const layer of layers) {
            values = mergeConfig(values, layer.patch);
            if (Object.keys(layer.patch).length > 0)
                source = layer.source;
            if (hasOwn(layer.patch, 'enabled') && layer.patch.enabled !== undefined) {
                requestedEnabled = layer.patch.enabled;
                requestedEnabledSource = layer.source;
            }
        }
        return { values, source, requestedEnabled, requestedEnabledSource };
    }
    async setPersisted(scope, workspaceId, patch) {
        if (scope === 'global')
            this.persistedGlobal = mergePatch(this.persistedGlobal, patch);
        else
            this.persistedWorkspaces[workspaceId] = mergePatch(this.persistedWorkspaces[workspaceId] ?? {}, patch);
        const file = {
            version: 1,
            global: this.persistedGlobal,
            workspaces: this.persistedWorkspaces,
        };
        await this.persistence.saveConfig(file);
    }
    setEphemeral(scope, workspaceId, sessionId, patch) {
        const target = scope === 'session' ? this.sessionOverrides : this.nextTurnOverrides;
        const key = scopeKey(workspaceId, sessionId);
        target.set(key, mergePatch(target.get(key) ?? {}, patch));
    }
    consumeNextTurn(workspaceId, sessionId) {
        return this.nextTurnOverrides.delete(scopeKey(workspaceId, sessionId));
    }
    clearSession(workspaceId, sessionId) {
        const key = scopeKey(workspaceId, sessionId);
        this.sessionOverrides.delete(key);
        this.nextTurnOverrides.delete(key);
    }
}
//# sourceMappingURL=config-state.js.map