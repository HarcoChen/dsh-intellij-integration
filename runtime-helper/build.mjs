import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { build } = process.env.DSH_ESBUILD_PATH ? require(process.env.DSH_ESBUILD_PATH) : require('esbuild');
await build({
    entryPoints: [fileURLToPath(new URL('./main.ts', import.meta.url))],
    bundle: true,
    platform: 'node',
    target: 'node20',
    format: 'cjs',
    outfile: fileURLToPath(new URL('../src/main/resources/runtime/helper.cjs', import.meta.url)),
    legalComments: 'eof',
});
