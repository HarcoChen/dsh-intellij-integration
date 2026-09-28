import { score, scoreConfidence } from '../jev-client.js';
const MANDATORY_TOOL_NAMES = new Set(['run_code', 'skill', 'jev_ask', 'jev_rank', 'jev_check']);
function serializedLength(value) {
    try {
        const serialized = JSON.stringify(value);
        return typeof serialized === 'string' ? serialized.length : 0;
    }
    catch {
        return 0;
    }
}
function toolName(value) {
    return typeof value.name === 'string' ? value.name : '';
}
function cloneTools(tools) {
    return tools.slice();
}
function boundedIntent(intent, max = 4_000) {
    return intent.length > max ? intent.slice(0, max) : intent;
}
function scoreValue(answer) {
    if (!answer || answer.unknown || answer.type !== 'score' || typeof answer.score !== 'number')
        return undefined;
    return answer.score;
}
/**
 * Minimal Jev-backed implementation of the DSH `ctx.toolPruner` service.
 *
 * The service deliberately returns a subset of the input schemas in their
 * original order. It never changes the ToolRuntime registry or permissions;
 * it only changes the model-facing assembly that DSH is already preparing.
 */
export class JevToolPrunerService {
    async pruneTools(intent, candidates, options) {
        if (!options?.config || !options.call)
            return cloneTools(candidates);
        const result = await this.prune(intent, candidates, options);
        return result.tools;
    }
    async prune(intent, candidates, options) {
        const started = Date.now();
        const config = options.config;
        const original = cloneTools(candidates);
        const seen = original.length;
        if (!config.enabled || seen <= config.maxTools || seen < Math.max(config.minKeep + 1, 2) || intent.trim().length < config.minIntentChars) {
            return { tools: original, outcome: 'skipped', seen, kept: seen, removedChars: 0, latencyMs: Date.now() - started };
        }
        const namesToRetain = new Set([...MANDATORY_TOOL_NAMES, ...config.alwaysRetain]);
        const retained = original.filter(candidate => namesToRetain.has(toolName(candidate)));
        const rankable = original
            .filter(candidate => !namesToRetain.has(toolName(candidate)))
            .slice(0, config.maxCandidates);
        if (rankable.length === 0) {
            return { tools: original, outcome: 'skipped', seen, kept: seen, removedChars: 0, latencyMs: Date.now() - started };
        }
        const questions = {};
        rankable.forEach((candidate, index) => {
            const name = toolName(candidate);
            const description = typeof candidate.description === 'string' ? candidate.description : '';
            questions[`tool_${index}`] = score(`How relevant is this DSH tool to the current user request?\nTool: ${name}\nDescription: ${description.slice(0, 1_000)}\nRequest: ${boundedIntent(intent)}`, ['not relevant', 'possibly useful', 'directly useful']);
        });
        let response;
        try {
            response = await options.call({
                state: { intent: boundedIntent(intent), candidateCount: rankable.length },
                questions,
            }, options.signal === undefined ? { timeoutMs: config.requestTimeoutMs } : { timeoutMs: config.requestTimeoutMs, signal: options.signal });
        }
        catch {
            return { tools: original, outcome: 'fail-open', seen, kept: seen, removedChars: 0, latencyMs: Date.now() - started };
        }
        const ranked = [];
        let maximumConfidence = 0;
        rankable.forEach((_candidate, index) => {
            const answer = response.answers[`tool_${index}`];
            const value = scoreValue(answer);
            const confidence = scoreConfidence(answer) ?? 0;
            maximumConfidence = Math.max(maximumConfidence, confidence);
            if (value !== undefined)
                ranked.push({ index, value, confidence });
        });
        if (ranked.length === 0 || maximumConfidence < config.minConfidence) {
            return { tools: original, outcome: 'low-confidence', seen, kept: seen, removedChars: 0, latencyMs: Date.now() - started };
        }
        ranked.sort((left, right) => right.value - left.value || right.confidence - left.confidence || left.index - right.index);
        const availableSlots = Math.max(0, config.maxTools - retained.length);
        const selected = ranked
            .filter(item => item.value >= config.minScoreThreshold && item.confidence >= config.minConfidence)
            .slice(0, availableSlots);
        const targetKeep = Math.min(config.minKeep, rankable.length, availableSlots);
        if (selected.length < targetKeep) {
            for (const item of ranked) {
                if (selected.some(existing => existing.index === item.index))
                    continue;
                selected.push(item);
                if (selected.length >= targetKeep)
                    break;
            }
        }
        const selectedNames = new Set(selected.map(item => rankable[item.index]).filter((candidate) => candidate !== undefined).map(toolName));
        const retainedNames = new Set([...namesToRetain, ...selectedNames]);
        const output = original.filter(candidate => retainedNames.has(toolName(candidate)));
        const removedChars = Math.max(0, serializedLength(original) - serializedLength(output));
        return {
            tools: output.length > 0 ? output : original,
            outcome: 'call',
            seen,
            kept: output.length > 0 ? output.length : seen,
            removedChars: output.length > 0 ? removedChars : 0,
            latencyMs: Date.now() - started,
        };
    }
}
//# sourceMappingURL=tool-pruner.js.map