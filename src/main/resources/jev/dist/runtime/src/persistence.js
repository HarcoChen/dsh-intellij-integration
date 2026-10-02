import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
function homeDshDir() {
    return process.env.DSH_HOME ?? join(homedir(), '.dsh');
}
export function defaultPersistencePaths(override) {
    const rootDir = override ?? process.env.DSH_JEV_INTEGRATION_HOME ?? join(homeDshDir(), 'jev-integration');
    return {
        rootDir,
        configFile: join(rootDir, 'config.json'),
        statsFile: join(rootDir, 'stats.json'),
    };
}
function emptyConfig() {
    return { version: 1, global: {}, workspaces: {} };
}
function emptyStats() {
    return { version: 1, scopes: {} };
}
async function readJson(path, fallback) {
    try {
        const raw = await readFile(path, 'utf8');
        const text = typeof raw === 'string' ? raw : new TextDecoder().decode(raw);
        const value = JSON.parse(text);
        return value;
    }
    catch (error) {
        const code = error && typeof error === 'object' && 'code' in error ? error.code : undefined;
        if (code === 'ENOENT')
            return fallback;
        throw error;
    }
}
async function writeJsonAtomic(path, value) {
    await mkdir(dirname(path), { recursive: true });
    const temporary = `${path}.tmp-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
    await rename(temporary, path);
}
export class IntegrationPersistence {
    paths;
    constructor(rootDir) {
        this.paths = defaultPersistencePaths(rootDir);
    }
    async loadConfig() {
        const value = await readJson(this.paths.configFile, emptyConfig());
        if (!value || value.version !== 1 || typeof value.global !== 'object' || typeof value.workspaces !== 'object') {
            return emptyConfig();
        }
        return value;
    }
    async saveConfig(value) {
        await writeJsonAtomic(this.paths.configFile, value);
    }
    async loadStats() {
        const value = await readJson(this.paths.statsFile, emptyStats());
        if (!value || value.version !== 1 || typeof value.scopes !== 'object')
            return emptyStats();
        return value;
    }
    async saveStats(value) {
        await writeJsonAtomic(this.paths.statsFile, value);
    }
}
//# sourceMappingURL=persistence.js.map