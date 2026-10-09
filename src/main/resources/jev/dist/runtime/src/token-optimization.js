function numeric(value) {
    return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}
function messageForContent(content, isError, callId) {
    const safeCallId = typeof callId === 'string' && callId.length > 0 ? callId : 'dsh-jev';
    return {
        role: 'user',
        content: [{ type: 'tool-result', toolCallId: safeCallId, content, isError }],
        source: { kind: 'tool', callId: safeCallId },
    };
}
export function estimateContentTokens(meter, content, isError, callId) {
    if (!meter || typeof meter.estimateMessage !== 'function')
        return null;
    try {
        const blocks = Array.isArray(content) ? content : typeof content === 'string' ? [{ type: 'text', text: content }] : undefined;
        if (!blocks)
            return null;
        return numeric(meter.estimateMessage(messageForContent(blocks, isError, callId)));
    }
    catch {
        return null;
    }
}
export function estimateTextTokens(meter, text) {
    if (!meter || typeof meter.estimateMessage !== 'function')
        return null;
    try {
        return numeric(meter.estimateMessage({ role: 'user', content: [{ type: 'text', text }], source: { kind: 'plugin', plugin: 'dsh-jev-integration' } }));
    }
    catch {
        return null;
    }
}
export function measureSessionTokens(meter, session) {
    if (!meter || typeof meter.measure !== 'function' || !session || typeof session !== 'object')
        return null;
    try {
        return numeric(meter.measure(session)?.totalTokens);
    }
    catch {
        return null;
    }
}
function textLines(content) {
    if (!Array.isArray(content))
        return [];
    return content
        .filter(block => block && typeof block === 'object' && block.type === 'text')
        .flatMap(block => typeof block.text === 'string' ? block.text.split('\n') : []);
}
function criticalLines(content) {
    const seen = new Set();
    const result = [];
    for (const line of textLines(content)) {
        const trimmed = line.trim();
        if (!trimmed || !/(?:warning|warn|failure|failed|error|fatal|exception|traceback|summary)\b/i.test(trimmed))
            continue;
        if (seen.has(trimmed))
            continue;
        seen.add(trimmed);
        result.push(trimmed.slice(0, 2_000));
    }
    return result;
}
export function preserveCriticalContent(original, pruned) {
    const wanted = criticalLines(original);
    if (wanted.length === 0)
        return [...pruned];
    const retainedText = textLines(pruned).join('\n');
    const missing = wanted.filter(line => !retainedText.includes(line));
    if (missing.length === 0)
        return [...pruned];
    return [
        ...pruned,
        { type: 'text', text: `[DSH Jev preserved critical lines]\n${missing.join('\n')}` },
    ];
}
export function applyNativeResultPruner(pruner, content, isError) {
    if (!pruner || typeof pruner.pruneContent !== 'function' || !Array.isArray(content) || isError) {
        return { content, changed: false, charsBefore: null, charsAfter: null, charsRemoved: 0 };
    }
    let charsBefore = null;
    try {
        if (typeof pruner.measureContent === 'function')
            charsBefore = numeric(pruner.measureContent(content));
        const returned = pruner.pruneContent(content);
        if (!Array.isArray(returned))
            return { content, changed: false, charsBefore, charsAfter: charsBefore, charsRemoved: 0 };
        const preserved = preserveCriticalContent(content, returned);
        let charsAfter = null;
        if (typeof pruner.measureContent === 'function')
            charsAfter = numeric(pruner.measureContent(preserved));
        return {
            content: preserved,
            changed: true,
            charsBefore,
            charsAfter,
            charsRemoved: charsBefore !== null && charsAfter !== null ? Math.max(0, charsBefore - charsAfter) : 0,
        };
    }
    catch {
        return { content, changed: false, charsBefore, charsAfter: charsBefore, charsRemoved: 0 };
    }
}
function repeatedText(text) {
    const seen = new Set();
    const repeated = [];
    for (const line of text.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed)
            continue;
        if (seen.has(trimmed))
            repeated.push(line);
        else
            seen.add(trimmed);
    }
    if (repeated.length > 0)
        return repeated.join('\n');
    const lines = text.split('\n').filter(line => line.trim().length > 0);
    return lines.length > 32 ? lines.slice(Math.floor(lines.length / 3)).join('\n') : '';
}
export function estimatePotentialSavings(meter, text) {
    const candidate = repeatedText(text);
    return candidate ? estimateTextTokens(meter, candidate) : null;
}
export function estimateDecisionCost(meter, request) {
    const state = typeof request.state === 'string' ? request.state : JSON.stringify(request.state);
    const questions = JSON.stringify(request.questions);
    return estimateTextTokens(meter, `${state}\n${questions}`);
}
export function tokenPair(before, after) {
    return {
        before,
        after,
        removed: before !== null && after !== null ? Math.max(0, before - after) : null,
    };
}
export function failureType(error) {
    if (!error || typeof error !== 'object')
        return undefined;
    const candidate = error;
    if (candidate.kind === 'timeout')
        return 'timeout';
    if (candidate.kind === 'cancelled')
        return 'cancel';
    if (candidate.kind === 'network')
        return 'network';
    if (candidate.kind === 'invalid-response')
        return 'invalid-response';
    if (candidate.kind === 'http') {
        if (candidate.status === 429)
            return '429';
        if (candidate.status !== undefined && candidate.status >= 500)
            return '5xx';
        return 'http';
    }
    return undefined;
}
export function executionCallId(exec) {
    return typeof exec.callId === 'string' ? exec.callId : undefined;
}
//# sourceMappingURL=token-optimization.js.map