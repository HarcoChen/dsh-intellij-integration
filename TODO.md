# IntelliJ port TODO

This file tracks behavior that is still stubbed, simplified, or missing compared with `dsh-ide`.
An item is complete only after the host action, runtime protocol, projected state, and user-visible error path all work.

Status: `[x]` implemented, `[-]` usable but not yet at VS Code parity, `[ ]` not implemented.

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
- [x] Replace raw Agent Preset text output with native list/detail/copy/edit flows.
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

- [ ] Message checkpoints: fork from a finalized message, restore code to that message, or fork and restore together.
- [ ] Plan Mode: consume the public `plan` projection and expose `/plan` plus a composer toggle.
- [ ] IDE Provider management: configure endpoints, credentials, and models; discover models through `llm.models` and `llm.discoverModels`.
- [ ] Debug Context: attach a bounded, one-shot snapshot of the current XDebugger frame, stack, locals, source excerpt, and diagnostics.
- [ ] Subagent timing: consume `subagentTiming` and show settled/active duration in the tree and preview.
- [ ] Managed Runtime distribution: cache and integrity-check the platform Runtime instead of relying only on pnpm/npx.
- [ ] Conversation outline: provide a native session message navigator.
- [ ] Agent status candidates: support a validated list of status labels instead of one fixed label.
- [-] Terminal context: deferred until IntelliJ exposes a stable shell-execution event API; do not depend on terminal plugin internals.
- [-] Message feedback: deferred until the evaluation/statistics loop has a product surface; the upstream sidecar remains optional.

## Quality gates for each batch

- [x] Reject malformed or surplus WebView action fields at the host boundary for the completed batch.
- [x] Keep IntelliJ model reads inside read actions and mutations inside write commands for the completed batch.
- [x] Provide a visible failure message instead of silently dropping supported actions in the completed batch.
- [x] Run `clean buildPlugin` and IntelliJ Plugin Verifier for IC and PC 2024.3.6.
- [-] Smoke-test the installed ZIP, not only `runIde`.
  - [x] The distribution ZIP is verified to carry the plugin jar with `plugin.xml`, the webview bundle,
        icons, and the message bundle.
  - [ ] Installing that ZIP into a real IntelliJ IDEA and PyCharm and exercising the surfaces by hand is
        still outstanding; it needs a human at the IDE.
