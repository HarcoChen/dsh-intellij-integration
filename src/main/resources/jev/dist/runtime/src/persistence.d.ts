import type { IntegrationConfigPatch, StatisticsSnapshot } from '../../protocol/src/index.js';
export interface PersistedConfigFile {
    version: 1;
    global: IntegrationConfigPatch;
    workspaces: Record<string, IntegrationConfigPatch>;
}
export interface PersistedStatsFile {
    version: 1;
    scopes: Record<string, StatisticsSnapshot>;
}
export interface PersistencePaths {
    rootDir: string;
    configFile: string;
    statsFile: string;
}
export declare function defaultPersistencePaths(override?: string): PersistencePaths;
export declare class IntegrationPersistence {
    readonly paths: PersistencePaths;
    constructor(rootDir?: string);
    loadConfig(): Promise<PersistedConfigFile>;
    saveConfig(value: PersistedConfigFile): Promise<void>;
    loadStats(): Promise<PersistedStatsFile>;
    saveStats(value: PersistedStatsFile): Promise<void>;
}
//# sourceMappingURL=persistence.d.ts.map