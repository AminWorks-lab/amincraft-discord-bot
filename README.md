# Amincraft Discord 机器人

Amincraft Minecraft 服务器的 Discord 机器人。它作为 Paper 1.21.11 插件（Java 21）运行在游戏服务器里，直接连接 Discord，不需要另外的机器人主机。当前版本 1.2.1。

只在 Amincraft 自己的 Discord 服务器里使用。

## 功能
- 远程执行服务器指令（管理员），查看在线玩家。
- 发布公告：可以发到文字频道或论坛，可以提醒订阅的人；也可以在服务器控制台用草稿文件发帖、改帖（见下面的控制台指令）。
- 身份组和订阅面板：玩家点按钮领取游戏相关的身份组、订阅通知。
- 通知组 @：管理员能 @ 面板上任何身份组 / 通知组；一般成员只能 @ 管理员开放的那几个（`ping.public`，例如「缺人找我」），有冷却和字数限制，消息后面会写上是谁呼叫的，不会带出 @everyone 或别的 @。
- 收集反馈：只能在设置好的反馈频道（bug 汇报、建议）里收集消息，整理成汇总发到管理员的私密子区；服务器上不保留副本。
- 隐私：成员可以用 `/minmin 隐私 退出` 选择不被收集、不被转发到游戏里。
- 服务器密钥：学习版玩家在 Discord 领取和账号绑定的服务器登录密钥；正版玩家可以把正版账号关联到同一个 Discord。
- 类脑市地块申请：新申请发到管理频道，管理员点按钮同意 / 驳回（可填原因），结果同步回游戏；绑定了游戏号的成员可以 `/minmin 申请地块`、`/minmin 加入申请`、`/minmin 我的申请`；共同开发者的权限变动用私信按钮确认（私信发不出去就改成游戏内确认）。
- 每日对局回放：把前一天的回放包发到论坛的回放贴。
- 对局精彩回放（可选，默认关闭）：五杀、团灭、翻盘、ACE 等精彩片段发一张卡片到指定频道；不 @ 任何人，退出隐私的玩家名字换成「某位玩家」。
- 游戏内一键反馈（可选，默认关闭）：玩家在游戏里 `/反馈` 的内容和截图发到指定频道，不 @ 任何人。
- 定时广告：在某个频道 / 子区按时间发服务器介绍卡片，只有 `promo.allowed-users` 里的人能设置。
- 开服/关服通知、在线人数状态。
- 聊天互通（可选，默认关闭）：游戏公屏 → Discord 可以「只发不读」，不需要 Message Content Intent；Discord 里的人用 `/minmin 说` 把话发进游戏。对局里的聊天不会发出去。
- 对局聊天（可选，默认关闭）：一局小游戏开始时开 Discord 子区，和游戏里的房间聊天互相转发。

## Discord 指令（`/minmin …`）
所有人可用：
- `状态`：服务器状态和在线玩家。
- `隐私 退出 | 恢复 | 查看`：选择是否让机器人收集你的消息、转发你的话。
- `说 <内容>`：在聊天互通频道里把一句话发到游戏公屏（最多 200 字，退出隐私的人不能用）。
- `通知 <通知组> [内容] [频道]`：@ 一个通知组。一般成员只能 @ `ping.public` 里开放的项目，只能发在自己所在的频道。
- `领取密钥`、`关联正版 <正版名字>`、`网页登录码`（`web-login.enabled` 打开时才有）。
- `申请地块`、`加入申请`、`我的申请`。

管理员：
- `公告`、`身份面板`、`通知组 添加 | 删除 | 列表`、`定时广告 设置 | 预览 | 关闭 | 列表`。
- `执行`、`封禁`、`解封`、`踢出`、`封禁列表`、`查询`、`收集`。

## 需要的 Discord 权限
- **Message Content Intent**：收集反馈（读取反馈帖的文字）要用；对局聊天和 `bridge.read-messages: true` 也要用。聊天互通只发不读（`bridge.read-messages: false`）时不需要。
- 不需要 Server Members Intent 和 Presence Intent。

## 隐私政策
见 [PRIVACY.md](PRIVACY.md)。

## 安装和设置
1. 把 `AminPlayDiscord.jar` 放进服务器的 `plugins/`，启动一次，生成 `plugins/AminPlayDiscord/config.yml`。
2. 在 `config.yml` 里填 `discord.token`。Token 只能手动填在配置文件里，不要发给任何人。
3. 其它设置都可以在服务器控制台用指令完成，不用改文件：
   - `/discordbot set <项目> <值>`：设置服务器 id、各个频道 id、回放论坛等，例如 `/discordbot set replays.forum-channel 123456789012345678`。不带值就是清空。
     - 频道：`channels.announce | status | bridge | log | collect | plots | feedback | highlights`。
     - 通知 @：`ping.public key1,key2`（填 `roles.panel` 的 key；留空 = 只有管理员能用）、`ping.user-cooldown-seconds`（同一个人两次 @ 的间隔，默认 600）、`ping.group-cooldown-seconds`（同一个项目两次被 @ 的间隔，默认 300）、`ping.max-length`（一般成员附带的话最多几个字，默认 200）。
     - 对局聊天：`match-chat.channel <文字频道id>`、`match-chat.games bedwars,murder`（留空 = 全部）。
   - `/discordbot admins list | add <身份组id> | remove <身份组id>`：额外允许使用管理指令的身份组。
   - `/discordbot sources list | add <频道id> | remove <频道id>`：可以收集的反馈频道 / 论坛（不设置就不能收集）。
   - `/discordbot panel …`：身份组和订阅面板。
   - `/discordbot status`、`/discordbot reload`、`/discordbot replays status`、`/discordbot keys`、`/discordbot plots`（地块申请状态；`plots post-open` 补发还没发的待审申请）。
   - 控制台发帖：草稿写在 `plugins/AminPlayDiscord/posts/<名字>.md`，`/discordbot posts` 列出草稿，`/discordbot post <频道|别名> <草稿|text:…> [--dry-run]` 发消息，`/discordbot forum <论坛|别名> <草稿> [--dry-run]` 开论坛帖，`/discordbot edit <消息链接> <草稿>` 改机器人发过的消息。
   - 地块申请的管理频道：`/discordbot set channels.plots <频道id>`（请用只有管理员能看的频道）。

仓库里不带任何服务器或频道 id，全部由使用者自己设置。

## 构建
需要 JDK 21，以及 Paper API 1.21.11 和它带的 Adventure、Gson、Guava、SnakeYAML 等 jar。把它们放在一个工具链目录里（`jdk/` 和 `deps/`），然后：

```bash
TC=/path/to/toolchain bash build.sh 1
```

构建产物是 `build-1/AminPlayDiscord.jar`，构建时会同时跑全部单元测试（每个测试都必须 `0 failed`）。

服务器密钥、类脑市地块申请、对局聊天、精彩回放和游戏内反馈需要配套的 MiniGameHub 插件；没有它时，这些功能会显示「不可用」或不启动，其它功能不受影响。

## 许可证
GPL-3.0，见 [LICENSE](LICENSE)。
