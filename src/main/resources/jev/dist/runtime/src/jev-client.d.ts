import { type IntegrationConfig } from '../../protocol/src/index.js';
export type JevQuestion = {
    type: 'noul';
    instructions: string;
} | {
    type: 'choice';
    instructions: string;
    criteria: Record<string, string | null>;
} | {
    type: 'score';
    instructions: string;
    criteria: string[];
};
export declare function noul(instructions: string): JevQuestion;
export declare function choice(instructions: string, criteria: Record<string, string | null>): JevQuestion;
export declare function score(instructions: string, criteria: string[]): JevQuestion;
export interface JevQuestionResult {
    type: 'noul' | 'choice' | 'score';
    noul?: number;
    probability?: number;
    score?: number;
    choice?: string;
    probabilities?: Record<string, number>;
    confidence?: number;
    unknown?: boolean;
    [key: string]: unknown;
}
export interface JevCallRequest {
    state: string | Record<string, unknown>;
    questions: Record<string, JevQuestion>;
    model?: string;
}
export interface JevCallResult {
    answers: Record<string, JevQuestionResult>;
    latencyMs: number;
    requestBytes: number;
    responseBytes: number;
}
export type JevClientErrorKind = 'missing-api-key' | 'timeout' | 'cancelled' | 'http' | 'invalid-response' | 'network';
export declare class JevClientError extends Error {
    readonly kind: JevClientErrorKind;
    readonly status?: number | undefined;
    constructor(kind: JevClientErrorKind, message: string, status?: number | undefined);
}
export declare function noulProbability(result: unknown): number | undefined;
export declare function topBucketProbability(result: unknown): number | undefined;
export declare function scoreConfidence(result: unknown): number | undefined;
export declare function choiceConfidence(result: unknown): number | undefined;
export declare class JevClient {
    private readonly config;
    private readonly explicitApiKey?;
    private readonly fetchImpl;
    private resolvedApiKey;
    private apiKeyLoaded;
    constructor(config: Pick<IntegrationConfig, 'baseUrl' | 'model' | 'timeoutMs'>, explicitApiKey?: string | undefined, fetchImpl?: typeof globalThis.fetch);
    get baseUrl(): string;
    get model(): string;
    hasApiKey(): Promise<boolean>;
    private loadApiKey;
    systemOne(request: JevCallRequest, options?: {
        timeoutMs?: number;
        signal?: AbortSignal;
    }): Promise<JevCallResult>;
}
//# sourceMappingURL=jev-client.d.ts.map