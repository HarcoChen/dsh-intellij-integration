import { randomUUID } from 'node:crypto';
import { noul, noulProbability, score, scoreConfidence, topBucketProbability, } from '../jev-client.js';
export const STUCK_SEVERITY_CRITERIA = [
    'Normal progress or healthy exploration',
    'Marginal repeat or stagnant exploration',
    'Definite dead loop, circular failures, or unrecoverable repetition',
];
function hashString(value) {
    let hash = 0x811c9dc5;
    for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 0x01000193);
    }
    return (hash >>> 0).toString(16);
}
export function canonicalArgs(args) {
    const normalize = (value) => {
        if (Array.isArray(value))
            return value.map(normalize);
        if (value && typeof value === 'object') {
            return Object.entries(value)
                .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
                .map(([key, entry]) => [key, normalize(entry)]);
        }
        return value;
    };
    try {
        return JSON.stringify(normalize(args ?? {}));
    }
    catch {
        return String(args);
    }
}
export function extractResultText(content) {
    if (typeof content === 'string')
        return content;
    if (Array.isArray(content)) {
        return content
            .filter(value => value && typeof value === 'object' && value.type === 'text')
            .map(value => value.text)
            .filter((value) => typeof value === 'string')
            .join('\n');
    }
    try {
        return JSON.stringify(content ?? '');
    }
    catch {
        return String(content);
    }
}
export function evaluateStuckTrajectory(answers, thresholds) {
    const progress = noulProbability(answers.has_progress);
    const pLoop = topBucketProbability(answers.stuck_severity);
    const confidence = scoreConfidence(answers.stuck_severity);
    if (progress === undefined || pLoop === undefined || confidence === undefined)
        return { action: 'unknown' };
    if (progress >= thresholds.noProgressThreshold || pLoop < thresholds.pLoopThreshold || confidence < thresholds.minConfidence) {
        return { action: 'pass', progress, pLoop, confidence };
    }
    return { action: pLoop >= 0.85 ? 'interrupt' : 'warn', progress, pLoop, confidence };
}
function notice(tool, scoreValue, progress, pLoop, confidence) {
    const severity = scoreValue === undefined ? '?' : scoreValue.toFixed(2);
    const text = `[DSH Jev LoopGuard] Potential loop or stagnation detected after "${tool}". `
        + `Progress probability ${(progress * 100).toFixed(0)}%, dead-loop probability ${(pLoop * 100).toFixed(0)}% `
        + `(severity ${severity}/${STUCK_SEVERITY_CRITERIA.length - 1}, confidence ${(confidence * 100).toFixed(0)}%). `
        + 'Review recent results and change the plan instead of repeating the same query or retry.';
    return {
        id: randomUUID(),
        role: 'user',
        source: {
            kind: 'plugin',
            plugin: 'dsh-jev-integration',
            form: 'notice',
            summary: 'Jev loop guard requested a plan change',
            policy: 'loop-guard',
            tool,
            severity: scoreValue,
            progress,
            pLoop,
            confidence,
        },
        content: [{ type: 'text', text }],
    };
}
export class LoopGuardPolicy {
    chains = new WeakMap();
    reset(agent) {
        if (agent && typeof agent === 'object')
            this.chains.delete(agent);
    }
    async inspect(exec, result, config, call) {
        if (!config.enabled || !exec.name || !exec.agent || typeof exec.agent !== 'object')
            return { action: 'skipped' };
        if (config.exclude.includes(exec.name) || (config.include.length > 0 && !config.include.includes(exec.name)))
            return { action: 'skipped' };
        const agent = exec.agent;
        let chain = this.chains.get(agent);
        if (chain === undefined) {
            chain = { history: [], noProgressStreak: 0, cooldown: 0 };
            this.chains.set(agent, chain);
        }
        const content = extractResultText(result.content ?? result.error);
        const argsKey = canonicalArgs(exec.arguments ?? exec.args);
        const contentHash = hashString(content);
        const previous = chain.history.at(-1);
        const exactRepeat = previous !== undefined
            && previous.tool === exec.name
            && previous.argsKey === argsKey
            && previous.contentHash === contentHash;
        const cooling = chain.cooldown > 0;
        if (cooling)
            chain.cooldown -= 1;
        chain.history.push({ tool: exec.name, argsKey, contentHash });
        if (chain.history.length > config.maxHistory)
            chain.history.splice(0, chain.history.length - config.maxHistory);
        if (config.deferExactRepeats && exactRepeat)
            return { action: 'skipped' };
        if (chain.noProgressStreak + 1 < config.triggerThreshold || cooling) {
            chain.noProgressStreak += 1;
            return { action: 'skipped' };
        }
        const recent = chain.history.slice(-config.triggerThreshold);
        try {
            const started = Date.now();
            const response = await call({
                state: {
                    currentTool: exec.name,
                    currentArgs: exec.arguments ?? exec.args,
                    currentOutputSample: content.slice(0, 1_500),
                    historyDepth: chain.history.length,
                    recentTrajectory: recent.map((step, index) => ({
                        step: index + 1,
                        tool: step.tool,
                        args: step.argsKey.slice(0, 300),
                        outputHash: step.contentHash,
                    })),
                },
                questions: {
                    has_progress: noul('Does the latest tool execution provide new, meaningful progress or fresh information towards solving the task?'),
                    stuck_severity: score('Rate how severely this execution sequence is stuck in a repetitive loop or stagnation without progress', [...STUCK_SEVERITY_CRITERIA]),
                },
            }, exec.signal === undefined ? { timeoutMs: config.requestTimeoutMs } : { timeoutMs: config.requestTimeoutMs, signal: exec.signal });
            const verdict = evaluateStuckTrajectory(response.answers, {
                noProgressThreshold: config.noProgressThreshold,
                pLoopThreshold: config.pLoopThreshold,
                minConfidence: config.minConfidence,
            });
            if (verdict.action === 'unknown') {
                chain.noProgressStreak += 1;
                return { action: 'uncertain', latencyMs: Date.now() - started };
            }
            if (verdict.action === 'pass') {
                chain.noProgressStreak = verdict.progress !== undefined && verdict.progress < config.noProgressThreshold
                    ? chain.noProgressStreak + 1
                    : 0;
                return { action: 'pass', latencyMs: Date.now() - started };
            }
            chain.noProgressStreak = 0;
            chain.cooldown = config.cooldownSteps;
            const severity = response.answers.stuck_severity?.score;
            return {
                action: verdict.action === 'interrupt' ? 'interrupt' : 'warning',
                context: notice(exec.name, typeof severity === 'number' ? severity : undefined, verdict.progress ?? 0, verdict.pLoop ?? 0, verdict.confidence ?? 0),
                latencyMs: Date.now() - started,
            };
        }
        catch {
            // The caller records the failure; a semantic advisory outage never changes
            // the tool result or blocks the DSH loop.
            return { action: 'uncertain' };
        }
    }
}
//# sourceMappingURL=loop-guard.js.map