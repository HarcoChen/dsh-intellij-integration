import { score, scoreConfidence } from '../jev-client.js';
function bounded(value, max) {
    if (!value || max <= 0)
        return '';
    return value.length > max ? value.slice(0, max) : value;
}
function nameMatch(name, intent) {
    const words = name.toLowerCase().split(/[^a-z0-9一-鿿]+/).filter(Boolean);
    const lower = intent.toLowerCase();
    return words.some(word => word.length >= 3 && lower.includes(word));
}
/**
 * Selects skill advice without replacing the user's prompt or deleting skill
 * definitions. The actual skill body is fetched only after a confident rank;
 * the returned value is an additive context for system-prompt assembly.
 */
export class JevSkillRouterPolicy {
    async route(intent, options) {
        const started = Date.now();
        const empty = (outcome) => ({
            advice: [], outcome, adviceCount: 0, adviceChars: 0, latencyMs: Date.now() - started,
        });
        const config = options.config;
        if (!config.enabled || intent.trim().length < config.minIntentChars)
            return empty('skipped');
        let snapshot;
        try {
            const serviceOptions = {
                ...(options.cwd !== undefined ? { cwd: options.cwd } : {}),
                ...(options.scope !== undefined ? { scope: options.scope } : {}),
                ...(options.signal !== undefined ? { signal: options.signal } : {}),
            };
            if (typeof options.service.snapshot === 'function') {
                snapshot = await options.service.snapshot(serviceOptions);
            }
            else if (typeof options.service.list === 'function') {
                snapshot = { skills: await options.service.list(serviceOptions), complete: true };
            }
            else {
                return empty('skipped');
            }
        }
        catch {
            return empty('fail-open');
        }
        if (!snapshot || !Array.isArray(snapshot.skills))
            return empty('fail-open');
        if (!snapshot.complete)
            return empty('skipped');
        const candidates = snapshot.skills
            .filter(skill => skill.invocation?.modelInvocable !== false)
            .slice(0, config.maxCandidates);
        if (candidates.length < config.minCandidates)
            return empty('skipped');
        const questions = {};
        candidates.forEach((skill, index) => {
            questions[`skill_${index}`] = score(`How relevant is this skill to the current user request?\nSkill: ${skill.name}\nDescription: ${bounded(skill.description, 1_000)}\nWhen to use: ${bounded(skill.whenToUse, 1_000)}\nRequest: ${bounded(intent, 4_000)}`, ['not relevant', 'possibly useful', 'directly useful']);
        });
        let response;
        try {
            response = await options.call({
                state: { intent: bounded(intent, 4_000), candidateCount: candidates.length },
                questions,
            }, options.signal === undefined ? { timeoutMs: config.requestTimeoutMs } : { timeoutMs: config.requestTimeoutMs, signal: options.signal });
        }
        catch {
            return empty('fail-open');
        }
        const ranked = [];
        let maximumConfidence = 0;
        candidates.forEach((skill, index) => {
            const answer = response.answers[`skill_${index}`];
            if (!answer || answer.unknown || answer.type !== 'score' || typeof answer.score !== 'number')
                return;
            const confidence = scoreConfidence(answer) ?? 0;
            maximumConfidence = Math.max(maximumConfidence, confidence);
            const boosted = answer.score + (nameMatch(skill.name, intent) ? config.nameMatchBoost : 0);
            ranked.push({ skill, score: boosted, confidence });
        });
        if (ranked.length === 0 || maximumConfidence < config.minConfidence)
            return empty('low-confidence');
        ranked.sort((left, right) => right.score - left.score || right.confidence - left.confidence || left.skill.name.localeCompare(right.skill.name));
        const selected = ranked.filter(item => item.score >= config.minScore && item.confidence >= config.minConfidence).slice(0, config.maxSkills);
        if (selected.length === 0)
            return empty('low-confidence');
        const advice = [];
        let remaining = config.maxAdviceChars;
        for (const item of selected) {
            let detail;
            try {
                const serviceOptions = {
                    ...(options.cwd !== undefined ? { cwd: options.cwd } : {}),
                    ...(options.scope !== undefined ? { scope: options.scope } : {}),
                    ...(options.signal !== undefined ? { signal: options.signal } : {}),
                };
                detail = typeof options.service.get === 'function'
                    ? await options.service.get(item.skill.name, serviceOptions)
                    : undefined;
            }
            catch {
                return empty('fail-open');
            }
            const body = bounded(detail?.content, Math.max(0, remaining - 80));
            const description = bounded(detail?.description ?? item.skill.description, Math.max(0, remaining - body.length - 80));
            const text = `Relevant skill advice (${item.skill.name}):\n${description}${body ? `\n${body}` : ''}`.trim();
            if (!text || text.length > remaining)
                continue;
            advice.push(text);
            remaining -= text.length;
            if (remaining <= 0)
                break;
        }
        const adviceChars = advice.reduce((total, item) => total + item.length, 0);
        return { advice, outcome: 'call', adviceCount: advice.length, adviceChars, latencyMs: Date.now() - started };
    }
}
//# sourceMappingURL=skill-router.js.map