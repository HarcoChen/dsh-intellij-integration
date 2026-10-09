# dsh-ide 0.10.3 同步记录

IntelliJ 基线为 `5b001ac`，原 companion 同步点为
`deb8d8f882586c42a20abfbb5609cb0643fac309`（0.10.2）。本次对照已拉取的
companion `origin/main`：`7e45c2b19d8d7c724fda824a88e78b4d448cf05d`，包含
0.10.3 和随后合入的 Runtime 引号修复 #41。

## 差异与迁移

| 上游改动 | IntelliJ 处理 |
| --- | --- |
| Jev 子模块 `795907c` → `e5d74c5` | 更新整个已编译分发包、协议 schema 和提取清单；保留 dist-only 包的 schema / bundle patch 路径映射。 |
| token 优化计量、决策收益门槛、延迟预算、失败回退 | 随共享 Runtime 包迁移；协调器和语义回退沿用上游默认关闭。 |
| 确定性安全规则、凭据分类与统计、请求和失败信息脱敏 | 随共享 Runtime 包迁移，无额外 IDE 网络调用。 |
| VSIX 缺少 Jev 子模块及打包校验 | IntelliJ 使用提交在仓库中的分发包；新增 Gradle `verifyBundledJev`，检查处理后的必需资源、所有模块和提取清单，接入 PR CI 和插件打包。 |
| Windows shell 参数引号与反斜杠修复 | 在 helper 的统一进程入口适配 Java 传来的 `cmd.exe /d /c` 包装；转为 `/d /s /c` 单条命令并启用 `windowsVerbatimArguments`。 |

Jev pin 为 `e5d74c5d9153c5cac0d0373345afe6ce0622247c`。使用
`scripts/sync-jev.mjs` 从指定 commit 导出分发包，修正 dist-only metadata 并生成
`files.txt`，避免未来更新遗漏新增模块。

Windows 命令行只在已知的命令解释器包装处编码：每个参数分别加引号，按 companion
处理嵌入引号前和参数末尾的反斜杠。原生 exe 和 POSIX 继续使用原始参数数组。
适配发生在创建进程时，恢复 composition、patch 路径与进程所有权继续使用原始数据。

此次上游 `webview/src`、Remote 契约和 Runtime 默认版本没有变化。
Webview 重建 pin 保留 `deb8d8f`，已合入的 Markdown 渲染和主题对比度修复继续使用。
VS Code 专用的 VSIX 清单和递归子模块 checkout 没有直接复制到 IntelliJ 的发布流程。

## 验证

```sh
node scripts/sync-jev.mjs
npm run build --prefix runtime-helper
./gradlew lint buildPlugin
./gradlew verifyBundledJev
```

- helper 通过现有构建和 TypeScript strict / noEmit 检查。
- companion 的现有 Jev smoke 通过：挂载、capabilities、配置、token meter/pruner、
  收益门槛、失败回退、脱敏、Session 隔离、策略钩子、统计状态和 SSE。
- 所有分发模块和许可证与 Jev pin 字节一致；package metadata 仅保留上述路径映射。
- 实际插件 ZIP 内的 82 个 Jev 资源完整，内容与提交的分发包一致。
- 临时移除构建输出中的 `sanitize.js` 后，`verifyBundledJev` 正确失败；恢复后重验。
- Windows 参数编码检查覆盖空格、空参数、引号、引号前的反斜杠和末尾反斜杠。
  macOS 实际子进程收到的原生 argv 与原始数组一致。

没有创建或添加单元测试。Windows 原生进程执行、真实 IDE 安装后的交互，以及真实
Jev/Laya 服务调用仍需对应环境验收；本次没有调用真实模型或第三方服务。

## 待审查的进行中改动

审查结束时，相邻 companion checkout 出现了新的未提交修改，本次没有采用这些
进行中的源码。后续需要在确定 commit 后同步：

- `workingDirectory` 投影、会话目录展示、当前操作的目录解析，以及按历史事件序号
  解析文件链接和 Diff 的目录；`header.cwd` 继续定义会话身份和权限根。
- Session catalog 中的缓存投影与迁移状态处理。
- 当前文件的 Problems / diagnostics 附件入口，以及相应的 action 和候选类型。

这些变化涉及 Webview action 契约和 IntelliJ 路径解析，后续迁移需要一起修改 Java
投影、action 校验、原生文件导航与 Diff/context 控制器。
