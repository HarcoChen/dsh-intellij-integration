import { randomUUID } from 'node:crypto';
import { noul, noulProbability } from '../jev-client.js';
export function looksLikeCompletionClaim(text) {
    return /(?:\b(?:done|completed?|finished|implemented|fixed|resolved|ready)\b|成功|完成|已修复|已实现|可以了)/i.test(text);
}
function completionNotice(turn, evidenceCount) {
    return {
        id: randomUUID(),
        role: 'user',
        source: {
            kind: 'plugin',
            plugin: 'dsh-jev-integration',
            form: 'notice',
            summary: 'Jev requested completion evidence',
            policy: 'done-evidence-gate',
            turn,
            evidenceCount,
        },
        content: [{
                type: 'text',
                text: 'Before claiming completion, verify the result with concrete evidence. '
                    + 'Run the smallest relevant check (build, typecheck, test, or targeted inspection), '
                    + 'report what it actually proved, and correct any remaining failure.',
            }],
    };
}
export class DoneEvidencePolicy {
    lastIntervention = new WeakMap();
    reset(agent) {
        if (agent && typeof agent === 'object')
            this.lastIntervention.delete(agent);
    }
    async inspect(agent, trace, config, call, signal) {
        if (!config.enabled || !agent || typeof agent !== 'object' || !trace || !trace.claim || !looksLikeCompletionClaim(trace.claim))
            return { action: 'skipped' };
        const last = this.lastIntervention.get(agent);
        if (last !== undefined && trace.turn - last <= config.cooldownTurns)
            return { action: 'skipped' };
        const started = Date.now();
        try {
            const response = await call({
                state: {
                    turn: trace.turn,
                    claim: trace.claim.slice(0, config.maxClaimChars),
                    userPrompt: trace.userPrompt?.slice(0, 1_000),
                    evidence: trace.evidence.slice(-8).map(item => ({ text: item.text.slice(0, 1_000), isError: item.isError === true })),
                    evidenceCount: trace.evidence.length,
                    successfulEvidenceCount: trace.evidence.filter(item => item.isError !== true).length,
                },
                questions: {
                    claims_completion: noul('Does the assistant message make a concrete claim that the requested work is complete?'),
                    evidence_sufficient: noul('Does the available tool evidence substantiate the assistant completion claim, rather than merely describe an intention or assumption?'),
                },
            }, signal === undefined ? { timeoutMs: config.requestTimeoutMs } : { timeoutMs: config.requestTimeoutMs, signal });
            const completion = noulProbability(response.answers.claims_completion);
            const evidence = noulProbability(response.answers.evidence_sufficient);
            if (completion === undefined || evidence === undefined)
                return { action: 'uncertain', latencyMs: Date.now() - started };
            const successfulEvidence = trace.evidence.filter(item => item.isError !== true).length;
            if (completion >= config.blockThreshold && (evidence < config.blockThreshold || successfulEvidence < config.minEvidenceItems)) {
                this.lastIntervention.set(agent, trace.turn);
                return {
                    action: 'intervene',
                    context: completionNotice(trace.turn, trace.evidence.length),
                    latencyMs: Date.now() - started,
                };
            }
            return { action: 'pass', latencyMs: Date.now() - started };
        }
        catch {
            // A completion check is advisory. An unavailable Jev service must not
            // turn a normal DSH completion into a blocked turn.
            return { action: 'uncertain' };
        }
    }
}
//# sourceMappingURL=done-gate.js.map