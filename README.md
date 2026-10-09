<p align="center">
  <img src="src/main/resources/icons/dsh.svg" alt="DeepSeek Harness" width="112">
</p>

<h1 align="center">DeepSeek Harness for JetBrains IDEs</h1>

<p align="center">Bring DeepSeek Harness into JetBrains IDEs — chat with your code, review native diffs, and inspect every run.</p>

<p align="center"><strong>English</strong> | <a href="README.zh-CN.md">简体中文</a></p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration"><img src="https://img.shields.io/jetbrains/plugin/v/33924?style=flat-square&amp;label=Marketplace" alt="JetBrains Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration"><img src="https://img.shields.io/jetbrains/plugin/d/33924?style=flat-square" alt="JetBrains Marketplace downloads"></a>
  <a href="https://github.com/HarcoChen/dsh-intellij-integration/stargazers"><img src="https://img.shields.io/github/stars/HarcoChen/dsh-intellij-integration?style=flat-square" alt="GitHub stars"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/HarcoChen/dsh-intellij-integration?style=flat-square" alt="MIT license"></a>
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration">Install plugin</a> ·
  <a href="https://github.com/HarcoChen/dsh-intellij-integration/releases">Releases</a> ·
  <a href="https://github.com/HarcoChen/dsh-intellij-integration/issues">Feedback</a> ·
  <a href="https://github.com/HarcoChen/dsh-vsc-integration">VS Code edition</a>
</p>

An independent community plugin for IntelliJ IDEA, PyCharm, and other IntelliJ Platform IDEs. Explain unfamiliar code, investigate a bug, or review a change from the same place you write it.

## Quick start

1. **Install** — in **Settings → Plugins → Marketplace**, search for **DeepSeek Harness Integration**, or open the [Marketplace page](https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration). Restart the IDE if prompted.
2. **Connect** — open a project and the **DSH** tool window. The plugin discovers a compatible local `dsh`, including the command registered by the official [DeepSeek Desktop app](https://www.deepseek.com/en/download/); the bundled lifecycle helper still needs Node.js 24+. The plugin starts the Runtime automatically by default. Configure the launch command or an existing Runtime's **Server URL** in **Settings → Tools → DeepSeek Harness**.
3. **Configure credentials** — find **DSH: Configure API Key** via **Find Action**. Restart the local Runtime after changing the key so it receives the new value. An existing external Runtime uses its own credentials.
4. **Try it** — select a function, right-click **DSH → Explain Selection**, or ask a question in the chat. Inspect tool cards and proposed diffs when an approval is requested.

**Requires:** IntelliJ Platform **2024.3+** with JCEF (the embedded browser). The build targets IntelliJ IDEA Community and PyCharm Community 2024.3.6 for compatibility verification. Other IntelliJ Platform IDEs need the same platform APIs and JCEF support.

## From question to reviewed change

| What you want to do | What DSH brings into your IDE |
| --- | --- |
| Understand or improve code | Explain, fix, review, and document selected code from the editor's context menu. |
| Inspect an agent's edits | Open supported tool diff cards in JetBrains' native side-by-side viewer, including proposed changes awaiting approval. |
| Keep track of a task | Chat history, session switching, tool cards, and runtime status in the DSH tool window. |
| Understand a run | Built-in Trace analysis for inspecting session events and tool activity. |
| Watch usage | A status-bar balance indicator with DeepSeek pricing information when available. |
| Resume across editors | Reuse a compatible local Harness Runtime across IDE windows and the companion VS Code extension. |

### Native diffs, even without Git

Review file edits in the IDE's own diff viewer. For supported tool diff cards, DSH reconstructs before/after content from session history, so the preview does not depend on a Git repository. Proposed edits can be previewed before approval. If later file changes make reconstruction unreliable, the plugin reports that instead of showing a misleading comparison.

Before an approval can release a structured file edit, DSH checks IntelliJ's unsaved editor buffers against the proposed paths. Overlapping edits keep the approval pending until you save or revert the document.

### Put the right context into the conversation

Use **DSH: Ask About Selection** for an open-ended question, or the **DSH** context menu for explain / fix / review / documentation tasks. The context picker also supports attaching the current unstaged Git diff. Editor context is bounded by the configurable byte limit.

The composer accepts files pasted from the clipboard, dropped from the desktop, or chosen with the file picker. Files are uploaded to the connected Runtime and represented in the prompt by session-scoped receipts; the host never sends a client file path to the model. Use the copy button on a user or assistant message to place its text on the system clipboard.

Use the `/template` slash command to choose a bounded Markdown draft from `.dsh/prompts` and place it in the composer. The template is visible and editable; it is never sent until you submit it.

The native **Conversation Outline** command jumps to a selected turn. Finalized assistant messages also expose optional Runtime-backed feedback controls (rating, note, and session feedback); older Runtimes hide the controls without affecting chat. From a finalized message you can fork the session, restore that turn's code changes, or do both after the host revalidates the checkpoint.

### Plan before implementing

Use the composer's **Plan** toggle or `/plan` to enter plan mode; `/plan off` leaves it.
The toggle changes the session mode without sending a task or consuming attached IDE context.
When the agent submits a plan for review, approve it or provide feedback with **Continue planning**.
The subagent tree and preview show active and completed execution time when the Runtime supplies it.

The Activity Dock also shows validated active reminders and, when the Runtime exposes them, a
read-only plugin inventory and dynamic Cordis plugin state. Dynamic plugin stop/remove/decline
actions are host-validated; the IDE never executes an untrusted Client-half plugin.

### Chat and debug from the editor

Use **DSH: Open Chat in Editor Tab** from Find Action or the chat menu. The tab mirrors
its tool window: both share the selected Session, messages, controls and Runtime.
Closing a tab releases its view. Question drafts are saved in the project's local
IDE settings, including across IDE restarts; idle views do not overwrite edits.

Enable **Allow Agent to control this IDE debugger** in plugin settings, then restart
its locally owned Runtime. The Agent can start an existing Run/Debug configuration,
manage source breakpoints, pause/resume/step, select paused threads and frames, and
read bounded context through a local authenticated MCP endpoint. Source diagnostics
reuse existing IDE highlights; sensitive variable values are redacted by name.
The endpoint cannot create arbitrary launch configurations or evaluate expressions.
Externally managed Runtimes retain their own integrations.

Product-specific debugger options and terminal context on the 2024.3 baseline are
listed with API evidence in [TODO](TODO.md#platform-exceptions--deliberately-not-implemented).

### Keep the Runtime close to your tools

The plugin manages local Runtime startup, shutdown, and restart. Automatic startup discovers a compatible installed `dsh`; if none is available, it offers the official DeepSeek Desktop download. Explicit pnpm/npx launch commands remain available for advanced setups and use the configured Runtime version. You can also connect to an existing Runtime or open its Web UI in a browser.

Runtime 0.1.5 support includes transient assistant streaming across reconnects, V3 history and compaction records, submitted attachments, queued subagent prompts, and explicit Goal resume after reconnect. Mode selection follows Runtime policy, and skill menus show source paths on hover. The context ring still shows statistics on hover; click the model name to switch models.

The default Runtime is now `0.2.0-rc.2`. The chat menu opens native **Manage sessions**, **Manage DeepSeek account**, and **Manage schedules** dialogs. Session management supports pinning and restoring archived sessions. Account management uses the Runtime's browser sign-in flow and shows profile, wallet balances, and bonus notices. Schedule management shows reminders across sessions; reminders owned by the current session can be edited or deleted, and delivery history can be inspected. In projects without a folder, the first explicit session can initialize the Runtime's default Workspace.

The IDE also bundles the shared Laya/Jev Runtime integration and mounts it into local Runtimes it starts. It is disabled by default. Enable it and selected decision features under **Settings → Tools → DeepSeek Harness**, configure a System One API key from the chat menu, then restart the Runtime. The key is stored in IntelliJ Password Safe. The endpoint accepts HTTPS or localhost/127.0.0.1 HTTP for local Laya; externally managed Runtimes are not modified.

Owned local Runtimes retry after crashes and can recover through isolated validation, bounded repair attempts, and reversible bundle isolation. The status banner offers cancellation, restoration, and redacted diagnostics export. Both automatic recovery and persistent bundle isolation can be disabled in settings. The bundled Node helper runs locally; it needs Node.js 24+ even with an installed standalone `dsh`.

Only a compatible Runtime advertised by an editor is reused automatically. The plugin reads dsh-ide Runtime advertisements and publishes only its own discovery record; legacy editor locks are read-only hints and never block startup. A short loopback gate reduces simultaneous startup races. A wrapper that exits while its Runtime still serves relinquishes ownership without causing a duplicate launch. Updating a verified local npm installation requires a native confirmation dialog. Manually configured external Runtimes remain externally managed. Upgrade old Runtimes before connecting; 0.1.5 history migrations are not a downgrade path.

Optional Agent Teams show members, tasks, dependencies, and write-scope warnings in the Activity Dock. Team members use the existing subagent history, follow-up, and interrupt controls. Enable the official Team profile bundle in the Runtime to use this feature; the plugin reads its Session projection.

Timed questions show the Host countdown and preserve answer drafts through reconnects and view recreation. Questions that continue after timeout can still be answered in the next turn. Jobs expose live output and cancellation, with bounded output tails and reconnect cursors. Plugin settings show Bundle selections and plugin switches, including read-only reasons and whether a change needs a restart. Schedule reminders require the optional Schedule bundle; enable it in plugin settings.

Use **DSH: Browse Runtime Workspace Files** from Find Action or `/ide` to browse the selected Session’s Host files. Native previews are read-only UTF-8 text up to 1 MiB, refresh on Host file changes, and remain bound to their Runtime. **DSH: Refresh Runtime File Previews** provides manual refresh.

API keys are stored in IntelliJ **Password Safe** and passed to a newly started local Runtime. The UI includes English and Simplified Chinese resources.

## Configuration


Open **Settings | Tools | DeepSeek Harness**.

| Setting | Default | What it does |
| --- | --- | --- |
| Command / Args | `auto` / empty | Discover a compatible local or Desktop-registered `dsh`; explicit pnpm/npx launch commands remain supported. Automatic standalone Runtime download is deprecated. |
| Server URL / Port | `""` / `0` | Prefer an already running DSH Runtime; port `0` selects an available port for local startup. |
| Auto start | `true` | Start or connect to the Runtime when the project opens. |
| Runtime version | `0.2.0-rc.2` | Version for explicit package-manager launch commands; the minimum supported local Runtime is `0.1.5-rc.1`. |
| Autonomous debugger | Disabled | Local MCP access to existing IDE configurations and public debugger controls; restart an owned Runtime after enabling. |
| Laya/Jev integration | Disabled | Bundled System One integration for locally started Runtimes; the endpoint, model, and optional policies are configurable. |
| npm registry | `https://registry.npmmirror.com` | Registry mirror used as a download fallback. |
| Timeouts | `30s` startup, `600s` request | How long to wait for startup and individual RPC calls. |
| Context bytes | `120000` | Maximum UTF-8 bytes of `<ide_context>` included per prompt. |
| API key env | `DEEPSEEK_API_KEY` | Environment variable the stored key is injected as. |

## Troubleshooting

| Symptom | Where to start |
| --- | --- |
| Runtime does not start | Run **DSH: Diagnose Environment** and **DSH: Open Runtime Logs** via Find Action; check the command and package manager in settings. |
| Chat reports JCEF is unavailable | Use an IDE runtime with JCEF. The fallback panel provides a browser entry point. |
| A changed API key is not taking effect | Restart the local Runtime. For an external Runtime, update its credentials directly. |

## Install from a release or build locally

Download the plugin `.zip` from [GitHub Releases](https://github.com/HarcoChen/dsh-intellij-integration/releases), then choose **Settings → Plugins → ⚙ → Install Plugin from Disk…**. Select the archive without unpacking it.

To build from source, use **JDK 21** and the included Gradle wrapper:

```bash
git submodule update --init --recursive
./gradlew format         # apply the repository's Java formatting rules
./gradlew lint           # verify formatting and run Checkstyle
./gradlew verifyPlugin   # structure and compatibility checks
./gradlew buildPlugin    # produce the installable zip
```

On Windows, use `gradlew.bat`. The first build downloads Gradle and the IntelliJ Platform dependencies.

Gradle bundles Jev from the compiled distribution in `vendor/dsh-jev-integration`.
The parent repository pins its commit; initialize the submodule before building.
Jev requires no separate npm install or build for plugin packaging.

## Data use and privacy

The plugin does not collect telemetry. Prompts and attached context are sent through the DSH Runtime to your configured model provider; that provider's terms and privacy policy apply. API keys stored by the plugin remain in IntelliJ Password Safe and are passed to newly started local Runtime processes.

## Feedback and contributing

[Report a bug or suggest a feature](https://github.com/HarcoChen/dsh-intellij-integration/issues). For bugs, include your IDE and plugin versions, OS, Runtime version, and reproduction steps. Remove API keys and private code from any logs you share. Documentation improvements and focused pull requests are welcome; see [repository rules](AGENTS.md) before contributing.

If DSH helps your workflow, a GitHub star helps others discover it. For release history and attribution, see [Releases](https://github.com/HarcoChen/dsh-intellij-integration/releases) and [Third-party notices](THIRD_PARTY_NOTICES.md).

This community project is not endorsed or maintained by DeepSeek or JetBrains. Licensed under [MIT](LICENSE).
