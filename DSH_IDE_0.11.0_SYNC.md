# dsh-ide 0.11.0 同步记录

同步基线为本仓库 `4b4a4d3`。上游固定到
`96d3aaff973879617b82f9187d4b2d344a12f5ed`（v0.11.0），覆盖自 `7e45c2b`
以来的新功能、修复及 UI 重构。Runtime 默认版本和 Remote 契约更新为
`dsh-v0.2.1-rc.2`，源码 pin 为 `687f282e8625c6b8d11ff58211a90524be2e4317`。
协议依据相邻 `deepseek-harness/` 中该提交的服务实现核对。

## 迁移范围

| 上游变化 | JetBrains 实现 |
| --- | --- |
| 新版聊天、插件清单和设置界面 | 重建共享 Webview 与 Trace，保留原生账号、日程、编辑器 Tab、Runtime 文件浏览和 Jev 密钥入口。 |
| Enter 发送、焦点恢复、英文默认文案 | 保留本插件已有 Enter 发送设置、Ctrl/Cmd+Enter、Shift+Enter 和 IME 保护；继承上游发送后恢复焦点及英文恢复诊断。 |
| 工作目录投影和历史定位 | 展示 `workingDirectory`；文件链接、Trace 和工具 Diff 按事件序号解析目录；会话身份和权限根仍使用原始 `cwd`。 |
| 文件系统元数据 Diff | 支持 Remote 持久化的 write/edit 参数与执行元数据；已执行工具不使用提议片段冒充结果；按实际绝对路径区分同名相对文件。 |
| 整轮改动捕获 | 独立串行捕获队列；轮中目录改变时拒绝整轮恢复并提示逐项 Diff；修正 Git 子目录中的相对路径和 smudge filter 路径。 |
| 当前文件 Problems | 原生多选诊断附件，支持附件菜单、补全候选和直接输入 `@diagnostics`；沿用单轮附件消费时机和字节预算。 |
| 会话中心 | 实时更新的原生可搜索列表，按待处理请求、错误、运行状态排序，显示 Harness Workspace 和迁移状态。 |
| 历史 Jobs | 从 durable 注册/结算事件还原只读记录，与实时 roster 合并；只有实时所有权允许取消。 |
| Runtime 终端 | 独立 JCEF/xterm 视图和 Java Remote 双向传输，支持创建/重连、retain、屏幕恢复、输入确认、限流和 resize。断线丢弃输入，关闭请求清理 Host 进程；兼容旧 RC.2 follow/unary 传输。 |
| 插件安装/卸载 | 使用官方 manager 的检查、安装、等待、取消和卸载接口；原生确认、显式构建脚本批准、进度与脱敏日志；展示失败、不兼容和重启原因。 |
| 网页搜索和推理等级 | 引导配置 Search/Fetch，保持同 namespace 原子选择、revision 和 Runtime 身份检查；自定义模型的推理等级来自 Host schema，不自行增加等级。 |
| Remote 访问控制 | 消费访问快照与授权更新，限制聊天操作和订阅；访问变化时清理代码/消息缓存，撤权后清除不可读历史；Host 保持最终授权。 |
| 缓存投影与迁移 | `cached` / `migration-required` 只作展示提示，绝不写入有序 live cells；支持 workspace `replace` 帧。 |
| 账号通知 | 有序显示标题/消息，检查过期和重复项，每次展示和确认前重新验证账号与 Runtime。 |

终端使用 `@xterm/xterm` 5.5.0 和 `@xterm/addon-fit` 0.10.0；依赖、构建源码及
MIT 许可证一并提交。Gradle 仍直接打包已生成资源，插件用户无需 npm 安装。
Jev 子模块保持 `e5d74c5`，本批上游没有更新该指针。

旧 Runtime 的 owner 兼容只在已知版本低于 `0.2.1-alpha.3` 且访问接口确实缺失时启用；
启动器和兼容广告保留实际版本。未知版本的外部 Runtime 不通过缺失接口推断 owner。
IDE 终端上下文仍受 2024.3 公共 API 限制，与本次 Host PTY 功能分别处理。

## 完整性复查与后续修复

首批迁移提交 `bf52093` 后，再按上游入口和生命周期复查，补齐以下遗漏：

- 注册 Runtime 终端、诊断附件、插件管理和会话中心的原生 Find Action 入口，
  并把诊断附件加入编辑器菜单；默认状态短语与上游英文一致。
- 本地重启、恢复和密钥配置不再被尚未建立的 Remote 授权拦截；需要 Host 的
  管理入口先等待连接和访问基线，再执行授权检查。终端没有选中会话时明确提示。
- 聊天和 Trace 共用文件定位：项目内文件在 IDE 打开，其他路径尝试 Host 提供的
  `canOpenWorkspacePath` / `openWorkspacePath`；保留事件目录与跨系统路径语义。
- 授权更新时先暂停订阅恢复，取消的 mux 流不再向已关闭视图发送结束回调；
  Trace 在撤权时清除历史、投影和详情，迟到结果不能重新填入。
- 终端按连接代次丢弃过时画面，用 xterm 完成写入后的确认限制待渲染字节，
  超限时关闭附件并显示错误；恢复屏幕与已排队写入保持顺序。

这表示已实现本次核对出的可迁移能力，不代表所有平台功能或环境均已验收。
明确没有实现的差异仍见 TODO 的 Platform exceptions：2024.3 的 IDE shell
命令/输出上下文、通用 XDebugger 没有公开契约的 DAP 选项，以及 VS Code
Chat Participant/proposed provider API。日程编辑、删除和历史等操作使用原生
管理流程承接，共享 Webview 的只读摘要不代表相应 Host 功能缺失。

## 原生选择器与弃用 API 维护

模型、Provider、Preset、插件、日程、Harness Workspace、模板等现有单选入口统一使用
`DshChoiceDialog`。支持不区分大小写的多关键词过滤、键盘上下选择、回车或双击确认，
无结果时禁用确认；筛选使用原始索引，重复名称不会把操作路由到其他选项。提示和
选项按纯文本显示，提示可滚动，取消仍返回原有的 `-1` 语义。

移除全部 `Messages.showChooseDialog` 调用，以项目 base path 计算资源相对位置，
不再调用 `Project.getBaseDir()`。

自动采集局部变量和参数值的功能已移除，包括 `computeChildren` 调用、变量呈现回调、
值缓存和 `tooManyChildren` 兼容桥。`XCompositeNode` 官方说明不面向插件自行实现，
且该方案需要覆写弃用的抽象方法，不再保留这一依赖。
依据为 [243 版本 XCompositeNode 源码](https://github.com/JetBrains/intellij-community/blob/idea/243.26574.91/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XCompositeNode.java)。

“解释调试状态”和 MCP `debug_context` 继续提供调用栈、当前源码与已有 IDE 诊断；
输出和工具说明明确告知变量值未采集，需要用户手动附加。等待逻辑仅等待栈帧，不再
等待变量回调，栈读取失败时也会完成等待。基础调试控制、断点、暂停线程及栈帧选择保留。

IC/PC 2024.3.6 的 Plugin Verifier 弃用使用数从各 23 处降为 0；未通过报告过滤、
抑制验证或平台内部实现隐藏警告。打包后的插件也确认不再包含变量采集类或相关接口引用。

本批 `compileJava`、Spotless、Checkstyle、`buildPlugin` 和 IC/PC Plugin Verifier
均通过；没有新增单元测试。搜索、键盘、IME、缩放和辅助功能的完整原生界面验收仍需
在安装后的 IDE 中完成。

## 验证

- helper 与 terminal-view TypeScript strict/noEmit 检查通过。
- Webview、Trace、Runtime helper、xterm 资源从固定源码重建。
- Gradle `lint buildPlugin` 通过；84 个 Jev 资源校验通过。
- IntelliJ Plugin Verifier 在 IC 243.26574.91 与 PC 243.26574.90 上判定 Compatible。
  弃用 API 提示已清零；仍有可选 JCEF 模块未解析提示。
- 运行上游现有 `verify-remote-runtime.mjs --workflow-controls --feature-controls`
  对接真实 0.2.1-rc.2：账号通知、授权撤销、终端屏幕/输入/保留、插件检查/安装/卸载/
  取消、目录改变和同名文件 Diff、schema 推理等级、搜索设置、历史 Jobs 及重连均通过。
  测试使用隔离 DSH_HOME、临时本地软件包和 loopback 模型，共 22 次本地模型请求，
  没有调用外部模型。
- 另以临时 Java 集成入口直接连接隔离的真实 Host：Java 访问/事件/工作区/控制/目录
  基线、会话 follow、PTY retain、双向输入 ack、有序输出、关闭清理与连接重建通过。
  该检查验证 Java 适配层；上游脚本的覆盖不能替代 Java UI 的人工验收。
- 后续复查重新通过 TypeScript strict、Gradle lint/buildPlugin 和 IC/PC Plugin
  Verifier，并复跑 Java 真实 Host 的终端/重连检查。另用临时非 owner 策略验证
  取消的 mux 流不触发结束回调，以及连续四次撤权/重新授权：历史和目录缓存清空，
  原有 follow 自动恢复，carrier generation 不变。
- 已尝试启动隔离 IntelliJ 沙箱，但本机界面自动化无法访问该 Java 应用窗口；
  因此没有将 JCEF/原生界面点击流程标为验收通过。
- 实际 Git 子目录验证确认 `hash-object --path` 使用 cwd 相对路径，`cat-file --filters`
  使用仓库相对路径，避免 CRLF/filter 处理错位。

没有创建或添加单元测试。完整的安装后 JCEF/原生对话框交互、PyCharm 调试工作流、
Windows/Linux 原生进程、跨机器文件定位、真实账户和第三方在线插件仍需对应环境验收。
本次不发布插件，也不调用真实第三方模型或安装用户 Runtime 插件。
