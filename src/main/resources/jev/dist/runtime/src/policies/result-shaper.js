import { choice } from '../jev-client.js';
export const DEFAULT_SHAPE_TOOLS = ['bash', 'pwsh', 'terminal', 'run_command', 'execute_command'];
export const DROP_MARKER = '[... %d lines dropped by DSH Jev result shaper ...]';
export const KIND_CRITERIA = {
    routine_progress: 'Routine progress: steps completed, files processed, chunks emitted, items listed',
    summary: 'Neutral summary: totals, counts, timings, versions, a final status line',
    warning: 'A deprecation or a warning that may need attention',
    failure: 'A failure: an error code, an exception, a failed build, a failing test, a stack frame',
    error: 'An error or diagnostic that explains why an operation failed or needs attention',
};
export function extractText(content) {
    if (typeof content === 'string')
        return content;
    if (!Array.isArray(content))
        return undefined;
    const parts = content
        .filter((block) => Boolean(block) && typeof block === 'object')
        .filter(block => block.type === 'text' && typeof block.text === 'string')
        .map(block => block.text);
    return parts.length > 0 ? parts.join('\n') : undefined;
}
export function replaceText(content, text) {
    if (typeof content === 'string')
        return text;
    if (!Array.isArray(content))
        return text;
    const rebuilt = [];
    let inserted = false;
    for (const blockValue of content) {
        if (!blockValue || typeof blockValue !== 'object') {
            rebuilt.push(blockValue);
            continue;
        }
        const block = blockValue;
        if (block.type === 'text') {
            if (!inserted) {
                rebuilt.push({ ...block, text });
                inserted = true;
            }
            continue;
        }
        rebuilt.push(block);
    }
    if (!inserted)
        rebuilt.push({ type: 'text', text });
    return rebuilt;
}
export function lineShape(line) {
    return line.replace(/[0-9a-f]{6,}|\d+/gi, '#');
}
export function clusterLines(text) {
    const clusters = new Map();
    for (const line of text.split('\n')) {
        if (line.trim().length === 0)
            continue;
        const shape = lineShape(line);
        const existing = clusters.get(shape);
        if (existing)
            existing.count += 1;
        else
            clusters.set(shape, { shape, count: 1, sample: line });
    }
    return [...clusters.values()];
}
function repeatedRatio(lines) {
    return 1 - new Set(lines).size / lines.length;
}
export function looksRepetitive(text) {
    const lines = text.split('\n').map(line => line.trim()).filter(line => line.length > 0);
    if (lines.reduce((max, line) => Math.max(max, line.length), 0) > 4_000)
        return true;
    if (lines.length >= 120)
        return true;
    if (lines.length < 40)
        return false;
    if (repeatedRatio(lines) >= 0.25)
        return true;
    return repeatedRatio(lines.map(lineShape)) >= 0.5;
}
export class ResultShaperPolicy {
    budgets = new WeakMap();
    reset(agent) {
        if (agent && typeof agent === 'object')
            this.budgets.delete(agent);
    }
    budgetFor(agent) {
        if (!agent || typeof agent !== 'object')
            return undefined;
        let budget = this.budgets.get(agent);
        if (!budget) {
            budget = { shaped: 0, declined: false };
            this.budgets.set(agent, budget);
        }
        return budget;
    }
    shouldConsider(exec, content, config) {
        const budget = this.budgetFor(exec.agent);
        if (!budget || budget.declined || !config.enabled)
            return false;
        if (!config.shapeTools.includes(exec.name ?? ''))
            return false;
        if (content.length < config.thresholdChars || budget.shaped >= config.maxPerTurn)
            return false;
        return looksRepetitive(content);
    }
    async shape(content, toolName, exec, config, call) {
        const budget = this.budgetFor(exec.agent);
        if (!budget)
            return undefined;
        const clusters = clusterLines(content);
        if (clusters.length < 2)
            return undefined;
        const selected = clusters.slice(0, config.maxClusters);
        const questions = {};
        selected.forEach((cluster, index) => {
            questions[`kind_${index}`] = choice('Classify this line of tool output by kind:\n---\n'
                + cluster.sample.slice(0, config.sampleChars)
                + '\n---', KIND_CRITERIA);
        });
        const started = Date.now();
        let response;
        try {
            response = await call({
                state: {
                    tool: toolName,
                    note: 'Each question carries one line of output; classify that line.',
                    distinctLines: clusters.length,
                    occurrences: selected.map(cluster => cluster.count),
                },
                questions,
            }, exec.signal === undefined ? { timeoutMs: config.requestTimeoutMs } : { timeoutMs: config.requestTimeoutMs, signal: exec.signal });
        }
        catch {
            return undefined;
        }
        const keptShapes = new Set();
        for (const cluster of clusters.slice(selected.length))
            keptShapes.add(cluster.shape);
        const keepKinds = new Set(config.keepKinds);
        selected.forEach((cluster, index) => {
            const answer = response.answers[`kind_${index}`];
            if (!answer || answer.unknown) {
                keptShapes.add(cluster.shape);
                return;
            }
            const confidence = typeof answer.confidence === 'number' ? answer.confidence : 0;
            if (answer.choice !== undefined && keepKinds.has(answer.choice) && confidence >= config.minKindConfidence)
                keptShapes.add(cluster.shape);
        });
        if (keptShapes.size === 0) {
            budget.declined = true;
            return undefined;
        }
        const lines = content.split('\n');
        const rebuilt = [];
        let droppedLines = 0;
        let runStart = -1;
        const flush = (endExclusive) => {
            if (runStart < 0)
                return;
            const count = endExclusive - runStart;
            droppedLines += count;
            rebuilt.push(DROP_MARKER.replace('%d', String(count)));
            runStart = -1;
        };
        lines.forEach((line, index) => {
            if (line.trim().length === 0 || keptShapes.has(lineShape(line))) {
                flush(index);
                rebuilt.push(line);
            }
            else if (runStart < 0) {
                runStart = index;
            }
        });
        flush(lines.length);
        const text = rebuilt.join('\n');
        if (droppedLines === 0 || text.length >= content.length)
            return undefined;
        budget.shaped += 1;
        return {
            text,
            keptClusters: keptShapes.size,
            droppedClusters: Math.max(0, clusters.length - keptShapes.size),
            droppedLines,
            latencyMs: Date.now() - started,
        };
    }
}
//# sourceMappingURL=result-shaper.js.map