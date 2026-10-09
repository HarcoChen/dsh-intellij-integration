import { execFileSync } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const upstream = resolve(process.argv[2] ?? join(repo, '../dsh-ide/vendor/dsh-jev-integration'));
const pin = 'e5d74c5d9153c5cac0d0373345afe6ce0622247c';
const target = join(repo, 'src/main/resources/jev');
const temp = await mkdtemp(join(tmpdir(), 'dsh-intellij-jev-'));
try {
    const archive = execFileSync('git', ['archive', pin, 'dist', 'package.json', 'LICENSE', 'THIRD_PARTY_NOTICES.md'], {
        cwd: upstream, maxBuffer: 8 * 1024 * 1024,
    });
    execFileSync('tar', ['-x', '-C', temp], { input: archive });
    const files = [];
    for (const relative of await readdir(temp, { recursive: true })) {
        if ((await stat(join(temp, relative))).isFile()) files.push(relative.split(sep).join('/'));
    }
    // The IntelliJ snapshot ships dist only, so point the package metadata at
    // the copied schema and bundle patch rather than their upstream source paths.
    const metadataPath = join(temp, 'package.json');
    const metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
    metadata.exports['./schema'] = './dist/protocol/schema/jev-integration.schema.json';
    metadata.dsh.bundle.patch = './dist/runtime/cordis.patch.yml';
    await writeFile(metadataPath, JSON.stringify(metadata, null, 2) + '\n');
    let previous = [];
    try { previous = (await readFile(join(target, 'files.txt'), 'utf8')).split(/\r?\n/); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    for (const relative of files) {
        const destination = join(target, relative);
        await mkdir(dirname(destination), { recursive: true });
        await copyFile(join(temp, relative), destination);
    }
    const current = new Set([...files, 'files.txt']);
    for (const relative of previous) {
        if (!relative || current.has(relative)) continue;
        const obsolete = resolve(target, relative);
        if (!obsolete.startsWith(target + sep)) throw new Error('Invalid previous Jev resource path');
        await rm(obsolete, { force: true });
    }
    await writeFile(join(target, 'files.txt'), [...current].sort().join('\n') + '\n');
    console.log(`Synced ${current.size} Jev resources from ${pin}`);
} finally { await rm(temp, { recursive: true, force: true }); }
