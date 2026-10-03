# DSH 0.2.0-rc.2 / dsh-ide 0.10.2 IntelliJ 迁移

迁移基线为本仓库 `3aadb731042c3bf4254aeec83997f104d8fe6789`。目标是配套
`dsh-ide v0.10.2`，commit `deb8d8f882586c42a20abfbb5609cb0643fac309`；Remote
契约逐项对照 `deepseek-harness/` 的 `dsh-v0.2.0-rc.2`，commit
`639ed015397290b3745d163aafe02ffee4aa3f84`。Workspace 始终指 Harness 领域对象。

本文记录 0.10.2 迁移及后续差异补全。当前可迁移差异已经补齐；当前平台的终端 API
和通用调试接口限制见 [TODO](TODO.md#platform-exceptions--deliberately-not-implemented)。

## 迁移内容

- 默认 Runtime 和契约 pin 为 `0.2.0-rc.2`，兼容下限仍为 `0.1.5-rc.1`。
  已保存的明确版本不被改写；旧 Runtime 缺少可选能力时保留原有展示路径。
- 限时问答通过 `userQuestions/attachWait` 获取 Host 剩余时间，到期提交
  `ASK_TIMED_OUT` rejection。普通 waterfall 回答与 `userQuestions/answer` 延迟
  回答共享 `u:<callId>` 卡片身份；连接代、原子 claim 和状态投影阻止重复或过期提交。
  延迟回答 accepted 仅表示排队，直到 durable settled 投影到达才展示已记录的回答。
  JCEF 草稿按 Session/call 保存到项目本地设置的有界缓存，视图重建和 IDE 重启
  可恢复，回答结算后清理。不会把草稿写入仓库或外部服务。
- Jobs 使用 `job/list` 全量 roster 和 `job/follow` 非消费输出流，按绝对字节游标
  重连续读。单任务保留最多 128 Ki UTF-16 code units，缺失或截断会显示提示。
  `job/kill` 只用于当前可见任务；收到 requested 后等待真实 roster/status 收敛。
  切换会话、Runtime 停止和视图关闭会释放订阅。
- Team 使用 `session/projections` / live control 的 `agentTeam`，删除不再存在的
  `agentTeams/view|createTask|updateTask` 客户端。面板展示成员、任务、依赖、写入范围
  和冲突；Team profile 不暴露 `subagents/list`，因此成员导航从投影构造，历史与
  follow-up/interrupt 复用公开 addressed subagent API。
- 权限选项合并 `permissionPresets/catalog` 与 Session 当前值。缓存包括缺失结果，
  随重连和 `permission-presets/catalog-changed` 等配置事件更新；`custom` 可以显示，
  不能作为切换目标。新增的 credentials/reference 与 llm/adapters 事件刷新模型目录。
- 插件设置接入 `pluginManager/listBundles|listPlugins|setPluginEnabled|setBundleEnabled`，
  校验 patch 目标、只读原因、加载错误及变更结果。保留 applied、restart-required、
  overridden、failed、cancelled 的区别；安装和卸载沿用上游的后续计划。
  RC.2 preset inventory 缺省 `trust` 时不再拒绝整份清单。
- Runtime 文件浏览使用 Host 的 `workspaceFiles/list|stat|readBytes|changes`。根请求为
  `.`，readBytes 显式提供 options。Java 解码 Connection multipart bytes，限制响应
  大小与总读取时间；预览校验前后版本和绝对路径，拒绝二进制、无效 UTF-8 和超过
  1 MiB 的文本。原生只读编辑器保留内容，Host 变更与手动操作均可刷新，预览绑定
  原 Runtime，关闭即释放 watcher。IntelliJ Document 显示时统一行分隔符，不写回 Host。
- `auto` 只发现兼容的本机或官方 Desktop 注册的 dsh，缺失时提供官方下载入口。
  停止自动 npm/CNB 安装，保留明确 pnpm/npx 启动。可读取 dsh-ide per-owner Runtime
  广告，并只发布/撤回自己的广告；旧锁只作只读线索，不参与启动或回收；进程所有权保存在本编辑器内存中。
- Laya/Jev 接受 HTTPS 或 localhost/127.0.0.1 HTTP，保留现有 Jev package pin。
  首次打开新插件版本时显示一次更新通知，也可从 Find Action 手动打开。
- React bundle 从上述 companion commit 重建，保留 IntelliJ 原生账户和日程管理
  入口。`scripts/sync-webview.mjs` 和 Schedule adapter 记录重建方式。

## 验证

没有创建或添加单元测试。自动质量门禁使用现有命令：

```sh
npm run build --prefix runtime-helper
node scripts/sync-webview.mjs ../dsh-ide
./gradlew format lint buildPlugin verifyPlugin
git diff --check
```

真实协议联调使用临时安装的精确 `@deepseek-ai/dsh@0.2.0-rc.2`，临时 DSH_HOME、
Workspace 和回环 Messages 模拟模型。临时 Java runner 直接构造生产
`DshRuntimeService` / `DshRemoteService`，使用轻量 Project service registry，
没有替换本机 CLI、访问用户会话、调用真实模型 API 或登录账号。

已验证 Java unary/mux baseline、权限 catalog、multipart 文件读取及 CRLF/BOM/UTF-8
保真、文件 watcher、二进制/大小拒绝、插件和 Bundle 控制、前台/倒计时/延迟问答、
问答重连、Jobs 输出游标与取消、原生 Team 成员导航和 addressed 历史预览，以及 Remote
连接 stop/start。另复用 companion
现有集成脚本，验证核心历史、反馈、Goal、可选 Team profile 与成员 addressed 历史。

临时 Chrome smoke 使用实际打包的 Webview，验证选项/文本草稿重建、Host 状态推送、
延迟回答 action、Jobs 输出/取消、Team 导航/任务、插件/Bundle 开关、只读项与回答结算。
这些 runner 及截图均在临时目录，未添加到仓库。

另验证 Runtime 广告 schema、发现与仅撤回自身记录，以及旧安装开关即使已保存为 true
也不会再生成自动 pnpm/npx/managed 候选。ZIP 资源检查确认新增 Java controller、
Webview 与 helper 均已打包。

IC / PC 2024.3.6 Plugin Verifier 均报告 Compatible，保留现有 deprecated API 提示。
真实 IDE 中的 ZIP 安装、原生文件预览交互、Windows/Linux 进程执行和跨机器部署仍需
人工验收；没有执行真实账号登录、Schedule 到期投递或第三方插件 HMR。


## Goal 差异补全（2026-10-03）

补齐 Preset 默认项、可选自主调试、编辑器 Tab、持久化问答草稿和 Runtime 生命周期。
Preset 的 copy/delete/directory opener 已清理；不再把 RC.2 缺省 trust 解释为可编辑用户预设。
调试通过本机带凭据的 MCP 挂到 owned Runtime，bearer 从进程环境读取，不写入 patch。
只操作已有 IDE 配置与公共 XDebugger 操作；不支持的产品专用/DAP 选项明确报错。

聊天 Tab 与工具窗口使用同一个控制器和会话状态，独立释放 JCEF 视图。草稿写入项目本地
IDE 设置，保存的是每个视图实际发生的变更，避免空闲镜像覆盖另一视图的输入。
旧 Runtime 锁只作为发现线索，不参与启动/回收；并发 gate 与 companion 的端口和时间界限一致。
包装启动器提前退出时仍验证其持续服务的端点，并撤销进程所有权、保留发现记录；明确关闭
只停止自己仍拥有的进程。健康探测拒绝普通 HTTP 错误和非法 Remote envelope。

补充验证使用临时 validation plugin 和隔离 IC 2024.3.6 项目：真实 Java debug_start、断点、
暂停、栈/变量/源码、单步、MCP 鉴权与调用；真实 RC.2 通过官方 MCP client 发现并调用 IDE 工具；
原生 Runtime/helper/debug-server 启停、Editor FileEditor 入口、草稿状态存储。
启动插入 patch 保持 `--profile web` 为完整参数对。另用隔离 wrapper 验证启动器退出早于
Runtime ready 时仍不重复启动，退出 helper 保留已移交的服务。没有加入单元测试。

终端例外依据为 [JetBrains SDK](https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html)：
Reworked Terminal 公共 API 从 2025.3 提供且仍实验，无法作为本插件 2024.3 的共同能力。
完整差异清单、通用调试接口限制及仍需环境验收的项目统一在 TODO 中维护。

问答草稿的真实 IDE 存储验证确认 `DshChatDrafts` 已写入项目 IDE 状态文件；同时验证
空闲镜像的空增量不会覆盖另一视图草稿。调试上下文复用已有源码诊断读取路径。

补充确认：真实 IntelliJ 重启后从本地项目状态恢复问答草稿；Preset 的默认项更改通过 RC.2 的配置 namespace 和 roster 验证。
