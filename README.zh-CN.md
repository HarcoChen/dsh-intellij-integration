<p align="center">
  <img src="src/main/resources/icons/dsh.svg" alt="DeepSeek Harness" width="112">
</p>

<h1 align="center">DeepSeek Harness for JetBrains IDEs</h1>

<p align="center">在 JetBrains IDE 中使用 DeepSeek Harness：围绕代码对话，用原生 Diff 审查改动，通过 Trace 查看执行过程。</p>

<p align="center"><a href="README.md">English</a> | <strong>简体中文</strong></p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration"><img src="https://img.shields.io/jetbrains/plugin/v/33924?style=flat-square&amp;label=Marketplace" alt="JetBrains Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration"><img src="https://img.shields.io/jetbrains/plugin/d/33924?style=flat-square" alt="JetBrains Marketplace downloads"></a>
  <a href="https://github.com/HarcoChen/dsh-intellij-integration/stargazers"><img src="https://img.shields.io/github/stars/HarcoChen/dsh-intellij-integration?style=flat-square" alt="GitHub stars"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/HarcoChen/dsh-intellij-integration?style=flat-square" alt="MIT license"></a>
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration">安装插件</a> ·
  <a href="https://github.com/HarcoChen/dsh-intellij-integration/releases">版本下载</a> ·
  <a href="https://github.com/HarcoChen/dsh-intellij-integration/issues">反馈建议</a> ·
  <a href="https://github.com/HarcoChen/dsh-vsc-integration">VS Code 版本</a>
</p>

面向 IntelliJ IDEA、PyCharm 等 IntelliJ Platform IDE 的独立社区插件。在写代码的地方解释陌生逻辑、排查问题、审查改动。

## 快速上手

1. **安装插件** — 打开 **Settings → Plugins → Marketplace**，搜索 **DeepSeek Harness Integration**，也可以前往 [插件商店](https://plugins.jetbrains.com/plugin/33924-deepseek-harness-integration)。按提示重启 IDE。
2. **连接 Runtime** — 打开项目和 **DSH** 工具窗口。插件会先尝试已安装的 `dsh`，再尝试包管理器，最后使用已校验的托管 Runtime 缓存；随插件提供的生命周期助手仍需要 Node.js 24+。插件默认自动启动 Runtime。启动命令或已有 Runtime 的 **Server URL** 可在 **Settings → Tools → DeepSeek Harness** 中配置。
3. **配置凭据** — 通过 **Find Action** 找到 **DSH: Configure API Key**。修改密钥后重启本地 Runtime，让新值生效；外部已运行的 Runtime 使用自身配置的凭据。
4. **试一次** — 选中一个函数，右键选择 **DSH → Explain Selection**，或直接在聊天窗口提问。出现审批请求时，查看工具卡片和待执行改动的 Diff。

**运行要求：** IntelliJ Platform **2024.3+**，且带有 JCEF（内嵌浏览器）。构建配置以 IntelliJ IDEA Community 和 PyCharm Community 2024.3.6 为兼容性验证目标；其他 IntelliJ Platform IDE 需要相同的平台 API 和 JCEF 支持。

## 从提问到审查改动

| 你想做什么 | DSH 在 IDE 中提供什么 |
| --- | --- |
| 理解或改进代码 | 在编辑器右键菜单中解释、修复、审查选区，或生成文档。 |
| 检查 Agent 改动 | 用 JetBrains 原生并排 Diff 打开支持的工具改动卡片，也能预览待审批的修改。 |
| 跟进任务 | 在 DSH 工具窗口查看聊天历史、切换会话、检查工具卡片和 Runtime 状态。 |
| 了解执行过程 | 内建 Trace 分析，查看会话事件和工具活动。 |
| 关注用量 | 状态栏余额指示器，在数据可用时展示 DeepSeek 价格信息。 |
| 在多个编辑器中继续工作 | 多个 IDE 窗口及配套 VS Code 插件可复用兼容的本地 Harness Runtime。 |

### 原生 Diff，无需依赖 Git

直接在 IDE 的 Diff 查看器里审查文件改动。对于支持的工具 Diff 卡片，DSH 从会话历史重建修改前后的内容，因此不依赖 Git 仓库，也能在审批前预览提议的修改。如果文件后续变化导致无法可靠重建，插件会说明原因，避免展示失真的对比。

### 把相关代码带入对话

通过 **DSH: Ask About Selection** 自由提问，或用 **DSH** 右键菜单执行解释、修复、审查、生成文档。上下文选择器还支持附加当前未暂存的 Git Diff。编辑器上下文大小受可配置的字节上限约束。

输入框支持从剪贴板粘贴、从桌面拖放，或通过文件选择器添加附件。附件会上传到已连接的 Runtime，并在提示中只使用会话范围的 receipt；插件不会把本地文件路径发送给模型。点击用户或助手消息下方的复制按钮，可将消息文本放入系统剪贴板。

使用 `/template` 斜杠命令，可以从 `.dsh/prompts` 选择有大小限制的 Markdown 草稿并放入输入框。模板会以可见、可编辑的内容出现，只有手动发送后才会提交。

原生 **Conversation Outline** 命令可以定位当前会话的指定回合。已完成的助手消息还可以使用 Runtime 提供的可选反馈（评分、备注和整会话反馈）；旧 Runtime 会隐藏这些控件，不影响聊天。对已完成消息可以 Fork 会话、恢复该回合的代码改动，或在宿主重新校验检查点后同时执行两者。

### 先规划，再执行

通过输入框的 **Plan** 开关或 `/plan` 进入计划模式，使用 `/plan off` 退出。
开关只改变会话模式，不会发送任务或消耗已附加的 IDE 上下文。
代理提交计划后，可以批准计划，也可以填写反馈并选择“继续规划”。
Runtime 提供耗时数据时，子代理树和预览会显示运行中及已完成的执行耗时。

Activity Dock 还会显示经过校验的活动提醒；Runtime 提供相应能力时，也会显示只读插件清单
和动态 Cordis 插件状态。动态插件的停止、移除和拒绝操作都由宿主校验，IDE 不会执行不可信的
Client half 插件代码。

### 在 IDE 内管理 Runtime

插件管理本地 Runtime 的启动、停止和重启。仅当本机和包管理器启动方式不可用时，才会从固定的 CNB 发布清单下载托管分发包，校验精确大小和 SHA-256，使用按版本锁并原子缓存；包管理器使用配置的 Runtime 版本。也可以连接已有 Runtime，或在浏览器中打开 Web UI。

Runtime 0.1.5 适配包括断线后恢复临时流式回答、V3 历史与压缩记录、附件提交、子代理排队提示，以及重连后显式恢复 Goal。模式选择遵循 Runtime 策略，技能菜单悬停显示来源路径。上下文环形条悬停查看统计，点击模型名称切换模型。

插件启动的本地 Runtime 崩溃后会重试，并通过隔离验证、有限次数修复与可撤销的 bundle 隔离尝试恢复。状态栏提供取消恢复、还原修改和导出脱敏诊断。设置中可分别关闭自动恢复和持久化 bundle 隔离。恢复助手随插件打包在本地运行，即使使用独立安装的 `dsh`，仍需要 Node.js 24+。

自动复用仅针对共享编辑器锁中登记的兼容 Runtime。升级经过身份核验的本机 npm 安装，或停止已确认归属的孤儿 Runtime，均通过原生对话框确认。手动填写的外部服务地址由外部管理；旧 Runtime 请先升级，0.1.5 的历史迁移不支持通过降级回退。

Agent Team 实验能力已提供内部类型化客户端，支持查看、创建和更新任务、取消请求、版本检查及结构化业务错误；不会自动安装可选服务，也未新增 Team 界面。

API Key 保存在 IntelliJ **Password Safe** 中，传递给新启动的本地 Runtime。界面提供英文和简体中文资源。

## 配置


打开 **Settings | Tools | DeepSeek Harness**。

| 设置项 | 默认值 | 说明 |
| --- | --- | --- |
| 命令 / 参数 | `auto` / 空 | 优先使用兼容的本机 `dsh`，然后在开启自动安装时回退到固定版本的 pnpm/npx，最后使用已校验的托管分发包；填写 `managed` 可强制使用托管分发包，也支持显式指定命令。 |
| 服务地址 / 端口 | `""` / `0` | 优先连接已运行的 DSH Runtime；本地启动时端口 `0` 表示自动选择可用端口。 |
| 自动启动 | `true` | 项目打开时自动启动或连接 Runtime。 |
| Runtime 版本 | `0.1.5-rc.2` | 包管理器回退版本；本机 Runtime 最低支持 `0.1.5-rc.1`。 |
| 托管 Runtime | `true` | 开启通过固定清单校验的平台分发包，作为自动启动的最终后备。 |
| npm 镜像 | `https://registry.npmmirror.com` | 下载后备重试的 Registry 镜像。 |
| 超时 | 启动 `30s`，请求 `600s` | 等待启动和单次 RPC 调用的超时时间。 |
| 上下文字节数 | `120000` | 单次请求中 `<ide_context>` 的最大 UTF-8 字节数。 |
| API Key 环境变量 | `DEEPSEEK_API_KEY` | 凭据注入到 Runtime 进程时使用的环境变量名。 |

## 常见问题

| 现象 | 排查入口 |
| --- | --- |
| Runtime 无法启动 | 通过 Find Action 运行 **DSH: Diagnose Environment** 和 **DSH: Open Runtime Logs**，检查设置里的启动命令和包管理器。 |
| 聊天窗口提示 JCEF 不可用 | 使用带 JCEF 的 IDE 运行时；降级面板也提供浏览器入口。 |
| 修改 API Key 后没有生效 | 重启本地 Runtime；如果连接外部 Runtime，请直接更新它的凭据。 |

## 从 Release 安装或本地构建

在 [GitHub Releases](https://github.com/HarcoChen/dsh-intellij-integration/releases) 下载插件 `.zip`，然后打开 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，选择压缩包，无需解压。

源码构建需要 **JDK 21**，使用仓库附带的 Gradle wrapper：

```bash
./gradlew format         # 应用仓库统一的 Java 格式化规则
./gradlew lint           # 检查格式并运行 Checkstyle
./gradlew verifyPlugin   # 结构与兼容性检查
./gradlew buildPlugin    # 生成可安装的 zip
```

Windows 使用 `gradlew.bat`。首次构建会下载 Gradle 和 IntelliJ Platform 依赖。

## 数据使用与隐私

插件自身不收集遥测数据。提问及附加的上下文经 DSH Runtime 发送给你配置的模型服务商，相应请求受该服务商的条款和隐私政策约束。由插件保存的 API Key 存放在 IntelliJ Password Safe 中，并传递给新启动的本地 Runtime 进程。

## 反馈与贡献

欢迎 [报告问题或提出建议](https://github.com/HarcoChen/dsh-intellij-integration/issues)。报告问题时，请附上 IDE 和插件版本、操作系统、Runtime 版本及复现步骤；分享日志前请移除 API Key 和私有代码。也欢迎改进文档、提交范围明确的 PR，参与前请阅读 [仓库规则](AGENTS.md)。

如果 DSH 帮到了你，欢迎点一个 Star，让更多人发现它。版本记录见 [Releases](https://github.com/HarcoChen/dsh-intellij-integration/releases)，致谢与归属见 [第三方声明](THIRD_PARTY_NOTICES.md)。

本项目由社区独立维护，未经 DeepSeek 或 JetBrains 背书。采用 [MIT 许可证](LICENSE)。
