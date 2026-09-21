// runtime-helper/main.ts
var import_node_child_process3 = require("node:child_process");
var import_node_util3 = require("node:util");

// runtime-helper/upstream/runtimeLock.ts
var import_node_net = require("node:net");
var import_node_crypto = require("node:crypto");
var import_promises = require("node:fs/promises");
var import_node_path = require("node:path");

// runtime-helper/upstream/localize.ts
function t(value, args = {}) {
  return value.replace(/\{([^}]+)\}/g, (match, key) => key in args ? String(args[key]) : match);
}

// runtime-helper/upstream/runtimeProcess.ts
var import_node_child_process = require("node:child_process");
var import_node_util = require("node:util");
var execFileAsync = (0, import_node_util.promisify)(import_node_child_process.execFile);
var owned = /* @__PURE__ */ new WeakMap();
var pause = (milliseconds) => new Promise((resolve6) => setTimeout(resolve6, milliseconds));
function spawnOwnedRuntime(command, args, options) {
  const group = process.platform !== "win32";
  const child2 = (0, import_node_child_process.spawn)(command, args, { ...options, detached: group });
  if (child2.pid !== void 0) owned.set(child2, { pid: child2.pid, ...group ? { group: child2.pid } : {} });
  return child2;
}
async function processRows(timeout = 250) {
  const { stdout } = await execFileAsync("ps", ["-axo", "pid=,pgid=,stat="], {
    timeout,
    maxBuffer: 4 * 1024 * 1024,
    windowsHide: true
  });
  return stdout.trim().split("\n").filter(Boolean).map((line) => {
    const [pid, group, state] = line.trim().split(/\s+/u);
    if (!/^\d+$/u.test(pid ?? "") || !/^\d+$/u.test(group ?? "") || !state) {
      throw new Error("Cannot verify the owned Runtime process group");
    }
    return { pid: Number(pid), group: Number(group), state };
  });
}
async function processGroupHasExited(groupPid, timeout = 250) {
  if (process.platform === "win32" || !Number.isSafeInteger(groupPid) || groupPid <= 1) return false;
  try {
    return !(await processRows(timeout)).some((row) => row.group === groupPid && !row.state.startsWith("Z"));
  } catch {
    return false;
  }
}
async function terminateGroup(child2, pid) {
  const shutdownDeadline = Date.now() + 2500;
  let rows;
  try {
    rows = await processRows();
  } catch (error) {
    if (!error.killed || Date.now() >= shutdownDeadline - 1950) throw error;
    rows = await processRows(Math.min(250, shutdownDeadline - Date.now() - 1750));
  }
  const leader = rows.find((row) => row.pid === pid);
  if (leader && leader.group !== pid) throw new Error("Runtime process group ownership changed; refusing shutdown");
  if (leader && !leader.state.startsWith("Z") && (child2.exitCode !== null || child2.signalCode !== null)) {
    throw new Error("Runtime launcher PID has been reused; refusing shutdown");
  }
  if (!rows.some((row) => row.group === pid && !row.state.startsWith("Z"))) return;
  const signal = (value) => {
    try {
      process.kill(-pid, value);
    } catch (error) {
      if (error.code !== "ESRCH") throw error;
    }
  };
  signal("SIGTERM");
  const gracefulDeadline = Date.now() + 1200;
  while (Date.now() < gracefulDeadline) {
    if (await processGroupHasExited(pid, Math.max(1, Math.min(250, gracefulDeadline - Date.now())))) return;
    await pause(Math.max(0, Math.min(50, gracefulDeadline - Date.now())));
  }
  signal("SIGKILL");
  const killDeadline = Math.min(shutdownDeadline, Date.now() + 500);
  do {
    if (await processGroupHasExited(pid, Math.max(1, Math.min(250, killDeadline - Date.now())))) return;
    await pause(Math.max(0, Math.min(50, killDeadline - Date.now())));
  } while (Date.now() < killDeadline);
  throw new Error(`Owned Runtime process group ${pid} did not exit within the shutdown deadline`);
}
var RuntimeDescendantOwnershipUnknownError = class extends Error {
  constructor() {
    super("Runtime launcher already exited; descendant ownership cannot be verified on Windows");
  }
};
async function terminateWindowsTree(child2, pid) {
  if (child2.exitCode !== null || child2.signalCode !== null) {
    throw new RuntimeDescendantOwnershipUnknownError();
  }
  await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
    timeout: 2e3,
    windowsHide: true
  });
  const deadline = Date.now() + 500;
  while (child2.exitCode === null && child2.signalCode === null && Date.now() < deadline) await pause(25);
  if (child2.exitCode === null && child2.signalCode === null) throw new Error("Owned Runtime tree shutdown was not confirmed");
}
function terminateOwnedRuntime(child2) {
  const ownership = owned.get(child2);
  if (!ownership) {
    if (child2.pid === void 0) return Promise.resolve();
    return Promise.reject(new Error("Refusing to terminate a Runtime not launched by this extension instance"));
  }
  if (!ownership.termination) {
    ownership.termination = ownership.group === void 0 ? terminateWindowsTree(child2, ownership.pid) : terminateGroup(child2, ownership.group);
    void ownership.termination.catch(() => {
      ownership.termination = void 0;
    });
  }
  return ownership.termination;
}

// runtime-helper/upstream/runtimeLock.ts
function validPid(value) {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}
function exactRuntimeVersion(value) {
  return typeof value === "string" && /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/u.test(value);
}
function processHasExited(pid) {
  try {
    process.kill(pid, 0);
    return false;
  } catch (error) {
    return error.code === "ESRCH";
  }
}
async function readRuntimeLock(path) {
  try {
    const stat2 = await (0, import_promises.lstat)(path);
    if (!stat2.isFile()) return { path, stat: stat2, contents: "" };
    const contents = await (0, import_promises.readFile)(path, "utf8");
    let record;
    try {
      const value = JSON.parse(contents);
      if (typeof value === "object" && value !== null && !Array.isArray(value)) {
        const raw = value;
        if (validPid(raw.pid) && (raw.runtimePid === void 0 || validPid(raw.runtimePid)) && (raw.runtimeProcess === void 0 || raw.runtimeProcess === "direct" || raw.runtimeProcess === "wrapper") && (raw.runtimeProcessGroup === void 0 || validPid(raw.runtimeProcessGroup) && raw.runtimeProcessGroup === raw.runtimePid) && (raw.runtimeVersion === void 0 || exactRuntimeVersion(raw.runtimeVersion)) && (raw.ownerId === void 0 || typeof raw.ownerId === "string" && raw.ownerId.length > 0) && (raw.compositionHash === void 0 || typeof raw.compositionHash === "string" && /^[a-f0-9]{64}$/u.test(raw.compositionHash)) && (raw.recoverySessionId === void 0 || typeof raw.recoverySessionId === "string" && raw.recoverySessionId.length > 0) && (raw.url === void 0 || typeof raw.url === "string") && (raw.launchUrl === void 0 || typeof raw.launchUrl === "string")) record = raw;
      }
    } catch {
    }
    return { path, stat: stat2, contents, record };
  } catch (error) {
    if (error.code === "ENOENT") return void 0;
    throw error;
  }
}
async function listenerHasExited(address) {
  let url;
  try {
    url = new URL(address);
  } catch {
    return false;
  }
  if (url.protocol !== "http:" || !url.port || url.username || url.password || !["127.0.0.1", "localhost", "0.0.0.0", "[::1]"].includes(url.hostname)) return false;
  if (url.hostname === "localhost") return false;
  const host = url.hostname === "[::1]" ? "::1" : url.hostname === "0.0.0.0" ? "127.0.0.1" : url.hostname;
  return new Promise((resolve6) => {
    const socket = (0, import_node_net.createConnection)({ host, port: Number(url.port) });
    const finish = (exited) => {
      socket.destroy();
      resolve6(exited);
    };
    socket.setTimeout(1e3, () => finish(false));
    socket.once("connect", () => finish(false));
    socket.once("error", (error) => finish(error.code === "ECONNREFUSED"));
  });
}
async function runtimeHasExited(record) {
  if (record.runtimeProcessGroup !== void 0) {
    if (!await processGroupHasExited(record.runtimeProcessGroup)) return false;
  } else if (record.runtimePid !== void 0 && !processHasExited(record.runtimePid)) return false;
  const address = record.url ?? record.launchUrl;
  if (!address) return record.runtimeProcessGroup !== void 0 || record.runtimePid !== void 0 && record.runtimeProcess === "direct";
  if (record.url && record.launchUrl) {
    try {
      if (new URL(record.url).origin !== new URL(record.launchUrl).origin) return false;
    } catch {
      return false;
    }
  }
  return listenerHasExited(address);
}
function sameRuntimeLockFile(left, right) {
  return left.dev === right.dev && left.ino === right.ino && right.isFile();
}
async function acquireMutationGate(path, deadline) {
  const canonical = (0, import_node_path.join)(await (0, import_promises.realpath)((0, import_node_path.dirname)(path)), (0, import_node_path.basename)(path));
  const key = process.platform === "win32" ? canonical.toLowerCase() : canonical;
  const port = 16384 + (0, import_node_crypto.createHash)("sha256").update(key).digest().readUInt32BE(0) % 16384;
  while (true) {
    const server = (0, import_node_net.createServer)((socket) => socket.destroy());
    try {
      await new Promise((resolve6, reject) => {
        server.once("error", reject);
        server.listen({ host: "127.0.0.1", port, exclusive: true }, () => {
          server.removeListener("error", reject);
          resolve6();
        });
      });
      return server;
    } catch (error) {
      server.close();
      if (error.code !== "EADDRINUSE") throw error;
      if (Date.now() >= deadline) throw new Error(t("DSH Runtime lock recovery is busy: {path}. Retry shortly.", { path }));
      await new Promise((resolve6) => setTimeout(resolve6, 25));
    }
  }
}
async function mutateRuntimeLock(path, action) {
  const gate = await acquireMutationGate(path, Date.now() + 2e3);
  try {
    return await mutateRuntimeLockWithGate(path, action);
  } finally {
    await new Promise((resolve6, reject) => gate.close((error) => error ? reject(error) : resolve6()));
  }
}
async function mutateRuntimeLockWithGate(path, action) {
  const guardPath = `${path}.mutation`;
  const deadline = Date.now() + 2e3;
  const contents = JSON.stringify({ pid: process.pid, createdAt: Date.now(), ownerId: (0, import_node_crypto.randomUUID)() });
  let guard;
  while (!guard) {
    try {
      guard = await publishMutationGuard(guardPath, contents);
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
      const abandoned = await readRuntimeLock(guardPath);
      if (!abandoned) continue;
      if (abandoned.record && processHasExited(abandoned.record.pid) && await removeRuntimeLock(abandoned)) continue;
      if (Date.now() >= deadline) {
        throw new Error(t("DSH Runtime lock mutation is busy or abandoned: {path}. Retry; if it persists, verify its owner has exited before manual cleanup.", { path: guardPath }));
      }
      await new Promise((resolve6) => setTimeout(resolve6, 25));
    }
  }
  try {
    return await action();
  } finally {
    const stat2 = await guard.stat();
    await guard.close();
    const current = await readRuntimeLock(guardPath);
    if (current && current.contents === contents && sameRuntimeLockFile(stat2, current.stat)) await removeRuntimeLock(current);
  }
}
async function publishMutationGuard(path, contents) {
  const staging = `${path}.${(0, import_node_crypto.randomUUID)()}.tmp`;
  const handle = await (0, import_promises.open)(staging, "wx", 384);
  let published = false;
  try {
    await handle.writeFile(contents, "utf8");
    await (0, import_promises.link)(staging, path);
    published = true;
    await (0, import_promises.unlink)(staging);
    return handle;
  } catch (error) {
    const stat2 = await handle.stat().finally(() => handle.close());
    if (published) {
      const current = await readRuntimeLock(path);
      if (current && sameRuntimeLockFile(stat2, current.stat) && current.contents === contents) {
        await removeRuntimeLock(current);
      }
    }
    await (0, import_promises.unlink)(staging).catch(() => void 0);
    throw error;
  }
}
async function removeRuntimeLock(snapshot) {
  const current = await readRuntimeLock(snapshot.path);
  if (!current || !sameRuntimeLockFile(snapshot.stat, current.stat) || current.contents !== snapshot.contents) return false;
  try {
    await (0, import_promises.unlink)(snapshot.path);
    return true;
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
}

// runtime-helper/upstream/runtimeMigration.ts
var import_node_child_process2 = require("node:child_process");
var import_node_util2 = require("node:util");

// runtime-helper/upstream/remote/errors.ts
var RemoteError = class _RemoteError extends Error {
  /** Structural marker preserved across duplicate bundles/realms. */
  isDSHRemoteError = true;
  code;
  details;
  endpoint;
  constructor(code, message, details = {}, endpoint) {
    super(message);
    this.name = "RemoteError";
    this.code = code;
    this.details = details;
    this.endpoint = endpoint;
  }
  static fromFailure(failure, endpoint) {
    return new _RemoteError(failure.code, failure.message, failure.details, endpoint);
  }
};
var RemoteHttpError = class extends Error {
  constructor(endpoint, status, message = httpMessage(endpoint, status)) {
    super(message);
    this.endpoint = endpoint;
    this.status = status;
    this.name = "RemoteHttpError";
  }
  get isAuthenticationFailure() {
    return this.status === 401 || this.status === 403;
  }
};
var RemoteProtocolError = class extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = "RemoteProtocolError";
  }
};
function isAbortError(error) {
  return error instanceof DOMException && error.name === "AbortError" || error instanceof Error && error.name === "AbortError";
}
function httpMessage(endpoint, status) {
  if (status === 401) return `Remote RPC ${endpoint} requires authentication (HTTP 401)`;
  if (status === 403) return `Remote RPC ${endpoint} is not authorized (HTTP 403)`;
  if (status === 404) return `Remote RPC ${endpoint} is unavailable (HTTP 404)`;
  return `Remote RPC ${endpoint} returned HTTP ${status}`;
}

// runtime-helper/upstream/runtimeMigration.ts
var exec = (0, import_node_util2.promisify)(import_node_child_process2.execFile);
function migrationUrl(snapshot) {
  try {
    const url = new URL(snapshot.record?.url ?? snapshot.record?.launchUrl ?? "");
    if (url.protocol !== "http:" || !url.port || url.username || url.password || url.hash || url.pathname !== "/" || !["127.0.0.1", "[::1]", "localhost"].includes(url.hostname)) return void 0;
    return url;
  } catch {
    return void 0;
  }
}
async function listenerIdentity(port) {
  try {
    if (process.platform === "win32") {
      const script = `$pids = @(Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique); if ($pids.Count -ne 1) { exit 2 }; $p = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $pids[0]); @{pid=$p.ProcessId; command=$p.CommandLine; born=$p.CreationDate.ToString("o")} | ConvertTo-Json -Compress`;
      const { stdout: stdout2 } = await exec("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], { timeout: 3e3, windowsHide: true });
      const value = JSON.parse(stdout2);
      if (!Number.isSafeInteger(value.pid) || Number(value.pid) <= 0 || typeof value.command !== "string" || typeof value.born !== "string") return void 0;
      return { pid: Number(value.pid), command: value.command, signature: `${value.born}
${value.command}` };
    }
    const { stdout } = await exec("lsof", ["-nP", "-a", `-iTCP:${port}`, "-sTCP:LISTEN", "-Fp"], { timeout: 2e3 });
    const pids = [...new Set(stdout.split("\n").filter((line) => /^p\d+$/u.test(line)).map((line) => Number(line.slice(1))))];
    if (pids.length !== 1 || pids[0] <= 0) return void 0;
    const { stdout: signature } = await exec("ps", ["-p", String(pids[0]), "-o", "lstart=,args="], { timeout: 2e3 });
    const { stdout: command } = await exec("ps", ["-p", String(pids[0]), "-o", "args="], { timeout: 2e3 });
    return { pid: pids[0], signature: signature.trim(), command: command.trim() };
  } catch {
    return void 0;
  }
}
async function inspectLegacyRuntime(snapshot) {
  if (!snapshot.record || !processHasExited(snapshot.record.pid)) return void 0;
  const url = migrationUrl(snapshot);
  if (!url) return void 0;
  const candidate = await listenerIdentity(Number(url.port));
  if (!candidate || candidate.pid === process.pid) return void 0;
  const command = candidate.command.replace(/\\/gu, "/");
  if (!/^(?:"[^"]*\/node(?:\.exe)?"|\S*\bnode(?:\.exe)?)\s+(?:"[^"\r\n]*\/@deepseek-ai\/dsh\/lib\/bin\.js"|[^\s"\r\n]*\/@deepseek-ai\/dsh\/lib\/bin\.js)(?:\s|$)/u.test(command)) return void 0;
  return { pid: candidate.pid, signature: candidate.signature, baseUrl: url.origin };
}
async function stopLegacyRuntime(snapshot, approved, sharedLockPath, signal) {
  signal?.throwIfAborted();
  await mutateRuntimeLock(sharedLockPath, async () => {
    signal?.throwIfAborted();
    const current = await readRuntimeLock(snapshot.path);
    if (!current || !sameRuntimeLockFile(snapshot.stat, current.stat) || current.contents !== snapshot.contents) {
      throw new Error(t("The Runtime lock changed while awaiting confirmation. Retry without stopping any process."));
    }
    const actual = await inspectLegacyRuntime(current);
    if (!actual || actual.pid !== approved.pid || actual.signature !== approved.signature || actual.baseUrl !== approved.baseUrl) {
      throw new Error(t("The old Runtime process changed or its owner is still alive. No process was stopped."));
    }
    signal?.throwIfAborted();
    process.kill(actual.pid, "SIGTERM");
    const deadline = Date.now() + 3e3;
    while (Date.now() < deadline && (!processHasExited(actual.pid) || !await runtimeHasExited(current.record))) {
      await new Promise((resolve6) => setTimeout(resolve6, 50));
    }
    if (!processHasExited(actual.pid)) {
      const latest = await readRuntimeLock(snapshot.path);
      const remaining = latest && latest.contents === current.contents && sameRuntimeLockFile(current.stat, latest.stat) ? await inspectLegacyRuntime(latest) : void 0;
      if (!remaining || remaining.pid !== actual.pid || remaining.signature !== actual.signature || remaining.baseUrl !== actual.baseUrl) {
        throw new Error(t("The old Runtime process changed or its owner is still alive. No process was stopped."));
      }
      signal?.throwIfAborted();
      try {
        process.kill(actual.pid, "SIGKILL");
      } catch (error) {
        if (error.code !== "ESRCH") throw error;
      }
      const forceDeadline = Date.now() + 3e3;
      while (Date.now() < forceDeadline && !processHasExited(actual.pid)) {
        await new Promise((resolve6) => setTimeout(resolve6, 50));
      }
    }
    if (!processHasExited(actual.pid) || !await runtimeHasExited(current.record)) {
      throw new Error(t("The old Runtime or its listener is still running. Its shared lock was retained. Retry after it exits."));
    }
    if (!await removeRuntimeLock(current)) {
      throw new Error(t("The Runtime stopped, but its lock changed. Retry to inspect the current lock."));
    }
  });
}

// runtime-helper/upstream/localRuntimeUpgrade.ts
var import_promises2 = require("node:fs/promises");
var import_node_path2 = require("node:path");

// runtime-helper/upstream/localUi.ts
var import_node_crypto2 = require("node:crypto");
var pending = /* @__PURE__ */ new Map();
function acceptReply(message) {
  if (message.type === "prompt-result") {
    pending.get(message.id)?.(message.value);
    pending.delete(message.id);
  }
}
function prompt(message, options, detail) {
  const id = (0, import_node_crypto2.randomUUID)();
  process.stdout.write("\nDSH_INTELLIJ_HELPER " + JSON.stringify({ event: "prompt", value: { id, message, detail, options } }) + "\n");
  return new Promise((resolve6) => pending.set(id, resolve6));
}
var ProgressLocation = { Notification: 1 };
var window = {
  showWarningMessage(message, config, ...options) {
    return prompt(message, options, config.detail);
  },
  async withProgress(_options, task) {
    return task({}, { isCancellationRequested: false, onCancellationRequested: () => ({ dispose() {
    } }) });
  }
};
var env = { clipboard: { async writeText(text) {
  process.stdout.write("\nDSH_INTELLIJ_HELPER " + JSON.stringify({ event: "clipboard", value: { text } }) + "\n");
} } };

// runtime-helper/upstream/runtimeVersion.ts
function parseVersion(value) {
  if (value === void 0) return void 0;
  const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/u.exec(value);
  if (!match || match[4]?.split(".").some((id) => /^0\d+$/u.test(id))) return void 0;
  return match.slice(1);
}
function compareRuntimeVersions(actual, target) {
  const left = parseVersion(actual);
  const right = parseVersion(target);
  if (!left || !right) return void 0;
  for (let index = 0; index < 3; index += 1) {
    if (BigInt(left[index]) !== BigInt(right[index])) return BigInt(left[index]) < BigInt(right[index]) ? -1 : 1;
  }
  if (left[3] === void 0 || right[3] === void 0) {
    return left[3] === right[3] ? 0 : left[3] === void 0 ? 1 : -1;
  }
  const a = left[3].split(".");
  const b = right[3].split(".");
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    if (a[index] === b[index]) continue;
    if (a[index] === void 0 || b[index] === void 0) return a[index] === void 0 ? -1 : 1;
    const numericA = /^\d+$/u.test(a[index]);
    const numericB = /^\d+$/u.test(b[index]);
    if (numericA && numericB) return BigInt(a[index]) < BigInt(b[index]) ? -1 : 1;
    if (numericA !== numericB) return numericA ? -1 : 1;
    return a[index] < b[index] ? -1 : 1;
  }
  return 0;
}
function isOlderRuntimeVersion(actual, target) {
  return compareRuntimeVersions(actual, target) === -1;
}

// runtime-helper/upstream/managedRuntime/types.ts
var RUNTIME_MINIMUM_VERSION = "0.1.5-rc.1";
function isSupportedRuntimeVersion(version) {
  const order = compareRuntimeVersions(version, RUNTIME_MINIMUM_VERSION);
  return order !== void 0 && order >= 0;
}

// runtime-helper/upstream/localRuntimeUpgrade.ts
var PACKAGE = "@deepseek-ai/dsh";
async function npmInstallation(command, npm, prefix, node) {
  try {
    if (!(0, import_node_path2.isAbsolute)(prefix)) return void 0;
    const packageRoot = (0, import_node_path2.join)(prefix, ...process.platform === "win32" ? [] : ["lib"], "node_modules", PACKAGE);
    const manifest = JSON.parse(await (0, import_promises2.readFile)((0, import_node_path2.join)(packageRoot, "package.json"), "utf8"));
    const bin = typeof manifest.bin === "string" ? manifest.bin : manifest.bin?.dsh;
    if (manifest.name !== PACKAGE || typeof bin !== "string") return void 0;
    const entry = await (0, import_promises2.realpath)((0, import_node_path2.resolve)(packageRoot, bin));
    const withinPackage = (0, import_node_path2.relative)(await (0, import_promises2.realpath)(packageRoot), entry);
    if (withinPackage.startsWith("..") || (0, import_node_path2.isAbsolute)(withinPackage)) return void 0;
    const commandPath = await (0, import_promises2.realpath)(command);
    if (process.platform === "win32") {
      if ((0, import_node_path2.resolve)((0, import_node_path2.dirname)(command)).toLowerCase() !== (0, import_node_path2.resolve)(prefix).toLowerCase()) return void 0;
      const shim = (await (0, import_promises2.readFile)(command, "utf8")).replaceAll("\\", "/");
      if (!shim.includes(`node_modules/${PACKAGE}/${bin.replaceAll("\\", "/")}`)) return void 0;
    } else if (commandPath !== entry) return void 0;
    const cli = process.platform === "win32" ? await (0, import_promises2.realpath)((0, import_node_path2.join)((0, import_node_path2.dirname)(npm), "node_modules", "npm", "bin", "npm-cli.js")) : await (0, import_promises2.realpath)(npm);
    if (!cli.endsWith(`${process.platform === "win32" ? "\\" : "/"}npm-cli.js`)) return void 0;
    const npmManifest = JSON.parse(await (0, import_promises2.readFile)((0, import_node_path2.join)((0, import_node_path2.dirname)(cli), "..", "package.json"), "utf8"));
    if (npmManifest.name !== "npm") return void 0;
    return { prefix, cli, entry, node: (0, import_node_path2.resolve)(node) };
  } catch {
    return void 0;
  }
}
async function choose(choice, signal) {
  signal.throwIfAborted();
  return new Promise((resolveChoice, reject) => {
    const abort = () => {
      signal.removeEventListener("abort", abort);
      reject(signal.reason);
    };
    signal.addEventListener("abort", abort, { once: true });
    void Promise.resolve(choice).then(resolveChoice, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}
async function install(installation, target, registry, timeout, signal) {
  signal.throwIfAborted();
  const child2 = spawnOwnedRuntime(installation.node, [
    installation.cli,
    "install",
    "--global",
    "--prefix",
    installation.prefix,
    `${PACKAGE}@${target}`,
    ...registry ? ["--registry", registry] : []
  ], {
    cwd: installation.prefix,
    env: { ...process.env },
    windowsHide: true,
    stdio: "ignore"
  });
  try {
    await new Promise((resolveInstall, reject) => {
      const abort = () => {
        cleanup();
        reject(signal.reason);
      };
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error(t("The CLI upgrade timed out.")));
      }, timeout);
      const cleanup = () => {
        clearTimeout(timer);
        signal.removeEventListener("abort", abort);
      };
      signal.addEventListener("abort", abort, { once: true });
      child2.once("error", (error) => {
        cleanup();
        reject(error);
      });
      child2.once("exit", (code) => {
        cleanup();
        if (code === 0) resolveInstall();
        else reject(new Error(t("npm exited with code {code}.", { code: String(code) })));
      });
      if (signal.aborted) abort();
    });
  } finally {
    if (process.platform !== "win32" || child2.exitCode === null && child2.signalCode === null) {
      await terminateOwnedRuntime(child2);
    }
  }
}
var LocalRuntimeUpgradeCancelledError = class extends Error {
};
async function offerLocalRuntimeUpgrade(options) {
  const { command, actual, target, signal } = options;
  const installation = isOlderRuntimeVersion(actual, target) && options.npm && options.prefix && options.node ? await npmInstallation(command, options.npm, options.prefix, options.node) : void 0;
  signal.throwIfAborted();
  const skip = t("Skip upgrade");
  if (!installation) {
    const copy = t("Copy target version");
    const selected2 = await choose(window.showWarningMessage(
      t("Local dsh reports {actual} and is not compatible. Install the supported version {target} with its original installer: {path}", { actual: actual ?? t("unknown"), target, path: command }),
      { modal: true, detail: t("Automatic upgrade requires an older, verified npm global installation. Unknown versions are not overwritten. Skip to use the plugin Runtime, or copy the target version and restart DSH after upgrading manually.") },
      copy,
      skip
    ), signal);
    signal.throwIfAborted();
    if (selected2 === copy) {
      await env.clipboard.writeText(target);
      throw new LocalRuntimeUpgradeCancelledError();
    }
    return void 0;
  }
  const upgrade = t("Upgrade to {version}", { version: target });
  const selected = await choose(window.showWarningMessage(
    t("Local dsh {actual} is older than the required {target}. Upgrade it now?", { actual, target }),
    { modal: true, detail: t("CLI: {path}\nnpm prefix: {prefix}\nThis runs npm install --global @deepseek-ai/dsh@{target} in this prefix. Other tools using this installation will use the new version.", { path: command, prefix: installation.prefix, target }) },
    upgrade,
    skip
  ), signal);
  signal.throwIfAborted();
  if (selected !== upgrade) return void 0;
  const current = await options.probe();
  if (isSupportedRuntimeVersion(current)) return current;
  const checked = options.npm && options.prefix && options.node ? await npmInstallation(command, options.npm, options.prefix, options.node) : void 0;
  if (!isOlderRuntimeVersion(current, target) || current !== actual || checked?.entry !== installation.entry || checked?.cli !== installation.cli) {
    options.log("[dsh:upgrade] installation changed while awaiting confirmation; skipped");
    return void 0;
  }
  try {
    await window.withProgress({ location: ProgressLocation.Notification, title: t("Upgrading local dsh to {version}", { version: target }), cancellable: true }, async (_progress, token) => {
      const controller = new AbortController();
      const abort = () => controller.abort(signal.reason);
      signal.addEventListener("abort", abort, { once: true });
      const subscription = token.onCancellationRequested(() => controller.abort());
      if (signal.aborted || token.isCancellationRequested) controller.abort();
      try {
        await install(installation, target, options.registry, options.timeout, controller.signal);
      } finally {
        signal.removeEventListener("abort", abort);
        subscription.dispose();
      }
    });
    signal.throwIfAborted();
    const version = await options.probe();
    if (version !== target) throw new Error(t("The CLI still reports {actual} after upgrading to {target}.", { actual: version ?? t("unknown"), target }));
    options.log(`[dsh:upgrade] verified ${command}: ${version}`);
    return version;
  } catch (error) {
    signal.throwIfAborted();
    const reason = error instanceof Error ? error.message : String(error);
    options.log(`[dsh:upgrade] ${reason}`);
    const fallback = t("Use plugin Runtime");
    const selected2 = await choose(window.showWarningMessage(
      t("Local dsh upgrade did not complete: {reason}. Use the plugin Runtime instead?", { reason }),
      { modal: true },
      fallback,
      t("Cancel startup")
    ), signal);
    signal.throwIfAborted();
    if (selected2 !== fallback) throw new LocalRuntimeUpgradeCancelledError();
    return void 0;
  }
}

// runtime-helper/main.ts
var import_node_readline = require("node:readline");
var import_node_path9 = require("node:path");
var import_promises8 = require("node:fs/promises");

// runtime-helper/upstream/guards.ts
function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

// runtime-helper/upstream/recovery/composition.ts
var import_node_crypto3 = require("node:crypto");
var import_promises3 = require("node:fs/promises");
var import_node_os = require("node:os");
var import_node_path3 = require("node:path");
var SECRET_NAME = /(?:api[-_]?key|auth|credential|password|secret|token|cookie|private[-_]?key)/iu;
var SECRET_FILE = /(?:^|[\\/])(?:\.env(?:\.[^\\/]+)?|\.credentials(?:\.[^\\/]+)?|.*secret.*)$/iu;
var INSTALLATION_BUNDLES = /* @__PURE__ */ new Set([
  "@deepseek-ai/dsh-base",
  "@deepseek-ai/dsh-acp-app",
  "@deepseek-ai/dsh-web-app",
  "@deepseek-ai/dsh-headless",
  "@deepseek-ai/dsh-sdk-app",
  "@deepseek-ai/dsh-sdk-minimal"
]);
var MAX_HASHED_FILE_BYTES = 2 * 1024 * 1024;
function sha256(value) {
  return (0, import_node_crypto3.createHash)("sha256").update(value).digest("hex");
}
function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(([key, child2]) => [key, stableValue(child2)])
    );
  }
  return value;
}
function normalizedPath(path) {
  const normalized2 = (0, import_node_path3.normalize)((0, import_node_path3.resolve)(path)).replace(/\\/gu, "/");
  return process.platform === "win32" ? normalized2.toLowerCase() : normalized2;
}
function secretPath(path) {
  return SECRET_FILE.test(path) || SECRET_NAME.test((0, import_node_path3.basename)(path));
}
function safeEnvironment() {
  const entries = Object.entries(process.env);
  const inheritedNames = entries.map(([name]) => name).sort();
  const presentSensitiveNames = entries.filter(([name]) => SECRET_NAME.test(name)).map(([name]) => name).sort();
  const safeValues = entries.filter(([name]) => !SECRET_NAME.test(name)).map(([name, value]) => [name, value ?? ""]).sort(([left], [right]) => left.localeCompare(right));
  const stableValuesHash = sha256(JSON.stringify(safeValues));
  return {
    cwd: normalizedPath(process.cwd()),
    dshHome: normalizedPath(process.env.DSH_HOME || (0, import_node_path3.join)((0, import_node_os.homedir)(), ".dsh")),
    platform: process.platform,
    arch: process.arch,
    nodeVersion: process.versions.node,
    inheritedNames,
    presentSensitiveNames,
    stableValuesHash
  };
}
async function fingerprintPath(path) {
  const normalized2 = normalizedPath(path);
  try {
    const stat2 = await (0, import_promises3.lstat)(path);
    if (stat2.isSymbolicLink()) {
      return { path: normalized2, kind: "link", mtimeMs: stat2.mtimeMs };
    }
    if (stat2.isDirectory()) {
      return { path: normalized2, kind: "directory", mtimeMs: stat2.mtimeMs };
    }
    if (!stat2.isFile()) return { path: normalized2, kind: "missing" };
    const fingerprint = { path: normalized2, kind: "file", size: stat2.size, mtimeMs: stat2.mtimeMs };
    if (secretPath(path)) {
      return {
        ...fingerprint,
        secret: true,
        contentHash: sha256(`secret-present:${stat2.size}:${stat2.mtimeMs}`)
      };
    }
    if (stat2.size > MAX_HASHED_FILE_BYTES) return fingerprint;
    return { ...fingerprint, contentHash: sha256(await (0, import_promises3.readFile)(path)) };
  } catch (error) {
    if (error.code === "ENOENT") {
      return { path: normalized2, kind: "missing" };
    }
    throw error;
  }
}
function profileNameFromArgs(args) {
  const inline = args.find((argument) => argument.startsWith("--profile="));
  if (inline) return inline.slice("--profile=".length) || "default";
  const index = args.findIndex((argument) => argument === "--profile");
  if (index >= 0 && args[index + 1]) return args[index + 1];
  if (args.includes("web")) return "web";
  return "default";
}
function patchPathsFromArgs(args) {
  const paths = [];
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--patch" && args[index + 1]) {
      paths.push(args[index + 1]);
      index += 1;
    } else if (argument.startsWith("--patch=")) {
      paths.push(argument.slice("--patch=".length));
    }
  }
  return paths;
}
function parsePatchLayer(path, order, extensionOverlayPaths, recoveryOverlayPaths) {
  const normalized2 = normalizedPath(path);
  const kind = recoveryOverlayPaths.some((candidate) => normalizedPath(candidate) === normalized2) ? "recovery-overlay" : extensionOverlayPaths.some((candidate) => normalizedPath(candidate) === normalized2) ? "extension-overlay" : "unknown";
  return {
    kind,
    path: normalized2,
    order,
    sourceLabel: (0, import_node_path3.basename)(path)
  };
}
async function readPackageJson(path) {
  try {
    const value = JSON.parse(await (0, import_promises3.readFile)(path, "utf8"));
    return isRecord(value) ? value : void 0;
  } catch {
    return void 0;
  }
}
async function resolveBundle(packageName, profileDir) {
  if (INSTALLATION_BUNDLES.has(packageName)) {
    return { packageName, origin: "installation", selected: true };
  }
  const packageDir = (0, import_node_path3.join)(profileDir, "node_modules", packageName);
  const packageJsonPath = (0, import_node_path3.join)(packageDir, "package.json");
  const packageJson = await readPackageJson(packageJsonPath);
  const packageExists = (await fingerprintPath(packageDir)).kind !== "missing";
  const packageHash = packageJson ? sha256(JSON.stringify(stableValue(packageJson))) : void 0;
  const dsh = packageJson?.dsh;
  const bundle = isRecord(dsh) ? dsh.bundle : void 0;
  const patchValue = isRecord(bundle) ? bundle.patch : void 0;
  const patchPath = typeof patchValue === "string" ? (0, import_node_path3.resolve)(packageDir, patchValue) : void 0;
  const patchFingerprint = patchPath ? await fingerprintPath(patchPath) : void 0;
  return {
    packageName,
    packageDir: packageExists ? normalizedPath(packageDir) : void 0,
    manifestPath: normalizedPath(packageJsonPath),
    patchPath: patchPath && patchFingerprint?.kind !== "missing" ? normalizedPath(patchPath) : void 0,
    origin: packageExists ? "profile-dependency" : "unknown",
    selected: true,
    packageHash,
    patchHash: patchFingerprint?.contentHash
  };
}
async function readBundles(manifestPath, profileDir) {
  if (!manifestPath) return [];
  const packageJson = await readPackageJson(manifestPath);
  const dsh = packageJson?.dsh;
  const profile = isRecord(dsh) ? dsh.profile : void 0;
  const bundles = isRecord(profile) ? profile.bundles : void 0;
  if (!Array.isArray(bundles)) return [];
  const result = [];
  for (const value of bundles) {
    if (typeof value !== "string" || !value.trim()) continue;
    result.push(await resolveBundle(value.trim(), profileDir));
  }
  return result;
}
function hashableComposition(composition) {
  return stableValue({
    schemaVersion: composition.schemaVersion,
    profile: composition.profile,
    profileManifestPath: composition.profileManifestPath,
    profilePackageHash: composition.profilePackageHash,
    binary: composition.binary,
    appArgs: normalizeArgsForHash(composition.appArgs),
    patchLayers: composition.patchLayers,
    bundles: composition.bundles,
    profileFiles: composition.profileFiles,
    homeFiles: composition.homeFiles,
    environment: {
      ...composition.environment,
      cwd: normalizedPath(composition.environment.cwd),
      dshHome: normalizedPath(composition.environment.dshHome)
    },
    extensionOverlayPaths: composition.extensionOverlayPaths.map(normalizedPath).sort(),
    recoveryOverlayPaths: composition.recoveryOverlayPaths.map(normalizedPath).sort()
  });
}
function normalizeArgsForHash(args) {
  const result = [...args];
  for (let index = 0; index < result.length; index += 1) {
    if ((result[index] === "--port" || result[index] === "-p") && result[index + 1] === "0") {
      result[index + 1] = "<dynamic-port>";
    } else if (result[index] === "--port=0") {
      result[index] = "--port=<dynamic-port>";
    }
  }
  return result;
}
function recomputeComposition(composition, changes) {
  const next = {
    ...composition,
    ...changes,
    schemaVersion: 1
  };
  return {
    ...next,
    compositionHash: sha256(JSON.stringify(hashableComposition(next)))
  };
}
async function buildComposition(input2) {
  const dshHome = (0, import_node_path3.resolve)(input2.dshHome || process.env.DSH_HOME || (0, import_node_path3.join)((0, import_node_os.homedir)(), ".dsh"));
  const profile = input2.profile || profileNameFromArgs(input2.appArgs);
  const profileDir = (0, import_node_path3.join)(dshHome, "profiles", profile);
  const profileManifestPath = input2.profileManifestPath || (0, import_node_path3.join)(profileDir, "package.json");
  const patchPaths = patchPathsFromArgs(input2.appArgs).map((path) => (0, import_node_path3.resolve)(input2.workspaceRoot, path));
  const extensionOverlayPaths = [...input2.extensionOverlayPaths ?? []].map(normalizedPath);
  const recoveryOverlayPaths = [...input2.recoveryOverlayPaths ?? []].map(normalizedPath);
  const patchLayers = [];
  for (const [index, path] of patchPaths.entries()) {
    const layer = parsePatchLayer(path, index, extensionOverlayPaths, recoveryOverlayPaths);
    const fingerprint = await fingerprintPath(path);
    patchLayers.push({
      ...layer,
      contentHash: fingerprint.contentHash
    });
  }
  const profileManifest = await fingerprintPath(profileManifestPath);
  const profileFiles = [
    profileManifest,
    await fingerprintPath((0, import_node_path3.join)(profileDir, "cordis.patch.yml"))
  ].filter((item) => item.kind !== "missing");
  const homeFiles = [
    await fingerprintPath((0, import_node_path3.join)(dshHome, "cordis.patch.yml"))
  ].filter((item) => item.kind !== "missing");
  const environment = {
    ...safeEnvironment(),
    cwd: normalizedPath(input2.workspaceRoot),
    dshHome: normalizedPath(dshHome)
  };
  const binary = {
    command: input2.command,
    ...input2.resolvedPath === void 0 ? {} : { resolvedPath: normalizedPath(input2.resolvedPath) },
    source: input2.source,
    ...input2.version === void 0 ? {} : { version: input2.version },
    launcherArgs: [...input2.launcherArgs]
  };
  const composition = {
    schemaVersion: 1,
    profile,
    ...profileManifest.kind === "missing" ? {} : { profileManifestPath: normalizedPath(profileManifestPath) },
    ...profileManifest.contentHash === void 0 ? {} : { profilePackageHash: profileManifest.contentHash },
    ...profileManifest.mtimeMs === void 0 ? {} : { profilePackageMtimeMs: profileManifest.mtimeMs },
    binary,
    appArgs: [...input2.appArgs],
    patchLayers,
    bundles: await readBundles(
      profileManifest.kind === "missing" ? void 0 : profileManifestPath,
      profileDir
    ),
    profileFiles,
    homeFiles,
    environment,
    extensionOverlayPaths,
    recoveryOverlayPaths
  };
  return {
    ...composition,
    compositionHash: sha256(JSON.stringify(hashableComposition(composition)))
  };
}
function compositionDiff(current, lastKnownGood) {
  if (!current || !lastKnownGood) {
    return {
      currentHash: current?.compositionHash,
      lastKnownGoodHash: lastKnownGood?.compositionHash
    };
  }
  return {
    currentHash: current.compositionHash,
    lastKnownGoodHash: lastKnownGood.compositionHash,
    profile: current.profile === lastKnownGood.profile ? void 0 : [lastKnownGood.profile, current.profile],
    appArgs: JSON.stringify(current.appArgs) === JSON.stringify(lastKnownGood.appArgs) ? void 0 : { before: lastKnownGood.appArgs, after: current.appArgs },
    patchLayers: JSON.stringify(current.patchLayers) === JSON.stringify(lastKnownGood.patchLayers) ? void 0 : { before: lastKnownGood.patchLayers, after: current.patchLayers },
    bundles: JSON.stringify(current.bundles) === JSON.stringify(lastKnownGood.bundles) ? void 0 : { before: lastKnownGood.bundles, after: current.bundles },
    environment: current.environment.stableValuesHash === lastKnownGood.environment.stableValuesHash ? void 0 : { before: lastKnownGood.environment.stableValuesHash, after: current.environment.stableValuesHash }
  };
}

// runtime-helper/upstream/recovery/recoverySession.ts
var import_node_crypto6 = require("node:crypto");

// runtime-helper/upstream/recovery/ledger.ts
var import_node_crypto4 = require("node:crypto");
var import_promises4 = require("node:fs/promises");
var import_node_path4 = require("node:path");
var EMPTY_LEDGER = {
  schemaVersion: 1,
  revision: 0,
  clean: true,
  sessions: [],
  entries: []
};
var RecoveryLedgerCorruptError = class extends Error {
  constructor(path, message) {
    super(message);
    this.path = path;
    this.name = "RecoveryLedgerCorruptError";
  }
};
function validLedger(value) {
  if (!isRecord(value) || value.schemaVersion !== 1 || typeof value.revision !== "number" || !Number.isSafeInteger(value.revision) || value.revision < 0 || typeof value.clean !== "boolean" || !Array.isArray(value.sessions) || !Array.isArray(value.entries)) {
    return false;
  }
  if (value.activeSessionId !== void 0 && typeof value.activeSessionId !== "string") return false;
  return value.sessions.every((session) => isRecord(session) && typeof session.id === "string" && typeof session.compositionHash === "string" && typeof session.clean === "boolean" && ["detected", "searching", "fix-applied", "recovered", "unrecoverable", "cancelled"].includes(String(session.phase)) && isRecord(session.budget) && Number.isInteger(session.budget.usedBoots) && Number.isInteger(session.budget.maxBoots) && Array.isArray(session.evidence) && session.evidence.every((item) => isRecord(item) && typeof item.variantId === "string" && typeof item.verdict === "string" && isRecord(item.cleanup))) && value.entries.every((entry) => isRecord(entry) && typeof entry.id === "string" && typeof entry.sessionId === "string" && typeof entry.status === "string" && isRecord(entry.fix) && typeof entry.fix.kind === "string" && Array.isArray(entry.fix.targetIds));
}
function cloneState(state) {
  return JSON.parse(JSON.stringify(state));
}
var RecoveryLedgerStore = class {
  directory;
  path;
  leasePath;
  leaseOwner;
  constructor(storagePath) {
    this.directory = (0, import_node_path4.join)(storagePath, "recovery");
    this.path = (0, import_node_path4.join)(this.directory, "ledger.json");
    this.leasePath = (0, import_node_path4.join)(this.directory, "session.lease");
  }
  async acquireLease() {
    await (0, import_promises4.mkdir)(this.directory, { recursive: true });
    await mutateRuntimeLock(this.leasePath, async () => {
      const current = await readRuntimeLock(this.leasePath);
      if (current?.record?.ownerId !== void 0 && current.record.ownerId === this.leaseOwner) return;
      if (current) {
        if (!current.record || !processHasExited(current.record.pid) || !await removeRuntimeLock(current)) {
          throw new Error("Another recovery or restore owns the recovery lease; retry after it finishes.");
        }
      }
      const ownerId = (0, import_node_crypto4.randomUUID)();
      await (0, import_promises4.writeFile)(this.leasePath, JSON.stringify({ pid: process.pid, ownerId }), { flag: "wx", mode: 384 });
      this.leaseOwner = ownerId;
    });
  }
  async releaseLease() {
    const ownerId = this.leaseOwner;
    if (!ownerId) return;
    await mutateRuntimeLock(this.leasePath, async () => {
      const current = await readRuntimeLock(this.leasePath);
      if (current?.record?.ownerId === ownerId) await removeRuntimeLock(current);
    });
    this.leaseOwner = void 0;
  }
  async assertLease() {
    if (!this.leaseOwner || (await readRuntimeLock(this.leasePath))?.record?.ownerId !== this.leaseOwner) {
      throw new Error("Recovery lease ownership changed");
    }
  }
  async read() {
    try {
      const contents = await (0, import_promises4.readFile)(this.path, "utf8");
      let parsed;
      try {
        parsed = JSON.parse(contents);
      } catch (error) {
        const corrupt = new RecoveryLedgerCorruptError(
          this.path,
          `Recovery ledger JSON is invalid: ${error instanceof Error ? error.message : String(error)}`
        );
        return { state: cloneState(EMPTY_LEDGER), exists: true, corrupt };
      }
      if (!validLedger(parsed)) {
        const corrupt = new RecoveryLedgerCorruptError(
          this.path,
          "Recovery ledger schema is invalid"
        );
        return { state: cloneState(EMPTY_LEDGER), exists: true, corrupt };
      }
      return { state: cloneState(parsed), exists: true };
    } catch (error) {
      if (error.code === "ENOENT") {
        return { state: cloneState(EMPTY_LEDGER), exists: false };
      }
      throw error;
    }
  }
  async update(mutator, expectedRevision) {
    return this.withMutation(async () => {
      const loaded = await this.read();
      if (loaded.corrupt) throw loaded.corrupt;
      if (expectedRevision !== void 0 && loaded.state.revision !== expectedRevision) {
        throw new Error(
          `Recovery ledger revision changed from ${expectedRevision} to ${loaded.state.revision}`
        );
      }
      const next = cloneState(loaded.state);
      const returned = mutator(next);
      const updated = returned ? cloneState(returned) : next;
      updated.schemaVersion = 1;
      updated.revision = loaded.state.revision + 1;
      await this.writeUnlocked(updated);
      return updated;
    });
  }
  async readEntries() {
    const loaded = await this.read();
    if (loaded.corrupt) throw loaded.corrupt;
    return loaded.state.entries;
  }
  async beginSession(composition, budget, error) {
    const id = (0, import_node_crypto4.randomUUID)();
    const session = {
      id,
      startedAt: (/* @__PURE__ */ new Date()).toISOString(),
      clean: false,
      phase: "detected",
      compositionHash: composition.compositionHash,
      budget,
      evidence: [],
      ...error === void 0 ? {} : { error }
    };
    const loaded = await this.read();
    if (loaded.corrupt) throw loaded.corrupt;
    const unfinished = loaded.state.sessions.find((item) => item.id === loaded.state.activeSessionId && !item.clean);
    if (unfinished) {
      const entries = loaded.state.entries.filter((entry) => entry.sessionId === unfinished.id);
      const applied = entries.some((entry) => entry.status === "applied" || entry.status === "verified");
      if (!entries.some((entry) => entry.status === "planned") && (applied || unfinished.compositionHash === composition.compositionHash && entries.every((entry) => entry.status === "reverted"))) {
        return unfinished;
      }
    }
    if (unfinished) throw new Error("Interrupted recovery has an unresolved file change or changed composition; inspect or restore it before retrying.");
    await this.update((state) => {
      state.clean = false;
      state.activeSessionId = id;
      state.sessions.push(session);
    }, loaded.state.revision);
    return session;
  }
  async reserveBoot(sessionId) {
    await this.update((state) => {
      const session = state.sessions.find((item) => item.id === sessionId);
      if (!session || state.activeSessionId !== sessionId || session.budget.usedBoots >= session.budget.maxBoots) {
        throw new Error("Recovery boot budget or session ownership changed");
      }
      session.budget.usedBoots += 1;
    });
  }
  async appendEvidence(sessionId, evidence) {
    await this.update((state) => {
      const session = state.sessions.find((candidate) => candidate.id === sessionId);
      if (!session) throw new Error(`Recovery session ${sessionId} does not exist`);
      session.phase = "searching";
      session.evidence.push(evidence);
      session.budget.usedBoots = Math.max(session.budget.usedBoots, session.evidence.length);
    });
  }
  async planFix(sessionId, fix, beforeCompositionHash) {
    await this.update((state) => {
      if (state.activeSessionId !== sessionId) throw new Error("Recovery session ownership changed");
      state.entries.push({
        id: fix.id,
        sessionId,
        status: "planned",
        fix,
        plannedAt: (/* @__PURE__ */ new Date()).toISOString(),
        beforeCompositionHash
      });
      const session = state.sessions.find((candidate) => candidate.id === sessionId);
      if (session) session.phase = "searching";
    });
  }
  async markFixApplied(sessionId, fixId, afterCompositionHash) {
    await this.update((state) => {
      const entry = state.entries.find(
        (candidate) => candidate.sessionId === sessionId && candidate.id === fixId
      );
      if (!entry) throw new Error(`Recovery fix ${fixId} does not exist`);
      entry.status = "applied";
      entry.appliedAt = (/* @__PURE__ */ new Date()).toISOString();
      if (afterCompositionHash !== void 0) entry.afterCompositionHash = afterCompositionHash;
      const session = state.sessions.find((candidate) => candidate.id === sessionId);
      if (session) session.phase = "fix-applied";
    });
  }
  async finishSession(sessionId, phase, options = {}) {
    await this.update((state) => {
      const session = state.sessions.find((candidate) => candidate.id === sessionId);
      if (!session) throw new Error(`Recovery session ${sessionId} does not exist`);
      session.phase = phase;
      session.clean = phase === "recovered" || phase === "cancelled" || phase === "unrecoverable";
      session.finishedAt = (/* @__PURE__ */ new Date()).toISOString();
      if (options.attribution !== void 0) session.attribution = options.attribution;
      if (options.error !== void 0) session.error = options.error;
      const active = state.activeSessionId === sessionId;
      if (active) {
        state.clean = session.clean;
        delete state.activeSessionId;
      }
      if (phase === "recovered" && options.composition) {
        state.lastKnownGood = options.composition;
      }
      for (const entry of state.entries) {
        if (entry.sessionId !== sessionId) continue;
        if (entry.status === "applied" && phase === "recovered") {
          entry.status = "verified";
          entry.verifiedAt = (/* @__PURE__ */ new Date()).toISOString();
        } else if (entry.status === "planned" && phase !== "recovered") {
          entry.status = "conflicted";
          entry.note = "Recovery session ended before this fix was applied.";
        }
      }
    });
  }
  async markFixConflict(sessionId, fixId, note) {
    await this.update((state) => {
      const entry = state.entries.find(
        (candidate) => candidate.sessionId === sessionId && candidate.id === fixId
      );
      if (!entry) return;
      entry.status = "conflicted";
      entry.note = note;
    });
  }
  async restoreEntry(fixId, status = "reverted", note) {
    await this.update((state) => {
      const entry = [...state.entries].reverse().find((candidate) => candidate.id === fixId);
      if (!entry) throw new Error(`Recovery fix ${fixId} does not exist`);
      entry.status = status;
      entry.revertedAt = (/* @__PURE__ */ new Date()).toISOString();
      if (note !== void 0) entry.note = note;
    });
  }
  async writeUnlocked(state) {
    await atomicWrite(this.path, `${JSON.stringify(state, null, 2)}
`);
  }
  async withMutation(action) {
    await (0, import_promises4.mkdir)(this.directory, { recursive: true });
    return mutateRuntimeLock(this.path, action);
  }
};
var ATOMIC_WRITE_RETRIES = 5;
var ATOMIC_WRITE_RETRY_MS = 50;
var RETRYABLE_RENAME_CODES = /* @__PURE__ */ new Set(["EPERM", "EACCES", "EBUSY"]);
async function atomicWrite(path, contents) {
  await (0, import_promises4.mkdir)((0, import_node_path4.dirname)(path), { recursive: true });
  const temporary = `${path}.${(0, import_node_crypto4.randomUUID)()}.tmp`;
  try {
    await (0, import_promises4.writeFile)(temporary, contents, { encoding: "utf8", flag: "wx", mode: 384 });
    await renameWithRetry(temporary, path);
  } finally {
    await (0, import_promises4.rm)(temporary, { force: true });
  }
}
async function renameWithRetry(source, target) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      await (0, import_promises4.rename)(source, target);
      return;
    } catch (error) {
      const code = error.code ?? "";
      if (attempt >= ATOMIC_WRITE_RETRIES || !RETRYABLE_RENAME_CODES.has(code)) throw error;
      await new Promise((resolve6) => {
        setTimeout(resolve6, ATOMIC_WRITE_RETRY_MS);
      });
    }
  }
}

// runtime-helper/upstream/recovery/variantEngine.ts
var import_node_crypto5 = require("node:crypto");
var import_node_path5 = require("node:path");
function pathKey(path) {
  const resolvedPath = (0, import_node_path5.resolve)(path);
  return process.platform === "win32" ? resolvedPath.toLowerCase() : resolvedPath;
}
function idFor(kind, target) {
  const digest = (0, import_node_crypto5.createHash)("sha256").update(`${kind}:${target.join("\0")}`).digest("hex").slice(0, 12);
  return `${kind}-${digest}`;
}
function bundleVariant(base, kind, selected, assumption) {
  const selectedSet = new Set(selected);
  const bundles = base.bundles.map((bundle) => ({
    ...bundle,
    selected: bundle.origin === "installation" ? bundle.selected : selectedSet.has(bundle.packageName)
  }));
  const composition = recomputeComposition(base, { bundles });
  return {
    id: idFor(kind, selected),
    kind,
    parentHash: base.compositionHash,
    assumption,
    bundleSelection: bundles.filter((bundle) => bundle.selected).map((bundle) => bundle.packageName),
    composition
  };
}
function overlayVariant(base) {
  const removed = [...base.extensionOverlayPaths];
  const remaining = new Set(removed.map(pathKey));
  const patchLayers = base.patchLayers.filter((layer) => !remaining.has(pathKey(layer.path)));
  const composition = recomputeComposition(base, {
    patchLayers,
    extensionOverlayPaths: []
  });
  const candidate = {
    id: idFor("v4-extension-overlays-removed", removed),
    kind: "remove-extension-overlay",
    targetIds: removed.map((path) => (0, import_node_path5.basename)(path)),
    reason: "The composition becomes healthy after removing the extension-owned patch layer.",
    evidenceBootIds: [],
    precondition: {
      runtimeMustBeDead: true,
      expectedSourceHashes: Object.fromEntries(
        base.patchLayers.filter((layer) => removed.some((path) => pathKey(path) === pathKey(layer.path))).map((layer) => [layer.path, layer.contentHash ?? ""])
      )
    },
    removedPatchPaths: removed,
    restore: {
      kind: "remove-managed-state",
      target: "recovery/active-fixes.json",
      displayCommand: "Remove the recovery overlay state",
      requiresConfirmation: false
    }
  };
  return {
    id: idFor("v4-extension-overlays-removed", removed),
    kind: "v4-extension-overlays-removed",
    parentHash: base.compositionHash,
    assumption: "The extension-owned patch layer is the failing part of the boot composition.",
    removedOverlayPaths: removed,
    composition,
    candidateFix: candidate
  };
}
function bundleCandidate(base, variant, removed) {
  if (removed.length === 0 || removed.length > 2 || !base.profileManifestPath) return void 0;
  const targets = base.bundles.filter(
    (bundle) => removed.includes(bundle.packageName) && bundle.origin === "profile-dependency"
  );
  if (targets.length !== removed.length) return void 0;
  return {
    id: idFor(variant.kind, removed),
    kind: "disable-profile-bundles",
    targetIds: [...removed],
    reason: `Profile dependency bundle candidate: ${removed.join(", ")}`,
    evidenceBootIds: [],
    precondition: {
      runtimeMustBeDead: true,
      expectedSourceHashes: {
        [base.profileManifestPath]: base.profilePackageHash ?? ""
      }
    },
    profileManifestPath: base.profileManifestPath,
    expectedProfileManifestHash: base.profilePackageHash,
    restore: {
      kind: "restore-json",
      target: base.profileManifestPath,
      expectedHash: base.profilePackageHash,
      displayCommand: `Restore the managed bundle list in ${base.profileManifestPath}`,
      requiresConfirmation: true
    }
  };
}
var VariantEngine = class {
  plan(composition, budget) {
    const variants = [];
    const seen = /* @__PURE__ */ new Set();
    const add = (variant) => {
      if (seen.has(variant.composition.compositionHash)) {
        budget.skipped.push({ variantId: variant.id, reason: "duplicate" });
        return;
      }
      seen.add(variant.composition.compositionHash);
      variants.push(variant);
    };
    add({
      id: idFor("v1-reproduce", [composition.compositionHash]),
      kind: "v1-reproduce",
      parentHash: composition.compositionHash,
      assumption: "The failure was transient; reproduce the exact launch composition.",
      composition
    });
    if (composition.extensionOverlayPaths.length > 0) {
      add(overlayVariant(composition));
    }
    const userBundles = composition.bundles.filter((bundle) => bundle.selected && bundle.origin === "profile-dependency").map((bundle) => bundle.packageName);
    if (userBundles.length > 0) {
      const empty = bundleVariant(
        composition,
        "v3-all-user-bundles-removed",
        [],
        "All non-installation bundles are removed in the sandbox to test the core/profile baseline."
      );
      if (userBundles.length === 1) {
        empty.candidateFix = bundleCandidate(composition, empty, userBundles);
      }
      add(empty);
      const addSelection = (kind, selected, assumption) => {
        const variant = bundleVariant(composition, kind, selected, assumption);
        const removed = userBundles.filter((name) => !selected.includes(name));
        variant.candidateFix = bundleCandidate(composition, variant, removed);
        add(variant);
      };
      const midpoint = Math.ceil(userBundles.length / 2);
      const halves = [userBundles.slice(0, midpoint), userBundles.slice(midpoint)];
      for (const half of halves.filter((half2) => half2.length)) {
        addSelection("v3-bundle-half-added", half, `Only this bundle half is retained: ${half.join(", ")}`);
      }
      for (const bundle of userBundles) {
        addSelection("v3-bundle-singleton", [bundle], `Only this bundle is retained: ${bundle}`);
      }
    }
    const limit = Math.max(1, budget.maxBoots);
    const plannedBundles = userBundles.length;
    budget.reserved = {
      v1: 1,
      v3: plannedBundles ? 1 + Math.ceil(Math.log2(plannedBundles)) + 1 : 0,
      v4: composition.extensionOverlayPaths.length ? 1 : 0,
      confirmation: 1
    };
    if (budget.reserved.v1 + budget.reserved.v3 + budget.reserved.v4 + budget.reserved.confirmation > limit) {
      budget.skipped.push({ variantId: "budget-shortfall", reason: "budget" });
    }
    for (const dropped of variants.slice(limit)) {
      budget.skipped.push({ variantId: dropped.id, reason: "budget" });
    }
    return variants.slice(0, limit);
  }
  /** Confirm that re-adding only the candidate set reproduces the original failure. */
  readdConfirmation(base, targetIds) {
    return bundleVariant(
      base,
      "v3-confirm-culprit",
      targetIds,
      `Re-add only the candidate bundle set (${targetIds.join(", ")}) to confirm it is the culprit.`
    );
  }
  explain(variant, evidence) {
    const current = evidence.find((item) => item.variantId === variant.id);
    if (!current || current.verdict !== "healthy") return void 0;
    if (variant.kind === "v1-reproduce") {
      return {
        category: "transient",
        confidence: "medium",
        culpritIds: [],
        humanSummary: "\u539F\u59CB\u7EC4\u5408\u5728\u6C99\u7BB1\u4E2D\u6062\u590D\u5065\u5EB7\uFF0C\u6545\u969C\u66F4\u63A5\u8FD1\u77AC\u6001\u542F\u52A8\u5931\u8D25\u3002"
      };
    }
    if (variant.kind === "v4-extension-overlays-removed") {
      return {
        category: "extension-overlay",
        confidence: "high",
        culpritIds: variant.removedOverlayPaths ?? [],
        humanSummary: "\u79FB\u9664\u6269\u5C55\u9644\u52A0\u5C42\u540E\u6C99\u7BB1\u901A\u8FC7\u5065\u5EB7\u63A2\u9488\u3002"
      };
    }
    return {
      category: "bundle",
      confidence: variant.candidateFix?.targetIds.length === 1 ? "high" : "medium",
      culpritIds: variant.candidateFix?.targetIds ?? [],
      humanSummary: `\u6536\u7F29 bundle \u7EC4\u5408\u540E\u6C99\u7BB1\u901A\u8FC7\u5065\u5EB7\u63A2\u9488\uFF1A${variant.candidateFix?.targetIds.join(", ") || "\u7EC4\u5408\u4EA4\u4E92"}`
    };
  }
};

// runtime-helper/upstream/recovery/recoverySession.ts
var SAFE_MAX_BOOTS = 8;
var TERMINAL_FAILURE_CLASSES = /* @__PURE__ */ new Set(["auth", "sandbox-build", "launcher"]);
var RecoverySession = class {
  constructor(options) {
    this.options = options;
    this.maxBoots = Math.max(1, Math.min(SAFE_MAX_BOOTS, options.maxBoots ?? SAFE_MAX_BOOTS));
    this.variants = options.variants ?? new VariantEngine();
  }
  maxBoots;
  variants;
  running;
  controller;
  sessionId;
  status;
  attribution;
  getStatus() {
    return this.status ? { ...this.status } : void 0;
  }
  getSessionId() {
    return this.sessionId;
  }
  async reconcileHealthyStart(composition) {
    if (this.running || this.sessionId) return;
    const loaded = await this.options.ledger.read();
    if (loaded.corrupt || !loaded.state.activeSessionId) return;
    const session = loaded.state.sessions.find(
      (item) => item.id === loaded.state.activeSessionId && !item.clean
    );
    if (!session) return;
    const entries = loaded.state.entries.filter((entry) => entry.sessionId === session.id);
    if (entries.some((entry) => entry.status === "planned")) return;
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
        attribution: session.attribution
      });
      this.publish({
        sessionId: session.id,
        phase: "recovered",
        usedBoots: session.budget.usedBoots,
        maxBoots: session.budget.maxBoots,
        summary: "Runtime started successfully; interrupted recovery was closed.",
        canRestore: entries.some(
          (entry) => entry.status === "applied" || entry.status === "verified"
        )
      });
    } finally {
      this.sessionId = void 0;
      await this.options.ledger.releaseLease().catch(() => void 0);
    }
  }
  cancel() {
    this.controller?.abort(new Error("Recovery was cancelled by the user"));
  }
  recover(composition, failureMessage, signal) {
    if (this.running) return this.running;
    this.sessionId = void 0;
    this.attribution = void 0;
    const controller = new AbortController();
    this.controller = controller;
    const relay = () => controller.abort(signal?.reason);
    signal?.addEventListener("abort", relay, { once: true });
    if (signal?.aborted) relay();
    let retainLease = false;
    this.running = this.run(composition, failureMessage, controller.signal).catch((error) => this.sessionId && controller.signal.aborted ? this.cancelled(this.sessionId) : this.unrecoverable(this.sessionId ?? "ledger-unavailable", String(error))).then((outcome) => {
      retainLease = outcome.status === "retry" || outcome.status === "candidate";
      return outcome;
    }).finally(() => {
      signal?.removeEventListener("abort", relay);
      if (this.controller === controller) this.controller = void 0;
      this.running = void 0;
      if (!retainLease) {
        return this.options.ledger.releaseLease().catch((error) => this.options.onLog?.(`Failed to release recovery lease: ${String(error)}`));
      }
    });
    return this.running;
  }
  confirm(composition, attribution = this.attribution) {
    return this.finish("recovered", attribution?.humanSummary ?? "Runtime recovered", composition, attribution);
  }
  fail(message) {
    return this.finish("unrecoverable", message);
  }
  async finish(phase, summary, composition, attribution = this.attribution) {
    const sessionId = this.sessionId;
    if (!sessionId) return;
    try {
      await this.options.ledger.finishSession(sessionId, phase, {
        composition,
        attribution,
        error: phase === "unrecoverable" ? summary : void 0
      });
      this.publishTerminal(sessionId, phase, summary);
    } finally {
      this.sessionId = void 0;
      await this.options.ledger.releaseLease().catch(() => void 0);
    }
  }
  async run(composition, failureMessage, signal) {
    const budget = this.createBudget();
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
      canRestore: false
    });
    const evidence = [];
    let usedBoots = session.budget.usedBoots;
    const maxBoots = Math.min(this.maxBoots, session.budget.maxBoots);
    const probe = async (variant, base = composition) => {
      signal.throwIfAborted();
      if (usedBoots >= maxBoots) return void 0;
      await this.options.ledger.reserveBoot(session.id);
      this.publish({
        sessionId: session.id,
        phase: "searching",
        usedBoots: ++usedBoots,
        maxBoots,
        currentVariant: variant.id,
        summary: variant.assumption,
        canRestore: false
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
        { ...variant, id: result.variantId },
        evidence
      );
      if (variant.kind === "v1-reproduce") {
        this.attribution = attribution;
        return {
          status: "retry",
          sessionId: session.id,
          composition,
          attribution,
          message: "The original composition passed the recovery health probe."
        };
      }
      const candidate = variant.candidateFix;
      if (!candidate) continue;
      if (candidate.kind === "disable-profile-bundles") {
        if (this.options.allowBundleIsolation?.() === false) continue;
        if (usedBoots + 2 > maxBoots) continue;
        const readd = this.variants.readdConfirmation(composition, candidate.targetIds);
        const original = evidence.find((item) => item.verdict === "process-error");
        const confirmation = await probe(readd, readd.composition);
        if (!confirmation || confirmation.verdict !== "process-error" || !original || confirmation.failureClass !== original.failureClass) {
          this.options.onLog?.(`Bundle attribution was not confirmed: ${candidate.targetIds.join(", ")}`);
          continue;
        }
      }
      if (usedBoots >= maxBoots) continue;
      const fix = {
        ...candidate,
        id: `${candidate.id}-${(0, import_node_crypto6.randomUUID)()}`,
        evidenceBootIds: evidence.map((item) => item.bootId)
      };
      let applied = false;
      try {
        signal.throwIfAborted();
        await this.options.ledger.planFix(session.id, fix, composition.compositionHash);
        const changed = await this.options.fixes.apply(fix, composition);
        applied = true;
        await this.options.ledger.markFixApplied(session.id, fix.id, changed.compositionHash);
        const verified = await probe({
          id: `${fix.id}-verify`,
          kind: variant.kind,
          parentHash: changed.compositionHash,
          assumption: "Verify the applied recovery change.",
          composition: changed
        }, changed);
        if (verified?.verdict !== "healthy") throw new Error("The applied recovery change did not pass its health probe.");
        this.attribution = attribution;
        this.publish({
          sessionId: session.id,
          phase: "fix-applied",
          usedBoots,
          maxBoots,
          currentVariant: variant.id,
          summary: attribution?.humanSummary ?? fix.reason,
          canRestore: true
        });
        return {
          status: "candidate",
          sessionId: session.id,
          composition: changed,
          fix,
          attribution,
          message: fix.reason
        };
      } catch (error) {
        if (applied) await this.options.fixes.rollback(fix.id);
        else await this.options.ledger.markFixConflict(session.id, fix.id, String(error));
        this.options.onLog?.(`Recovery fix rejected: ${String(error)}`);
        if (applied) throw error;
      }
    }
    const message = signal.aborted ? "Recovery was cancelled" : `Recovery found no verified fix after ${usedBoots} of ${maxBoots} allowed boots.`;
    return signal.aborted ? this.cancelled(session.id) : this.unrecoverable(session.id, message);
  }
  createBudget() {
    return {
      maxBoots: this.maxBoots,
      usedBoots: 0,
      // Bundle reservations depend on the composition and are filled by the planner.
      reserved: { v1: 1, v3: 0, v4: 1, confirmation: 1 },
      skipped: []
    };
  }
  async cancelled(sessionId) {
    await this.options.ledger.finishSession(sessionId, "cancelled", {
      attribution: this.attribution,
      error: "Recovery was cancelled"
    });
    this.publishTerminal(sessionId, "cancelled", "Recovery was cancelled");
    return { status: "cancelled", sessionId, message: "Recovery was cancelled" };
  }
  async unrecoverable(sessionId, message) {
    if (sessionId !== "ledger-unavailable" && sessionId !== "composition-unavailable" && sessionId !== "recovery-busy") {
      try {
        await this.options.ledger.finishSession(sessionId, "unrecoverable", {
          attribution: this.attribution,
          error: message
        });
      } catch (error) {
        this.options.onLog?.(`Failed to finalize recovery ledger: ${String(error)}`);
      }
    }
    this.publishTerminal(sessionId, "unrecoverable", message);
    return {
      status: "unrecoverable",
      sessionId,
      ...this.attribution === void 0 ? {} : { attribution: this.attribution },
      message
    };
  }
  publishTerminal(sessionId, phase, summary) {
    this.publish({
      sessionId,
      phase,
      summary,
      canRestore: true,
      usedBoots: this.status?.usedBoots ?? 0,
      maxBoots: this.maxBoots
    });
  }
  publish(status) {
    this.status = { ...status };
    this.options.onStatus?.({ ...status });
  }
};

// runtime-helper/upstream/recovery/diagnostics.ts
var import_node_crypto7 = require("node:crypto");
var import_promises5 = require("node:fs/promises");
var import_node_path6 = require("node:path");
var MAX_BOOT_BYTES = 2 * 1024 * 1024;
var MIN_TAIL_BYTES = 32 * 1024;
function redactRecoveryText(value) {
  return value.replace(/([?&](?:token|access_token|auth|api_key|apikey|secret|password)=)[^&\s"<>]+/giu, "$1<redacted>").replace(/((?:authorization|proxy-authorization|cookie|set-cookie)\s*:\s*)([^\r\n]+)/giu, "$1<redacted>").replace(/((?:[\w-]*(?:api[-_]?key|secret|password|credential|token))["']?\s*[=:]\s*)[^\r\n]+/giu, "$1<redacted>").replace(/\bBearer\s+[A-Za-z0-9._~+/-]+=*/giu, "Bearer <redacted>").replace(/\b(?:sk|key|token|secret|password)[-_]?[A-Za-z0-9]{16,}\b/giu, "<redacted>");
}
function diagnosticValue(value, key = "") {
  if (typeof value === "string") return redactRecoveryText(value);
  if (Array.isArray(value)) {
    return value.map((item, index) => /args$/iu.test(key) && index > 0 && typeof value[index - 1] === "string" && /^--?[\w-]*(?:token|secret|password|api[-_]?key)$/iu.test(value[index - 1]) ? "<redacted>" : diagnosticValue(item));
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([name, item]) => [
      name,
      /^(?:token|cookie|authorization|password|apiKey|secret)$/iu.test(name) ? "<redacted>" : diagnosticValue(item, name)
    ]));
  }
  return value;
}
function ignoreMissing(error) {
  if (error.code !== "ENOENT") throw error;
  return void 0;
}
function textBytes(value) {
  return Buffer.byteLength(value, "utf8");
}
function keepBounded(value) {
  if (textBytes(value) <= MAX_BOOT_BYTES) {
    return { value, truncated: false };
  }
  const bytes = Buffer.from(value, "utf8");
  const head = bytes.subarray(0, 32 * 1024).toString("utf8");
  const tail = bytes.subarray(-Math.max(MIN_TAIL_BYTES, MAX_BOOT_BYTES - textBytes(head) - 256)).toString("utf8");
  return {
    value: `${head}
[recovery log truncated]
${tail}`,
    truncated: true
  };
}
var RecoveryDiagnostics = class {
  directory;
  logsDirectory;
  exportsDirectory;
  constructor(storagePath) {
    this.directory = (0, import_node_path6.join)(storagePath, "recovery");
    this.logsDirectory = (0, import_node_path6.join)(this.directory, "logs");
    this.exportsDirectory = (0, import_node_path6.join)(this.directory, "diagnostics");
  }
  async beginBoot(sessionId, variantId, compositionHash, bootId = (0, import_node_crypto7.randomUUID)()) {
    if (!/^[a-zA-Z0-9-]+$/u.test(sessionId) || !/^[a-zA-Z0-9-]+$/u.test(bootId)) {
      throw new Error("Invalid recovery log identity");
    }
    const directory = (0, import_node_path6.join)(this.logsDirectory, sessionId);
    const path = (0, import_node_path6.join)(directory, `${bootId}.log`);
    await (0, import_promises5.mkdir)(directory, { recursive: true });
    await this.rotate(sessionId);
    let contents = [
      `bootId=${bootId}`,
      `sessionId=${sessionId}`,
      `variantId=${variantId}`,
      `compositionHash=${compositionHash}`,
      `startedAt=${(/* @__PURE__ */ new Date()).toISOString()}`,
      ""
    ].join("\n");
    let truncated = false;
    let pending2 = Promise.resolve();
    const enqueue = (write) => {
      pending2 = pending2.then(write, write);
      return pending2;
    };
    await (0, import_promises5.writeFile)(path, contents, { encoding: "utf8", mode: 384 });
    return {
      path,
      append: (stream, text) => enqueue(async () => {
        const safe = redactRecoveryText(text);
        const line = `[${stream}] ${safe}
`;
        const next = keepBounded(`${contents}${line}`);
        contents = next.value;
        if (next.truncated) {
          truncated = true;
          await (0, import_promises5.writeFile)(path, contents, { encoding: "utf8", mode: 384 });
        } else {
          await (0, import_promises5.appendFile)(path, line, { encoding: "utf8", mode: 384 });
        }
      }),
      finish: (summary) => enqueue(async () => {
        const tail = redactRecoveryText(summary.outputTail);
        const summaryText = [
          "",
          "summary:",
          JSON.stringify(diagnosticValue({
            bootId: summary.bootId,
            verdict: summary.verdict,
            failureClass: summary.failureClass,
            finishedAt: summary.finishedAt,
            durationMs: summary.durationMs,
            process: summary.process,
            endpoint: summary.endpoint,
            cleanup: summary.cleanup,
            classifierNotes: summary.classifierNotes,
            outputTail: tail,
            outputTruncated: summary.outputTruncated || truncated
          })),
          ""
        ].join("\n");
        const next = keepBounded(`${contents}${summaryText}`);
        await (0, import_promises5.writeFile)(path, next.value, { encoding: "utf8", mode: 384 });
      })
    };
  }
  async export(ledger, current, corrupt) {
    const exportId = `${(/* @__PURE__ */ new Date()).toISOString().replace(/[:.]/gu, "-")}-${(0, import_node_crypto7.randomUUID)().slice(0, 8)}`;
    const target = (0, import_node_path6.join)(this.exportsDirectory, exportId);
    await (0, import_promises5.mkdir)((0, import_node_path6.join)(target, "logs"), { recursive: true });
    const documents = {
      "manifest.json": {
        schemaVersion: 1,
        generatedAt: (/* @__PURE__ */ new Date()).toISOString(),
        sessionId: ledger.activeSessionId,
        revision: ledger.revision
      },
      "ledger.json": ledger,
      "composition-current.json": current ?? null,
      "composition-last-known-good.json": ledger.lastKnownGood ?? null,
      "composition-diff.json": compositionDiff(current, ledger.lastKnownGood)
    };
    for (const [name, value] of Object.entries(documents)) {
      await (0, import_promises5.writeFile)(
        (0, import_node_path6.join)(target, name),
        `${JSON.stringify(diagnosticValue(value), null, 2)}
`,
        { encoding: "utf8", mode: 384 }
      );
    }
    await (0, import_promises5.writeFile)(
      (0, import_node_path6.join)(target, "conclusion.txt"),
      redactRecoveryText(corrupt?.message ?? ledger.sessions.at(-1)?.error ?? ledger.sessions.at(-1)?.attribution?.humanSummary ?? "No recovery recorded."),
      { encoding: "utf8", mode: 384 }
    );
    if (corrupt) {
      await (0, import_promises5.writeFile)(
        (0, import_node_path6.join)(target, "ledger-corrupt.txt"),
        redactRecoveryText(await (0, import_promises5.readFile)(corrupt.path, "utf8")),
        { encoding: "utf8", mode: 384 }
      );
    }
    await this.copyRecentLogs(target);
    return target;
  }
  async copyRecentLogs(target) {
    const recent = (await this.recentSessions()).slice(0, 5);
    for (const { name: session } of recent) {
      const files = await (0, import_promises5.readdir)((0, import_node_path6.join)(this.logsDirectory, session), { withFileTypes: true }).catch(ignoreMissing);
      if (!files) continue;
      const destination = (0, import_node_path6.join)(target, "logs", (0, import_node_path6.basename)(session));
      await (0, import_promises5.mkdir)(destination, { recursive: true });
      for (const file of files) {
        if (!file.isFile() || !file.name.endsWith(".log")) continue;
        const contents = await (0, import_promises5.readFile)((0, import_node_path6.join)(this.logsDirectory, session, file.name), "utf8").catch(ignoreMissing);
        if (contents === void 0) continue;
        await (0, import_promises5.writeFile)(
          (0, import_node_path6.join)(destination, file.name),
          redactRecoveryText(contents),
          { encoding: "utf8", mode: 384 }
        );
      }
    }
  }
  async recentSessions(exclude) {
    const entries = await (0, import_promises5.readdir)(this.logsDirectory, { withFileTypes: true }).catch(ignoreMissing) ?? [];
    const dated = [];
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name === exclude) continue;
      const info = await (0, import_promises5.stat)((0, import_node_path6.join)(this.logsDirectory, entry.name)).catch(ignoreMissing);
      if (info) dated.push({ name: entry.name, time: info.mtimeMs });
    }
    return dated.sort((a, b) => b.time - a.time);
  }
  async rotate(active) {
    for (const item of (await this.recentSessions(active)).slice(4)) {
      await (0, import_promises5.rm)((0, import_node_path6.join)(this.logsDirectory, item.name), { recursive: true, force: true }).catch(() => void 0);
    }
  }
};

// runtime-helper/upstream/recovery/fixExecutor.ts
var import_node_crypto8 = require("node:crypto");
var import_promises6 = require("node:fs/promises");
var import_node_path7 = require("node:path");
function sha2562(value) {
  return (0, import_node_crypto8.createHash)("sha256").update(value).digest("hex");
}
function samePath(left, right) {
  return pathKey2(left) === pathKey2(right);
}
function pathKey2(path) {
  const resolved = (0, import_node_path7.resolve)(path);
  return process.platform === "win32" ? resolved.toLowerCase() : resolved;
}
var FixConflictError = class extends Error {
  /** Entries that were already reverted before this conflict was detected. */
  restored;
  constructor(message, restored = []) {
    super(message);
    this.name = "FixConflictError";
    this.restored = restored;
  }
};
var FixExecutor = class {
  constructor(ledger, output) {
    this.ledger = ledger;
    this.output = output;
    this.backupsDirectory = (0, import_node_path7.join)(ledger.directory, "backups");
  }
  backupsDirectory;
  async filterLaunchArgs(args) {
    const entries = await this.ledger.readEntries();
    const removed = new Set(entries.filter((entry) => entry.status === "applied" || entry.status === "verified").flatMap((entry) => entry.fix.removedPatchPaths ?? []).map(pathKey2));
    if (!removed.size) return [...args];
    const result = [];
    for (let index = 0; index < args.length; index += 1) {
      const argument = args[index];
      if (argument === "--patch" && args[index + 1]) {
        const path = pathKey2(args[index + 1]);
        if (!removed.has(path)) result.push(argument, args[index + 1]);
        index += 1;
      } else if (!argument.startsWith("--patch=") || !removed.has(pathKey2(argument.slice("--patch=".length)))) {
        result.push(argument);
      }
    }
    return result;
  }
  async apply(fix, composition) {
    await this.ledger.assertLease();
    if (fix.kind === "remove-extension-overlay") {
      return recomputeComposition(composition, {
        patchLayers: composition.patchLayers.filter((layer) => !(fix.removedPatchPaths ?? []).some((path) => samePath(path, layer.path))),
        extensionOverlayPaths: composition.extensionOverlayPaths.filter((path) => !(fix.removedPatchPaths ?? []).some((candidate) => samePath(candidate, path)))
      });
    }
    const manifestPath = fix.profileManifestPath;
    if (!manifestPath) throw new FixConflictError("Recovery bundle fix has no profile manifest");
    await assertRegularFile(manifestPath);
    const original = await (0, import_promises6.readFile)(manifestPath, "utf8");
    const beforeHash = sha2562(original);
    if (fix.expectedProfileManifestHash && beforeHash !== fix.expectedProfileManifestHash) {
      throw new FixConflictError(`Profile manifest changed before recovery: ${manifestPath}`);
    }
    let parsed;
    try {
      const value = JSON.parse(original);
      if (!isRecord(value)) throw new Error("root is not an object");
      parsed = value;
    } catch (error) {
      throw new FixConflictError(`Profile manifest is invalid JSON: ${String(error)}`);
    }
    const dsh = isRecord(parsed.dsh) ? parsed.dsh : {};
    const profile = isRecord(dsh.profile) ? dsh.profile : {};
    const bundles = profile.bundles;
    if (!Array.isArray(bundles) || !bundles.every((item) => typeof item === "string")) {
      throw new FixConflictError("Profile manifest has no valid dsh.profile.bundles");
    }
    const removed = new Set(fix.targetIds);
    const nextBundles = bundles.filter((item) => !removed.has(item));
    if (nextBundles.length === bundles.length) {
      throw new FixConflictError("Recovery bundle is no longer present");
    }
    const nextContents = `${JSON.stringify({
      ...parsed,
      dsh: { ...dsh, profile: { ...profile, bundles: nextBundles } }
    }, null, 2)}
`;
    const afterHash = sha2562(nextContents);
    await (0, import_promises6.mkdir)(this.backupsDirectory, { recursive: true });
    const backup = {
      schemaVersion: 1,
      fixId: fix.id,
      target: manifestPath,
      beforeHash,
      afterHash,
      original
    };
    await atomicWrite((0, import_node_path7.join)(this.backupsDirectory, `${fix.id}.json`), `${JSON.stringify(backup, null, 2)}
`);
    await this.ledger.assertLease();
    if (sha2562(await (0, import_promises6.readFile)(manifestPath, "utf8")) !== beforeHash) {
      throw new FixConflictError(`Profile manifest changed during recovery: ${manifestPath}`);
    }
    await assertRegularFile(manifestPath);
    await atomicWrite(manifestPath, nextContents);
    this.output?.appendLine(`[dsh:recovery] applied ${fix.id} to ${manifestPath}`);
    return recomputeComposition(composition, {
      bundles: composition.bundles.map((bundle) => ({
        ...bundle,
        selected: !removed.has(bundle.packageName)
      })),
      profilePackageHash: afterHash
    });
  }
  async restore() {
    await this.ledger.acquireLease();
    try {
      return await this.restoreEntries((await this.ledger.readEntries()).filter((entry) => entry.status === "applied" || entry.status === "verified"));
    } finally {
      await this.ledger.releaseLease().catch(() => void 0);
    }
  }
  async rollback(fixId) {
    await this.ledger.assertLease();
    const entry = (await this.ledger.readEntries()).find((entry2) => entry2.id === fixId);
    if (!entry) throw new FixConflictError(`Recovery fix ${fixId} does not exist`);
    await this.restoreEntries([entry]);
  }
  async restoreEntries(entries) {
    const restored = [];
    const conflicts = [];
    for (const entry of [...entries].reverse()) {
      if (entry.fix.kind === "remove-extension-overlay") {
        await this.ledger.restoreEntry(entry.id);
        restored.push(entry.id);
        continue;
      }
      try {
        const backup = await this.readBackup(entry);
        const current = await (0, import_promises6.readFile)(backup.target, "utf8");
        if (sha2562(current) !== backup.afterHash) {
          throw new FixConflictError(`Profile manifest changed after recovery: ${backup.target}`);
        }
        await assertRegularFile(backup.target);
        await atomicWrite(backup.target, backup.original);
        await this.ledger.restoreEntry(entry.id);
        restored.push(entry.id);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        await this.ledger.restoreEntry(entry.id, "conflicted", message);
        conflicts.push(message);
      }
    }
    if (conflicts.length) throw new FixConflictError(conflicts.join("; "), restored);
    return restored;
  }
  async readBackup(entry) {
    try {
      const value = JSON.parse(await (0, import_promises6.readFile)(
        (0, import_node_path7.join)(this.backupsDirectory, `${entry.id}.json`),
        "utf8"
      ));
      if (!isRecord(value) || value.schemaVersion !== 1 || value.fixId !== entry.id || typeof value.target !== "string" || typeof value.beforeHash !== "string" || typeof value.afterHash !== "string" || typeof value.original !== "string" || sha2562(value.original) !== value.beforeHash) {
        throw new Error("invalid recovery backup");
      }
      return value;
    } catch (error) {
      throw new FixConflictError(`Recovery backup is unavailable for ${entry.id}: ${String(error)}`);
    }
  }
};
async function assertRegularFile(path) {
  const info = await (0, import_promises6.lstat)(path);
  if (!info.isFile() || info.isSymbolicLink()) throw new FixConflictError(`Refusing to restore non-regular file: ${path}`);
}

// runtime-helper/upstream/recovery/healthOracle.ts
var import_node_crypto10 = require("node:crypto");
var import_node_string_decoder = require("node:string_decoder");

// runtime-helper/upstream/remote/unaryClient.ts
var import_node_crypto9 = require("node:crypto");

// runtime-helper/upstream/remote/contracts.ts
var REMOTE_API_PREFIX = "/api/";
var REMOTE_EVENT_STREAM_ENDPOINT = "$events";
var REMOTE_EVENT_RESULT_ENDPOINT = "$events/result";
function remoteEndpointUrl(baseUrl, endpoint) {
  assertRemoteEndpoint(endpoint);
  return new URL(`${REMOTE_API_PREFIX}${endpoint}`, `${baseUrl.replace(/\/+$/u, "")}/`).toString();
}
function assertRemoteEndpoint(endpoint) {
  const segments = endpoint.split("/");
  if (segments.length !== 2 || segments.some(
    (segment) => segment.length === 0 || segment === "." || segment === ".." || !/^[A-Za-z0-9_$.-]+$/u.test(segment)
  )) {
    if (endpoint !== REMOTE_EVENT_STREAM_ENDPOINT && endpoint !== REMOTE_EVENT_RESULT_ENDPOINT) {
      throw new Error(`Remote endpoint is invalid: ${JSON.stringify(endpoint)}`);
    }
  }
}
function parseRemoteServerResponse(value) {
  if (!isPlainRecord(value) || !exactKeys(value, ["type", "rpcId", "result"]) || value.type !== "server-response" || !isNonEmptyString(value.rpcId)) {
    throw new TypeError("Remote response is not a server-response envelope");
  }
  if (!isPlainRecord(value.result)) {
    throw new TypeError("Remote response has no result envelope");
  }
  const result = value.result;
  if (result.ok === true) {
    if (!(exactKeys(result, ["ok"]) || exactKeys(result, ["ok", "value"])) || Object.hasOwn(result, "value") && !isRemoteJsonValue(result.value)) {
      throw new TypeError("Remote response value is not JSON-safe");
    }
    return {
      type: "server-response",
      rpcId: value.rpcId,
      result: Object.hasOwn(result, "value") ? { ok: true, value: result.value } : { ok: true }
    };
  }
  if (result.ok !== false || !exactKeys(result, ["ok", "error"]) || !isPlainRecord(result.error)) {
    throw new TypeError("Remote response has an invalid result");
  }
  const error = result.error;
  if (!exactKeys(error, ["code", "message", "details"]) || !isNonEmptyString(error.code) || typeof error.message !== "string" || !isPlainRecord(error.details) || !isRemoteJsonValue(error.details)) {
    throw new TypeError("Remote response has an invalid failure");
  }
  return {
    type: "server-response",
    rpcId: value.rpcId,
    result: {
      ok: false,
      error: {
        code: error.code,
        message: error.message,
        details: error.details
      }
    }
  };
}
function isRemoteJsonValue(value) {
  return visitJsonValue(value, /* @__PURE__ */ new Set());
}
function isRecord2(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function isPlainRecord(value) {
  return isRecord2(value) && (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
}
function isNonEmptyString(value) {
  return typeof value === "string" && value.length > 0;
}
function exactKeys(value, expected) {
  const keys = Reflect.ownKeys(value);
  return keys.length === expected.length && expected.every((key) => Object.hasOwn(value, key));
}
function visitJsonValue(value, ancestors) {
  if (value === null || typeof value === "string" || typeof value === "boolean") return true;
  if (typeof value === "number") return Number.isFinite(value) && !Object.is(value, -0);
  if (typeof value !== "object" || ancestors.has(value)) return false;
  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      if (Object.getPrototypeOf(value) !== Array.prototype || Reflect.ownKeys(value).length !== value.length + 1) {
        return false;
      }
      for (let index = 0; index < value.length; index += 1) {
        if (!Object.hasOwn(value, index) || !visitJsonValue(value[index], ancestors)) return false;
      }
      return true;
    }
    if (!isPlainRecord(value)) return false;
    for (const key of Reflect.ownKeys(value)) {
      if (typeof key !== "string") return false;
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (descriptor?.enumerable !== true || !visitJsonValue(Reflect.get(value, key), ancestors)) return false;
    }
    return true;
  } finally {
    ancestors.delete(value);
  }
}

// runtime-helper/upstream/remote/unaryClient.ts
var RemoteUnaryClient = class {
  constructor(options) {
    this.options = options;
    this.doFetch = options.fetch ?? fetch;
    this.mintRpcId = options.mintRpcId ?? import_node_crypto9.randomUUID;
  }
  doFetch;
  mintRpcId;
  async call(endpoint, args = {}, signal) {
    assertRemoteEndpoint(endpoint);
    if (!isPlainRecord2(args) || !isRemoteJsonValue(args)) {
      throw new TypeError(`Remote ${endpoint} args must be a plain object`);
    }
    const base = this.baseUrl();
    const rpcId = this.mintRpcId();
    const request = {
      type: "client-request",
      rpcId,
      method: endpoint,
      payload: { args }
    };
    const controller = new AbortController();
    const relayAbort = () => controller.abort(signal?.reason);
    signal?.addEventListener("abort", relayAbort, { once: true });
    if (signal?.aborted) relayAbort();
    const timeoutMs = this.timeoutMs();
    const timeout = setTimeout(
      () => controller.abort(new Error(`Remote RPC ${endpoint} timed out`)),
      timeoutMs
    );
    try {
      const response = await this.doFetch(remoteEndpointUrl(base, endpoint), {
        method: "POST",
        headers: {
          ...this.requestHeaders(),
          "content-type": "application/json"
        },
        body: JSON.stringify(request),
        signal: controller.signal
      });
      if (!response.ok) {
        throw new RemoteHttpError(endpoint, response.status);
      }
      let decoded;
      try {
        decoded = await response.json();
      } catch (cause) {
        throw new RemoteProtocolError(`Remote ${endpoint} returned invalid JSON`, { cause });
      }
      let full;
      try {
        full = parseRemoteServerResponse(decoded);
      } catch (cause) {
        throw new RemoteProtocolError(`Remote ${endpoint} returned an invalid response`, { cause });
      }
      if (full.rpcId !== rpcId) {
        throw new RemoteProtocolError(
          `Remote ${endpoint} rpcId mismatch: sent ${rpcId}, received ${full.rpcId}`
        );
      }
      if (!full.result.ok) {
        throw RemoteError.fromFailure(full.result.error, endpoint);
      }
      return full.result.value;
    } catch (error) {
      if (controller.signal.aborted && !signal?.aborted && isAbortError(error)) {
        throw new Error(`Remote RPC ${endpoint} timed out`);
      }
      throw error;
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", relayAbort);
    }
  }
  /** A small authenticated probe used by startup diagnostics and health checks. */
  async probe(signal) {
    await this.call("session/list", { _request: {} }, signal);
  }
  baseUrl() {
    const configured = typeof this.options.baseUrl === "function" ? this.options.baseUrl() : this.options.baseUrl;
    if (!configured) throw new Error("DSH Runtime is not connected");
    return configured;
  }
  timeoutMs() {
    const value = typeof this.options.timeoutMs === "function" ? this.options.timeoutMs() : this.options.timeoutMs ?? 6e5;
    return Number.isFinite(value) && value > 0 ? value : 6e5;
  }
  requestHeaders() {
    return this.options.requestHeaders?.() ?? {};
  }
};
function isPlainRecord2(value) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

// runtime-helper/upstream/recovery/sandbox.ts
var import_promises7 = require("node:fs/promises");
var import_node_os2 = require("node:os");
var import_node_path8 = require("node:path");
var SENSITIVE_ENV_NAME = /(?:api[-_]?key|auth|credential|password|secret|token|cookie|private[-_]?key)/iu;
var SandboxBuildError = class extends Error {
  constructor(message, options) {
    super(message);
    this.name = "SandboxBuildError";
    if (options?.cause !== void 0) this.cause = options.cause;
  }
};
function normalized(path) {
  const normalized2 = (0, import_node_path8.resolve)(path).replace(/\\/gu, "/");
  return process.platform === "win32" ? normalized2.toLowerCase() : normalized2;
}
function materializedPortArgs(args) {
  const result = [];
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--port" || argument === "-p" || argument === "--host") {
      index += 1;
      continue;
    }
    if (argument.startsWith("--port=") || argument.startsWith("--host=")) continue;
    result.push(argument);
  }
  result.push("--host", "127.0.0.1");
  result.push("--port", "0");
  if (!result.includes("--no-open")) result.push("--no-open");
  return result;
}
function patchArgs(args, patchMap, allowedPaths, cwd) {
  const result = [];
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    let source;
    let inline = false;
    if (argument === "--patch") {
      source = args[index + 1];
      index += 1;
    } else if (argument.startsWith("--patch=")) {
      source = argument.slice("--patch=".length);
      inline = true;
    }
    if (source !== void 0) {
      const key = normalized((0, import_node_path8.resolve)(cwd, source));
      if (!allowedPaths.has(key)) continue;
      const mapped = patchMap.get(key);
      if (!mapped) throw new SandboxBuildError(`Patch layer was not materialized: ${source}`);
      if (inline) result.push(`--patch=${mapped}`);
      else result.push("--patch", mapped);
      continue;
    }
    result.push(argument);
  }
  return materializedPortArgs(result);
}
async function exists(path) {
  try {
    await (0, import_promises7.lstat)(path);
    return true;
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
}
async function copyIfPresent(source, target) {
  if (!await exists(source)) return false;
  await (0, import_promises7.mkdir)((0, import_node_path8.dirname)(target), { recursive: true });
  await (0, import_promises7.cp)(source, target, { recursive: true, force: true });
  return true;
}
async function linkOrCopy(source, target) {
  await (0, import_promises7.mkdir)((0, import_node_path8.dirname)(target), { recursive: true });
  try {
    await (0, import_promises7.symlink)(source, target, process.platform === "win32" ? "junction" : "dir");
  } catch (error) {
    try {
      await (0, import_promises7.cp)(source, target, { recursive: true, force: true });
    } catch (copyError) {
      throw new SandboxBuildError(`Unable to project bundle ${source}`, { cause: copyError ?? error });
    }
  }
}
async function rewriteProfileManifest(source, target, variant) {
  let value = {};
  if (await exists(source)) {
    try {
      const parsed = JSON.parse(await (0, import_promises7.readFile)(source, "utf8"));
      if (isRecord(parsed)) value = parsed;
    } catch (error) {
      throw new SandboxBuildError(`Unable to parse profile manifest ${source}`, { cause: error });
    }
  }
  if (variant.bundleSelection !== void 0) {
    const currentDsh = isRecord(value.dsh) ? value.dsh : {};
    const currentProfile = isRecord(currentDsh.profile) ? currentDsh.profile : {};
    value.dsh = {
      ...currentDsh,
      profile: {
        ...currentProfile,
        bundles: [...variant.bundleSelection]
      }
    };
  }
  await (0, import_promises7.mkdir)((0, import_node_path8.dirname)(target), { recursive: true });
  await (0, import_promises7.writeFile)(target, `${JSON.stringify(value, null, 2)}
`, { encoding: "utf8", mode: 384 });
}
var SandboxManager = class {
  constructor(parentDirectory = (0, import_node_os2.tmpdir)()) {
    this.parentDirectory = parentDirectory;
  }
  async create(composition, variant) {
    const root = await (0, import_promises7.mkdtemp)((0, import_node_path8.join)(this.parentDirectory, "dsh-recovery-"));
    const dshHome = (0, import_node_path8.join)(root, "home");
    const temp = (0, import_node_path8.join)(root, "tmp");
    const workspace = (0, import_node_path8.join)(root, "workspace");
    const profile = variant.composition.profile || profileNameFromArgs(composition.appArgs);
    const profileDir = (0, import_node_path8.join)(dshHome, "profiles", profile);
    try {
      await (0, import_promises7.mkdir)(workspace, { recursive: true });
      await (0, import_promises7.mkdir)(temp, { recursive: true });
      await (0, import_promises7.mkdir)(profileDir, { recursive: true });
      const sourceHome = composition.environment.dshHome || process.env.DSH_HOME || (0, import_node_path8.join)((0, import_node_os2.homedir)(), ".dsh");
      const sourceProfile = (0, import_node_path8.join)(sourceHome, "profiles", profile);
      const sourceManifest = composition.profileManifestPath || (0, import_node_path8.join)(sourceProfile, "package.json");
      await rewriteProfileManifest(sourceManifest, (0, import_node_path8.join)(profileDir, "package.json"), variant);
      await copyIfPresent((0, import_node_path8.join)(sourceProfile, "cordis.patch.yml"), (0, import_node_path8.join)(profileDir, "cordis.patch.yml"));
      await copyIfPresent((0, import_node_path8.join)(sourceHome, "cordis.patch.yml"), (0, import_node_path8.join)(dshHome, "cordis.patch.yml"));
      const patchMap = /* @__PURE__ */ new Map();
      for (const [index, layer] of variant.composition.patchLayers.entries()) {
        const source = layer.path;
        const target = (0, import_node_path8.join)(root, "patches", `${String(index).padStart(3, "0")}-${(0, import_node_path8.basename)(source)}`);
        if (!await copyIfPresent(source, target)) {
          throw new SandboxBuildError(`Patch layer does not exist: ${source}`);
        }
        patchMap.set(normalized(source), target);
      }
      const selectedBundles = variant.composition.bundles.filter((bundle) => bundle.selected && bundle.origin !== "installation");
      for (const bundle of selectedBundles) {
        if (!bundle.packageDir) {
          throw new SandboxBuildError(`Bundle directory is unavailable: ${bundle.packageName}`);
        }
        const target = (0, import_node_path8.join)(profileDir, "node_modules", bundle.packageName);
        await linkOrCopy(bundle.packageDir, target);
      }
      const allowedPaths = new Set(variant.composition.patchLayers.map((layer) => normalized(layer.path)));
      const env2 = {};
      for (const [name, value] of Object.entries(process.env)) {
        if (!SENSITIVE_ENV_NAME.test(name)) env2[name] = value;
      }
      Object.assign(env2, {
        DSH_HOME: dshHome,
        TMPDIR: temp,
        TMP: temp,
        TEMP: temp,
        HOME: dshHome,
        ...process.platform === "win32" ? { USERPROFILE: dshHome } : {}
      });
      const launch = {
        command: composition.binary.command,
        args: patchArgs(composition.appArgs, patchMap, allowedPaths, composition.environment.cwd),
        cwd: workspace,
        env: env2,
        source: composition.binary.source
      };
      return {
        root,
        dshHome,
        workspace,
        launch,
        cleanup: async () => {
          let secretFilesRemoved = true;
          for (const path of [
            (0, import_node_path8.join)(dshHome, ".env"),
            (0, import_node_path8.join)(dshHome, ".credentials.yaml"),
            (0, import_node_path8.join)(profileDir, ".env"),
            (0, import_node_path8.join)(profileDir, ".credentials.yaml")
          ]) {
            try {
              await (0, import_promises7.rm)(path, { force: true });
            } catch {
              secretFilesRemoved = false;
            }
          }
          try {
            await (0, import_promises7.rm)(root, { recursive: true, force: true });
            return { removed: true, secretFilesRemoved };
          } catch {
            return { removed: false, secretFilesRemoved, deferredCleanup: true };
          }
        }
      };
    } catch (error) {
      try {
        await (0, import_promises7.rm)(root, { recursive: true, force: true });
      } catch {
      }
      if (error instanceof SandboxBuildError) throw error;
      throw new SandboxBuildError(`Unable to build recovery sandbox ${root}`, { cause: error });
    }
  }
};

// runtime-helper/upstream/recovery/healthOracle.ts
var pause2 = (ms) => new Promise((resolve6) => setTimeout(resolve6, ms));
function isAuthenticationFailure(error) {
  return error instanceof RemoteHttpError && error.isAuthenticationFailure;
}
var HealthOracle = class {
  constructor(sandboxManager = new SandboxManager(), options = {}) {
    this.sandboxManager = sandboxManager;
    this.options = options;
  }
  async evaluate(composition, variant, options = {}) {
    const started = Date.now();
    const bootId = (0, import_node_crypto10.randomUUID)();
    const controller = new AbortController();
    const relay = () => controller.abort(options.signal?.reason);
    options.signal?.addEventListener("abort", relay, { once: true });
    if (options.signal?.aborted) relay();
    const timer = setTimeout(() => controller.abort(new Error("Recovery boot timed out")), this.options.timeoutMs ?? 2e4);
    let sandbox;
    let child2;
    let log2;
    let endpoint;
    let launchError;
    let exited = false;
    let wrapper = false;
    const notes = [];
    const evidence = {
      bootId,
      variantId: variant.id,
      verdict: "unhealthy",
      failureClass: "unknown",
      startedAt: new Date(started).toISOString(),
      finishedAt: "",
      durationMs: 0,
      process: { launcherExited: false, descendantOwnership: "not-needed" },
      outputTail: "",
      outputTruncated: false,
      cleanup: { processStopped: true, secretFilesRemoved: true, sandboxRemoved: true },
      classifierNotes: []
    };
    try {
      controller.signal.throwIfAborted();
      if (options.sessionId && this.options.diagnostics) {
        log2 = await this.options.diagnostics.beginBoot(
          options.sessionId,
          variant.id,
          variant.composition.compositionHash,
          bootId
        );
      }
      sandbox = await this.sandboxManager.create(composition, variant);
      controller.signal.throwIfAborted();
      const launch = sandbox.launch;
      const shell = process.platform === "win32" && !/\.exe$/iu.test(launch.command);
      wrapper = shell || /\b(?:pnpm|npx)\b/iu.test(launch.source);
      if (shell && [launch.command, ...launch.args].some((value) => /["%!\r\n&|<>^]/u.test(value))) {
        throw new Error("Recovery launcher arguments cannot be safely invoked through cmd.exe");
      }
      child2 = spawnOwnedRuntime(
        shell ? `"${launch.command}"` : launch.command,
        shell ? launch.args.map((value) => `"${value}"`) : [...launch.args],
        {
          cwd: launch.cwd,
          env: launch.env,
          shell,
          stdio: ["ignore", "pipe", "pipe"],
          windowsHide: true
        }
      );
      evidence.process.pid = child2.pid;
      evidence.process.descendantOwnership = "owned";
      const output = (text, stream) => {
        for (const match of text.matchAll(/http:\/\/(?:127\.0\.0\.1|localhost|\[::1\]):\d+(?:\/\?token=[A-Za-z0-9_-]+)?/gu)) {
          const url = new URL(match[0]);
          const authenticated = url.searchParams.has("token");
          if (endpoint && !authenticated) continue;
          if (endpoint?.launchUrl === url.href) continue;
          endpoint = {
            baseUrl: url.origin,
            ...authenticated ? { launchUrl: url.href } : {}
          };
        }
        const safe = redactRecoveryText(text);
        const bytes = Buffer.from(evidence.outputTail + safe);
        evidence.outputTruncated ||= bytes.length > 32 * 1024;
        evidence.outputTail = bytes.subarray(-32 * 1024).toString("utf8");
        this.options.onOutput?.(safe);
        if (log2) void log2.append(stream, safe).catch((error) => notes.push(redactRecoveryText(String(error))));
      };
      for (const [stream, pipe] of [["stdout", child2.stdout], ["stderr", child2.stderr]]) {
        const decoder = new import_node_string_decoder.StringDecoder("utf8");
        let pending2 = "";
        let oversized = false;
        pipe?.on("data", (chunk) => {
          pending2 += decoder.write(chunk);
          let newline;
          while ((newline = pending2.indexOf("\n")) >= 0) {
            const line = pending2.slice(0, newline + 1);
            output(oversized || line.length > 64 * 1024 ? "[oversized output line omitted]\n" : line, stream);
            pending2 = pending2.slice(newline + 1);
            oversized = false;
          }
          if (pending2.length > 64 * 1024) {
            pending2 = "";
            oversized = true;
          }
        });
        pipe?.once("end", () => {
          const tail = decoder.end();
          pending2 += tail;
          if (pending2 && !oversized) output(pending2, stream);
          pending2 = "";
        });
      }
      child2.once("error", (error) => {
        launchError = error;
        exited = true;
      });
      child2.once("exit", () => {
        exited = true;
      });
      while (true) {
        controller.signal.throwIfAborted();
        if (exited) {
          const packageManagerError = wrapper && /(?:ERR_PNPM_|npm\s+(?:ERR!|error)\b)/iu.test(evidence.outputTail);
          evidence.failureClass = launchError || packageManagerError ? "launcher" : "boot-exit";
          throw launchError ?? new Error("Recovery Runtime exited before becoming healthy");
        }
        if (endpoint) {
          try {
            if (endpoint.launchUrl && !endpoint.cookie) {
              const response = await fetch(endpoint.launchUrl, {
                redirect: "manual",
                signal: AbortSignal.any([controller.signal, AbortSignal.timeout(1500)])
              });
              const cookie = response.headers.get("set-cookie")?.split(";", 1)[0]?.trim();
              if (response.status !== 303 || !cookie || !/^[^=;]+=[^;]*$/u.test(cookie)) {
                throw response.status === 401 || response.status === 403 ? new RemoteHttpError(endpoint.baseUrl, response.status) : new Error(`Runtime authentication returned HTTP ${response.status}`);
              }
              endpoint.cookie = cookie;
            }
            await new RemoteUnaryClient({
              baseUrl: endpoint.baseUrl,
              timeoutMs: 1500,
              requestHeaders: () => endpoint?.cookie === void 0 ? {} : { cookie: endpoint.cookie }
            }).probe(controller.signal);
            controller.signal.throwIfAborted();
            if (exited) continue;
            evidence.verdict = "healthy";
            evidence.failureClass = "none";
            evidence.endpoint = { baseUrl: endpoint.baseUrl, authenticated: Boolean(endpoint.cookie), probe: "session/list" };
            break;
          } catch (error) {
            notes.push(redactRecoveryText(String(error)));
            if (notes.length > 20) notes.shift();
            if (isAuthenticationFailure(error)) {
              evidence.failureClass = "auth";
              throw error;
            }
          }
        }
        await pause2(100);
      }
    } catch (error) {
      notes.push(redactRecoveryText(String(error)));
      evidence.verdict = options.signal?.aborted ? "cancelled" : controller.signal.aborted ? "timeout" : error instanceof SandboxBuildError ? "sandbox-error" : "process-error";
      if (evidence.failureClass === "unknown") {
        evidence.failureClass = error instanceof SandboxBuildError ? "sandbox-build" : controller.signal.aborted ? "boot-timeout" : "launcher";
      }
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", relay);
      evidence.process.launcherExited = exited;
      if (child2) {
        evidence.process.exitCode = child2.exitCode;
        evidence.process.signal = child2.signalCode;
        try {
          const deadDirect = process.platform === "win32" && !wrapper && (child2.exitCode !== null || child2.signalCode !== null);
          if (!deadDirect) await terminateOwnedRuntime(child2);
          evidence.process.descendantOwnership = "verified-exited";
        } catch (error) {
          evidence.cleanup.processStopped = false;
          evidence.process.descendantOwnership = "unknown";
          notes.push(redactRecoveryText(String(error)));
        }
      }
      if (sandbox && evidence.cleanup.processStopped) {
        const cleanup = await sandbox.cleanup();
        evidence.cleanup.secretFilesRemoved = cleanup.secretFilesRemoved;
        evidence.cleanup.sandboxRemoved = cleanup.removed;
        evidence.sandboxPath = sandbox.root;
      } else if (sandbox) {
        evidence.cleanup.secretFilesRemoved = false;
        evidence.cleanup.sandboxRemoved = false;
        evidence.sandboxPath = sandbox.root;
        notes.push("Sandbox retained because Runtime process ownership was not verified.");
      }
      if (!evidence.cleanup.processStopped || !evidence.cleanup.sandboxRemoved || !evidence.cleanup.secretFilesRemoved) {
        evidence.cleanup.deferredCleanup = true;
        if (evidence.verdict === "healthy") {
          evidence.verdict = "sandbox-error";
          evidence.failureClass = "sandbox-cleanup";
        }
      }
      evidence.durationMs = Date.now() - started;
      evidence.finishedAt = (/* @__PURE__ */ new Date()).toISOString();
      evidence.classifierNotes = notes.slice(-20);
      if (log2) {
        evidence.logPath = log2.path;
        await log2.finish(evidence);
      }
    }
    return evidence;
  }
};

// runtime-helper/main.ts
var exec2 = (0, import_node_util3.promisify)(import_node_child_process3.execFile);
var input = (0, import_node_readline.createInterface)({ input: process.stdin });
input.on("line", (line) => {
  try {
    acceptReply(JSON.parse(line));
  } catch {
  }
});
var cancellation = new AbortController();
var child;
var recovery;
var closing = false;
var emit = (event, value) => process.stdout.write("\nDSH_INTELLIJ_HELPER " + JSON.stringify({ event, value }) + "\n");
var log = (message) => process.stdout.write(redactRecoveryText(message) + "\n");
var pause3 = (ms) => new Promise((resolve6, reject) => {
  cancellation.signal.throwIfAborted();
  const abort = () => {
    clearTimeout(timer);
    reject(cancellation.signal.reason);
  };
  const timer = setTimeout(() => {
    cancellation.signal.removeEventListener("abort", abort);
    resolve6();
  }, ms);
  cancellation.signal.addEventListener("abort", abort, { once: true });
});
async function shutdown() {
  if (closing)
    return;
  closing = true;
  cancellation.abort(new Error("Runtime stopped"));
  recovery?.cancel();
  if (child)
    await terminateOwnedRuntime(child);
  if (recovery?.getSessionId())
    await recovery.fail("Recovery interrupted by a lifecycle action.");
  emit("stopped", {});
  process.exit(0);
}
input.once("close", () => void shutdown().catch((error) => {
  log(String(error));
  process.exit(1);
}));
process.once("SIGTERM", () => void shutdown().catch(() => process.exit(1)));
process.once("SIGINT", () => void shutdown().catch(() => process.exit(1)));
input.once("line", (line) => void main(JSON.parse(line)).catch(async (error) => {
  if (child)
    await terminateOwnedRuntime(child).catch(() => {
    });
  if (recovery?.getSessionId())
    await recovery.fail(String(error)).catch(() => {
    });
  emit("error", { message: redactRecoveryText(String(error)) });
  process.exit(cancellation.signal.aborted ? 0 : 1);
}));
async function main(config) {
  const ledger = new RecoveryLedgerStore(config.storage);
  const diagnostics = new RecoveryDiagnostics(config.storage);
  const fixes = new FixExecutor(ledger, { appendLine: log });
  recovery = new RecoverySession({ ledger, fixes, oracle: new HealthOracle(new SandboxManager(), { diagnostics, onOutput: log }), maxBoots: 8, allowBundleIsolation: () => config.isolate, onStatus: (value) => emit("recovery", value), onLog: log });
  if (config.operation === "orphan") {
    const snapshot = await readRuntimeLock(config.lockPath);
    const identity = snapshot && await inspectLegacyRuntime(snapshot);
    if (!snapshot || !identity) {
      emit("orphan-result", { stopped: false });
      process.exit(0);
    }
    const approved = await prompt(`Restart abandoned DSH Runtime at ${identity.baseUrl} (PID ${identity.pid})?`, ["Restart", "Cancel"], "Its editor has exited. The identified Runtime will be stopped, forcibly if necessary, before starting a compatible version. Session files are preserved.");
    if (approved === "Restart") {
      await stopLegacyRuntime(snapshot, identity, config.sharedLockPath, cancellation.signal);
      emit("orphan-result", { stopped: true });
    } else
      emit("orphan-result", { stopped: false });
    process.exit(0);
  }
  if (config.operation === "upgrade") {
    const probe = async () => {
      try {
        let command = config.command;
        let args2 = ["--version"];
        if (process.platform === "win32" && /\.cmd$/i.test(command)) {
          const root = (0, import_node_path9.join)((0, import_node_path9.dirname)(command), "node_modules", "@deepseek-ai", "dsh");
          const manifest = JSON.parse(await (0, import_promises8.readFile)((0, import_node_path9.join)(root, "package.json"), "utf8"));
          const bin = typeof manifest.bin === "string" ? manifest.bin : manifest.bin?.dsh;
          if (manifest.name !== "@deepseek-ai/dsh" || typeof bin !== "string")
            return void 0;
          command = process.execPath;
          args2 = [(0, import_node_path9.join)(root, bin), "--version"];
        }
        const { stdout } = await exec2(command, args2, { timeout: 15e3 });
        return stdout.trim().replace(/^(?:dsh\s+)?v/, "");
      } catch {
        return void 0;
      }
    };
    let prefix2;
    try {
      const npm = config.npm || "npm";
      const command = process.platform === "win32" ? process.execPath : npm;
      const args2 = process.platform === "win32" ? [(0, import_node_path9.join)((0, import_node_path9.dirname)(npm), "node_modules", "npm", "bin", "npm-cli.js"), "prefix", "-g"] : ["prefix", "-g"];
      prefix2 = (await exec2(command, args2, { timeout: 15e3 })).stdout.trim();
    } catch {
    }
    const version = await offerLocalRuntimeUpgrade({ command: config.command, actual: config.actual, target: config.target, npm: config.npm || "npm", node: process.execPath, prefix: prefix2, registry: config.registry, timeout: 12e4, signal: cancellation.signal, probe, log });
    emit("upgrade-result", { version });
    process.exit(0);
  }
  if (config.operation) {
    if (config.operation === "restore")
      emit("restored", { restored: await fixes.restore() });
    else {
      const state = await ledger.read();
      emit("diagnostics", { path: await diagnostics.export(state.state, void 0, state.corrupt) });
    }
    process.exit(0);
  }
  if (!config.command || !Array.isArray(config.args) || !config.args.every((arg) => typeof arg === "string"))
    throw new Error("Invalid IDE launch configuration");
  let composition;
  input.on("line", (line) => {
    void (async () => {
      const message = JSON.parse(line);
      if (message.type === "stop") {
        await shutdown();
        return;
      }
      if (message.type === "cancel") {
        recovery?.cancel();
        cancellation.abort(new Error("Recovery cancelled"));
        if (child)
          await terminateOwnedRuntime(child);
        return;
      }
      if (message.type === "restore") {
        if (child && child.exitCode === null)
          throw new Error("Stop the Runtime before restoring recovery changes.");
        const restored = await fixes.restore();
        emit("restored", { restored });
        return;
      }
      if (message.type === "export") {
        const state = await ledger.read();
        const path = await diagnostics.export(state.state, composition, state.corrupt);
        emit("diagnostics", { path });
      }
    })().catch((error) => emit("action-error", { message: redactRecoveryText(String(error)) }));
  });
  let args = await fixes.filterLaunchArgs(config.args);
  const packageIndex = args.findIndex((arg) => /^@deepseek-ai\/dsh(?:@|$)/.test(arg));
  const prefix = packageIndex < 0 ? [] : args.slice(0, packageIndex + 1);
  let appArgs = packageIndex < 0 ? args : args.slice(packageIndex + 1);
  const compose = () => buildComposition({ command: config.command, resolvedPath: config.command, source: "intellij", version: config.version, launcherArgs: prefix, appArgs, workspaceRoot: config.cwd, extensionOverlayPaths: config.overlays ?? [] });
  composition = await compose();
  await recovery.reconcileHealthyStart(composition);
  let failures = 0;
  for (; ; ) {
    cancellation.signal.throwIfAborted();
    const started = Date.now();
    child = spawnOwnedRuntime(config.command, [...prefix, ...appArgs], { cwd: config.cwd, env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    const current = child;
    emit("process", { pid: current.pid, version: config.version, ...process.platform === "win32" ? {} : { group: current.pid } });
    let output = "";
    let launchError;
    let exit;
    const completion = new Promise((resolve6) => {
      current.once("error", (error) => {
        launchError = error;
        resolve6();
      });
      current.once("exit", (code, signal) => {
        exit = { code, signal };
        resolve6();
      });
    });
    const collect = (data) => {
      output = (output + data.toString("utf8")).slice(-1e5);
      process.stdout.write(data);
    };
    current.stdout.on("data", collect);
    current.stderr.on("data", collect);
    let ready = false;
    for (let tries = 0; tries < 600 && !exit && !launchError; tries++) {
      cancellation.signal.throwIfAborted();
      const launch = output.match(/http:\/\/(?:127\.0\.0\.1|localhost|\[::1\]):\d+\/\?token=[A-Za-z0-9_-]+/)?.[0];
      if (launch) {
        try {
          const response = await fetch(launch, { redirect: "manual", signal: AbortSignal.timeout(1500) });
          const cookie = response.headers.getSetCookie().map((value) => value.split(";")[0]).join("; ");
          const base = new URL(launch).origin;
          const unary = new RemoteUnaryClient({ baseUrl: base, requestHeaders: () => cookie ? { Cookie: cookie } : {}, timeoutMs: 1500 });
          await unary.call("session/list", { _request: {} });
          cancellation.signal.throwIfAborted();
          emit("ready", { url: base, launchUrl: launch });
          ready = true;
          if (recovery.getSessionId())
            await recovery.confirm(composition);
          break;
        } catch (error) {
          if (cancellation.signal.aborted)
            throw error;
        }
      }
      await pause3(100);
    }
    if (ready)
      await completion;
    if (!exit && !launchError)
      await terminateOwnedRuntime(current);
    await terminateOwnedRuntime(current);
    child = void 0;
    cancellation.signal.throwIfAborted();
    const failure = launchError ? String(launchError) : `Runtime exited (${exit?.code ?? "timeout"}). ${redactRecoveryText(output.slice(-3e3))}`;
    if (!config.enabled)
      throw new Error(failure);
    if (ready && Date.now() - started > 6e4)
      failures = 0;
    if (failures < 3) {
      const delay = [1e3, 5e3, 15e3][failures++];
      emit("recovery", { phase: "retrying", summary: `Runtime exited; retrying in ${delay / 1e3}s`, usedBoots: failures, maxBoots: 8, canRestore: false });
      await pause3(delay);
      continue;
    }
    const outcome = await recovery.recover(composition, failure, cancellation.signal);
    if (outcome.status !== "candidate" && outcome.status !== "retry")
      throw new Error(outcome.message);
    if (!outcome.composition)
      throw new Error("Recovery did not return a composition");
    composition = outcome.composition;
    appArgs = [...composition.appArgs];
    appArgs = await fixes.filterLaunchArgs(appArgs);
    config.enabled = false;
  }
}
