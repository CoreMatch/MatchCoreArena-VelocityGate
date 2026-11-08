#!/bin/bash
# Local integration harness for VelocityGate against a real Velocity proxy.
#
# Verified with Velocity 3.4.0 (build 566, sha256
# fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e) on
# OpenJDK 25. NOT part of CI: it needs a downloaded velocity.jar next to
# this script and a built target/velocitygate-1.0.0.jar.
#
# Setup (once):
#   curl -fsSL -o velocity.jar "<velocity-3.4.0 jar url>"   # see README
#   first run of `java -jar velocity.jar` generates velocity.toml; then set:
#     online-mode = false
#     compression-threshold = -1
#     [servers] lobby = "127.0.0.1:30066", minigames = "127.0.0.1:30068"
#   plugins/velocitygate/velocitygate.json:
#     {"mca_base_url": "http://127.0.0.1:18080", "mca_token": "stub-test-token",
#      "lobby_server": "lobby", "query_timeout_ms": 2500,
#      "servers": [{"target": "127.0.0.1:30068", "server": "minigames"}]}
#
# Run:
#   echo '{"assigned": false}' > scenario.json && bash run_scenario.sh run1.log
#   echo '{"assigned": true, "group_id": 5, "match_type": "1v1", "team_id": "A",
#          "room": "arena-1", "join_target": {"host": "127.0.0.1", "port": 30068}}' \
#     > scenario.json && bash run_scenario.sh run2.log minigames
#
# Expected: scenario 1 queries the stub MCA and keeps the probe in the lobby;
# scenario 2 additionally transfers the probe to the minigames backend and the
# second ServerPostConnect is skipped (session-once semantics).
cd "$(dirname "$0")" || exit 1
RUNLOG="$1"
EXTRA="$2"

pids=()
python3 stub_mca.py > stub_mca.log 2>&1 & pids+=($!)
python3 fake_backend.py 30066 lobby > lobby.log 2>&1 & pids+=($!)
if [ -n "$EXTRA" ]; then
  python3 fake_backend.py 30068 minigames > minigames.log 2>&1 & pids+=($!)
fi
sleep 1

timeout 40 java -jar velocity.jar </dev/null > "$RUNLOG" 2>&1 & pids+=($!)
sleep 7
python3 probe_client.py 12
sleep 3

echo "=== stub_mca.log ==="
cat stub_mca.log
echo "=== lobby.log ==="
cat lobby.log
if [ -n "$EXTRA" ]; then
  echo "=== minigames.log ==="
  cat minigames.log
fi
echo "=== velocity relevant ==="
tr '\r' '\n' < "$RUNLOG" | grep -a -E "查询|分配|转向|留在大厅|connected player|WARN|ERROR" | grep -v "^> "

for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
exit 0
