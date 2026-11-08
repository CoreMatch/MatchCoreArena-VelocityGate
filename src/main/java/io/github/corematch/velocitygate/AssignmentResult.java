package io.github.corematch.velocitygate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * MCA 成局分配查询结果（GET /api/matchmaking/assignment/{uuid} 的 data 段，
 * 契约 matchcorearena-business.yaml 1.9.0）。只保留路由所需字段。
 */
public final class AssignmentResult {

    private static final AssignmentResult NONE = new AssignmentResult(false, 0, null, null, null, 0);

    private final boolean assigned;
    private final long groupId;
    private final String matchType;
    private final String teamId;
    private final String joinHost;
    private final int joinPort;

    private AssignmentResult(boolean assigned, long groupId, String matchType, String teamId,
                             String joinHost, int joinPort) {
        this.assigned = assigned;
        this.groupId = groupId;
        this.matchType = matchType;
        this.teamId = teamId;
        this.joinHost = joinHost;
        this.joinPort = joinPort;
    }

    /** 无有效分配（assigned=false 或玩家不存在）。 */
    public static AssignmentResult none() {
        return NONE;
    }

    /** 从 MCA 响应信封的 data 对象解析；assigned=false 时返回 {@link #none()}。 */
    public static AssignmentResult fromJson(JsonObject data) {
        if (data == null || !data.has("assigned") || !data.get("assigned").getAsBoolean()) {
            return NONE;
        }
        long groupId = data.has("group_id") ? data.get("group_id").getAsLong() : 0;
        String matchType = optString(data, "match_type");
        String teamId = optString(data, "team_id");
        String host = null;
        int port = 0;
        JsonElement jt = data.get("join_target");
        if (jt != null && jt.isJsonObject()) {
            JsonObject target = jt.getAsJsonObject();
            if (target.has("host") && target.has("port")) {
                host = target.get("host").getAsString();
                port = target.get("port").getAsInt();
            }
        }
        return new AssignmentResult(true, groupId, matchType, teamId, host, port);
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    public boolean isAssigned() {
        return assigned;
    }

    public long groupId() {
        return groupId;
    }

    public String matchType() {
        return matchType;
    }

    public String teamId() {
        return teamId;
    }

    /** join_target 的 "host:port" 键；服务端未配置地址时为 null。 */
    public String joinTargetKey() {
        if (joinHost == null || joinHost.isEmpty() || joinPort <= 0) {
            return null;
        }
        return joinHost.toLowerCase(java.util.Locale.ROOT) + ":" + joinPort;
    }
}
