# Third-Party Notices

## commonmark-java

The plugin bundles `org.commonmark:commonmark`, `commonmark-ext-gfm-tables`,
and `commonmark-ext-gfm-strikethrough`, version 0.30.0, from
[commonmark-java](https://github.com/commonmark/commonmark-java).
These libraries parse and render chat Markdown.

Copyright (c) 2015, Robin Stocker. Licensed under the BSD 2-Clause License.
The full license is included in `src/main/resources/licenses/commonmark-BSD-2-Clause.txt`.

## DeepSeek Harness fish icon

`src/main/resources/icons/dsh.svg` and `dsh-dark.svg` are derived from the
fish favicon distributed with [DeepSeek Harness](https://github.com/deepseek-ai/DeepSeek-Harness).

Copyright (c) 2026 DeepSeek. Licensed under the MIT License. The derivative
adds a code mark and adapts the asset for IntelliJ tool-window rendering.
DeepSeek does not endorse or maintain this community plugin.

## dsh-reasoning-effort chibi runner sprite

`src/main/resources/webview/chibi-runner-strip.png` is the 8-frame sprite from
[dsh-reasoning-effort](https://github.com/HanaAyane/dsh-reasoning-effort),
retained for compatibility with the shared dsh-ide Webview bundle.

Copyright (c) 2026 HanaAyane. Licensed under the MIT License.

## dsh-ide Webview bundle

`src/main/resources/webview/main.js` and `main.css` are built artifacts from
the MIT-licensed [dsh-ide](https://github.com/HarcoChen/dsh-vsc-integration)
repository, version 0.10.2, commit `deb8d8f882586c42a20abfbb5609cb0643fac309`,
with IntelliJ-specific menu and Schedule adapters. `scripts/sync-webview.mjs`
rebuilds the artifacts from that pin and the companion checkout's npm dependencies.
They are adapted at runtime through the JCEF bridge documented in `DshBridge.java`.

## dsh-ide Runtime recovery engine

`runtime-helper/upstream/` vendors recovery, process ownership, lock, migration, version,
local-upgrade and Remote client modules from the same MIT-licensed dsh-ide repository,
commit `ab5f7a813d99ffb029d3442f1c3666382dde8b3d`.
Copyright (c) 2026 dsh-community. The license is retained in that directory and in
`src/main/resources/runtime/LICENSE` alongside the bundled helper.

The IntelliJ adapter replaces editor UI and localization dependencies with a private
stdin/stdout bridge. `runtime-helper/runtimeLaunch.ts` adapts Windows command-interpreter
arguments using the quoting fix from companion commit
`7e45c2b19d8d7c724fda824a88e78b4d448cf05d`.
`runtime-helper/main.ts` supplies lifecycle orchestration;
`src/main/resources/runtime/helper.cjs` is the bundled artifact. Build instructions
and local adaptations are documented in `runtime-helper/README.md`.

The Remote contract and default-version pin follow Harness `dsh-v0.2.0-rc.2`,
commit `639ed015397290b3745d163aafe02ffee4aa3f84`. The local-upgrade UI has been
adapted to the companion's official Desktop guidance; the recovery algorithms
remain on the vendor baseline above. Runtime advertisement interoperability
follows the companion's `src/runtimeAdvertisement.ts` schema.

## dsh-jev-integration Runtime package

`src/main/resources/jev/` contains the built IDE-neutral
[dsh-jev-integration](https://github.com/HarcoChen/dsh-jev-integration) package,
version 0.1.0 (`e5d74c5d9153c5cac0d0373345afe6ce0622247c`) from the companion
dsh-ide checkout. Its MIT license and own
third-party notices are retained alongside the distribution. The integration
calls a separately operated System One endpoint only when enabled.


## dsh-ide loopback debugger MCP transport

`runtime-helper/upstream/debugMcpServer.ts` is vendored from dsh-ide commit
`deb8d8f882586c42a20abfbb5609cb0643fac309` under its retained MIT license.
`debugProtocol.ts` supplies IDE-neutral type definitions; `runtime-helper/main.ts`
connects this transport to IntelliJ's public debugger APIs through private stdin/stdout.
The generated helper retains the license alongside the existing vendor distribution.
