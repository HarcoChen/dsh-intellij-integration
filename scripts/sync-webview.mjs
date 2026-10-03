import { execFileSync } from 'node:child_process';
import { mkdtemp, symlink, readFile, writeFile, rm, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

// Rebuild the checked-in bundle from the audited companion commit and its npm dependencies.
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const upstream = resolve(process.argv[2] ?? join(repo, '../dsh-ide'));
const pin = 'deb8d8f882586c42a20abfbb5609cb0643fac309';
const require = createRequire(join(upstream, 'package.json'));
const { build } = require('esbuild');
const temp = await mkdtemp(join(tmpdir(), 'dsh-intellij-webview-'));
try {
    const archive = execFileSync('git', ['archive', pin, 'webview/src', 'src'], { cwd: upstream, maxBuffer: 20 * 1024 * 1024 });
    execFileSync('tar', ['-x', '-C', temp], { input: archive });
    await symlink(join(upstream, 'node_modules'), join(temp, 'node_modules'), 'dir');
    const headerPath = join(temp, 'webview/src/components/Header.tsx');
    let header = await readFile(headerPath, 'utf8');
    const anchor = '        { key: "workspaces",';
    if (!header.includes(anchor)) throw new Error('Header adapter anchor changed');
    header = header.replace(anchor, [
        '        { key: "editorTab", label: t("Open chat in editor tab"), action: { type: "openInEditor" } },',
        '        { key: "account", label: t("Manage DeepSeek account"), action: { type: "manageAccount" } },',
        '        { key: "schedules", label: t("Manage schedules"), action: { type: "manageSchedules" } },',
        '        { key: "runtimeFiles", label: t("Browse Runtime workspace files"), action: { type: "browseRuntimeFiles" }, disabled: !hasSession },',
        '        { key: "jevKey", label: t("Configure Jev API key"), action: { type: "configureJevApiKey" } },',
        '        { key: "whatsNew", label: t("What’s new"), action: { type: "showWhatsNew" } },',
        anchor,
    ].join('\n'));
    await writeFile(headerPath, header);
    await copyFile(join(repo, 'scripts/webview/SchedulePanel.tsx'), join(temp, 'webview/src/components/dock/SchedulePanel.tsx'));
    const localePath = join(temp, 'webview/src/i18n.ts');
    let locale = await readFile(localePath, 'utf8');
    const labels = {
        'Manage DeepSeek account': '管理 DeepSeek 账户', 'Manage schedules': '管理日程',
        'Browse Runtime workspace files': '浏览 Runtime 工作区文件', 'Configure Jev API key': '配置 Jev API Key',
        'What’s new': '更新说明', 'Enable the Schedule bundle in plugin settings to use reminders.': '请在插件设置中启用 Schedule bundle 以使用提醒。',
        'Open chat in editor tab': '在编辑器标签页打开聊天',
    };
    const localeAnchor = 'const ZH_CN:';
    const start = locale.indexOf('{', locale.indexOf(localeAnchor));
    if (start < 0 || !locale.includes(localeAnchor)) throw new Error('Locale adapter anchor changed');
    const added = Object.entries(labels).filter(([key]) => !locale.includes(`${JSON.stringify(key)}:`));
    locale = locale.slice(0, start + 1) + '\n' + added.map(([key, value]) => `    ${JSON.stringify(key)}: ${JSON.stringify(value)},`).join('\n') + locale.slice(start + 1);
    await writeFile(localePath, locale);
    await build({ absWorkingDir: temp, preserveSymlinks: true, bundle: true, platform: 'browser', target: ['es2022'],
        format: 'iife', banner: { js: '"use strict";' }, sourcemap: false, entryPoints: ['webview/src/main.tsx'], outfile: join(repo, 'src/main/resources/webview/main.js'), loader: { '.css': 'css' } });
    console.log(`Synced webview from dsh-ide ${pin}`);
} finally { await rm(temp, { recursive: true, force: true }); }
