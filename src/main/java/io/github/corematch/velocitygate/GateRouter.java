package io.github.corematch.velocitygate;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 路由决策核心：玩家连上代理并落在大厅后，按 UUID 查一次 MCA 成局分配，
 * 按契约 1.10.0 的三种形态分别处置：
 *
 * <ol>
 *   <li>{@code assigned:true} 且带 {@code join_target} → 转向映射的后端服；</li>
 *   <li>{@code assigned:false}（无 reason）→ 静默留大厅。这是正常态：
 *       未绑定用户、无成局、已结算、过 TTL 都走这里，不是错误；</li>
 *   <li>{@code assigned:false} + {@code reason:no_room_available} → 留大厅，
 *       并给玩家一句可读原因（成局有效但房间预占过期/清单变更）。</li>
 * </ol>
 *
 * <p>其余失败路径全部退化为"留在大厅（+ 提示）"，绝不卡住连接：</p>
 * <ul>
 *   <li>MCA 查询失败 → 静默留大厅（不阻断登录），日志按
 *       {@link McaUnavailableException.Category} 分类：网络不可达 / 超时 /
 *       凭据被拒（401/403）/ 路径不存在（404，多为 base_url 配错）/
 *       HRPAuth 不可达（502 oauth_hrpauth_down）/ 其余状态码 / 坏信封。
 *       注意"未绑定用户"与"HRPAuth 不可达"的区别：前者是 200
 *       assigned:false 的正常态（形态 2），后者是基础设施故障；</li>
 *   <li>成局未带地址 / 地址未在映射表 / 目标服未注册 / 连接失败
 *       → 留大厅并发送提示。</li>
 * </ul>
 *
 * <p>每个会话（连接→断开）只查一次，防止大厅↔目标服之间反复跳转；
 * 断线清除标记，重连重新查询。</p>
 */
public final class GateRouter {

    /** 路由结果（测试与日志用）。 */
    public enum Outcome {
        /** 已发起转向目标服（最终成败见后续日志）。 */
        ROUTED,
        /** 无有效分配，留大厅（含 no_room_available，后者会向玩家提示）。 */
        NO_ASSIGNMENT,
        /** MCA 查询失败，留大厅（日志带失败分类）。 */
        QUERY_FAILED,
        /** 成局有效但未携带游戏服地址。 */
        NO_JOIN_TARGET,
        /** join_target 未在映射表登记。 */
        UNKNOWN_TARGET,
        /** 映射到的服务器未在 Velocity 注册。 */
        TARGET_SERVER_MISSING,
        /** 发起连接时即失败。 */
        CONNECT_FAILED,
        /** 本次连接已处理过或不在大厅，跳过。 */
        SKIPPED
    }

    static final Component MSG_NO_JOIN_TARGET =
            Component.text("匹配成功，但未提供游戏服务器地址，请联系管理员。");
    static final Component MSG_UNKNOWN_TARGET =
            Component.text("匹配成功，但代理未配置对应的游戏服务器，请联系管理员。");
    static final Component MSG_TARGET_SERVER_MISSING =
            Component.text("无法进入对局：目标服务器未就绪，请稍后重试。");
    static final Component MSG_CONNECT_FAILED =
            Component.text("进入对局服务器失败，已留在大厅，请稍后重试。");
    static final Component MSG_NO_ROOM_AVAILABLE =
            Component.text("你的对局仍然有效，但房间已不可用（预占可能已过期），请重新加入匹配。");
    static final Component MSG_ASSIGNMENT_UNAVAILABLE =
            Component.text("匹配结果当前无法生效，请重新加入匹配或联系管理员。");

    private final ProxyServer proxy;
    private final GateConfig config;
    private final McaClient client;
    private final Executor executor;
    private final Logger logger;
    private final Set<UUID> handled = ConcurrentHashMap.newKeySet();

    public GateRouter(ProxyServer proxy, GateConfig config, McaClient client,
                      Executor executor, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.client = client;
        this.executor = executor;
        this.logger = logger;
    }

    /** 玩家断开时清除会话标记，允许下次连接重新路由。 */
    public void forget(UUID uuid) {
        handled.remove(uuid);
    }

    /**
     * 处理一次"玩家已连上代理"事件。仅当玩家当前在大厅且本会话未处理过
     * 时发起查询；返回的 future 以 {@link Outcome} 收尾，永不异常完成。
     */
    public CompletableFuture<Outcome> route(Player player) {
        UUID uuid = player.getUniqueId();
        if (!handled.add(uuid)) {
            return CompletableFuture.completedFuture(Outcome.SKIPPED);
        }
        String currentServer = player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(ServerInfo::getName)
                .orElse("");
        if (!currentServer.equals(config.lobbyServer())) {
            return CompletableFuture.completedFuture(Outcome.SKIPPED);
        }
        return CompletableFuture
                .supplyAsync(() -> query(uuid), executor)
                .handle((assignment, error) -> decide(player, uuid, assignment, error));
    }

    private AssignmentResult query(UUID uuid) {
        try {
            return client.queryAssignment(uuid.toString());
        } catch (McaUnavailableException e) {
            throw new CompletionException(e);
        }
    }

    private Outcome decide(Player player, UUID uuid, AssignmentResult assignment, Throwable error) {
        if (error != null) {
            McaUnavailableException mca = findMcaUnavailable(error);
            if (mca != null) {
                logger.warn("MCA 分配查询失败 [{}]（玩家 {} 留在大厅）: {}",
                        mca.category(), uuid, mca.getMessage());
            } else {
                logger.warn("MCA 分配查询失败 [UNEXPECTED]（玩家 {} 留在大厅）: {}",
                        uuid, messageOf(error));
            }
            return Outcome.QUERY_FAILED;
        }
        if (!assignment.isAssigned()) {
            String reason = assignment.reason();
            if (reason == null) {
                // 正常态（未绑定用户/无成局/已结算/过 TTL）：静默留大厅。
                logger.info("玩家 {} 当前无有效分配，留在大厅", uuid);
                return Outcome.NO_ASSIGNMENT;
            }
            if (AssignmentResult.REASON_NO_ROOM_AVAILABLE.equals(reason)) {
                logger.info("玩家 {} 成局有效但无可用房间（reason={}），留在大厅", uuid, reason);
                player.sendMessage(MSG_NO_ROOM_AVAILABLE);
            } else {
                // 契约枚举外的未知原因：不猜测语义，给通用提示并告警。
                logger.warn("玩家 {} 无有效分配且携带未知原因（reason={}），留在大厅", uuid, reason);
                player.sendMessage(MSG_ASSIGNMENT_UNAVAILABLE);
            }
            return Outcome.NO_ASSIGNMENT;
        }

        String key = assignment.joinTargetKey();
        if (key == null) {
            logger.warn("玩家 {} 的成局未携带 join_target，留在大厅", uuid);
            player.sendMessage(MSG_NO_JOIN_TARGET);
            return Outcome.NO_JOIN_TARGET;
        }

        int colon = key.lastIndexOf(':');
        String host = key.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(key.substring(colon + 1));
        } catch (NumberFormatException e) {
            port = 0;
        }
        String serverName = config.serverFor(host, port);
        if (serverName == null) {
            logger.warn("玩家 {} 的成局目标 {} 未在映射表登记，留在大厅", uuid, key);
            player.sendMessage(MSG_UNKNOWN_TARGET);
            return Outcome.UNKNOWN_TARGET;
        }

        Optional<RegisteredServer> server = proxy.getServer(serverName);
        if (server.isEmpty()) {
            logger.warn("映射目标 {} 未在 Velocity 注册，玩家 {} 留在大厅", serverName, uuid);
            player.sendMessage(MSG_TARGET_SERVER_MISSING);
            return Outcome.TARGET_SERVER_MISSING;
        }

        try {
            player.createConnectionRequest(server.get())
                    .connect()
                    .whenComplete((result, connectError) -> {
                        if (connectError != null) {
                            logger.warn("玩家 {} 连接目标服 {} 失败（{}），留在大厅",
                                    uuid, serverName, messageOf(connectError));
                            player.sendMessage(MSG_CONNECT_FAILED);
                        } else if (result == null || !result.isSuccessful()) {
                            logger.warn("玩家 {} 连接目标服 {} 失败（状态: {}），留在大厅",
                                    uuid, serverName, result == null ? "无结果" : result.getStatus());
                            player.sendMessage(MSG_CONNECT_FAILED);
                        } else {
                            logger.info("玩家 {} 已转入目标服 {}", uuid, serverName);
                        }
                    });
        } catch (RuntimeException e) {
            logger.warn("玩家 {} 发起连接异常，留在大厅: {}", uuid, e.getMessage());
            player.sendMessage(MSG_CONNECT_FAILED);
            return Outcome.CONNECT_FAILED;
        }
        logger.info("玩家 {} 按成局 {}（{} {}队）发起转向 {}",
                uuid, assignment.groupId(), assignment.matchType(), assignment.teamId(), serverName);
        return Outcome.ROUTED;
    }

    /**
     * 沿异常链找第一个 {@link McaUnavailableException}。不能直接解包到
     * 最内层：客户端抛出的分类异常通常携带更内层的原始 cause（如
     * ClosedChannelException），解包到底会丢掉分类信息。
     */
    private static McaUnavailableException findMcaUnavailable(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof McaUnavailableException mca) {
                return mca;
            }
            Throwable next = cursor.getCause();
            cursor = (next == cursor) ? null : next;
        }
        return null;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private static String messageOf(Throwable error) {
        Throwable root = rootCause(error);
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName().toLowerCase(Locale.ROOT) : message;
    }
}
