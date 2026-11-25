<div align="center">
  <h1>MatchCoreArena VelocityGate</h1>
  <p><strong>Formed groups, routed to their assigned backends.</strong></p>
  <p><a href="./README.zh-CN.md">简体中文</a></p>
</div>

MatchCoreArena VelocityGate is a Java 17 plugin for Velocity 3.4.0 that routes already-formed groups to their assigned game backends. It is built for proxy operators connecting MatchCore Arena assignments to Minecraft servers.

## Why VelocityGate

- **Assignment-aware routing.** Valid assignments map to registered backends through a single session lookup.
- **A safe lobby fallback.** Missing assignments and routing failures keep players in the lobby.
- **Explicit failure states.** No-assignment responses, unavailable services, and query failures remain distinguishable.
- **Reconnect retry.** A fresh session can retry routing after a disconnect.
- **Operational visibility.** Categorized logging records why a routing attempt failed.

## Learn more

- [Armorial Wiki](https://wiki.mcnb.dev/) — ecosystem documentation
- [Armorial Contract](https://github.com/CoreMatch/HA-Contract) — interface contracts
- [MatchCoreArena Server](https://github.com/CoreMatch/MatchCoreArena-Server) — assignment source
- [MCA-Cascade](https://github.com/CoreMatch/MCA-Cascade) — Cascade-side decision library

## Scope

VelocityGate is not a load balancer or mode router. Production deployment and validation with real human clients are not claimed.

## License and provenance

No license was detected in this repository; no license is asserted here.
