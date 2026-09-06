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
  <a href="https://github.com/HarcoChen/deepseek-harness-vscode">VS Code edition</a>
</p>

An independent community plugin for IntelliJ IDEA, PyCharm, and other IntelliJ Platform IDEs. Explain unfamiliar code, investigate a bug, or review a change from the same place you write it.

## Quick start

1. **Install** — in **Settings → Plugins → Marketplace**, search for **DeepSeek Harness Integration**, or open the [Marketplace page](https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration). Restart the IDE if prompted.
2. **Connect** — open a project and the **DSH** tool window. For local startup, have Node.js and `pnpm` or `npm`/`npx` available. The plugin starts the Runtime automatically by default. Configure the launch command or an existing Runtime's **Server URL** in **Settings → Tools → DeepSeek Harness**.
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

### Put the right context into the conversation

Use **DSH: Ask About Selection** for an open-ended question, or the **DSH** context menu for explain / fix / review / documentation tasks. The context picker also supports attaching the current unstaged Git diff. Editor context is bounded by the configurable byte limit.

### Keep the Runtime close to your tools

The plugin manages local Runtime startup, shutdown, and restart, with `pnpm`, an installed `dsh`, and `npx` launch options. Package-manager launches use the configured Runtime version. You can also connect to an existing Runtime or open its Web UI in a browser.

API keys are stored in IntelliJ **Password Safe** and passed to a newly started local Runtime. The UI includes English and Simplified Chinese resources.

## Configuration


Open **Settings | Tools | DeepSeek Harness**.

| Setting | Default | What it does |
| --- | --- | --- |
| Command / Args | `pnpm dlx @deepseek-ai/dsh web --no-open` | How the Runtime is launched; point it at an installed `dsh` or a local checkout instead. |
| Server URL / Port | `""` / `0` | Prefer an already running DSH Runtime; port `0` selects an available port for local startup. |
| Auto start | `true` | Start or connect to the Runtime when the project opens. |
| Runtime version | `0.1.1-rc.2` | Locked version of the managed Runtime. |
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
./gradlew buildPlugin              # Installable ZIP in build/distributions/
./gradlew verifyPluginStructure    # Plugin descriptor and archive checks
./gradlew verifyPlugin             # Compatibility checks against configured IDEs
```

On Windows, use `gradlew.bat`. The first build downloads Gradle and the IntelliJ Platform dependencies.

## Data use and privacy

The plugin does not collect telemetry. Prompts and attached context are sent through the DSH Runtime to your configured model provider; that provider's terms and privacy policy apply. API keys stored by the plugin remain in IntelliJ Password Safe and are passed to newly started local Runtime processes.

## Feedback and contributing

[Report a bug or suggest a feature](https://github.com/HarcoChen/dsh-intellij-integration/issues). For bugs, include your IDE and plugin versions, OS, Runtime version, and reproduction steps. Remove API keys and private code from any logs you share. Documentation improvements and focused pull requests are welcome; see [repository rules](AGENTS.md) before contributing.

If DSH helps your workflow, a GitHub star helps others discover it. For release history and attribution, see [Releases](https://github.com/HarcoChen/dsh-intellij-integration/releases) and [Third-party notices](THIRD_PARTY_NOTICES.md).

This community project is not endorsed or maintained by DeepSeek or JetBrains. Licensed under [MIT](LICENSE).
