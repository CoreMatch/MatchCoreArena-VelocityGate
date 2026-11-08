package io.github.corematch.velocitygate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 插件配置（velocitygate.json，位于插件数据目录）。
 *
 * <p>成局记录与 Velocity 服务器名的对应关系由 {@code servers} 映射表承担：
 * MCA 成局携带的 join_target（host:port）在表里查出已注册的服务器名。
 * 映射放在配置而非代码里，增删后端服无需重新构建插件。</p>
 *
 * <pre>{@code
 * {
 *   "mca_base_url": "https://arena.mcnb.dev",
 *   "mca_token": "<OPS 服务令牌>",
 *   "lobby_server": "lobby",
 *   "query_timeout_ms": 2500,
 *   "servers": [
 *     {"target": "10.0.0.5:25565", "server": "bw-game-1"}
 *   ]
 * }
 * }</pre>
 */
public final class GateConfig {

    public static final int DEFAULT_QUERY_TIMEOUT_MS = 2500;

    private final String mcaBaseUrl;
    private final String mcaToken;
    private final String lobbyServer;
    private final int queryTimeoutMs;
    /** "host:port"（host 小写）→ Velocity 已注册服务器名。 */
    private final Map<String, String> serverMap;

    private GateConfig(String mcaBaseUrl, String mcaToken, String lobbyServer,
                       int queryTimeoutMs, Map<String, String> serverMap) {
        this.mcaBaseUrl = mcaBaseUrl;
        this.mcaToken = mcaToken;
        this.lobbyServer = lobbyServer;
        this.queryTimeoutMs = queryTimeoutMs;
        this.serverMap = serverMap;
    }

    /** 解析配置 JSON；必填项缺失时抛 {@link IllegalArgumentException}。 */
    public static GateConfig parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置不是合法 JSON 对象: " + e.getMessage(), e);
        }

        String baseUrl = requireString(root, "mca_base_url").replaceAll("/+$", "");
        String token = requireString(root, "mca_token");
        String lobby = requireString(root, "lobby_server");
        int timeout = root.has("query_timeout_ms")
                ? root.get("query_timeout_ms").getAsInt()
                : DEFAULT_QUERY_TIMEOUT_MS;
        if (timeout <= 0) {
            throw new IllegalArgumentException("query_timeout_ms 必须为正数");
        }

        Map<String, String> serverMap = new LinkedHashMap<>();
        JsonElement servers = root.get("servers");
        if (servers != null && servers.isJsonArray()) {
            JsonArray arr = servers.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject entry = arr.get(i).getAsJsonObject();
                String target = normalizeTarget(entry.has("target") ? entry.get("target").getAsString() : "");
                String server = entry.has("server") ? entry.get("server").getAsString().trim() : "";
                if (target.isEmpty() || server.isEmpty()) {
                    throw new IllegalArgumentException("servers[" + i + "] 的 target/server 必填");
                }
                serverMap.put(target, server);
            }
        }
        return new GateConfig(baseUrl, token, lobby, timeout, serverMap);
    }

    /** 归一化 "host:port"：host 转小写、去空白；非法返回空串。 */
    static String normalizeTarget(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        int colon = t.lastIndexOf(':');
        if (colon <= 0 || colon == t.length() - 1) {
            return "";
        }
        String port = t.substring(colon + 1);
        for (int i = 0; i < port.length(); i++) {
            if (!Character.isDigit(port.charAt(i))) {
                return "";
            }
        }
        return t;
    }

    private static String requireString(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            throw new IllegalArgumentException("缺少必填配置项: " + key);
        }
        String value = root.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("配置项不能为空: " + key);
        }
        return value;
    }

    /** 按 join_target 的 host:port 查已注册服务器名；未登记返回 null。 */
    public String serverFor(String host, int port) {
        if (host == null || host.isEmpty() || port <= 0) {
            return null;
        }
        return serverMap.get(host.toLowerCase(Locale.ROOT) + ":" + port);
    }

    public String mcaBaseUrl() {
        return mcaBaseUrl;
    }

    public String mcaToken() {
        return mcaToken;
    }

    public String lobbyServer() {
        return lobbyServer;
    }

    public int queryTimeoutMs() {
        return queryTimeoutMs;
    }

    public int serverMapSize() {
        return serverMap.size();
    }
}
