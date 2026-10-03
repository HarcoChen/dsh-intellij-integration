# IntelliJ port TODO

This file tracks behavior that is still stubbed, simplified, or missing compared with `dsh-ide`.
An item is complete only after the host action, runtime protocol, projected state, and user-visible error path all work.

Status: `[x]` implemented, `[-]` usable but not yet at VS Code parity, `[ ]` not implemented.

## Current parity gaps — audit 2026-10-03

The 0.10.2 batch migrated its main new features; it does not establish complete parity
with the whole companion extension. Earlier checked items and validation notes below
are implementation history, not a blanket completion claim.

### Present in dsh-ide, still missing or different here

- [ ] **P1: Agent Preset management contract cleanup.** The native dialog still offers
  open-directory/delete when RC.2 omits `trust`, treating the preset as user-owned.
  It also retains copy wrappers. RC.2 exposes only list/read/select; remove those
  obsolete actions and add the companion's explicit Make default action using
  the available settings namespace (`selectedDefault` for agent-preset-registry).
  This is a compatibility defect, not only a UI difference.
- [ ] **P1: Autonomous debugger control.** Port the companion's optional local MCP
  bridge for launch, breakpoints, stepping and inspection using IntelliJ XDebugger
  APIs. Current Debug Context captures a read-only snapshot only.
- [ ] **P1: Editor-tab chat.** Add an editor-area chat entry sharing the same Remote
  service, selected Session and state as the tool window; no second chat store.
- [-] **P1: Runtime discovery/lifecycle parity.** Advertisement read/write works,
  but startup, orphan recovery and shutdown still use the previous shared-lock
  lifecycle. The companion now uses per-owner advertisements and a short startup
  mutex; migrate the remaining lifecycle and verify simultaneous editor startup,
  wrapper exit and stale legacy records.
- [-] **P2: IDE terminal context.** The companion captures local terminal context;
  IntelliJ currently reports this entry unavailable. Evaluate stable public
  terminal APIs before implementing capture; keep Runtime Terminal separate.
- [-] **P2: Question-draft persistence.** Drafts survive Host pushes, reconnects
  and JCEF view recreation through a bounded Project-service memory cache. They
  do not survive restarting the IDE, unlike the companion's saved Webview state.

### Also unfinished in the companion

- [ ] Runtime Terminal retention, output restoration and input.
- [ ] Plugin/Bundle installation and removal. Lists and enablement are implemented.

### Remaining acceptance work

- [ ] Install the ZIP in real IntelliJ IDEA/PyCharm and exercise native dialogs,
  file previews, clipboard, drag/drop and lifecycle behavior.
- [ ] Windows/Linux command discovery, startup/shutdown and process cleanup.
- [ ] Cross-machine Runtime deployment and connection-loss matrix.
- [ ] Real account sign-in, Schedule delivery, Jev/Laya and third-party plugin HMR.

VS Code-specific Chat Participant/provider APIs are not copied as IntelliJ features.
Harness Web/Desktop-only model search, Desktop command management and telemetry
are outside this IDE port's parity target.

## P0 — everyday chat workflow

- [x] Render fenced code blocks with host-owned `renderId` / `codeBlockId` payloads.
- [x] Copy code to the system clipboard.
- [x] Insert code at the active caret or replace the active selection as one undoable IDE command.
- [x] Open code in a temporary editor with a language-derived file type.
- [x] Apply code to a chosen project file with a diff preview, explicit confirmation, and IDE undo support.
  - [x] Add a native before/after diff preview.
  - [x] Add a project-file picker instead of requiring the target file to be active.
  - [x] Revalidate that the target did not change between preview and apply.
- [x] Populate reasoning-effort options from `session.models` and route changes through `session.selectModel`.
- [x] Route permission changes through the runtime `/permission <preset>` command.
- [x] Project the runtime `permissions` cell instead of a hard-coded permission option.
- [x] Replace the selection toggle masquerading as `openIdeContextPicker` with a native context picker.
- [x] Insert current/project file references into the composer without copying file contents.
- [x] Attach an unstaged Git diff as one-shot context and clear it only after a successful send.
- [x] Keep multiple one-shot items in `state.context`, including diagnostics and folder attachments.
- [x] Implement AppShot capture, reporting the gap plainly on hosts without the native selector.
- [x] Attach arbitrary files from paste, drag-and-drop, or the file picker; upload them through the
      Runtime binary route and submit only session-scoped receipts.
- [x] Copy finalized user and assistant messages through the native system clipboard, including a
      bounded host-side cache for stream-to-history races.
- [x] Discover bounded `.dsh/prompts/**/*.md` templates as visible composer drafts; templates are
      never injected or sent without an explicit user action.

## P1 — runtime projections and controls

- [x] Consume Harness mux frames, project pending approvals/questions into `state.interactions`, and submit answers.
- [x] Project and edit queued prompts in `state.queue`.
- [x] Project background jobs in `state.jobs`.
- [x] Load `commands/list`, expose `state.commands`, and execute registered slash commands directly.
- [x] Load the session skill catalog into `state.skills`.
- [x] Project Goal state and implement create, edit, pause, resume, complete, and clear actions.
- [x] Load the Subagent tree and implement refresh, preview, follow-up, and interrupt actions.
- [x] Project token usage, session statistics, TODOs, and image limits from runtime projection cells.

## P1 — file changes and diffs

- [x] Port tool-call before/after diff resolution for `openToolDiff`.
- [x] Port turn change-review projection for `state.changeReviews` and `openChangeDiff`.
- [x] Port guarded `restoreTurnChanges`; disallow restore while a turn is running and require confirmation.
- [x] Handle file-create, file-delete, rename, and binary/unavailable diff states.

## P2 — settings and workspace parity

- [x] Replace the read-only workspace text dialog with runtime workspace list/create/edit/remove flows.
- [x] Call `settings.openDocument` for `openSettingsDocument`, falling back to the browser root.
- [x] Project `settings.describe` and implement validated `settings.mutate` mutations.
- [x] Replace raw JSON provider output with a native provider/status view.
- [-] Agent Preset list/detail/select are native; RC.2 management cleanup remains above.
- [x] Preserve runtime session metadata such as attention, archived state, workspace identity, and model label.

## P0 — Runtime authentication compatibility

- [x] Preserve the token-bearing launch URL separately from the API base URL.
- [x] Exchange the launch token for a session cookie and send that cookie on RPC, response, health,
      and Mux WebSocket requests.
- [x] Share the authenticated launch URL between IDE integrations without exposing the token in
      Runtime logs or diagnostics; retain the pre-0.1.2 no-auth compatibility path.

## Cross-IDE parity — migrated from dsh-ide

These items track the post-0.2.1 functionality found in the adjacent `dsh-ide` checkout. Each
item is complete only when the IntelliJ host action, Harness RPC/projection boundary, WebView
action validation, and the user-visible failure path are wired together.

- [x] Composer usage controls: hover the context ring to inspect context composition and token statistics;
  click the model name to open the native model chooser. Keyboard focus opens the statistics,
  Escape dismisses them, and the panel stays inside narrow WebView viewports.

- [x] Message checkpoints: fork from a finalized message, restore code to that message, or fork and restore together.
- [x] Plan Mode: consume the public `plan` projection and expose `/plan` plus a composer toggle.
  - [x] Serialize composer toggles and use bare `/plan` so enabling the mode does not submit `on` as a task.
  - [x] Render declared plan reviews with approve / continue-planning feedback, preserving the question protocol.
  - [x] Validate nested answer fields and refuse replies to unavailable or mismatched session interactions.
- [x] IDE Provider management: configure endpoints, credentials, and models; discover models through `llm.listConfigurableProviders` and `llm.discoverModels`.
- [x] Debug Context: attach a bounded, one-shot snapshot of the current XDebugger frame, stack, locals, source excerpt, and diagnostics.
- [x] Subagent timing: consume `subagentTiming` and show settled/active duration in the tree and preview.
  - [x] Use live control projections, including children outside the session catalog, and compare timing watermarks.
  - [x] Refresh duration and activity in both views; reject malformed, fractional, or unsafe integer durations.
- [x] Historical managed Runtime distribution implementation; automatic download is deprecated
  as of the 0.10.2 batch in favor of local/Desktop dsh discovery.
- [x] Conversation outline: provide a native session message navigator.
- [x] Agent status candidates: support a validated list of status labels instead of one fixed label.
- [-] Terminal context: deferred until IntelliJ exposes a stable shell-execution event API; do not depend on terminal plugin internals.
- [x] Message feedback: optional `messageFeedback` CAS mutations and `sessionFeedback/record`, with graceful fallback on older Runtimes.
- [x] Read-only plugin inventory: consume `pluginInventory/list`, validate bounded Loader and Agent preset rows, and expose refresh/error state in the native settings bridge.
- [x] Dynamic Cordis plugin panel: consume optional inventory, expose stop/remove/decline actions with stale-state checks, and never execute untrusted Client-half code in the IDE.
- [x] Schedule projection: validate active reminders and render the read-only schedule tab in the Activity Dock.

## Quality gates for each batch

### dsh-ide 0.10.2 / Runtime 0.2.0-rc.2（2026-10-03）

- [x] Timed user-question claim、倒计时、前台/延迟回答、Session/call 草稿和 durable 结算。
- [x] Jobs 实时 roster、非消费输出、游标重连续读和取消。
- [x] Team 投影、任务与成员导航，移除过期 Team RPC。
- [x] 权限 catalog 缓存、插件/Bundle 开关与只读/应用结果。
- [x] Host Workspace 文件浏览、multipart UTF-8 预览、版本/大小校验和 watcher。
- [x] 官方 Desktop 启动引导、Runtime 广告互通、Laya loopback HTTP 与更新通知。
- [x] 现有 Gradle 门禁、真实 RC.2 Java/companion 联调和 Webview Chrome smoke。
- [ ] 真实 IC/PyCharm 安装 ZIP 后的原生 UI 人工验收。

迁移范围、pin 与验证边界见 [RC.2 迁移报告](RPC_0.2.0_RC2_ADAPTATION.md)。

上游仍计划后续实现的 Runtime Terminal、插件安装/卸载不在本次 0.10.2 UI 迁移中。

Latest parity batch verified on 2026-09-14 against the pinned `0.1.2-rc.1` Runtime:

- `clean buildPlugin`, formatting, lint, and Plugin Verifier for IC / PC 2024.3.6 passed.
- Isolated integration used the Java authentication, unary, mux, event, and state adapters with
  a temporary DSH home and a loopback model stub. Plan on/off projections, prompt-free toggles,
  review feedback, approval and mode exit, and a real subagent's active/settled timing passed.
- Plan review rendering preserved the original question payload and escaped embedded HTML;
  the WebView boundary accepted valid answers and rejected surplus fields and non-string feedback.
- No unit tests were added. Installed-IDE interaction remains the manual gate below.

Runtime 0.1.5 migration batch (2026-09-15):

- [x] RC.2 assistant streams, reconnect settlement, V3 history, attachments, subagent request envelopes, Goal activation.
- [x] Authoritative model catalog, mode-selection policy and skill source-path hints.
- [x] Compatible local Runtime preference, pinned package fallback, shared-lock reuse, confirmed local upgrades and orphan migration.
- [x] Bundled crash recovery engine, isolated validation, cancellation, reversible bundle isolation and diagnostic export.
- [x] Agent Team experimental typed client (optional service; no Team UI).
- Real RC.2 integration passed with a temporary Harness home and loopback model: streaming reconnect,
  durable settlement, history decoding, native Java startup/stop/cancel, crash restart and offline diagnostics.
- The vendored recovery engine passed its 12 existing process integration scenarios, including budget,
  cancellation, sandbox validation and restoration conflicts. Java/Node shared-lock interop passed.
- Final formatting/lint/build and IC/PC 2024.3.6 Plugin Verifier passed; both IDEs report compatible
  with the existing deprecated-API warnings. The ZIP contains the helper and its MIT license.
- Browser smoke verified the recovering banner, Chinese status and cancel action. Remaining banner
  clicks were not completed because UI approval timed out twice; they remain a manual check.
- No unit tests were added. Windows/Linux execution and installed-IDE interaction remain manual gates.

Chat parity batch (2026-09-21):

- [x] Migrated dsh-ide file drafts and message-copy WebView behavior without exposing the deferred
      feedback surface; the host validates drafts, uploads raw bytes, and verifies opaque receipts.
- [x] Added English and Simplified Chinese failure messages for invalid, oversized, and unavailable
      attachments/messages.
- `compileJava`, `spotlessCheck`, and `checkstyleMain` passed with the repository's JDK 21 toolchain.
- No unit tests were added. Real Runtime file-upload and installed-IDE clipboard smoke checks remain
  manual gates.

Provider/runtime parity batch (2026-09-21):

- [x] Provider management now exposes active/configurable providers, endpoint/protocol/API-key
      configuration, model discovery and model catalog details, with native dialogs and a Web UI
      fallback for settings that cannot be represented safely in the IDE.
- [x] Message and session feedback use the Runtime's versioned sidecar endpoints and fail closed
      when an older Runtime does not advertise them.
- [x] Managed Runtime installation is platform-gated, manifest-pinned, SHA-256/size verified,
      lock-serialized, path-safe, and atomically cached as the final local fallback.
- [x] Conversation outline and checkpoint actions are available from native IDE commands and the
      WebView, with host-side sequence validation and reveal routing.
- [x] Prompt-template selection is host-owned and bounded; the latest WebView's `/template` action
      now pre-fills the composer without silently submitting or injecting a draft.
- [x] XDebugger context capture remains one-shot and bounded; agent status labels accept a
      validated candidate list with a fixed-label override.
- [x] Tool-write dirty-file guard: before approving a structured file diff, compare its target
      paths with IntelliJ's unsaved editor documents; keep the approval pending and explain how
      to save/revert when they overlap.
- [x] Latest Activity Dock surfaces are now host-backed: schedule, plugin inventory, and optional
      dynamic Cordis plugin state/actions are projected through the same sanitized WebView boundary.
- `compileJava`, `spotlessCheck`, `checkstyleMain`, and `buildPlugin` are the automated gates for
  this batch. No unit tests were added. Installed-IDE interaction and Windows/Linux Runtime
  installation remain manual gates; Terminal context remains intentionally deferred because the
  IntelliJ platform does not expose a stable scrollback/event API for it.

- [x] Reject malformed or surplus WebView action fields at the host boundary for the completed batch.
- [x] Keep IntelliJ model reads inside read actions and mutations inside write commands for the completed batch.
- [x] Provide a visible failure message instead of silently dropping supported actions in the completed batch.
- [x] Run `clean buildPlugin` and IntelliJ Plugin Verifier for IC and PC 2024.3.6.
- [-] Smoke-test the installed ZIP, not only `runIde`.
  - [x] The distribution ZIP is verified to carry the plugin jar with `plugin.xml`, the webview bundle,
        icons, and the message bundle.
  - [ ] Installing that ZIP into a real IntelliJ IDEA and PyCharm and exercising the surfaces by hand is
        still outstanding; it needs a human at the IDE.
