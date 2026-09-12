# AGENTS.md

## 🚫 硬性禁令（用户明确要求）

- **禁止启动服务器/客户端做测试**：不要跑 `./gradlew runServer` / `runClient`，
  也不要起任何 Minecraft 进程。本项目位于用户自己的机器上，起服务会占用资源、污染运行目录、
  干扰用户正在进行的实机测试。
- 因此**运行时行为一律由用户实机验证**，AI 侧只做到：`build`（编译 + spotless）、
  `test -PrunTests`、静态自检（grep），以及把可测的纯逻辑抽出来加测试。
- 反过来，这条禁令也意味着「易错的运行时逻辑」更应该被**抽成纯类并测试**，
  而不是依赖"起服务看一眼"。已有的例子：`ReportValidator`、`SubmitGate`、`TicketPage`、
  `NoticeCardLayout`、`TicketId`、`SkinUrl`、`StartupSmokeTest`。

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/bananaxiao/Documents/MirageV/mod/CCNR-Com/.gradle-home   # 共享 2GB 缓存（勿提交）

./gradlew build              # -Xlint:all + spotlessCheck 门禁，产出 build/libs/ccnr_pm-*.jar
./gradlew spotlessApply      # 唯一格式化入口（提交前必跑）
./gradlew test -PrunTests    # 单元测试（不加 -PrunTests 会被跳过！）
./gradlew compileJava        # 只验语法，最快
./gradlew runServer / runClient
```

## 版本号（规范全文见 docs/04）

- 格式 `<主版本>.<功能批次>.<修订号>`；**唯一真源**是 `gradle.properties` 的 `mod_version`。
- 交付一次改动 → 判断是「批次」还是「修订」→ bump → `CHANGELOG.md` 顶部补条目 → README「当前版本」同步。
- `mods.toml` 用 `${file.jarVersion}` 注入，**不要**手写第二个版本号。
- 门禁：`VersionConsistencyTest` 会拦住三处不同步；版本号**不参与任何逻辑判断**。

## Architecture

- `com.ccnrcom.pm` 入口 `CCNRPMMod`；子包按系统划分（见 docs/00 §3）。
  **纯类（无 MC import）** 集中在 `ticket` / `data` / `client.ui`（`PmTextLayout`、`PmTextBuffer`、
  `PmPopupLayout`）/ `client.hud`（`NoticeCardLayout`、`BoardAvatarLayout`）/ `integration.ProfessionRef`，可直接 JUnit 测。
- **无外部模组依赖**：不引用 CCNR-RP / CCNR-Com 的任何类。与 CCNR-Com 的唯一耦合是
  在 `RegisterCommandsEvent` 里**后置覆盖**它的 `/a` 与 `/admin` 命令；
  与 CCNR-RP 的耦合一律**反射 + 降级**（`client.RpPanel`、`integration.RpBridge`）。
- 存储分层：serverconfig toml = 调参；`config/ccnr_pm/report_categories.json` = 管理员可编辑定义；
  `config/ccnr_pm/db.properties` = 数据库开关；`world/ccnr_pm/tickets.json` 或数据库 = 运行时工单。
- 网络通道 `ccnr_pm:main`（SimpleChannel，version=1），12 个包（见 docs/00 §7）。
  **注册顺序即协议编号，新增只能追加在末尾。** 动作 id 这类字符串协议两端共用 `PmPackets` 的常量。
- 存储后端选择只看 `Database.usable()`（= 配置启用 **且** 真的连上），不要用 `enabled()`。

## 开发纪律（完整版见 docs/01）

- **先读后写、修根因**：按「现象→调用链→根因→影响面→最小改动→实现→验证」推进。
- **服务端权威**：客户端只发意图；服务端重新校验类别/内容长度/关联玩家数量与名字格式/冷却/并发。
  关联玩家是**选填**，但**要校验合法性**：**在线 或 本服见过面**（`player.KnownPlayers`）才算数，
  否则指名回绝。判据刻意不是「必须在线」——人刚下线/改名恰恰最该能举报（见 docs/02 §4）。
- **校验只有一份**：`ReportValidator` 被客户端与服务端共用，两边各写一份必然漂移。
- **线程边界**：`ccnr-pm-db-writer` 线程只跑 JDBC，绝不触碰 Level/NBT/网络/渲染。
- **对称清理**：`ServerStoppingEvent → TicketService.shutdown()` 排空写队列 + 断开连接。
- **界面**：颜色只取自 `PmTheme`（禁止裸 `0x` 色值）；同类构件只走共享入口；亮底必须用 `ACCENT_TEXT`；
  文案按像素逐字符断行；弹层打开时吞点击、Esc 只关弹层。
- **输入控件的字符规则**：过滤/搜索文字（如关联玩家的过滤词）**只挡控制字符**，任何可见字符都收。
  把过滤文字限成业务字段的字符集（例如玩家名字符 `[A-Za-z0-9_]`）会让中文输入法下的玩家
  看到「框点得亮却打不进字」——0.11.0 真踩过，0.12.0 回退。「只收名单里的」是**提交值**的规则，
  与**过滤文字**无关。
- **`charTyped` 只发给获得焦点的控件**（`ContainerEventHandler` 的默认实现就是
  `getFocused().charTyped(...)`，`Screen` 没有重写它）。所以：①任何输入控件都要自查 `isFocused()`；
  ②挂在输入框上的候选弹层要绑定焦点（失焦即消失），否则会变成「一片候选吞掉所有点击」；
  ③`init()` 会因窗口缩放/资源重载被重新调用，**焦点必须搬到重建后的控件上**，
  留在旧实例上的症状与「打字没反应」完全一样。找不到原因时看
  `[CCNR-PM] 举报面板收到了字符但没有任何输入框接收它` 这行 WARN（会报当前焦点类名）。
- **状态文字必须上色**：`PmTheme.statusColor`（聊天里是 `PmCommand.statusFormat`）不是装饰，
  任何显示状态文字的地方都要用；亮底例外——那处不给文字上色，亮底按钮改用同色描边。
- **质量**：无占位/空壳/伪异步逻辑；未验证的必须明说；提交前查 `git diff`。

## Gotchas

- **构建环境**：需 JDK 21（系统默认 Zulu 26，务必 export JAVA_HOME），且 `GRADLE_USER_HOME` 必须指向共享缓存。
- **测试门控**：`build` **不跑测试**。验收必须显式 `-PrunTests`。
- **产物分两种**：
  - `ccnr_pm-<版本>.jar` —— **不含** SQLite 驱动（无 `META-INF/jarjar/`）
  - `ccnr_pm-<版本>-all.jar` —— **含**内嵌 SQLite 驱动，要用数据库就发这个
  只发普通 jar 时 `db.enabled=true` 会因找不到驱动而连接失败 → 本模组会**安全降级**为文件存储并记日志，
  不会丢数据，但也没有数据库。（对照：CCNR-RP 的 README 写的是发布普通 jar，那会导致它的数据库后端不可用。）
- **`/a` 接管依赖事件优先级**：我们用 `EventPriority.LOWEST`、CCNR-Com 用默认 `NORMAL`，
  靠「NORMAL 先于 LOWEST」保证覆盖成功。若未来有模组也用 `LOWEST` 注册 `/a`，接管会失效——
  `/pm status` 与启动日志（`[CCNR-PM] 已接管 /a 与 /admin` / `未发现 /a 命令`）可用于确认。
- **`/admin` 必须一并重指**：它是 CCNR-Com 对 `/a` 的 Brigadier redirect，redirect 目标在 `build()` 时
  就固定了，不重指会绕过接管。
- **权限判据必须与对方一致**：`canAdminChat` = `OP≥2` 或权限节点 `ccnrcom.admin.chat`。
  该节点通过 `PermissionAPI.getRegisteredNodes()` **按名字运行时解析**（Forge 没有按字符串查询的重载），
  否则用权限插件授权但没有 OP 的管理员会被误导向举报面板。
- **头像必须在玩家在线时抓，且每个玩家登录都要抓**：皮肤 URL 只存在于在线 `ServerPlayer` 的
  `GameProfile.textures` 里，玩家一下线就**再也抓不到**。曾经为了省一次 HTTP 只抓管理员，
  结果管理员看板上的其他玩家全变默认皮肤（重启后尤其明显）。抓取回调完成后要把看板
  广播给**所有**管理员，而不只是触发者。
- **头像抓取只在服务端做**：离线服务器的客户端解析不出真实头像，所以服务端从 `GameProfile.textures`
  取 URL、异步下载、转 base64 缓存（`AvatarService`）。HTTP 在 `ccnr-pm-avatar` 线程上，
  **只能碰 `PlayerRef` 快照**，发包必须回主线程（`server.execute`）。
  皮肤 URL 必须过 `SkinUrl.isHttpUrl`——URL 来自外部数据，`file:` 会让服务端去读本机文件。
- **HUD 浮层无法点击**：无界面时鼠标被相机抓取、没有光标坐标，所以「认领/关闭/释放」与头像上的
  玩家动作只能放在 Screen 里（`PmAdminScreen` / `PmBoardScreen`）。通知卡片只负责「抬头看一眼」。
- **HUD 浮层会连 Screen 一起渲染**：原版把 HUD 画在最底层，`IGuiOverlay` 并不会因为「打开了界面」
  就跳过——所以 `PmBoardScreen` 打开时必须**主动**跳过只读浮层的绘制，否则同一个看板画两遍＝重影。
- **与 CCNR-RP 的软依赖只能反射 + 降级**：`integration.RpBridge` 按类名/方法名调用对方的
  **唯一入口**（`SpawnFramework.deploy` / `StatusManager.retire`），绝不把对方流程抄一份进来
  （对方一改就漂移，症状是「刷出来的人少一件装备」）。对方没装、签名变了、初始化未完成时
  一律退回原版语义（刷出＝冒险模式，送回阴间＝旁观模式），绝不影响本模组自身功能。
- **离线服的头像**：`textures` 属性只有正版验证/皮肤插件才会填；纯离线服上服务端拿不到，
  于是按玩家名回查 Mojang（`avatar.mojangLookup`）。**别把「拿不到头像」直接当成抓取坏了**——
  先跑 `/pm status` 看「头像缓存 / 抓不到 / Mojang 反查」三行。
- **关联玩家合法性靠名录，不靠头像缓存**：`AvatarService` 只装「抓到皮肤的人」且按容量淘汰，
  拿它当「谁是本服玩家」的判据会得出「有皮肤的人才算合法」这种荒谬结论（见 `KnownPlayers` 的类注释）。
- **PM 面板刷人不播入场演出**：`forceDeploy` 带 `SKIP_CINEMATIC` + `NO_MUSIC`，
  对应「强制跳过开场黑屏 / 左下角电影 HUD / 场景动画」。这是**每次部署的 flag**，
  不是改对方配置——玩家自己/波次/招募的演出必须保持原样。
- **管理面板新增页签**：在 `PmAdminScreen.TABS` 加 id + new 出 `PmTab` 实现 + 补
  `ccnr_pm.gui.panel.tab.<id>` 语言键，外壳不用改。页签注册 widget 必须走
  `addPanelWidget`（只有 Screen 能持有 widget 生命周期）。
- **面板客户端不自己改列表**：动作成功后等服务端回推权威快照；
  客户端自行把状态改成 claimed 会造成「本地以为办成了、服务端没办成」。
- **通知卡片是 HUD 浮层不是 Screen**：`registerAboveAll` 注册，只在没有打开任何界面时绘制。
  头像贴图是 GPU 资源，卡片清空/离开世界时必须 `release`（对称清理）。
- **改表结构必须同时补迁移阶梯**：`CREATE TABLE IF NOT EXISTS` 对**已存在**的表什么都不做，
  所以列结构变化不会自动生效。v2 的 `seq INTEGER NOT NULL` 就是实例：若不迁移，
  v3 的 INSERT 不再写该列，老库会在**玩家提交工单那一刻**因 NOT NULL 失败（而不是启动时，更难发现）。
  阶梯在 `DbSchema.migrate()`，由 `JdbcTicketStoreTest` 用真实 SQLite 覆盖（造老库 → 连接 → 验证）。
  改列时删索引要小心：SQLite 不允许删除仍被索引引用的列。
- **语言包**：zh_cn/en_us 键集合必须一致，`LangFileTest` 会拒绝漏翻、空翻译、值等于键、`%s` 数量不一致。
- **不要改动 CCNR-RP / CCNR-Com / CC-api**：所有变更限本仓库。
