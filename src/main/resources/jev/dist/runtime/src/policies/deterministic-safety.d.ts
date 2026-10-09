import type { CredentialClass, DeterministicSafetyGuardConfig } from '../../../protocol/src/index.js';
import type { ToolExecutionLike, ToolsServiceLike } from '../types.js';
export interface DeterministicSafetyMatch {
    category: 'destructive' | 'privilege' | 'credential';
    reason: string;
    ruleId: string;
    credentialClass?: CredentialClass;
}
export declare function classifyCredential(value: string): CredentialClass | undefined;
export declare function evaluateDeterministicSafety(execution: ToolExecutionLike, guardedTools: readonly string[], config: DeterministicSafetyGuardConfig): DeterministicSafetyMatch | undefined;
export declare function installDeterministicSafetyGuard(tools: ToolsServiceLike | undefined, guardedTools: readonly string[], config: DeterministicSafetyGuardConfig, onCheck?: (execution: ToolExecutionLike, match: DeterministicSafetyMatch | undefined) => void): (() => void) | undefined;
//# sourceMappingURL=deterministic-safety.d.ts.map