# DSH 0.1.7-rc.2 IntelliJ adaptation

Target source: `../dsh-ide/deepseek-harness` tag `dsh-v0.1.7-rc.2`, commit
`477b4f420553e8a52c2fbccc464d7561b239c443`. The companion extension's
`RPC_0.1.7_ADAPTATION.md` was used to identify changes, and the tagged Host
controller signatures were checked directly.

The default local Runtime and managed download target are `0.1.7-rc.2`. The
minimum compatible Runtime remains `0.1.5-rc.1`. Persisted explicit Runtime versions, including `0.1.5-rc.2`, are preserved;
missing or blank versions use the new default.

The IntelliJ host now consumes the RC.2 Workspace pin, unpin, unarchive, and
default-initialization RPCs. `workspace/follow` pins are retained in snapshot
order and shown before unpinned Sessions. Active Session archival offers the
RC.2 `stopActivity` path only after the Host rejects ordinary archival.

Account management calls the RC.2 metadata-bearing profile, balance, bonus,
browser sign-in, and sign-out RPCs. Client metadata contains the plugin version,
IDE locale, and local UTC offset. Sign-in is restricted to a localhost HTTP
Runtime callback origin; external account links require HTTPS or loopback HTTP.
The Host events for account expiry and account model sign-in requirements refresh
the model catalog and notify the user. A short-lived poll of `account/getState`
settles browser sign-in and initializes the default account model. The account
key remains separate from provider API keys.

Schedule management reads `schedule/list` for the selected Session and
`schedule/catalog` across Sessions. Current-Session reminders can be edited with
the full observed record as the `expected` compare-and-swap value, deleted, or
inspected through a bounded `schedule/history` page. The agent's
`schedule_create` tool remains the creation path; RC.2 has no Remote create
endpoint. `schedule/changed` refreshes the selected Session's Activity Dock.

The bundled Runtime helper adopts the companion extension's RC.2 Remote
contract parser and multipart unary decoder. The Java host's currently used
RPCs remain JSON-valued. The optional `dsh-jev-integration` package is bundled
from companion submodule commit `795907cbdf3347f97b27c473f4f6194f5877a74d`
and mounted only into Runtimes started by this plugin. Its System One features
are disabled by default; the API key is held in IntelliJ Password Safe.

Validation: `npm run build` for the bundled helper, Gradle `check` and
`buildPlugin`, direct Node import of the packaged Jev entry, a `dsh
0.1.7-rc.2 web --patch ... --dump-config` composition using an isolated
temporary `DSH_HOME`, and `git diff
--check`. No unit tests were added. No live 0.1.7-rc.2 Runtime account, Schedule,
or Jev end-to-end session was available for this adaptation.
