import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { DEFAULT_JEV_BASE_URL, DEFAULT_JEV_MODEL, } from '../../protocol/src/index.js';
export function noul(instructions) {
    return { type: 'noul', instructions };
}
export function choice(instructions, criteria) {
    return { type: 'choice', instructions, criteria };
}
export function score(instructions, criteria) {
    return { type: 'score', instructions, criteria };
}
export class JevClientError extends Error {
    kind;
    status;
    constructor(kind, message, status) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.name = 'JevClientError';
    }
}
function isTestEnvironment() {
    return process.env.NODE_ENV === 'test' || process.env.VITEST !== undefined || process.env.NODE_TEST_CONTEXT !== undefined;
}
async function resolveApiKey(explicit) {
    if (explicit && !explicit.startsWith('__jsExpr'))
        return explicit;
    if (isTestEnvironment())
        return undefined;
    const environmentKey = process.env.TYPESAFE_API_KEY;
    if (environmentKey)
        return environmentKey;
    try {
        const envFile = join(homedir(), '.dsh', '.env');
        const raw = await readFile(envFile, 'utf8');
        const text = typeof raw === 'string' ? raw : new TextDecoder().decode(raw);
        const match = text.match(/^\s*(?:export\s+)?TYPESAFE_API_KEY\s*=\s*([^\r\n]+)\s*$/m);
        if (!match?.[1])
            return undefined;
        return match[1].trim().replace(/^(['"])(.*)\1$/, '$2');
    }
    catch {
        return undefined;
    }
}
function cloneAnswers(answers) {
    return JSON.parse(JSON.stringify(answers));
}
function normalizeAnswers(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value))
        throw new JevClientError('invalid-response', 'Jev response did not contain an answer object');
    const normalized = {};
    for (const [key, raw] of Object.entries(value)) {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
            normalized[key] = { type: 'noul', unknown: true };
            continue;
        }
        const answer = raw;
        if (answer.type === 'noul') {
            const probability = typeof answer.noul === 'number' ? answer.noul : typeof answer.probability === 'number' ? answer.probability : undefined;
            normalized[key] = probability === undefined
                ? { type: 'noul', unknown: true }
                : { type: 'noul', noul: probability, probability };
            continue;
        }
        if (answer.type === 'choice') {
            normalized[key] = typeof answer.choice === 'string'
                ? answer
                : { type: 'choice', choice: '', confidence: 0, unknown: true };
            continue;
        }
        if (answer.type === 'score') {
            normalized[key] = typeof answer.score === 'number'
                ? answer
                : { type: 'score', score: 0, confidence: 0, unknown: true };
            continue;
        }
        normalized[key] = { type: 'noul', unknown: true };
    }
    return normalized;
}
export function noulProbability(result) {
    if (!result || typeof result !== 'object')
        return undefined;
    const answer = result;
    if (answer.type !== 'noul' || answer.unknown)
        return undefined;
    if (typeof answer.noul === 'number')
        return answer.noul;
    return typeof answer.probability === 'number' ? answer.probability : undefined;
}
export function topBucketProbability(result) {
    if (!result || typeof result !== 'object')
        return undefined;
    const answer = result;
    if (answer.type !== 'score' || answer.unknown || !answer.probabilities)
        return undefined;
    let bestKey;
    for (const key of Object.keys(answer.probabilities)) {
        const numeric = Number(key);
        if (!Number.isFinite(numeric))
            continue;
        if (bestKey === undefined || numeric > Number(bestKey))
            bestKey = key;
    }
    if (bestKey === undefined)
        return undefined;
    const value = answer.probabilities[bestKey];
    return typeof value === 'number' ? value : undefined;
}
export function scoreConfidence(result) {
    if (!result || typeof result !== 'object')
        return undefined;
    const answer = result;
    if (answer.type !== 'score' || answer.unknown)
        return undefined;
    return typeof answer.confidence === 'number' ? answer.confidence : undefined;
}
export function choiceConfidence(result) {
    if (!result || typeof result !== 'object')
        return undefined;
    const answer = result;
    if (answer.type !== 'choice' || answer.unknown)
        return undefined;
    return typeof answer.confidence === 'number' ? answer.confidence : undefined;
}
export class JevClient {
    config;
    explicitApiKey;
    fetchImpl;
    resolvedApiKey;
    apiKeyLoaded = false;
    constructor(config, explicitApiKey, fetchImpl) {
        this.config = config;
        this.explicitApiKey = explicitApiKey;
        this.fetchImpl = fetchImpl ?? globalThis.fetch.bind(globalThis);
    }
    get baseUrl() {
        return this.config.baseUrl || DEFAULT_JEV_BASE_URL;
    }
    get model() {
        return this.config.model || DEFAULT_JEV_MODEL;
    }
    async hasApiKey() {
        await this.loadApiKey();
        return Boolean(this.resolvedApiKey);
    }
    async loadApiKey() {
        if (this.apiKeyLoaded)
            return;
        this.resolvedApiKey = await resolveApiKey(this.explicitApiKey);
        this.apiKeyLoaded = true;
    }
    async systemOne(request, options = {}) {
        await this.loadApiKey();
        if (!this.resolvedApiKey)
            throw new JevClientError('missing-api-key', 'TYPESAFE_API_KEY is not configured');
        if (options.signal?.aborted)
            throw new JevClientError('cancelled', 'Jev call was cancelled before it started');
        const payload = JSON.stringify({
            model: request.model ?? this.model,
            state: typeof request.state === 'string' ? request.state : JSON.stringify(request.state),
            questions: request.questions,
        });
        const requestBytes = new TextEncoder().encode(payload).byteLength;
        const timeoutMs = options.timeoutMs ?? this.config.timeoutMs;
        const controller = new AbortController();
        let timedOut = false;
        const timer = setTimeout(() => {
            timedOut = true;
            controller.abort();
        }, timeoutMs);
        const abortUpstream = () => controller.abort();
        options.signal?.addEventListener('abort', abortUpstream, { once: true });
        const started = Date.now();
        try {
            const response = await this.fetchImpl(this.baseUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${this.resolvedApiKey}`,
                },
                body: payload,
                signal: controller.signal,
            });
            const bodyText = await response.text();
            const responseBytes = new TextEncoder().encode(bodyText).byteLength;
            if (!response.ok)
                throw new JevClientError('http', `Jev request failed with status ${response.status}: ${bodyText.slice(0, 300)}`, response.status);
            let data;
            try {
                data = JSON.parse(bodyText);
            }
            catch {
                throw new JevClientError('invalid-response', 'Jev response was not valid JSON');
            }
            const object = data && typeof data === 'object' && !Array.isArray(data) ? data : undefined;
            const answers = normalizeAnswers(object?.answers ?? object?.results ?? object);
            return { answers: cloneAnswers(answers), latencyMs: Date.now() - started, requestBytes, responseBytes };
        }
        catch (error) {
            if (error instanceof JevClientError)
                throw error;
            if (timedOut)
                throw new JevClientError('timeout', `Jev request exceeded ${timeoutMs}ms`);
            if (options.signal?.aborted)
                throw new JevClientError('cancelled', 'Jev call was cancelled');
            if (error instanceof DOMException && error.name === 'AbortError')
                throw new JevClientError('timeout', `Jev request exceeded ${timeoutMs}ms`);
            throw new JevClientError('network', error instanceof Error ? error.message : String(error));
        }
        finally {
            clearTimeout(timer);
            options.signal?.removeEventListener('abort', abortUpstream);
        }
    }
}
//# sourceMappingURL=jev-client.js.map