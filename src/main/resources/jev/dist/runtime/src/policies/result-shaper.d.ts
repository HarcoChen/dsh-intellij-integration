import type { ResultShaperConfig } from '../../../protocol/src/index.js';
import type { PolicyJevCall, PolicyToolExecution, ShapeOutcome } from './types.js';
export declare const DEFAULT_SHAPE_TOOLS: string[];
export declare const DROP_MARKER = "[... %d lines dropped by DSH Jev result shaper ...]";
export declare const KIND_CRITERIA: Record<string, string>;
export interface ContentBlockLike {
    type?: string;
    text?: string;
    [key: string]: unknown;
}
export declare function extractText(content: unknown): string | undefined;
export declare function replaceText(content: unknown, text: string): unknown;
export declare function lineShape(line: string): string;
export interface LineCluster {
    shape: string;
    count: number;
    sample: string;
}
export declare function clusterLines(text: string): LineCluster[];
export declare function looksRepetitive(text: string): boolean;
export declare class ResultShaperPolicy {
    private readonly budgets;
    reset(agent: unknown): void;
    private budgetFor;
    shouldConsider(exec: PolicyToolExecution, content: string, config: ResultShaperConfig): boolean;
    shape(content: string, toolName: string, exec: PolicyToolExecution, config: ResultShaperConfig, call: PolicyJevCall): Promise<ShapeOutcome | undefined>;
}
//# sourceMappingURL=result-shaper.d.ts.map