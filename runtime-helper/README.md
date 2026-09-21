# IntelliJ Runtime helper

The plugin ships `src/main/resources/runtime/helper.cjs`; plugin users do not install
this directory's dependencies. Local Runtime management requires Node.js 24+.
Gradle packages the checked-in artifact without fetching Node dependencies.

To rebuild after editing the TypeScript sources:

```sh
cd runtime-helper
npm ci
npm run build
```

Commit the source and generated artifact together. `DSH_ESBUILD_PATH` can point to an
existing esbuild 0.25.12 installation for offline builds. The build uses Node built-ins
only at runtime. The TypeScript entry point can also be checked with TypeScript 5.9,
Node type definitions, `--noEmit --strict --target ES2022 --module commonjs
--moduleResolution node --esModuleInterop --skipLibCheck`.

`upstream/` is vendored from dsh-ide commit
`ab5f7a813d99ffb029d3442f1c3666382dde8b3d` under its retained MIT license. Recovery,
lock, process ownership and migration modules are shared algorithms. The local
`localUi`, `localize` and `guards` adapters replace VS Code dependencies;
`localRuntimeUpgrade` imports the UI adapter and uses native PromiseLike typing.

`main.ts` adapts those modules to the IntelliJ lifecycle. The IDE sends configuration
and actions through private stdin. Prefixed stdout frames carry status, process
identity, confirmation requests and results. Runtime output passes through the IDE's
log redactor. Credentials are inherited in the child environment, never in launch
arguments. Recovery storage is under the IDE system directory, keyed by project path;
the launch lock is shared with dsh-ide in the OS temporary directory.

Recovery is bounded and validates candidates in a sandbox before applying reversible
fixes. Cancellation and shutdown terminate the owned process tree; an unverified
shutdown retains the lock. Local npm upgrades and orphan migration require an IDE
confirmation. Restoring prior recovery changes first stops the owned Runtime.

Validation uses temporary homes, loopback model stubs and upstream process integration
scripts; this repository deliberately does not add unit tests. macOS execution has
been verified. Windows/Linux process execution and installed-IDE UI remain manual gates.
