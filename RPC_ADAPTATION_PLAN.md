# DSH `0.1.2-rc.1` Remote RPC IntelliJ 适配方案

## 1. 目标与结论

本方案以 `../dsh-ide/RPC_ADAPTATION_PLAN.md` 的协议审计，以及
`../dsh-ide/deepseek-harness` 中 `dsh-v0.1.2-rc.1`（commit
`a66e4702047846cdaa10c66c9d3df3951f5ea70d`）的源码为依据，目标是让
DSH IntelliJ Integration 完整支持默认托管 Runtime `0.1.2-rc.1`。

这里的 `workspace` 始终指 deepseek-harness 的 Workspace 领域对象，不指 IntelliJ
IDEA/PyCharm 的 Project、Module 或编辑器工作区。

建议采用以下路线：

1. **把 RC+ Remote 协议作为唯一主实现**，不在同一连接与状态机内混跑旧
   ApiProxy、`events.mux` 和新 Typert Remote。
2. **保留 IntelliJ 插件自己的 Java/Swing/JCEF 领域层**，实现小型、严格的 Java
   Remote carrier；不尝试把上游 TypeScript Cordis Client Runtime 嵌进 IDE 进程。
3. **新增 Project Service 作为稳定领域 facade**。`DshToolWindowPanel`、
   `DshTraceDialog`、Actions 和设置界面只调用领域方法、订阅不可变快照，不接触
   endpoint、`args`、streamId、generation 或 RemoteError wire 细节。
4. **连接和状态生命周期从 Tool Window 移到 Project Service**。关闭、重建 JCEF
   页面不应断开 Runtime，也不应丢失 session/workspace/control baseline。
5. **旧 Runtime 明确拒绝并提示升级**。如果以后必须兼容 `0.1.1-rc.2`，另建完整
   `LegacyApiProxyAdapter`；不得把旧 frame 分支塞入新的 Remote store。

只有在 Remote carrier、状态流和真实 Runtime smoke 全部完成后，才把
`DshSettingsState.runtimeVersion`、README 和设置提示中的默认版本从
`0.1.1-rc.2` 升到 `0.1.2-rc.1`。

## 2. 当前实现审计

当前 IntelliJ 实现不是只差一个 URL，核心协议与状态模型都属于旧代：

| 当前实现 | 现状 | `0.1.2-rc.1` 要求 |
| --- | --- | --- |
| `DshRpcClient.call()` | endpoint 可为点号或斜杠；`payload` 多数直接放 DTO | 统一 `/api/<namespace>/<method>`，`payload` 必须是 `{args:{...}}` |
| `DshMuxClient` | 连接 `/api/events.mux`，只识别 `server-request` | 连接 `/api/remote.mux`，复用多个 logical stream |
| `DshRpcClient.respond()` | POST `/api/respond`，用 `rpcId` 回执 | unary `$events/result`，用同 generation 的 `clientId/eventId` |
| `DshToolWindowPanel.refreshState()` | 定时轮询 `session.list`、`session.history`、`workspace.list` | opening baseline + stream increment；`session/page` 负责旧历史 |
| `DshToolWindowPanel` | 同时持有 UI、mux、queue/jobs/projection/workspace 状态 | UI 只订阅 Project Service 发布的领域快照 |
| `DshRuntimeService` | 管进程、URL、健康检查和 unary client | 继续管进程；Remote connection 作为独立 Project Service |
| Runtime 鉴权 | 启动 URL 正则丢弃 `?token=`，RPC/WebSocket 不带 Cookie | 根路径交换 launch token，按 authority 保存并发送 session Cookie |
| Runtime 默认值 | `0.1.1-rc.2` | 完成迁移后固定为 `0.1.2-rc.1` |

旧 endpoint 仍包括 `host.describe`、`session.history`、`workspace.list`、
`session.models`、`skill.list`、`agentPreset.*`、`goal.*`、`subagent.*`、
`settings.openDocument` 和 `/api/respond`。少量 `commands/list|execute` 已经使用斜杠，
但这不代表整体 payload 与状态协议已经迁移。

## 3. 适配边界

### 必须完成

- unary：`/api/<namespace>/<method>`、`payload:{args:...}`、严格顶层参数名；
- stream：单物理 WebSocket `/api/remote.mux`，承载多个
  `open/cancel/item/error/end` logical stream；
- connection generation：以 `$events` 的第一项 `ready` 为就绪边界；
- state：`workspace/follow`、`session/control`、按地址建立的
  `session/follow`、`session/page`；
- event：普通 Remote event，以及 approval/question waterfall 的应答与取消；
- error：auth/http、carrier、protocol、Remote/domain 四层错误；
- feature：当前插件已经暴露的 session、workspace、model、preset、goal、subagent、
  command、skill、settings、credentials、LLM 和 IDE context 能力；
- diagnostics：协议代次、generation、socket/logical stream 状态、目标 Runtime
  版本和脱敏连接信息；
- auth：保留 Runtime 打印的一次性 launch token，在根路径换取 Cookie，并让 unary 与
  WebSocket 共用同一个 authority-bound 会话；
- IntelliJ 生命周期：Project dispose、Tool Window 重建、EDT 切换、后台线程取消。

### 本轮不做

- 不新增单元测试，遵守仓库规则；
- 不为已删除的 `host.describe`、`workspace.list`、`session.history` 建假 endpoint；
- 不把 Remote wire frame 传给 JCEF，也不在 JavaScript 中实现协议 reducer；
- 不用 token、401、404 或单一错误码猜 Runtime 版本；
- 不把 IntelliJ `Project` 当作 Harness `workspace`；
- 不自动宣称兼容 `0.1.2-rc.1` 之后的 alpha/master。

## 4. 目标架构

```text
DshRuntimeService（进程、端口、凭据注入、健康探测）
                  │ baseUrl/status
                  ▼
DshRemoteService（Project Service；稳定领域 facade）
├─ DshRemoteUnaryClient
│  └─ POST /api/<namespace>/<method> + { args }
├─ DshRemoteAuth
│  └─ launch token → authority-bound session Cookie
├─ DshRemoteMuxClient
│  └─ WS /api/remote.mux
│     ├─ $events
│     ├─ workspace/follow
│     ├─ session/control
│     └─ session/follow × 当前引用计数地址
├─ DshRemoteConnection
│  └─ generation、ready、baseline barrier、退避重连
└─ DshRemoteState
   ├─ CatalogSnapshot    ← session/list + Remote events
   ├─ WorkspaceSnapshot  ← workspace/follow
   ├─ ControlSnapshot    ← session/control
   └─ SessionSnapshot    ← session/follow + session/page
                  │ immutable domain snapshots
                  ▼
DshToolWindowPanel / DshTraceDialog / Actions / Projectors / JCEF
```

隔离原则：

- carrier 只理解 envelope、streamId、取消、终止、帧上限和 socket；
- contract 层只声明 endpoint、wire args、返回值和 stream item 的最小结构；
- connection 只管理 generation、opening barrier、重连和订阅重建；
- state 只接收已经校验的 mutation/snapshot，不持有 rpcId/streamId；
- projector 继续接受 Gson 领域 JSON，避免在迁移中重写消息、Trace、Goal 等展示逻辑；
- UI 不比较 RemoteError code，不直接访问 `/api/`。

## 5. 文件级改造

建议新增 `src/main/java/top/harcochen/dsh/remote/`：

| 文件 | 职责 |
| --- | --- |
| `DshRemoteContracts.java` | 固定 `0.1.2-rc.1` endpoint、参数名、返回值和 frame 校验；文件头注明 tag/commit |
| `DshRemoteError.java` | auth/http、carrier、protocol、remote/domain 的结构化错误与 UI 映射 |
| `DshRemoteAuth.java` | launch URL 解析、根路径 token 交换、authority-bound Cookie 与脱敏 |
| `DshRemoteUnaryClient.java` | HTTP envelope、超时、取消、rpcId 对账、RemoteError 恢复 |
| `DshRemoteMuxClient.java` | 单 WebSocket、多 logical stream、streamId 分发、open/cancel、终止清理 |
| `DshRemoteEventClient.java` | `$events` ready/emit/waterfall/cancel 与 `$events/result` |
| `DshRemoteConnection.java` | generation、opening baseline、指数退避、订阅重建和 dispose |
| `DshRemoteState.java` | catalog/workspace/control/session 的原子快照与 mutation reducer |
| `DshRemoteService.java` | Project Service 领域 facade、订阅 API 和 UI command API |

现有文件随后调整：

- `DshRuntimeService` 只保留 Runtime 进程、launch/base URL、凭据注入、健康探测和共享
  启动锁；必须从输出中保留 `?token=`，但公开状态和日志只暴露脱敏 base URL；
  URL/status 变化通知 `DshRemoteService` 开启新 generation；
- `DshRpcClient` 的领域方法迁到 `DshRemoteService`，类本身由
  `DshRemoteUnaryClient` 取代；迁移完成后删除；
- `DshMuxClient` 不原地堆新旧分支，由 `DshRemoteMuxClient` 取代后删除；
- `DshToolWindowPanel` 删除 poller、`refreshInFlight`、mux client 和各类
  `*BySession` wire store，改为订阅 `DshRemoteState`；
- `DshToolWindowPanel` 保留 composer、Swing/JCEF 协调、IDE context、通知和用户动作；
- `DshMessageProjector`、`DshTraceProjector`、`DshSettingsProjector` 保持领域投影职责，
  只适配 normalized history/control 输入；
- `DshTraceDialog` 通过 address 订阅 session/subagent，不再一次性抓取全部旧历史；
- `DshBridge` 继续严格校验 UI action；只接收领域快照，不接收 Remote frame；
- `DshRuntimeLock` 对齐共享 schema `{pid,createdAt,url,launchUrl?}`，严格验证 launchUrl
  是同 authority 的 loopback 根 URL 且只含合法 token query；
- `DshSettingsState` 在最后发布批次更新默认 Runtime，并增加已验证/未验证版本提示；
- `plugin.xml` 注册 `DshRemoteService` 为 project service，由 IntelliJ dispose 管理资源。

## 6. Java 并发与 IntelliJ 生命周期

### 6.1 执行模型

`DshRemoteService` 使用一个 daemon single-thread executor 串行处理：

- socket frame；
- generation 切换；
- baseline/increment reducer；
- logical stream terminal；
- 订阅引用计数变化。

这样 store 不需要散布 `synchronized`，同一 generation 的 frame 顺序也与 wire 一致。
HTTP unary 可使用 `HttpClient.sendAsync()`，完成回调必须先投递回该 executor，并在提交
state 前比较 generation token。

`WebSocket.Listener.onText()` 只负责分片拼接、帧大小限制和投递，不在 HttpClient
回调线程执行 JSON reducer、磁盘操作或 Swing 操作。

### 6.2 EDT 边界

- 领域快照在 connection executor 中构造为不可变对象；
- `DshToolWindowPanel` listener 使用 `ApplicationManager.getApplication().invokeLater()`
  或 `SwingUtilities.invokeLater()` 更新组件/JCEF；
- UI action 立即更新本地 submitting 状态，再将 command 交给 Project Service；
- 不允许在 EDT 上 `.join()`、`HttpClient.send()`、等待 stream baseline 或执行分页；
- dispose 后排队的 EDT callback 必须通过 disposed/generation guard 静默退出。

### 6.3 生命周期

- `DshRemoteService` 生命周期与 IntelliJ `Project` 一致；
- Tool Window 打开/关闭只增减 session address 的订阅引用；
- `DshTraceDialog` 打开时增加 address 引用，关闭时释放；
- Project dispose 先禁止新调用，再 cancel logical streams，最后关闭物理 socket/executor；
- Runtime restart/serverUrl 改变时关闭旧 generation，但不销毁 UI 领域 facade；
- 旧 generation 的 HTTP/WebSocket/EDT 延迟回调不得写入新状态。

### 6.4 Runtime 鉴权

`0.1.2-rc.1` 不接受 RPC query token 或 Authorization token。Runtime 启动时打印的
`/?token=...` 只能用于根路径交换：Host 返回绑定 scheme/host/port 的 HttpOnly session
Cookie，之后每个 unary 与 `/api/remote.mux` upgrade 都发送该 Cookie。

IntelliJ 侧实现要求：

- `DshRuntimeService` 将 URL 解析为不含 token 的 `baseUrl` 和仅供认证/打开浏览器使用的
  `launchUrl`；当前 `URL_PATTERN` 必须扩展到安全识别可选 token；
- token 不进入 `RuntimeStatus`、普通日志、异常、诊断或 JCEF state；`launchUrl` 只保留
  在 Runtime Service 内存和跨 IDE 共享启动锁的专用字段中；
- `DshRemoteAuth` 使用不自动跟随重定向的专用 `HttpClient` 请求 launch URL，严格读取
  第一条合法 `Set-Cookie`，且永远不把 launch token 传给 RPC carrier；
- `DshRuntimeLock` 写入可选 `launchUrl`，让后启动的 IntelliJ/VS Code 进程各自交换
  Cookie；读取时要求其 base URL 与 `url` authority 完全一致；
- Cookie 只绑定规范化 authority；base URL、端口或进程 generation 改变就清除；
- `DshRemoteUnaryClient` 为每个 RPC 加 `Cookie` header；
- `DshRemoteMuxClient` 在 `newWebSocketBuilder()` upgrade 时加同一 Cookie；
- 同一 authority 的并发调用共享一个认证 future，失败后允许下一 generation 重试；
- 401 清除该 authority Cookie 并终止当前 generation，不在同一个请求中无限重放；
- 配置的 `serverUrl` 若携带 token，按同样流程交换；若既无 token 也无已知 Cookie，
  返回明确 auth error；
- 健康检查分为公开页面可达性与已认证 Remote probe，不能再用旧 `host.describe`。

## 7. Wire 契约实现

### 7.1 Unary client

统一入口建议为：

```java
CompletableFuture<JsonElement> call(
        RemoteEndpoint endpoint,
        JsonObject args,
        Cancellation cancellation);
```

请求示例：

```json
{
  "type": "client-request",
  "rpcId": "client-minted-id",
  "method": "session/list",
  "payload": {
    "args": {
      "request": { "cursor": "optional-cursor" }
    }
  }
}
```

必须纠正当前实现的扁平 payload：`args` 的字段名来自 Host 方法参数名，而不是把 DTO
摊平。典型映射：

| Host 方法 | 正确 `args` |
| --- | --- |
| `session.list(request, signal)` | `{request:{...}}` |
| `session.modelCatalog()` | `{}` |
| `workspace.follow(signal)` | `{}` |
| `settings.update(ns, patch, expectedRevision)` | `{ns,patch,expectedRevision}` |
| `goals.edit(agentId, ref, request)` | `{agentId,ref,request}` |
| `commands.execute(agentId, line, images, signal)` | `{agentId,line,images}` |

实现要求：

- endpoint 只允许 contract 中的 `<namespace>/<method>` 和保留项 `$events/result`；
- URL endpoint 与 envelope `method` 完全一致；
- 返回 `void` 的成功响应允许缺失 `value`，由 contract 决定而非硬编码单 endpoint；
- 校验 `type`、rpcId、result、ok，以及 endpoint 对应 value 的最小形状；
- Remote failure 保留 `{code,message,details,isDSHRemoteError?}`，不依赖 Java 异常类型猜测；
- JSON、rpcId、envelope、value 校验失败归为 protocol error；
- 401/403 是 auth，404 是 capability/method 不可用，均不得回退旧协议；
- 日志不得包含 token、Cookie、credential、prompt、图片或完整 settings value。

### 7.2 Remote mux

`DshRemoteMuxClient` 在一个 WebSocket 上维护：

- `Map<String, LogicalStream>`；
- UUID streamId；
- 每个 stream 独立的 callback/queue、取消句柄和 terminal flag；
- physical socket generation id；
- 最大完整文本帧大小、未知 streamId/重复 terminal 诊断计数。

打开与取消：

```json
{"type":"open","streamId":"s-1","endpoint":"session/follow","payload":{"args":{"request":{"address":{"kind":"session","sessionId":"..."}}}}}
{"type":"cancel","streamId":"s-1"}
```

服务端帧：

```json
{"type":"item","streamId":"s-1","value":{}}
{"type":"error","streamId":"s-1","error":{"code":"session/not-found","message":"...","details":{}}}
{"type":"end","streamId":"s-1"}
```

竞态规则：

- cancel 最多发送一次，并立即禁止该 stream 后续领域提交；
- `error` 与 `end` 都是 terminal，之后的同 streamId frame 忽略并记诊断；
- 未知 streamId frame 不得隐式创建 stream；
- socket 关闭时，所有 logical stream 以 carrier error 结束；
- mux 不自行重放 open，由 connection 在新 generation 按固定顺序重建；
- Java HttpClient WebSocket 自动处理协议级 Ping/Pong，但仍需正确调用 `request(1)`；
- WebSocket upgrade 使用 `DshRemoteAuth` 提供的 Cookie，并按 authority 隔离凭据。

### 7.3 Connection generation

每代连接按以下顺序建立：

1. 建立 `/api/remote.mux`；
2. 打开 `$events`，payload 固定 `{args:{}}`；
3. 第一项必须是 `ready`，保存本 generation 的 `clientId` 与 `host.home`；
4. 打开 `workspace/follow` 与 `session/control`，各自等待首个 baseline；
5. 执行 `session/list` baseline；
6. 重建当前引用计数大于零的 `session/follow`；
7. opening state 全部就绪后原子发布 `CONNECTED` 与首个完整领域快照。

任一步遇到非法首帧、stream error、socket 关闭或超时，都撤销整个 generation，清空
generation-scoped `clientId`、streamId 和取消句柄，再使用带 jitter 的指数退避重连。
旧 generation 的异步完成在 reducer 前必须被 token 拒绝。

## 8. 状态同步

### 8.1 Workspace

`workspace/follow` 是唯一权威来源：

- `baseline` 原子替换 workspace items、顺序和 archivedSessionIds；
- `upsert/remove/order/archived` 按到达顺序应用；
- baseline 前不对 UI 发布 increment；
- 重连不复用旧 generation 的 order/archive；
- mutation unary 可以提供短暂 optimistic UI，但同 generation follow 最终校准；
- `DshToolWindowPanel.refreshWorkspaceRegistry()` 与 `workspace.list` 删除。

### 8.2 Session catalog 与 control

catalog 由以下来源合并：

- `session/list`：generation opening 列表；
- `$events`：`api-session/added|removed|status|activity|error`；
- `session/control`：queue、jobs、title/goal/model 等 projection replacement；
- `workspace/follow`：Harness workspace 归属、顺序与归档。

opening 时一次性提交 session/workspace/control 三类 baseline，避免 JCEF 短暂显示“新
session + 旧 workspace 顺序”。control baseline 中缺失的 session 视为空；projection
按 key + seq 合并，低 seq 不覆盖高 seq。

这会替代 Tool Window 内的 `queueBySession`、`jobsBySession`、
`projectionCellsBySession`、`workspaceBySession` 和 `archivedSessionIds` wire store。

### 8.3 Session history、Trace 与 subagent

所有历史使用 `SessionAddress`：

```json
{"kind":"session","sessionId":"..."}
{"kind":"subagent","parentSessionId":"...","childSessionId":"...","mode":"continuable"}
```

流程：

1. 当前聊天、打开的 Trace 或 subagent preview 获取 address subscription；
2. `session/follow` 首个 `snapshot` 原子安装 header/cursor/records/hasMore/projections；
3. 后续 event 要求 seq 连续；重复 seq 幂等忽略，gap 触发该 address 重开；
4. 上翻调用 `session/page({request:{address,throughSeq,beforeSeq,maxMessages}})`；
5. pagination 固定 opening cursor 为 `throughSeq`，旧页向 head 合并，实时项继续追加 tail；
6. `event` 与 packed `chunks` record 先 normalization，再送给现有 projector；
7. `subagent.history` 特例删除，普通 session/subagent 共用 follow/page。

只订阅当前聊天、打开的 Trace 和正在查看的 subagent，不为整个 catalog 永久打开
follow。Tool Window 隐藏时是否保留当前会话订阅由引用计数策略决定，但
workspace/control/$events 始终属于 Project generation。

### 8.4 Prompt 幂等

用户提交前生成 requestId，并保持到 durable 对账完成：

- 同一次提交的超时恢复与重连对账复用 requestId；
- 用户明确再次发送才创建新 requestId；
- optimistic message 以 requestId 为键；
- control queue rpcId 或 durable user message source 命中时移除 optimistic echo；
- `accepted:true` 只表示进入 Agent inbox，不能当作 durable history 已出现；
- mode、content、clientTimeZone 全部放入 `args.request`。

当前 `sendPrompt()` 在调用后立即轮询 history，不具备这一幂等边界，需要迁到
`DshRemoteService.prompt()`。

## 9. Remote event 与交互

`$events` 同时是 Host event downlink 和 generation readiness barrier。

普通 `emit`：

- 按目标 tag 的 allowlist 校验最小 args 结构；
- catalog、settings、model、preset、credentials、commands 的事件触发对应失效或刷新；
- 未消费但合法的事件只记低级别诊断；
- 正确性依赖的状态必须有 baseline/query，不能只靠 emit。

`waterfall`（approval、question）：

- 用 eventId 建立 pending interaction，用 agentId 关联 session；
- UI 回答走 unary `$events/result`：`{args:{clientId,eventId,outcome}}`；
- clientId 必须来自同一 generation，旧 generation 的回答拒绝发送；
- Host `cancel` 立即撤销 UI 交互；
- 支持 `result`、`next`、`rejected` 三种 outcome；
- 同 eventId 最多提交一次，提交期间禁用按钮；
- 删除 `_rpcId` 注入、`DshRpcClient.respond()` 与 `/api/respond`。

## 10. Endpoint 迁移批次

### 批次 A：启动与核心会话（阻塞发布）

| 领域调用 | Remote endpoint | `args` 形状 |
| --- | --- | --- |
| list/search/create/rename/fork/prompt/attachment/updateQueue/cancel | `session/<method>` | 通常 `{request:{...}}` |
| models | `session/modelCatalog` | `{}` |
| select model | `session/selectModel` | `{request:{sessionId,...}}` |
| history | `session/follow` + `session/page` | `{request:{address,...}}` |
| queue/jobs/projections | `session/control` | `{}` |
| workspace state | `workspace/follow` | `{}` |
| workspace mutations | `workspace/<method>` | 通常 `{request:{...}}` |
| host facts/events | `$events` | `{}`；ready 给出 clientId、host.home |

完成后必须能启动/附加 Runtime、列出/创建/切换会话、收发消息、分页、取消、重连和
管理 Harness workspace。

### 批次 B：交互与 subagent（阻塞发布）

- approval/question：`$events` waterfall + `$events/result`；
- commands：`commands/list|execute`，使用 `agentId` 与方法参数；
- subagents：`subagents/list|prompt|interruptByParent`；历史统一使用 subagent address；
- skills：`skills/list`；
- message feedback：`messageFeedback/list|put|delete`（若当前 UI 暂未暴露，仍在 contract
  中记录 capability，不伪装旧 endpoint）。

### 批次 C：配置与现有扩展功能

- presets：`agentPresets/list|select|read|copy|deletePreset`；目录打开使用
  `settings/openAgentPresetDirectory`；
- goals：`goals/create|edit|pause|resume|complete|clear`，携带 agentId，并处理 revision
  conflict；
- LLM：`llm/listProviders|listConfigurableProviders|discoverModels`；
- settings：`settings/describe|update|replace|mutate|openSettingsDocument`；
- credentials：`credentials/describe|set|unset`；
- path：`session/canOpenWorkspacePath|openWorkspacePath` 或 `directoryPicker/*` capability。

每迁移一个领域就删除其旧 endpoint 与 fallback，不允许同一领域同时调用点号和斜杠
两套方法。

## 11. Capability 与版本策略

### 托管 Runtime

- `runtimeVersion` 是版本事实，默认值只在迁移验收完成后改为 `0.1.2-rc.1`；
- 更旧版本在启动前提示“不支持旧 ApiProxy 协议”；
- 更高版本标为“未验证”，不能仅凭 semver 宣称兼容；
- Remote handshake/contract 失败时给出 Runtime 版本、endpoint 和错误层级，但不输出凭据。

### 附加 Runtime

`serverUrl` 没有可靠版本字段，使用无副作用 capability probe：

1. 完成根路径 token/cookie 鉴权；
2. 调用 `session/list`，payload 为 `{args:{request:{}}}`；
3. 合法 `server-response`（成功或结构合法 RemoteError）证明 Remote v1；
4. 401/403 报 auth，404 或非 Remote envelope 报“不支持的 RPC 协议”；
5. probe 结果绑定 base URL + auth generation，URL/token 变化后重新探测。

不采用“先旧后新”自动回退，避免把鉴权、部署缺包和 descriptor 错误误判为版本差异。

## 12. 错误与用户提示

| 层 | 示例 | 行为 |
| --- | --- | --- |
| auth/http | 401、403、TLS、代理、origin 拒绝 | 按网络策略重试或停止，提示连接/凭据问题 |
| carrier | socket 关闭、timeout、用户 cancel | generation 重连；用户 cancel 不弹错误 |
| protocol | 非法 frame、id 不匹配、首帧非 baseline/ready | 撤销 generation，提示 Runtime/插件不兼容 |
| Remote/domain | `session/not-found`、`settings/conflict` | 按 code 做领域恢复或显示服务端消息 |

规则：

- 使用字符串 code，不依赖 `instanceof` 穿越 wire；
- not-found 返回选择器，conflict 刷新 revision，agent-busy 保留用户提交；
- `gateway/arguments-invalid`、`gateway/result-invalid` 视为契约不匹配；
- 未知 namespaced code 显示 message，日志只记录脱敏 details；
- diagnostics 区分 retryable carrier 与 terminal protocol/domain failure。

## 13. 实施顺序与提交边界

建议按以下可审查提交推进，每个提交保持 `compileJava` 通过：

1. `refactor(remote): add rc contracts and unary carrier`
   - contract、unary、错误分层；暂不切 UI。
2. `refactor(remote): add multiplexed stream generations`
   - mux、`$events ready`、取消/终止、认证和重连。
3. `refactor(state): migrate workspace and session control`
   - opening baseline、catalog 原子提交、Project Service。
4. `refactor(state): migrate session follow and paging`
   - address、snapshot/cursor/page、record normalization、引用计数。
5. `feat(remote): migrate prompts and interactive events`
   - requestId、approval/question、`$events/result`、commands/subagents。
6. `feat(remote): migrate settings presets goals llm and skills`
   - 剩余领域 Remote 与 capability 文案。
7. `refactor(ui): consume remote domain snapshots`
   - Tool Window/Trace 删除 polling 与 wire store，保留 projector/JCEF。
8. `refactor(remote): remove legacy apiproxy protocol`
   - 删除旧 client/mux/respond、旧 endpoint 字符串，补诊断和本地化。
9. `release: validate dsh 0.1.2-rc.1 integration`
   - 更新默认 Runtime/README、执行 smoke、准备发布说明。

临时 bridge 只能位于 `DshRemoteService` facade 内，并在同一批次末删除；不得让 UI、
projector 或 store 同时理解两代 wire 类型。

## 14. 验收方案

遵守“不新增单元测试”规则，使用现有 Gradle 检查、真实 Runtime smoke 和故障注入。

### 静态与构建检查

```bash
./gradlew clean compileJava buildPlugin verifyPlugin

rg -n 'events\.mux|events\.host|/api/respond|session\.history|workspace\.list|host\.describe' \
  src/main/java src/main/resources
```

要求：

- `compileJava`、`buildPlugin`、Plugin Verifier 通过；
- 上述旧协议字符串不出现在生产代码；
- `/api/` 只出现在 Remote carrier 和 Runtime 健康/启动模块；
- contract 文件注明 `dsh-v0.1.2-rc.1` 与目标 commit；
- IntelliJ EDT 上不存在阻塞 HTTP、stream opening 或 pagination；
- Project dispose 后没有活动 socket、logical stream 或 executor task。

### 真实 Runtime smoke

1. 托管 Runtime 首次下载、启动、token 鉴权和 Web UI 打开；
2. 附加 localhost Runtime、错误 token、端口/origin 变化；
3. session list/search/create/rename/fork；
4. prompt queue/steer/cancel，重试不产生重复 user message；
5. 历史首屏、多页上翻、流式 assistant chunk 与会话切换；
6. workspace create/rename/order/session order/archive/delete；
7. model、preset、goal、command、skill、settings、credential；
8. continuable/one-shot subagent 列表、预览、历史、prompt、interrupt；
9. approval/question 的 result、next、取消和重复点击保护；
10. Trace 打开/关闭与 Tool Window 重建不泄漏 address subscription；
11. Runtime restart/WebSocket 断开后恢复，无重复消息、旧 queue/job 或 workspace 回滚；
12. 同时打开两个 IntelliJ Project 时，共享 Runtime 但各自正确管理本地 UI 生命周期。

### 故障注入

- endpoint 404、`gateway/arguments-invalid`、未知 RemoteError；
- logical stream 在 baseline 前 end/error；
- 非法 JSON、超大 frame、未知 streamId、重复 terminal；
- page `hasMore` 但 beforeSeq 不前进；
- 旧 generation unary/stream 结果延迟到新 generation；
- `$events cancel` 与用户提交 interaction outcome 竞态；
- Tool Window dispose 与 socket callback/EDT callback 竞态。

### 完成定义

只有同时满足以下条件才算完成：

- 默认 `0.1.2-rc.1` Runtime 的全部现有 UI 能力走 RC Remote；
- 启动、重连、history、workspace、control 和 interaction 有明确 generation/baseline 语义；
- 生产代码不再依赖旧点号 endpoint、`events.mux` 或 `/api/respond`；
- JCEF 只接收领域快照，不接收 Remote wire frame；
- 未知 Runtime/RemoteError 安全失败并给出可操作诊断；
- 构建检查和完整真实 Runtime smoke 通过；
- 实现与文档在同一 PR 中标注目标 tag/commit。

## 15. 主要风险与控制

| 风险 | 控制措施 |
| --- | --- |
| DTO 直接摊平进 `args`，导致全量 `arguments-invalid` | contract 按 Host 方法参数名建模，并保留 wire 示例 |
| HttpClient callback 与 EDT/轮询并发污染状态 | 单线程 connection executor + generation token + immutable snapshot |
| Tool Window 生命周期误当连接生命周期 | Remote 是 Project Service；UI 只持 listener/subscription handle |
| 重连后旧异步结果覆盖新 baseline | generation-scoped cancel + reducer 提交前 token 校验 |
| 所有 session 常驻 follow，logical stream 膨胀 | address 引用计数，只跟随当前 chat/trace/subagent |
| optimistic prompt 重复 | requestId 保持到 queue/durable history 对账 |
| interaction 回答串代 | `$events/result` 强制同 generation clientId/eventId |
| Gson 宽松解析吞掉协议漂移 | carrier/contract 严格校验 envelope/frame；领域扩展字段允许保留 |
| Project dispose 后 callback 操作 JCEF | disposed guard + unsubscribe handle + EDT callback generation guard |
| 直接升级默认 Runtime 导致现有用户全量失效 | 版本更新放在最后提交，先完成真实 Runtime smoke |
| 为旧版兼容引入双状态机 | 当前 fail-fast；需要时另建完整 Legacy adapter |

## 16. 后续 Runtime 升级门禁

每次修改默认 `runtimeVersion` 前必须：

1. 对上一个支持 tag 与目标 tag 做 endpoint、descriptor、DTO、stream protocol 和 Remote
   event allowlist diff；
2. 核对 `session/follow|page|control`、`workspace/follow`、`$events` 与 RemoteError；
3. 更新 `DshRemoteContracts.java` 的目标 tag/commit；
4. 重跑第 14 节 smoke checklist；
5. 更新 README、设置 tooltip 与发布说明；
6. alpha/master 版本不得沿用“RC 大概率兼容”的假设。

该门禁把协议升级从运行时猜测变为发布时验证，避免再次出现“Runtime 能启动、HTTP
健康检查通过，但 IDE 的 RPC 与状态同步实际不可用”的假完成状态。
