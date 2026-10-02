<div align="center">

<img src="src/main/resources/icon.png" width="128" height="128" alt="CCNR-PM 图标">

# CCNR-PM 玩家管理模组（Forge 1.20.1）

CCNR 服务器**玩家管理模组**。第一个功能：**玩家举报工单** —— 玩家执行管理员消息命令
`/a <消息>` 时不再被回绝，而是直接弹出工单面板。

[![Release](https://img.shields.io/github/v/release/bananaxiao2333/CCNR-PM?label=Release&color=brightgreen)](https://github.com/bananaxiao2333/CCNR-PM/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/bananaxiao2333/CCNR-PM/build.yml?branch=main&label=Build)](https://github.com/bananaxiao2333/CCNR-PM/actions)
[![License](https://img.shields.io/github/license/bananaxiao2333/CCNR-PM?label=License)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-important)](https://www.minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.2.0%2B-orange)](https://files.minecraftforge.net)

</div>

**当前版本：`0.14.0`**（开发期；版本号规范见 [docs/04-版本号规范.md](docs/04-版本号规范.md)）

---

## 这个模组解决什么问题

玩家想说话、打错命令或者真的想举报，最常见的就是去用管理员的 `/a` 广播命令。
在那个瞬间他得到的是「无权限」——**一腔想说的话被丢掉了**。

本模组把这个「被拒绝的动作」接住，转成一条**结构化的举报工单**：

| | 传统做法 | CCNR-PM |
| --- | --- | --- |
| 玩家看到 | 无权限 / 未知命令 | 工单面板打开，内容已预填 |
| 想说的话 | 丢掉 | 变成工单的「举报内容」，可编辑 |
| 管理侧收到 | 什么都没有 | 带类别、关联对象、状态的工单（UUID 标识） |
| 可否追踪 | 不能 | `/pm ticket list` 检索，认领/办结/驳回 |

**核心设计：把一个无意义的失败，变成一条有用的数据。**

---

## 功能一览

- **`/a <消息>` → 工单面板**：非管理员玩家执行后直接弹出面板，「举报内容」预填那条消息。
  管理员照旧发送管理通讯（**行为完全不变**，见下文原理）。
- **`/report` 独立命令**：不必先打 `/pm`，直接开工单面板。
- **面板四要素**：举报内容（预填可改，**必填**）· 关联玩家（**胶囊选择、可多个、选填**）·
  举报类别（数据驱动，**必填**）· 详细描述（**多行长文本、选填**）。
  关联玩家是**胶囊输入**：输入前缀 → 候选 → 回车/点击变成一个整体（`( Alice × )`），
  **只能从在线名单里选**，未确认的文字不会被提交；服务端再判一次「在线 或 本服见过面」。
  过滤文字本身**什么字符都收**（中文输入法打进来的字照收，只挡控制字符）——把过滤文字也限成
  玩家名字符，会让中文玩家看到「框点得亮却打不进字」。
- **管理员双通道提醒**：聊天区一条持久消息 + 左上角 **160×90 横版（16:9）通知卡片**
  （标题、描述过长自动换行/截断，一排玩家头像，**提交者与关联者分栏区分**）。
- **服务端抓取头像**：面向第三方/离线服务器——离线 UUID 在 Mojang 查无此人，
  所以由服务端从 `GameProfile` 的 `textures` 属性取皮肤 URL、异步下载 PNG、转 base64 缓存
  （全局共享、**新覆盖旧**、落盘 `avatars.json`），随通知下发。
  **纯离线服**上服务端拿不到 textures 属性，因此会**按玩家名回查 Mojang**（`avatar.mojangLookup`，默认开）
  ——正版名也能有真实头像；`/pm status` 会直接给出头像缓存与「抓不到」的诊断。
- **工单带提交时间**：管理面板详情与 `/pm ticket show` 都显示 `yyyy-MM-dd HH:mm`（纯类 `TimeText` 统一格式）。
- **服务端权威**：客户端只发意图，服务端重新校验类别、长度、关联玩家数量、冷却、并发上限。
- **按住 P 的交互看板**：短按 P 出鼠标，左上角每张活跃工单多一排按钮
  （待处理＝认领/忽略，处理中＝**关闭/释放**）；右下角两个快捷按钮
  （打开工单管理面板 / 打开 CCNR-RP 管理面板）；长按 P 直接开工单管理面板。
- **状态一眼可辨**：待处理＝红、处理中＝亮、已办结＝灰蓝、已驳回＝灰。
  看板卡片右上角带色状态标签、管理面板列表与详情、卡片按钮文案、玩家面板、聊天输出全部按状态上色。
- **头像可点（仅 P 看板内）**：**左键**＝把该玩家传送过来；**右键**＝快捷菜单
  （点玩家名复制 `名字 (UUID)` / 把玩家传送过来 / 传送到该玩家 / 刷出玩家 / 刷出并传送至此 / 送回阴间）。
  装了 **CCNR-RP** 时「刷出玩家」弹出可滚动角色选择器、走对方的部署入口
  （**强制跳过开场黑屏 / 左下角电影 / 场景动画**，直接落位）；
  「送回阴间」走对方的退场清理（观察者 + 清背包 + 卸属性，不生成遗体）。
  没装时退回原版语义：刷出＝**冒险模式**，送回阴间＝**旁观模式**。
- **双存储后端**：默认 JSON 文件（原子写 + `.bak`），可切换 SQLite / MySQL。连接失败自动回退文件，不丢数据。
- **管理命令**：`/pm ticket list|show|claim|close|reject`，办结/驳回会私聊告知举报人。
- **管理面板是页签式管理终端**：按键 P（或 `/pm panel`）打开，含
  **工单管理**（列表/详情/认领/办结/驳回）、**对局管理**、**当局事件** 三个页签。
- **对局管理（装了 CCNR-RP 时）**：一屏看完成当局状态——是否运行中、当前模式、
  `第 n/m 幕 名称`、倒计时、正在跑哪些事件；左列幕表可「下一幕 / 切到此幕」，
  右列事件表可「触发 / 结束」，底部是「收束对局 / 重开一局 / 结束动画」。
  **数据经 CCNR-RP 反射取得，本模组不直连数据服务器**（理由见 [docs/05](docs/05-对局对接.md) §2）。
- **当局事件（装了 CCNR-RP 时）**：这一局里发生过什么——击杀、死亡、阶段推进、事件启停、结算……
  **最新的一行在最上面**，与 CCNR-RP 的 `/rp data events` 同源。
  数据服务掉线时这个页签照样有内容（事件流是对方的本地缓冲，可见性不依赖连通性）。
- **数据驱动的举报类别**：`config/ccnr_pm/report_categories.json`，`/pm reload` 热重载。
- **界面**：与 CCNR-RP 同一套「CCNR:NET 机密终端」视觉语言（黑白灰，仅告警用红）。
- **国际化**：中英双语，语言包键一致性有测试门禁。

---

## 安装

### 环境

- Minecraft **1.20.1**
- Forge **47.x**
- （可选）**CCNR-Com** —— 只有装了它才有 `/a` 命令可接管。未安装时举报面板仍可用 `/pm report` 打开。

### 步骤

1. 下载产物，放入 `mods/`：
   - **想要 SQLite/MySQL 后端** → 用 `ccnr_pm-<版本>-all.jar`（内含打包的 SQLite 驱动）
   - **只用默认 JSON 文件后端** → `ccnr_pm-<版本>.jar` 即可

   > ⚠️ 这是一个容易踩的坑：`-all.jar` 才是**内嵌了 SQLite 驱动**的产物（含 `META-INF/jarjar/`）。
   > 只放普通 jar 时，`db.enabled=true` 会因为找不到驱动而连接失败——本模组会**安全降级**为文件存储并在日志中说明，
   > 但不会有数据库。若确实要数据库，请用 `-all.jar`。

2. 启动一次服务器。会在 `config/ccnr_pm/` 生成 `report_categories.json`（举报类别）。
3. 需要数据库时，手动放置 `config/ccnr_pm/db.properties`：

   ```properties
   db.enabled=true
   db.mode=sqlite
   db.file=config/ccnr_pm/ccnr-pm.db
   ```

   改完重启。`/pm status` 可以确认实际生效的后端。

### 验证安装

```
/pm status
```

应显示存储后端、连通性、工单计数、类别数量，以及**管理通讯权限节点的解析结果**。

---

## 使用

### 玩家：举报

在聊天框输入（内容随便写，它会变成举报内容）：

```
/a 刚才 Bob 隔着墙把我矿挖了
```

面板自动弹出，「举报内容」已填好那句话。选好举报类别（必需），
需要的话在「关联玩家」里填上相关玩家（可多个、有补全，**选填**），再补详细描述，点「提交举报」。
成功后显示 `已创建工单 #a3f2c1d8`（UUID 短号），表单锁定。

不想用 `/a` 也可以（**推荐**，不依赖 CCNR-Com）：

```
/report
```

`/pm report` 是同一个入口，`/report` 是省掉前缀的独立命令。

### 管理员：收到提醒

工单创建时，有 `ccnrpm.admin.ticket` 权限（或 OP≥2）的在线管理员会同时收到：

1. **聊天区**：`[工单] #a3f2c1d8 Alice 举报 Bob（作弊 / 外挂）` —— 持久、可回看
2. **左上角卡片**：4:3 横版，约 12 秒后淡出，显示类别、举报内容（过长自动截断）、
   以及一排头像（**提交者**在左栏亮框、**关联玩家**在右栏暗框）

### 管理员：处理工单

```
/pm ticket list open          # 看待处理的
/pm ticket show 7             # 看详情
/pm ticket claim 7            # 认领
/pm ticket close 7 已警告并回档 # 办结（举报人会收到私聊）
/pm ticket reject 7 证据不足   # 驳回
```

新工单会私聊提醒在线管理员（可在 serverconfig 关闭）。

### 配置

`world/serverconfig/ccnr_pm-server.toml`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `report.maxContent` | 256 | 举报内容长度上限 |
| `report.maxDetail` | 512 | 详细描述长度上限（0 = 禁用该栏） |
| `report.cooldownSeconds` | 60 | 提交冷却 |
| `report.maxOpenPerPlayer` | 3 | 同时未办结工单上限 |
| `report.notifyAdmins` | true | 新工单提醒管理员（**聊天消息与 HUD 卡片一起开关**） |
| `avatar.enabled` | true | 是否抓取并缓存玩家头像 |
| `avatar.timeoutMs` | 3000 | 等待头像下载的上限；到点就用已有头像发卡片 |
| `admin.listPageSize` | 10 | 工单列表每页条数 |

头像缓存 `config/ccnr_pm/avatars.json`（服务端抓来的 base64 皮肤，自动生成，不必手改）。

> **离线服务器注意**：皮肤 URL 来自玩家 `GameProfile` 的 `textures` 属性，由**服务端**填充。
> 正版验证、或装了皮肤插件（SkinsRestorer 一类）/自建皮肤站时能拿到真实头像；
> **纯离线且无任何皮肤方案时服务端没有这个信息**，卡片会回退到客户端自己解析的皮肤。

举报类别 `config/ccnr_pm/report_categories.json`：内置六类（辱骂骚扰 / 作弊外挂 / 破坏盗窃 / 刷屏广告 / 违反 RP / 其他），
可增删改序、可用 `name` 字段写死显示名。改完 `/pm reload`。

---

## `/a` 是怎么被接管的（不改 CCNR-Com 一行代码）

CCNR-Com 用 Brigadier 的 `.requires(Permissions::canAdmin)` 注册 `/a`，普通玩家执行会被判为「命令不存在」。

本模组以 `EventPriority.LOWEST` 在 **CCNR-Com 之后**再注册一个同名 `/a`，
利用 Brigadier「同名子节点后注册者覆盖」的特性取而代之：

```
管理员   → 转交 CCNR-Com 原执行器  → 管理通讯照旧（广播范围/长度校验/提示全部不变）
普通玩家 → 打开工单面板，把消息预填成举报内容
```

判定「是不是管理员」时不引用对方的类，而是运行时从 `PermissionAPI.getRegisteredNodes()` 按名字解析
`ccnrcom.admin.chat` 节点后求值——**判据与 CCNR-Com 完全一致**，
因此用权限插件单独授权、没有 OP 的管理员不会被误导向举报面板。

别名 `/admin` 也一并处理（它的 redirect 目标在构建期就固定了，不重指会绕过接管）。

完整原理、边界与排查表见 [`docs/02-举报工单.md`](docs/02-举报工单.md)。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/00-总览.md](docs/00-总览.md) | 架构、包布局、数据流、模块边界 |
| [docs/01-工程规范.md](docs/01-工程规范.md) | 开发纪律、界面与弹层规范、提交前检查 |
| [docs/02-举报工单.md](docs/02-举报工单.md) | **举报工单功能设计**（原理/边界/存储/排查） |
| [docs/03-界面设计.md](docs/03-界面设计.md) | 主题令牌、共享绘制入口、面板布局、通知卡片 |
| [docs/04-版本号规范.md](docs/04-版本号规范.md) | **版本号怎么涨**、必须同步的三处、门禁 |
| [docs/05-对局对接.md](docs/05-对局对接.md) | **与 CCNR-RP 的对局对接**（数据从哪来/信任边界/降级/排查） |
| [AGENTS.md](AGENTS.md) | 给 AI/新人的构建命令与坑位清单 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更 |

---

## 构建

```bash
./gradlew build            # 语法(-Xlint:all) + 风格(spotlessCheck) 门禁，产出 build/libs/*.jar
./gradlew spotlessApply    # 唯一格式化入口（提交前必跑）
./gradlew test -PrunTests   # 单元测试（默认不跑，必须显式加 -PrunTests）
```

产物两种，按是否需要数据库选择：

| 产物 | 内容 | 用途 |
| --- | --- | --- |
| `build/libs/ccnr_pm-<版本>.jar` | 纯 mod | 默认 JSON 文件后端 |
| `build/libs/ccnr_pm-<版本>-all.jar` | **含内嵌 SQLite 驱动** | 要用 SQLite / MySQL 就发这个 |

## 持续集成

[`.github/workflows/build.yml`](.github/workflows/build.yml)：push / PR 到 `main` 时跑
`./gradlew build`（含 `spotlessCheck`）与 `./gradlew test -PrunTests`，并上传 jar 产物；
打 tag 时自动把 jar 附到 GitHub Release。**门禁不过就不合并**——版本号漂移（`VersionConsistencyTest`）、
语言包漏翻（`LangFileTest`）、接线缺失（`EventWiringTest`）都由 CI 拦。

## 技术栈

| 项 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.2.0+ |
| Java | 17 字节码 / JDK 21 构建 |
| Gradle | 8.5 |
| SQLite 驱动 | 内嵌于 `-all.jar`（`META-INF/jarjar/`） |

无外部模组硬依赖：CCNR-Com 为可选（提供 `/a` 命令），CCNR-RP 为可选（角色选择与刷出联动，
一律反射 + 降级）。

---

## 开发环境

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/bananaxiao/Documents/MirageV/mod/CCNR-Com/.gradle-home   # 共享缓存

./gradlew runServer    # 开发环境服务端
./gradlew runClient    # 开发环境客户端
```

构建命令与产物见下方[构建](#构建)。

---

## 与其它 CCNR 模组的关系

| 模组 | 关系 |
| --- | --- |
| **CCNR-PM**（本仓库） | 独立的玩家管理模组 |
| CCNR-RP | **软依赖（反射 + 降级，无编译期依赖）**。装了就能在管理面板里刷人/送回阴间、并多出「对局管理」与「当局事件」两个页签；没装则退回原版语义、页签显示「未安装 CCNR-RP」。代码不互相引用，见 [docs/05](docs/05-对局对接.md) |
| CCNR-Com | **可选依赖**（`mandatory=false`, `ordering=AFTER`）。它提供 `/a` 命令；未安装时本模组不创建该命令，只保留 `/pm report` |

三个仓库各自独立，本模组的任何改动都不需要改另外两个。

## License

MIT —— 见 [LICENSE](LICENSE)。
