<div align="center">
  <h1>MatchCoreArena VelocityGate</h1>
  <p><strong>已成局的队伍，直达指定的后端。</strong></p>
  <p><a href="./README.md">English</a></p>
</div>

MatchCoreArena VelocityGate 是面向 Velocity 3.4.0 的 Java 17 插件，将已成局的队伍路由至指定游戏后端，服务于需要把 MatchCore Arena 分配接入 Minecraft 服务器的代理运维人员。

## 为什么选择 VelocityGate

- **按分配路由。** 有效分配通过单次会话查询映射到已注册后端。
- **稳妥的大厅兜底。** 无分配或路由失败时，玩家继续留在大厅。
- **失败状态清晰。** 明确区分无分配、服务不可用与查询失败。
- **重连自动重试。** 新会话可在掉线后重新尝试路由。
- **运维可见。** 分类日志记录路由尝试失败的原因。

## 进一步了解

- [HA Wiki](https://wiki.mcnb.dev/) — 生态文档
- [HA Contract](https://github.com/CoreMatch/HA-Contract) — 接口契约
- [MatchCoreArena Server](https://github.com/CoreMatch/MatchCoreArena-Server) — 分配来源
- [MCA-Cascade](https://github.com/CoreMatch/MCA-Cascade) — Cascade 侧决策库

## 适用范围

VelocityGate 不是负载均衡器，也不负责按游戏模式分流；不宣称已完成生产部署或真实玩家客户端验证。

## 许可证与来源

本仓库未检测到许可证，本文不作任何授权声明。
