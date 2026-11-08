# MatchCoreArena-VelocityGate

Velocity 代理插件：把"已成局的玩家"送进对应的后端游戏服。玩家连上代理后，
插件按 UUID 查一次 MatchCoreArena 的成局分配（OPS 只读端点
`GET /api/matchmaking/assignment/{uuid}`，契约 `matchcorearena-business.yaml`
1.9.1），查到有效分配且能映射到已注册服务器就转过去，其余一律留在大厅。

**客户端零改动**：原版客户端、不装任何模组即可走通。后端游戏服只对代理可达，
不暴露公网。

## 当前状态（如实描述）

- **可构建**：`mvn -B package` 产出 `target/velocitygate-1.0.0.jar`
  （Java 17 目标，velocity-api 3.4.0 provided）。
- **jar 可加载**：含注解处理器生成的 `velocity-plugin.json`
  （id `velocitygate`，主类 `io.github.corematch.velocitygate.VelocityGatePlugin`），
  放入 Velocity `plugins/` 目录即被识别。
- **测试覆盖（21 个，全绿）**：
  - 路由四路径（`GateRouterTest`，9 例）：查到→转向；查不到→留大厅；
    查询失败→留大厅；连接失败→留大厅并提示；另覆盖目标未映射、目标未注册、
    成局无地址、会话内只查一次、非大厅跳过。
  - MCA 客户端（`HttpMcaClientTest`，8 例）：200 有效分配 / 200 无分配 /
    无 join_target / 404 视为无分配 / 500 与拒连与坏信封与非法 UUID 均按
    "MCA 不可用"处理。
  - 配置解析（`GateConfigTest`，4 例）：必填项、映射归一化（host 大小写不敏感）、
    非法条目拒绝、超时默认值。
- **没有在真实 Velocity 实例上联调过**：以上全部是 JVM 内单元测试（Mockito
  桩 + JDK HttpServer 桩）。事件时序、真实代理的线程模型与真实玩家连接
  尚未被验证，首次上机需要人工观察日志。
- 依赖的服务端能力（MCA main `26527ba` 起、契约 1.9.1）已合并并部署生产：
  `GET /api/matchmaking/assignment/{uuid}`（仅 OPS 凭据；未知 UUID 回
  `assigned:false`；`matches.matchmaking_group_id` 关联列由迁移 v3 自动补）。

## 行为与失败路径

| 场景 | 行为 |
|---|---|
| 有效分配 + 映射命中 + 目标服可达 | 转向目标服 |
| 无分配 / 已结算 / 已过 TTL（默认 1800s） | 静默留大厅 |
| MCA 不可达 / 超时 / 响应异常 | 静默留大厅，**不阻断登录**，仅日志告警 |
| 成局有效但服务端未配置 `matchmaking.join_target` | 留大厅，提示"匹配成功，但未提供游戏服务器地址，请联系管理员。" |
| `join_target` 未在 `servers[]` 映射表登记 | 留大厅，提示"匹配成功，但代理未配置对应的游戏服务器，请联系管理员。" |
| 映射到的服务器未在 Velocity 注册 | 留大厅，提示"无法进入对局：目标服务器未就绪，请稍后重试。" |
| 连接目标服失败（宕机/人满等） | 留在大厅，提示"进入对局服务器失败，已留在大厅，请稍后重试。" |

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
  或运维令牌；玩家令牌会被 403 拒绝。
- `servers[].target` 必须与 MCA 配置的 `matchmaking.join_target`
  **逐字一致**（host 大小写不敏感，端口必须精确相同）；未登记的地址不会
  被路由。映射放配置而非代码：增删后端服无需重新构建插件。
- **MCA 侧 `matchmaking.join_target` 当前保持为空**：成局记录不带地址，
  插件会把被分配的玩家留在大厅并给出上述提示。等真实游戏服就位后再配置，
  不要填假地址。

## 构建

```
mvn -B package        # 产物 target/velocitygate-1.0.0.jar
mvn -B verify         # 构建 + 全部测试（CI 同款命令）
```

## 没做的

- 真实 Velocity 实例联调（见上）。
- 生产部署本插件（尚无 Velocity 代理实例与游戏服可对接）。
- 多游戏服选择策略、按模式分流、排队队列展示等扩展：本插件只做
  "已分配 → 送达"，其余不属于当前范围。
- 远端未建 Release、未打 tag。
