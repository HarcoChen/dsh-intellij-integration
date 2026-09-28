import { randomUUID } from "node:crypto";
import { FixExecutor } from "./fixExecutor";
import { RecoveryLedgerCorruptError, RecoveryLedgerStore } from "./ledger";
import { HealthOracle } from "./healthOracle";
import { VariantEngine } from "./variantEngine";
import type {
    Attribution,
    CandidateFix,
    CompositionDescriptor,
    CompositionVariant,
    FailureClass,
    HealthEvidence,
    RecoveryBudget,
    RecoveryOutcome,
    RecoveryStatusView,
} from "./types";

const SAFE_MAX_BOOTS = 8;

// Environment failures cannot establish bundle attribution.
const TERMINAL_FAILURE_CLASSES = new Set<FailureClass>(["auth", "sandbox-build", "launcher"]);

export interface RecoverySessionOptions {
    maxBoots?: number;
    allowBundleIsolation?: () => boolean;
    oracle: HealthOracle;
    variants?: VariantEngine;
    ledger: RecoveryLedgerStore;
    fixes: FixExecutor;
    onStatus?: (status: RecoveryStatusView) => void;
    onLog?: (message: string) => void;
}

export class RecoverySession {
    private readonly maxBoots: number;
    private readonly variants: VariantEngine;
    private running: Promise<RecoveryOutcome> | undefined;
    private controller: AbortController | undefined;
    private sessionId: string | undefined;
    private status: RecoveryStatusView | undefined;
    private attribution: Attribution | undefined;

    public constructor(private readonly options: RecoverySessionOptions) {
        this.maxBoots = Math.max(1, Math.min(SAFE_MAX_BOOTS, options.maxBoots ?? SAFE_MAX_BOOTS));
        this.variants = options.variants ?? new VariantEngine();
    }

    public getStatus(): RecoveryStatusView | undefined {
        return this.status ? { ...this.status } : undefined;
    }

    public getSessionId(): string | undefined {
        return this.sessionId;
    }

    public async reconcileHealthyStart(composition: CompositionDescriptor): Promise<void> {
        if (this.running || this.sessionId) return;
        const loaded = await this.options.ledger.read();
        if (loaded.corrupt || !loaded.state.activeSessionId) return;
        const session = loaded.state.sessions.find(item =>
            item.id === loaded.state.activeSessionId && !item.clean,
        );
        if (!session) return;
        const entries = loaded.state.entries.filter(entry => entry.sessionId === session.id);
        if (entries.some(entry => entry.status === "planned")) return;
        try {
            await this.options.ledger.acquireLease();
        } catch {
            return;
        }
        try {
            const current = await this.options.ledger.read();
            if (current.corrupt || current.state.activeSessionId !== session.id) return;
            this.sessionId = session.id;
            this.attribution = session.attribution;
            await this.options.ledger.finishSession(session.id, "recovered", {
                composition,
                attribution: session.attribution,
            });
            this.publish({
                sessionId: session.id,
                phase: "recovered",
                usedBoots: session.budget.usedBoots,
                maxBoots: session.budget.maxBoots,
                summary: "Runtime started successfully; interrupted recovery was closed.",
                canRestore: entries.some(entry =>
                    entry.status === "applied" || entry.status === "verified",
                ),
            });
        } finally {
            this.sessionId = undefined;
            await this.options.ledger.releaseLease().catch(() => undefined);
        }
    }

    public cancel(): void {
        this.controller?.abort(new Error("Recovery was cancelled by the user"));
    }

    public recover(
        composition: CompositionDescriptor,
        failureMessage: string,
        signal?: AbortSignal,
    ): Promise<RecoveryOutcome> {
        if (this.running) return this.running;
        this.sessionId = undefined;
        this.attribution = undefined;
        const controller = new AbortController();
        this.controller = controller;
        const relay = (): void => controller.abort(signal?.reason);
        signal?.addEventListener("abort", relay, { once: true });
        if (signal?.aborted) relay();
        let retainLease = false;
        this.running = this.run(composition, failureMessage, controller.signal)
            .catch((error) => this.sessionId && controller.signal.aborted
                ? this.cancelled(this.sessionId)
                : this.unrecoverable(this.sessionId ?? "ledger-unavailable", String(error)))
            .then((outcome) => {
                retainLease = outcome.status === "retry" || outcome.status === "candidate";
                return outcome;
            })
            .finally(() => {
                signal?.removeEventListener("abort", relay);
                if (this.controller === controller) this.controller = undefined;
                this.running = undefined;
                if (!retainLease) {
                    return this.options.ledger.releaseLease().catch((error) =>
                        this.options.onLog?.(`Failed to release recovery lease: ${String(error)}`));
                }
            });
        return this.running;
    }

    public confirm(composition: CompositionDescriptor, attribution = this.attribution): Promise<void> {
        return this.finish("recovered", attribution?.humanSummary ?? "Runtime recovered", composition, attribution);
    }

    public fail(message: string): Promise<void> {
        return this.finish("unrecoverable", message);
    }

    private async finish(
        phase: "recovered" | "unrecoverable",
        summary: string,
        composition?: CompositionDescriptor,
        attribution = this.attribution,
    ): Promise<void> {
        const sessionId = this.sessionId;
        if (!sessionId) return;
        try {
            await this.options.ledger.finishSession(sessionId, phase, {
                composition, attribution, error: phase === "unrecoverable" ? summary : undefined,
            });
            this.publishTerminal(sessionId, phase, summary);
        } finally {
            this.sessionId = undefined;
            await this.options.ledger.releaseLease().catch(() => undefined);
        }
    }

    private async run(
        composition: CompositionDescriptor,
        failureMessage: string,
        signal: AbortSignal,
    ): Promise<RecoveryOutcome> {
        const budget = this.createBudget();
        // Plan before beginSession: the ledger stores a clone of the budget it is given,
        // so the planner's skipped-variant accounting must be complete by then to persist.
        const variants = this.variants.plan(composition, budget);
        try {
            await this.options.ledger.acquireLease();
        } catch (error) {
            return this.unrecoverable("recovery-busy", error instanceof Error ? error.message : String(error));
        }
        let session;
        try {
            session = await this.options.ledger.beginSession(composition, budget, failureMessage);
        } catch (error) {
            if (error instanceof RecoveryLedgerCorruptError) {
                this.options.onLog?.(`Recovery ledger is corrupt; automatic recovery stopped: ${error.message}`);
                return { status: "unrecoverable", sessionId: "ledger-corrupt", message: error.message };
            }
            return this.unrecoverable("ledger-unavailable", error instanceof Error ? error.message : String(error));
        }
        this.sessionId = session.id;
        this.publish({
            sessionId: session.id,
            phase: "detected",
            usedBoots: session.budget.usedBoots,
            maxBoots: this.maxBoots,
            summary: failureMessage,
            canRestore: false,
        });
        const evidence: HealthEvidence[] = [];
        let usedBoots = session.budget.usedBoots;
        const maxBoots = Math.min(this.maxBoots, session.budget.maxBoots);
        const probe = async (variant: CompositionVariant, base = composition): Promise<HealthEvidence | undefined> => {
            signal.throwIfAborted();
            if (usedBoots >= maxBoots) return undefined;
            await this.options.ledger.reserveBoot(session.id);
            this.publish({
                sessionId: session.id, phase: "searching", usedBoots: ++usedBoots, maxBoots,
                currentVariant: variant.id, summary: variant.assumption, canRestore: false,
            });
            const result = await this.options.oracle.evaluate(base, variant, { sessionId: session.id, signal });
            evidence.push(result);
            await this.options.ledger.appendEvidence(session.id, result);
            signal.throwIfAborted();
            if (result.cleanup.deferredCleanup) {
                throw new Error("Recovery stopped because process or sandbox cleanup could not be verified.");
            }
            if (TERMINAL_FAILURE_CLASSES.has(result.failureClass)) {
                throw new Error(`Recovery stopped: probe failed as ${result.failureClass}.`);
            }
            return result;
        };
        for (const variant of variants) {
            let result = await probe(variant);
            if (!result) break;
            if (variant.kind === "v1-reproduce" && result.verdict !== "healthy") {
                result = await probe({ ...variant, id: `${variant.id}-retry` });
            }
            if (!result || result.verdict !== "healthy") continue;
            const attribution = this.variants.explain(
                { ...variant, id: result.variantId }, evidence,
            );
            if (variant.kind === "v1-reproduce") {
                this.attribution = attribution;
                return {
                    status: "retry", sessionId: session.id, composition, attribution,
                    message: "The original composition passed the recovery health probe.",
                };
            }
            const candidate = variant.candidateFix;
            if (!candidate) continue;
            if (candidate.kind === "disable-profile-bundles") {
                if (this.options.allowBundleIsolation?.() === false) continue;
                // Reserve both the re-add confirmation and the post-write probe.
                if (usedBoots + 2 > maxBoots) continue;
                const readd = this.variants.readdConfirmation(composition, candidate.targetIds);
                const original = evidence.find(item => item.verdict === "process-error");
                const confirmation = await probe(readd, readd.composition);
                if (!confirmation || confirmation.verdict !== "process-error" ||
                    !original || confirmation.failureClass !== original.failureClass) {
                    this.options.onLog?.(`Bundle attribution was not confirmed: ${candidate.targetIds.join(", ")}`);
                    continue;
                }
            }
            if (usedBoots >= maxBoots) continue;
            const fix: CandidateFix = {
                ...candidate,
                id: `${candidate.id}-${randomUUID()}`,
                evidenceBootIds: evidence.map(item => item.bootId),
            };
            let applied = false;
            try {
                signal.throwIfAborted();
                await this.options.ledger.planFix(session.id, fix, composition.compositionHash);
                const changed = await this.options.fixes.apply(fix, composition);
                applied = true;
                await this.options.ledger.markFixApplied(session.id, fix.id, changed.compositionHash);
                const verified = await probe({
                    id: `${fix.id}-verify`, kind: variant.kind,
                    parentHash: changed.compositionHash,
                    assumption: "Verify the applied recovery change.",
                    composition: changed,
                }, changed);
                if (verified?.verdict !== "healthy") throw new Error("The applied recovery change did not pass its health probe.");
                this.attribution = attribution;
                this.publish({
                    sessionId: session.id, phase: "fix-applied", usedBoots, maxBoots,
                    currentVariant: variant.id,
                    summary: attribution?.humanSummary ?? fix.reason, canRestore: true,
                });
                return {
                    status: "candidate", sessionId: session.id, composition: changed,
                    fix, attribution, message: fix.reason,
                };
            } catch (error) {
                // Roll back only this attempt, retaining the lease and older verified fixes.
                if (applied) await this.options.fixes.rollback(fix.id);
                else await this.options.ledger.markFixConflict(session.id, fix.id, String(error));
                this.options.onLog?.(`Recovery fix rejected: ${String(error)}`);
                if (applied) throw error;
            }
        }
        const message = signal.aborted
            ? "Recovery was cancelled"
            : `Recovery found no verified fix after ${usedBoots} of ${maxBoots} allowed boots.`;
        return signal.aborted ? this.cancelled(session.id) : this.unrecoverable(session.id, message);
    }

    private createBudget(): RecoveryBudget {
        return {
            maxBoots: this.maxBoots,
            usedBoots: 0,
            // Bundle reservations depend on the composition and are filled by the planner.
            reserved: { v1: 1, v3: 0, v4: 1, confirmation: 1 },
            skipped: [],
        };
    }

    private async cancelled(sessionId: string): Promise<RecoveryOutcome> {
        await this.options.ledger.finishSession(sessionId, "cancelled", {
            attribution: this.attribution,
            error: "Recovery was cancelled",
        });
        this.publishTerminal(sessionId, "cancelled", "Recovery was cancelled");
        return { status: "cancelled", sessionId, message: "Recovery was cancelled" };
    }

    private async unrecoverable(sessionId: string, message: string): Promise<RecoveryOutcome> {
        if (
            sessionId !== "ledger-unavailable" &&
            sessionId !== "composition-unavailable" &&
            sessionId !== "recovery-busy"
        ) {
            try {
                await this.options.ledger.finishSession(sessionId, "unrecoverable", {
                    attribution: this.attribution,
                    error: message,
                });
            } catch (error) {
                this.options.onLog?.(`Failed to finalize recovery ledger: ${String(error)}`);
            }
        }
        this.publishTerminal(sessionId, "unrecoverable", message);
        return {
            status: "unrecoverable",
            sessionId,
            ...(this.attribution === undefined ? {} : { attribution: this.attribution }),
            message,
        };
    }

    private publishTerminal(
        sessionId: string,
        phase: "recovered" | "unrecoverable" | "cancelled",
        summary: string,
    ): void {
        this.publish({
            sessionId, phase, summary, canRestore: true,
            usedBoots: this.status?.usedBoots ?? 0,
            maxBoots: this.maxBoots,
        });
    }

    private publish(status: RecoveryStatusView): void {
        this.status = { ...status };
        this.options.onStatus?.({ ...status });
    }
}
