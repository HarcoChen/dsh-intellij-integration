import { build } from 'esbuild';
import { copyFile } from 'node:fs/promises';
await build({ absWorkingDir: import.meta.dirname, entryPoints: ['terminal-view.ts'], bundle: true,
    platform: 'browser', format: 'iife', target: ['es2022'],
    outfile: '../src/main/resources/webview/terminal.js', legalComments: 'eof' });
await copyFile(new URL('./node_modules/@xterm/xterm/LICENSE', import.meta.url), new URL('../src/main/resources/licenses/xterm-MIT.txt', import.meta.url));
await copyFile(new URL('./node_modules/@xterm/addon-fit/LICENSE', import.meta.url), new URL('../src/main/resources/licenses/xterm-addon-fit-MIT.txt', import.meta.url));
