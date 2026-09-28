import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readRuntimeLock } from './upstream/runtimeLock';
import { inspectLegacyRuntime, stopLegacyRuntime } from './upstream/runtimeMigration';
import { offerLocalRuntimeUpgrade } from './upstream/localRuntimeUpgrade';
import { acceptReply, prompt } from './upstream/localUi';
const exec = promisify(execFile);
import { createInterface } from 'node:readline';
import { dirname, join } from 'node:path';
import { readFile } from 'node:fs/promises';
import { buildComposition } from './upstream/recovery/composition';
import { RecoverySession } from './upstream/recovery/recoverySession';
import { RecoveryLedgerStore } from './upstream/recovery/ledger';
import { RecoveryDiagnostics, redactRecoveryText } from './upstream/recovery/diagnostics';
import { FixExecutor } from './upstream/recovery/fixExecutor';
import { HealthOracle } from './upstream/recovery/healthOracle';
import { SandboxManager } from './upstream/recovery/sandbox';
import { spawnOwnedRuntime, terminateOwnedRuntime } from './upstream/runtimeProcess';
import { RemoteUnaryClient } from './upstream/remote/unaryClient';
import type { CompositionDescriptor } from './upstream/recovery/types';
// The IDE owns this process and sends control messages over its private stdin pipe.
const input = createInterface({ input: process.stdin });
input.on('line', line => { try {
    acceptReply(JSON.parse(line));
}
catch { } });
const cancellation = new AbortController();
let child: ReturnType<typeof spawnOwnedRuntime> | undefined;
let recovery: RecoverySession | undefined;
let closing = false;
const emit = (event: string, value: unknown) => process.stdout.write('\nDSH_INTELLIJ_HELPER ' + JSON.stringify({ event, value }) + '\n');
const log = (message: string) => process.stdout.write(redactRecoveryText(message) + '\n');
const pause = (ms: number) => new Promise<void>((resolve, reject) => {
    cancellation.signal.throwIfAborted();
    const abort = () => { clearTimeout(timer); reject(cancellation.signal.reason); };
    const timer = setTimeout(() => { cancellation.signal.removeEventListener('abort', abort); resolve(); }, ms);
    cancellation.signal.addEventListener('abort', abort, { once: true });
});
async function shutdown() {
    if (closing)
        return;
    closing = true;
    cancellation.abort(new Error('Runtime stopped'));
    recovery?.cancel();
    if (child)
        await terminateOwnedRuntime(child);
    if (recovery?.getSessionId())
        await recovery.fail('Recovery interrupted by a lifecycle action.');
    emit('stopped', {});
    process.exit(0);
}
input.once('close', () => void shutdown().catch(error => { log(String(error)); process.exit(1); }));
process.once('SIGTERM', () => void shutdown().catch(() => process.exit(1)));
process.once('SIGINT', () => void shutdown().catch(() => process.exit(1)));
input.once('line', line => void main(JSON.parse(line)).catch(async (error) => { if (child)
    await terminateOwnedRuntime(child).catch(() => { }); if (recovery?.getSessionId())
    await recovery.fail(String(error)).catch(() => { }); emit('error', { message: redactRecoveryText(String(error)) }); process.exit(cancellation.signal.aborted ? 0 : 1); }));
async function main(config: Record<string, any>) {
    const ledger = new RecoveryLedgerStore(config.storage);
    const diagnostics = new RecoveryDiagnostics(config.storage);
    const fixes = new FixExecutor(ledger, { appendLine: log });
    recovery = new RecoverySession({ ledger, fixes, oracle: new HealthOracle(new SandboxManager(), { diagnostics, onOutput: log }), maxBoots: 8, allowBundleIsolation: () => config.isolate, onStatus: value => emit('recovery', value), onLog: log });
    if (config.operation === 'orphan') {
        const snapshot = await readRuntimeLock(config.lockPath);
        const identity = snapshot && await inspectLegacyRuntime(snapshot);
        if (!snapshot || !identity) {
            emit('orphan-result', { stopped: false });
            process.exit(0);
        }
        const approved = await prompt(`Restart abandoned DSH Runtime at ${identity.baseUrl} (PID ${identity.pid})?`, ['Restart', 'Cancel'], 'Its editor has exited. The identified Runtime will be stopped, forcibly if necessary, before starting a compatible version. Session files are preserved.');
        if (approved === 'Restart') {
            await stopLegacyRuntime(snapshot, identity, config.sharedLockPath, cancellation.signal);
            emit('orphan-result', { stopped: true });
        }
        else
            emit('orphan-result', { stopped: false });
        process.exit(0);
    }
    if (config.operation === 'upgrade') {
        const probe = async () => {
            try {
                let command = config.command;
                let args = ['--version'];
                if (process.platform === 'win32' && /\.cmd$/i.test(command)) {
                    const root = join(dirname(command), 'node_modules', '@deepseek-ai', 'dsh');
                    const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
                    const bin = typeof manifest.bin === 'string' ? manifest.bin : manifest.bin?.dsh;
                    if (manifest.name !== '@deepseek-ai/dsh' || typeof bin !== 'string')
                        return undefined;
                    command = process.execPath;
                    args = [join(root, bin), '--version'];
                }
                const { stdout } = await exec(command, args, { timeout: 15000 });
                return stdout.trim().replace(/^(?:dsh\s+)?v/, '');
            }
            catch {
                return undefined;
            }
        };
        let prefix: string | undefined;
        try {
            const npm = config.npm || 'npm';
            const command = process.platform === 'win32' ? process.execPath : npm;
            const args = process.platform === 'win32' ? [join(dirname(npm), 'node_modules', 'npm', 'bin', 'npm-cli.js'), 'prefix', '-g'] : ['prefix', '-g'];
            prefix = (await exec(command, args, { timeout: 15000 })).stdout.trim();
        }
        catch { }
        const version = await offerLocalRuntimeUpgrade({ command: config.command, actual: config.actual, target: config.target, npm: config.npm || 'npm', node: process.execPath, prefix, registry: config.registry, timeout: 120000, signal: cancellation.signal, probe, log });
        emit('upgrade-result', { version });
        process.exit(0);
    }
    if (config.operation) {
        if (config.operation === 'restore')
            emit('restored', { restored: await fixes.restore() });
        else {
            const state = await ledger.read();
            emit('diagnostics', { path: await diagnostics.export(state.state, undefined, state.corrupt) });
        }
        process.exit(0);
    }
    if (!config.command || !Array.isArray(config.args) || !config.args.every(arg => typeof arg === 'string'))
        throw new Error('Invalid IDE launch configuration');
    let composition: CompositionDescriptor;
    input.on('line', line => {
        void (async () => {
            const message = JSON.parse(line);
            if (message.type === 'stop') {
                await shutdown();
                return;
            }
            if (message.type === 'cancel') {
                recovery?.cancel();
                cancellation.abort(new Error('Recovery cancelled'));
                if (child)
                    await terminateOwnedRuntime(child);
                return;
            }
            if (message.type === 'restore') {
                if (child && child.exitCode === null)
                    throw new Error('Stop the Runtime before restoring recovery changes.');
                const restored = await fixes.restore();
                emit('restored', { restored });
                return;
            }
            if (message.type === 'export') {
                const state = await ledger.read();
                const path = await diagnostics.export(state.state, composition, state.corrupt);
                emit('diagnostics', { path });
            }
        })().catch(error => emit('action-error', { message: redactRecoveryText(String(error)) }));
    });
    let args = await fixes.filterLaunchArgs(config.args);
    const packageIndex = args.findIndex(arg => /^@deepseek-ai\/dsh(?:@|$)/.test(arg));
    const prefix = packageIndex < 0 ? [] : args.slice(0, packageIndex + 1);
    let appArgs = packageIndex < 0 ? args : args.slice(packageIndex + 1);
    const compose = () => buildComposition({ command: config.command, resolvedPath: config.command, source: 'intellij', version: config.version, launcherArgs: prefix, appArgs, workspaceRoot: config.cwd, extensionOverlayPaths: config.overlays ?? [] });
    composition = await compose();
    await recovery.reconcileHealthyStart(composition);
    let failures = 0;
    for (;;) {
        cancellation.signal.throwIfAborted();
        const started = Date.now();
        child = spawnOwnedRuntime(config.command, [...prefix, ...appArgs], { cwd: config.cwd, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
        const current = child;
        emit('process', { pid: current.pid, version: config.version, ...(process.platform === 'win32' ? {} : { group: current.pid }) });
        let output = '';
        let launchError: Error | undefined;
        let exit: {
            code: number | null;
            signal: string | null;
        } | undefined;
        const completion = new Promise<void>(resolve => { current.once('error', error => { launchError = error; resolve(); }); current.once('exit', (code, signal) => { exit = { code, signal }; resolve(); }); });
        const collect = (data: Buffer) => { output = (output + data.toString('utf8')).slice(-100000); process.stdout.write(data); };
        current.stdout!.on('data', collect);
        current.stderr!.on('data', collect);
        let ready = false;
        for (let tries = 0; tries < 600 && !exit && !launchError; tries++) {
            cancellation.signal.throwIfAborted();
            const launch = output.match(/http:\/\/(?:127\.0\.0\.1|localhost|\[::1\]):\d+\/\?token=[A-Za-z0-9_-]+/)?.[0];
            if (launch) {
                try {
                    const response = await fetch(launch, { redirect: 'manual', signal: AbortSignal.timeout(1500) });
                    const cookie = response.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
                    const base = new URL(launch).origin;
                    const unary = new RemoteUnaryClient({ baseUrl: base, requestHeaders: (): Record<string, string> => cookie ? { Cookie: cookie } : {}, timeoutMs: 1500 });
                    await unary.call('session/list', { _request: {} });
                    cancellation.signal.throwIfAborted();
                    emit('ready', { url: base, launchUrl: launch });
                    ready = true;
                    if (recovery.getSessionId())
                        await recovery.confirm(composition);
                    break;
                }
                catch (error) {
                    if (cancellation.signal.aborted)
                        throw error;
                }
            }
            await pause(100);
        }
        if (ready)
            await completion;
        if (!exit && !launchError)
            await terminateOwnedRuntime(current);
        await terminateOwnedRuntime(current);
        child = undefined;
        cancellation.signal.throwIfAborted();
        const failure = launchError ? String(launchError) : `Runtime exited (${exit?.code ?? 'timeout'}). ${redactRecoveryText(output.slice(-3000))}`;
        if (!config.enabled)
            throw new Error(failure);
        if (ready && Date.now() - started > 60000)
            failures = 0;
        if (failures < 3) {
            const delay = [1000, 5000, 15000][failures++];
            emit('recovery', { phase: 'retrying', summary: `Runtime exited; retrying in ${delay / 1000}s`, usedBoots: failures, maxBoots: 8, canRestore: false });
            await pause(delay);
            continue;
        }
        const outcome = await recovery.recover(composition, failure, cancellation.signal);
        if (outcome.status !== 'candidate' && outcome.status !== 'retry')
            throw new Error(outcome.message);
        if (!outcome.composition)
            throw new Error("Recovery did not return a composition");
        composition = outcome.composition;
        appArgs = [...composition.appArgs];
        appArgs = await fixes.filterLaunchArgs(appArgs);
        // A candidate gets one production boot; failure does not silently spend a fresh budget.
        config.enabled = false;
    }
}
