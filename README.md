# MatchCoreArena-VelocityGate

Velocity 代理插件：把"已成局的玩家"送进对应的后端游戏服。玩家连上代理后，
插件按 UUID 查一次 MatchCoreArena 的成局分配（OPS 只读端点
`GET /api/matchmaking/assignment/{uuid}`，契约 `matchcorearena-business.yaml`
1.10.0），查到有效分配且能映射到已注册服务器就转过去，其余一律留在大厅。

**客户端零改动**：原版客户端、不装任何模组即可走通。后端游戏服只对代理可达，
不暴露公网。

**房间层分工（契约 1.10.0）**：本插件只消费 `join_target`（把玩家送到绑定
的子服）；分配响应里的 `room` 由子服上的 BedwarsRel-MCA 自行向 MCA 复核并
入房（BedwarsRel-MCA knowledge/0004），代理不中转房间名。

**分配语义三形态（契约 1.10.0）**：

| 响应形态 | 行为 |
|---|---|
| `assigned:true` 且带 `join_target` | 转向映射表命中的子服 |
| `assigned:false`（无 reason） | 静默留大厅。这是正常态：未绑定用户、无成局、已结算、过 TTL 都走这里，不是错误 |
| `assigned:false` + `reason:no_room_available` | 留大厅，并给玩家一句可读原因（成局有效但房间预占过期/清单变更），请玩家重新匹配 |

注意区分两种"没被送走"：未绑定/未知用户是 200 `assigned:false` 的正常态；
HRPAuth 不可达是 502 `oauth_hrpauth_down` 的基础设施故障（见下方错误分类）。

## 当前状态（如实描述）

- **可构建**：`mvn -B package` 产出 `target/velocitygate-1.0.0.jar`
  （Java 17 目标，velocity-api 3.4.0 provided）。
- **jar 可加载**：含注解处理器生成的 `velocity-plugin.json`
  （id `velocitygate`，主类 `io.github.corematch.velocitygate.VelocityGatePlugin`），
  放入 Velocity `plugins/` 目录即被识别。
- **测试覆盖（30 个，全绿）**：
  - 路由路径（`GateRouterTest`，12 例）：查到→转向；查不到→留大厅；
    no_room_available→留大厅并向玩家发送可读原因；未知 reason→留大厅+
    通用提示；查询失败→留大厅（含 HRPAuth 不可达分类日志断言）；
    连接失败→留大厅并提示；另覆盖目标未映射、目标未注册、成局无地址、
    会话内只查一次、非大厅跳过。
  - MCA 客户端（`HttpMcaClientTest`，14 例）：200 有效分配 / 200 无分配 /
    200 带 no_room_available / 无 join_target；404 显式失败（不再视为
    无分配）；502+oauth_hrpauth_down 分类为 HRP_AUTH_DOWN；502 其他码与
    坏体按 HTTP_ERROR；403 分类为 AUTH_REJECTED；超时分类为 TIMEOUT；
    拒连分类为 UNREACHABLE；坏信封分类为 BAD_RESPONSE；非法 UUID 不发网络。
  - 配置解析（`GateConfigTest`，4 例）：必填项、映射归一化（host 大小写不敏感）、
    非法条目拒绝、超时默认值。
- **真实 Velocity 联调（本机，2026-11-11 复跑，jar 为当前构建）**：在真实
  Velocity 3.4.0（build 566，sha256 与仓内记录一致）上用 `integration/` 探针
  验证了四个场景——protocol 47 客户端探针连代理 → 落大厅（假后端）→ 插件按
  UUID 查桩 MCA（真实 HTTP）：
  1. `assigned:false` → 留大厅（INFO 审计日志）；
  2. `assigned:true` + `join_target` 命中映射 → 真实转向第二台假后端
     （minigames LOGIN OK），转入后的第二次 ServerPostConnect 被会话去重跳过；
  3. `assigned:false` + `reason:no_room_available` → 留大厅，日志记录原因
     （玩家提示经 sendMessage 发出，协议探针不解析聊天包，未在探针侧回读）；
  4. 桩 MCA 不启动（连接拒绝）→ 留大厅，日志出现分类 `[UNREACHABLE]`
     （此轮联调还抓到并修复了一个真 bug：分类异常携带内层 cause 时曾被
     过度解包成 `[UNEXPECTED]`，已修并补回归测试）。
  尚未验证的边界：真实人类 Minecraft 客户端（含 1.20.2+ 配置阶段）与生产
  MCA 实例。首次上机仍需人工观察日志。
- **全链 E2E（本机临时环境，2026-11-12）**：真实 MCA-Server（Go + MariaDB
  + Redis 容器，HRPAuth 按公开端点契约 mock）+ 真实 Velocity 3.4.0 + 真实
  CraftBukkit 1.8.8 子服（BedwarsRel-MCA 当前构建）。两名 protocol-47 机器
  人排队成局（房间层预占 arena-e2e）→ 连代理 → 本插件查真实 MCA 后分别按
  A/B 队转向子服（日志「已转入目标服 bwsub」）→ 子服入房开局 → 一方离场
  触发结算上报 → MCA 消费 `matchmaking_group_id` 释放预占
  （`rooms_released_settled_total=1`），分配查询变为 `assigned:false`。
  用后环境已全部清理。
- 依赖的服务端能力（MCA main `26527ba` 起、契约 1.9.1）已合并并部署生产：
  `GET /api/matchmaking/assignment/{uuid}`（仅 OPS 凭据；未知 UUID 回
  `assigned:false`；`matches.matchmaking_group_id` 关联列由迁移 v3 自动补）。

## 行为与失败路径

| 场景 | 行为 |
|---|---|
| 有效分配 + 映射命中 + 目标服可达 | 转向目标服 |
| 无分配 / 未绑定用户 / 已结算 / 已过 TTL（默认 1800s） | 静默留大厅（正常态，INFO 日志） |
| 成局有效但无可用房间（`reason: no_room_available`，房间层 1.10.0） | 留大厅，向玩家发送可读原因，日志记录原因 |
| MCA 不可达 / 超时 / 响应异常 / 凭据被拒 | 静默留大厅，**不阻断登录**，日志按失败分类告警（见下表） |
| 成局有效但服务端未配置 `matchmaking.join_target` | 留大厅，提示"匹配成功，但未提供游戏服务器地址，请联系管理员。" |
| `join_target` 未在 `servers[]` 映射表登记 | 留大厅，提示"匹配成功，但代理未配置对应的游戏服务器，请联系管理员。" |
| 映射到的服务器未在 Velocity 注册 | 留大厅，提示"无法进入对局：目标服务器未就绪，请稍后重试。" |
| 连接目标服失败（宕机/人满等） | 留在大厅，提示"进入对局服务器失败，已留在大厅，请稍后重试。"，日志带失败状态/原因 |

所有失败路径的处置都是"留在大厅"，玩家永远不会卡在"既不在大厅也不在子服"
的中间态；断线清除会话标记，重连会重新查询并重试路由。

### 查询失败分类（日志中的 `[分类]`）

MCA 查询失败时日志形如 `MCA 分配查询失败 [TIMEOUT]（玩家 <uuid> 留在大厅）: ...`，
分类与排查方向一一对应：

| 分类 | 触发条件 | 排查方向 |
|---|---|---|
| `UNREACHABLE` | 连接被拒 / DNS 失败 | MCA 进程或网络 |
| `TIMEOUT` | 超过 `query_timeout_ms` | MCA 负载 / 网络延迟 |
| `AUTH_REJECTED` | 401/403 | `mca_token` 无效或不是 OPS 级服务令牌 |
| `NOT_FOUND` | 404 | 几乎必然是 `mca_base_url` 配错（服务端对未知 UUID 恒回 200，不会回 404） |
| `HRP_AUTH_DOWN` | 502 `oauth_hrpauth_down` | HRPAuth 服务本身（与"未绑定用户"严格区分：后者是 200 `assigned:false` 正常态） |
| `HTTP_ERROR` | 其余非 2xx | MCA 服务端故障 |
| `BAD_RESPONSE` | 200 但信封不可解析 | MCA 服务端响应异常 |

其他语义：

- 初始服务器固定为大厅（`lobby_server`），一切路由以大厅为基线。
- 每个会话只查一次；断线清除标记，重连会重新查询——对局未结算且未过 TTL
  时，掉线重连会被送回原游戏服。
- 配置文件缺失或 `mca_token` 仍是占位符时，插件整体降级为无操作
  （所有人留在大厅），首启会自动生成配置模板。

## 配置

`plugins/velocitygate/velocitygate.json`（首启自动生成模板）：

```json
{
  "mca_base_url": "https://arena.mcnb.dev",
  "mca_token": "<OPS 服务令牌>",
  "lobby_server": "lobby",
  "query_timeout_ms": 2500,
  "servers": [
    {"target": "10.0.0.5:25565", "server": "bw-game-1"}
  ]
}
```

- `mca_token`：MCA 侧 `tokens.yaml` 中的 OPS 服务令牌（如 `match-reporter`）
  或运维令牌；玩家令牌会被 403 拒绝（日志分类 `AUTH_REJECTED`）。
- `servers[].target` 必须与 MCA 下发的 `join_target` **逐字一致**（host
  大小写不敏感，端口必须精确相同）；未登记的地址不会被路由。映射放配置而非
  代码：增删后端服无需重新构建插件。房间层（1.10.0）下 `join_target` 取自
  MCA 静态清单（`matchmaking.servers`）中绑定子服的地址，因此映射表要与
  MCA 清单逐台对齐。
- **MCA 侧 `matchmaking.join_target` 当前保持为空**：成局记录不带地址，
  插件会把被分配的玩家留在大厅并给出上述提示。等真实游戏服就位后再配置，
  不要填假地址。

各键说明（无默认值的均为必填，配错/缺失插件整体降级为无操作并打 ERROR 日志）：

| 键 | 含义 |
|---|---|
| `mca_base_url` | MCA 服务地址（不带尾部斜杠的 scheme+host[:port]，如 `https://arena.mcnb.dev`）。配错会在日志中以 `NOT_FOUND` 分类显式暴露 |
| `mca_token` | OPS 级服务令牌，随每次查询以 `Authorization: Bearer` 发送。仍是占位符（以 `<` 开头）时插件保持禁用 |
| `lobby_server` | 大厅服在 Velocity `[servers]` 中的注册名。初始服务器固定为它，一切失败都退回这里；该名字未在 Velocity 注册时初始服务器不覆盖 |
| `query_timeout_ms` | 单次分配查询的超时（连接+读取），默认 2500。必须为正数 |
| `servers[].target` | MCA `join_target` 的 `host:port`（host 大小写不敏感，端口精确匹配） |
| `servers[].server` | 该目标对应的、已在 Velocity 注册的服务器名 |

## 构建

```
mvn -B package        # 产物 target/velocitygate-1.0.0.jar
mvn -B verify         # 构建 + 全部测试（CI 同款命令）
```

## 部署

- **产物**：`target/velocitygate-1.0.0.jar`（Java 17 字节码，自包含，无第三方
  运行期依赖——Gson 与 adventure 由 Velocity 运行期提供）。
- **安装**：把 jar 放进 Velocity 的 `plugins/` 目录。首次启动时插件在
  `plugins/velocitygate/` 下生成 `velocitygate.json` 配置模板并禁用自身；
  填好 `mca_token` 与 `servers` 映射后**重启代理**生效。
- **依赖版本**：面向 Velocity 3.4.0 API（`velocity-api` provided）；3.3.x/3.4.x
  系列同 API 兼容，换大版本前先过一遍 `mvn -B verify`。
- **启动顺序**：无硬依赖。Velocity 可以先于或晚于 MCA 启动——查询只发生在
  玩家登录落大厅之后；那一刻 MCA 不可达只会让该玩家留在大厅（分类日志告警），
  不阻断登录，也不需要重启代理。建议顺序仍是 MCA → 子服 → Velocity。
- **升级**：替换 `plugins/velocitygate-*.jar` 后重启代理；配置结构不变则无需
  改 `velocitygate.json`。

## 本机联调（integration/）

`integration/` 内的探针对真实 Velocity 实例做端到端验证（假大厅/游戏后端 +
桩 MCA + protocol 47 客户端），步骤见 `integration/run_scenario.sh` 头部注释
（含场景 1/2/3 命令；场景 4 "MCA 不可达" = 不启动桩 MCA 直接跑）。
不属于 CI；需要自备 velocity.jar（PaperMC Fill v3 API 下载，v2 已 410）。
运行期产物（velocity.jar、velocity-run/、*.log、scenario.json）均已 gitignore。

## 没做的

- 真实 Minecraft 客户端 + 真实游戏服后端的联调（本机联调用的是协议探针与
  假后端，见上）。
- 生产部署本插件（尚无 Velocity 代理实例与游戏服可对接）。
- 多游戏服选择策略、按模式分流、排队队列展示等扩展：本插件只做
  "已分配 → 送达"，其余不属于当前范围。
- 远端未建 Release、未打 tag。
