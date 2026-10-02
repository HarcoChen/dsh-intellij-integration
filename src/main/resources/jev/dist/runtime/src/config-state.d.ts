import { type ConfigSource, type IntegrationConfig, type IntegrationConfigPatch } from '../../protocol/src/index.js';
import type { IntegrationPersistence } from './persistence.js';
export interface ResolvedConfig {
    values: IntegrationConfig;
    source: ConfigSource;
    requestedEnabled: boolean;
    requestedEnabledSource: ConfigSource;
}
export declare function validateConfigPatch(value: unknown): {
    patch?: IntegrationConfigPatch;
    error?: string;
};
export declare class ConfigState {
    private readonly persistence;
    private persistedGlobal;
    private persistedWorkspaces;
    private readonly sessionOverrides;
    private readonly nextTurnOverrides;
    private readonly startupPatch;
    readonly ready: Promise<void>;
    constructor(persistence: IntegrationPersistence, startupPatch?: IntegrationConfigPatch);
    private load;
    private layers;
    resolve(workspaceId: string, sessionId?: string): ResolvedConfig;
    setPersisted(scope: 'global' | 'workspace', workspaceId: string, patch: IntegrationConfigPatch): Promise<void>;
    setEphemeral(scope: 'session' | 'next-turn', workspaceId: string, sessionId: string, patch: IntegrationConfigPatch): void;
    consumeNextTurn(workspaceId: string, sessionId: string): boolean;
    clearSession(workspaceId: string, sessionId: string): void;
}
//# sourceMappingURL=config-state.d.ts.map