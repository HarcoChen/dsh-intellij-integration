import { choice, noul, score, noulProbability } from './jev-client.js';
const askParameters = {
    type: 'object',
    additionalProperties: false,
    required: ['state', 'questions'],
    properties: {
        state: { type: 'string', minLength: 1, maxLength: 120_000 },
        questions: {
            type: 'array', minItems: 1, maxItems: 120,
            items: {
                type: 'object', additionalProperties: false, required: ['id', 'kind', 'instructions'],
                properties: {
                    id: { type: 'string', minLength: 1, maxLength: 80 },
                    kind: { type: 'string', enum: ['noul', 'choice', 'score'] },
                    instructions: { type: 'string', minLength: 1, maxLength: 120_000 },
                    criteria: { oneOf: [{ type: 'object' }, { type: 'array', items: { type: 'string', maxLength: 200 } }] },
                },
            },
        },
    },
};
const rankParameters = {
    type: 'object', additionalProperties: false, required: ['criterion', 'candidates'],
    properties: {
        state: { type: 'string', minLength: 1, maxLength: 120_000 },
        criterion: { type: 'string', minLength: 1, maxLength: 120_000 },
        candidates: {
            type: 'array', minItems: 1, maxItems: 120,
            items: {
                oneOf: [
                    { type: 'string', minLength: 1, maxLength: 120_000 },
                    {
                        type: 'object', additionalProperties: false, required: ['id', 'label'],
                        properties: {
                            id: { type: 'string', minLength: 1, maxLength: 120 },
                            label: { type: 'string', minLength: 1, maxLength: 120_000 },
                            description: { type: 'string', maxLength: 120_000 },
                        },
                    },
                ],
            },
        },
    },
};
const checkParameters = {
    type: 'object', additionalProperties: false, required: ['state', 'claim'],
    properties: {
        state: { type: 'string', minLength: 1, maxLength: 120_000 },
        claim: { type: 'string', minLength: 1, maxLength: 120_000 },
        threshold: { type: 'number', minimum: 0, maximum: 1 },
    },
};
const resultSchema = {
    type: 'object', additionalProperties: false, required: ['ok', 'unknown', 'text'],
    properties: {
        ok: { type: 'boolean' },
        unknown: { type: 'boolean' },
        text: { type: 'string', maxLength: 120_000 },
        latencyMs: { type: 'number', minimum: 0 },
        error: { type: 'string', maxLength: 300 },
        answers: { type: 'array' },
        ranked: { type: 'array' },
        holds: { type: 'boolean' },
        probability: { type: 'number', minimum: 0, maximum: 1 },
    },
};
function boundedText(value, max) {
    return typeof value === 'string' ? value.slice(0, max) : '';
}
function acceptedText(value, max) {
    if (typeof value !== 'string' || value.length === 0 || value.length > max)
        return undefined;
    return value;
}
function asObject(value) {
    return value && typeof value === 'object' && !Array.isArray(value) ? value : undefined;
}
function unknownResult(error) {
    const result = { ok: false, unknown: true, text: error ? `Jev decision unavailable: ${error}` : 'Jev decision unavailable.' };
    if (error)
        result.error = error.slice(0, 300);
    return result;
}
function trimForResult(value, max) {
    const text = typeof value.text === 'string' ? value.text.slice(0, max) : '';
    return { ...value, text };
}
function toQuestion(input, config) {
    const instructions = acceptedText(input.instructions, config.maxQuestionChars);
    if (!input.id || !instructions)
        return undefined;
    if (input.kind === 'noul')
        return noul(instructions);
    if (input.kind === 'choice') {
        if (!input.criteria || Array.isArray(input.criteria))
            return undefined;
        const criteria = {};
        for (const [key, value] of Object.entries(input.criteria).slice(0, 32)) {
            if (typeof key !== 'string' || typeof value !== 'string' && value !== null)
                return undefined;
            criteria[key.slice(0, 100)] = value === null ? null : value.slice(0, 300);
        }
        if (Object.keys(criteria).length === 0)
            return undefined;
        return choice(instructions, criteria);
    }
    const criteria = Array.isArray(input.criteria) && input.criteria.length > 0
        ? input.criteria.slice(0, 32).map(item => boundedText(item, 300)).filter(Boolean)
        : ['Low', 'Medium', 'High'];
    return score(instructions, criteria);
}
function answerText(answers) {
    return Object.entries(answers).map(([id, answer]) => {
        const value = answer.choice ?? answer.score ?? answer.noul ?? answer.probability;
        return `${id}: ${value === undefined ? 'unknown' : String(value)}`;
    }).join('\n');
}
function projectAnswer(id, answer) {
    if (!answer || answer.unknown)
        return { id, unknown: true };
    if (answer.type === 'noul') {
        const result = { id, kind: 'noul' };
        const probability = answer.noul ?? answer.probability;
        if (probability !== undefined)
            result.probability = probability;
        return result;
    }
    if (answer.type === 'score') {
        const result = { id, kind: 'score', score: answer.score };
        if (answer.confidence !== undefined)
            result.confidence = answer.confidence;
        if (answer.probabilities !== undefined)
            result.probabilities = answer.probabilities;
        return result;
    }
    const result = { id, kind: 'choice', choice: answer.choice };
    if (answer.confidence !== undefined)
        result.confidence = answer.confidence;
    if (answer.probabilities !== undefined)
        result.probabilities = answer.probabilities;
    return result;
}
function renderValue(_args, value) {
    const object = asObject(value);
    return [{ type: 'text', text: typeof object?.text === 'string' ? object.text : 'Jev decision unavailable.' }];
}
function definition(name, description, parameters, execute) {
    return {
        name,
        description,
        parameters,
        output: { schema: resultSchema, render: renderValue },
        execute,
    };
}
function normalizeArgs(value) {
    const args = asObject(value);
    if (!args)
        return undefined;
    return args;
}
export function registerDecisionTools(tools, runtime) {
    if (!tools || typeof tools.register !== 'function' || !runtime.config.enabled)
        return [];
    const disposers = [];
    const register = (definitionValue) => {
        try {
            const registered = runtime.register(definitionValue);
            if (typeof registered === 'function')
                disposers.push(registered);
            const name = asObject(definitionValue)?.name;
            if (typeof name === 'string')
                runtime.onRegistered?.(name);
        }
        catch {
            // A host may reject one optional tool name (for example a collision).
            // Keep the other primitives available and leave the rejected one absent.
        }
    };
    register(definition('jev_ask', 'Ask Jev bounded structured questions about the supplied state.', askParameters, async (rawArgs, exec) => {
        const started = Date.now();
        const config = runtime.resolveConfig?.(exec) ?? runtime.config;
        const args = normalizeArgs(rawArgs);
        const state = acceptedText(args?.state, config.maxStateChars) ?? '';
        const rawQuestions = Array.isArray(args?.questions) ? args.questions.slice(0, config.maxQuestions) : [];
        if (!args || !state || rawQuestions.length === 0) {
            runtime.record('unknown', Date.now() - started, exec);
            return unknownResult('invalid bounded arguments');
        }
        const questions = {};
        const seenIds = new Set();
        for (const raw of rawQuestions) {
            const input = asObject(raw);
            const question = input ? toQuestion(input, config) : undefined;
            if (!input || typeof input.id !== 'string' || input.id.length > 80 || !question || seenIds.has(input.id)) {
                runtime.record('unknown', Date.now() - started, exec);
                return unknownResult('invalid question schema');
            }
            const id = input.id.slice(0, 80);
            seenIds.add(id);
            questions[id] = question;
        }
        try {
            const response = await runtime.call({ state, questions }, exec.signal === undefined
                ? { timeoutMs: config.requestTimeoutMs }
                : { timeoutMs: config.requestTimeoutMs, signal: exec.signal }, exec);
            runtime.record('call', Date.now() - started, exec);
            const answers = Object.keys(questions).map(id => projectAnswer(id, response.answers[id]));
            const text = answerText(response.answers);
            return trimForResult({ ok: true, unknown: false, answers, latencyMs: Date.now() - started, text }, config.maxResultChars);
        }
        catch {
            runtime.record('fail-open', Date.now() - started, exec);
            return unknownResult('timeout or unavailable service');
        }
    }));
    register(definition('jev_rank', 'Rank bounded candidates against a criterion using Jev.', rankParameters, async (rawArgs, exec) => {
        const started = Date.now();
        const config = runtime.resolveConfig?.(exec) ?? runtime.config;
        const args = normalizeArgs(rawArgs);
        const criterion = acceptedText(args?.criterion, config.maxQuestionChars) ?? '';
        const rawCandidates = Array.isArray(args?.candidates) ? args.candidates.slice(0, config.maxCandidates) : [];
        const candidates = [];
        const seenCandidateIds = new Set();
        let duplicateCandidateId = false;
        let invalidCandidate = false;
        for (const [index, raw] of rawCandidates.entries()) {
            if (typeof raw === 'string') {
                const label = acceptedText(raw, config.maxCandidateChars);
                if (raw.length > 0 && !label)
                    invalidCandidate = true;
                const id = String(index);
                if (label && seenCandidateIds.has(id))
                    duplicateCandidateId = true;
                if (label && !seenCandidateIds.has(id)) {
                    seenCandidateIds.add(id);
                    candidates.push({ id, label });
                }
                continue;
            }
            const candidate = asObject(raw);
            const rawId = candidate?.id;
            const rawLabel = candidate?.label;
            const rawDescription = candidate?.description;
            const id = acceptedText(rawId, 120) ?? '';
            const label = acceptedText(rawLabel, config.maxCandidateChars) ?? '';
            if ((typeof rawId === 'string' && rawId.length > 120)
                || (typeof rawLabel === 'string' && rawLabel.length > config.maxCandidateChars)
                || (typeof rawDescription === 'string' && rawDescription.length > config.maxCandidateChars))
                invalidCandidate = true;
            if (!id || !label)
                invalidCandidate = true;
            if (id && label && seenCandidateIds.has(id))
                duplicateCandidateId = true;
            if (id && label && !seenCandidateIds.has(id)) {
                seenCandidateIds.add(id);
                candidates.push({ id, label, description: acceptedText(rawDescription, config.maxCandidateChars) ?? '' });
            }
        }
        if (!args || !criterion || candidates.length === 0 || duplicateCandidateId || invalidCandidate) {
            runtime.record('unknown', Date.now() - started, exec);
            return unknownResult('invalid bounded arguments');
        }
        const questions = {};
        candidates.forEach(candidate => {
            questions[`rank_${candidate.id}`] = score(`${criterion}\nCandidate: ${candidate.label}${candidate.description ? ` (${candidate.description})` : ''}`, ['Does not satisfy', 'Partially satisfies', 'Fully satisfies']);
        });
        try {
            const state = acceptedText(args?.state, config.maxStateChars) ?? '';
            const response = await runtime.call({ state: { context: state, criterion }, questions }, exec.signal === undefined
                ? { timeoutMs: config.requestTimeoutMs }
                : { timeoutMs: config.requestTimeoutMs, signal: exec.signal }, exec);
            runtime.record('call', Date.now() - started, exec);
            const ranked = candidates.map(candidate => {
                const answer = response.answers[`rank_${candidate.id}`];
                const result = {
                    id: candidate.id,
                    label: candidate.label,
                    score: answer?.unknown || typeof answer?.score !== 'number' ? null : answer.score,
                    confidence: answer?.unknown || typeof answer?.confidence !== 'number' ? null : answer.confidence,
                    unknown: answer?.unknown !== false && typeof answer?.score !== 'number',
                };
                if (candidate.description)
                    result.description = candidate.description;
                return result;
            }).sort((left, right) => (typeof right.score === 'number' ? right.score : -1) - (typeof left.score === 'number' ? left.score : -1));
            return trimForResult({ ok: true, unknown: false, ranked, latencyMs: Date.now() - started, text: JSON.stringify(ranked) }, config.maxResultChars);
        }
        catch {
            runtime.record('fail-open', Date.now() - started, exec);
            return unknownResult('timeout or unavailable service');
        }
    }));
    register(definition('jev_check', 'Check a bounded claim against bounded state using Jev.', checkParameters, async (rawArgs, exec) => {
        const started = Date.now();
        const config = runtime.resolveConfig?.(exec) ?? runtime.config;
        const args = normalizeArgs(rawArgs);
        const state = acceptedText(args?.state, config.maxStateChars) ?? '';
        const claim = acceptedText(args?.claim, config.maxQuestionChars) ?? '';
        const threshold = typeof args?.threshold === 'number' && Number.isFinite(args.threshold) ? Math.min(1, Math.max(0, args.threshold)) : 0.7;
        if (!args || !state || !claim) {
            runtime.record('unknown', Date.now() - started, exec);
            return unknownResult('invalid bounded arguments');
        }
        try {
            const response = await runtime.call({
                state: { state, claim },
                questions: { holds: noul(`Does the supplied state support this claim?\nClaim: ${claim}`) },
            }, exec.signal === undefined ? { timeoutMs: config.requestTimeoutMs } : { timeoutMs: config.requestTimeoutMs, signal: exec.signal }, exec);
            const probability = noulProbability(response.answers.holds);
            runtime.record('call', Date.now() - started, exec);
            if (probability === undefined)
                return trimForResult({ ok: true, unknown: true, holds: false, probability: 0, latencyMs: Date.now() - started, text: 'Jev could not determine whether the claim is supported.' }, config.maxResultChars);
            const holds = probability >= threshold;
            return trimForResult({ ok: true, unknown: false, holds, probability, latencyMs: Date.now() - started, text: `Claim support probability: ${probability.toFixed(3)} (${holds ? 'holds' : 'does not hold'} at threshold ${threshold.toFixed(3)}).` }, config.maxResultChars);
        }
        catch {
            runtime.record('fail-open', Date.now() - started, exec);
            return unknownResult('timeout or unavailable service');
        }
    }));
    return disposers;
}
//# sourceMappingURL=decision-tools.js.map