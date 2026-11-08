package io.github.corematch.velocitygate;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由四路径（任务验收口径）：
 * 查到→转向；查不到→留大厅；查询失败→留大厅；目标服不可用→退大厅。
 */
class GateRouterTest {

    private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final GateConfig CONFIG = GateConfig.parse("""
            {
              "mca_base_url": "http://mca.test",
              "mca_token": "tok",
              "lobby_server": "lobby",
              "servers": [{"target": "10.0.0.5:25565", "server": "bw-game-1"}]
            }
            """);

    private ProxyServer proxy;
    private Player player;
    private RegisteredServer targetServer;
    private ConnectionRequestBuilder requestBuilder;
    private Logger logger;

    /** 可替换的 MCA 客户端桩。 */
    private McaClient client;

    @BeforeEach
    void setUp() {
        proxy = mock(ProxyServer.class);
        player = mock(Player.class);
        targetServer = mock(RegisteredServer.class);
        requestBuilder = mock(ConnectionRequestBuilder.class);
        logger = mock(Logger.class);

        when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        // 玩家当前在大厅。
        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("lobby");
        lenient().when(connection.getServerInfo()).thenReturn(info);
        lenient().when(player.getCurrentServer()).thenReturn(Optional.of(connection));
    }

    private GateRouter router() {
        return new GateRouter(proxy, CONFIG, client, Runnable::run, logger);
    }

    private static AssignmentResult assigned(String host, int port) {
        return AssignmentResult.fromJson(com.google.gson.JsonParser.parseString(
                "{\"assigned\":true,\"group_id\":5,\"match_type\":\"2v2\",\"team_id\":\"A\","
                        + "\"join_target\":{\"host\":\"" + host + "\",\"port\":" + port + "}}")
                .getAsJsonObject());
    }

    private void stubSuccessfulConnect() {
        when(player.createConnectionRequest(targetServer)).thenReturn(requestBuilder);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        when(requestBuilder.connect()).thenReturn(CompletableFuture.completedFuture(result));
    }

    // ── 路径一：查到 → 转向 ────────────────────────────────────────────
    @Test
    void assignedPlayerIsTransferredToMappedServer() {
        client = uuid -> assigned("10.0.0.5", 25565);
        when(proxy.getServer("bw-game-1")).thenReturn(Optional.of(targetServer));
        stubSuccessfulConnect();

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.ROUTED, outcome);
        verify(player).createConnectionRequest(targetServer);
        verify(requestBuilder).connect();
        verify(player, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    // ── 路径二：查不到 → 留大厅 ────────────────────────────────────────
    @Test
    void unassignedPlayerStaysInLobby() {
        client = uuid -> AssignmentResult.none();

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.NO_ASSIGNMENT, outcome);
        verify(player, never()).createConnectionRequest(any());
        verify(player, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    // ── 路径三：查询失败 → 留大厅（不阻断登录）────────────────────────
    @Test
    void queryFailureKeepsPlayerInLobby() {
        client = uuid -> {
            throw new McaUnavailableException("connection refused");
        };

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.QUERY_FAILED, outcome);
        verify(player, never()).createConnectionRequest(any());
        verify(player, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    // ── 路径四：目标服不可用 → 退大厅并提示 ───────────────────────────
    @Test
    void failedTransferKeepsLobbyAndNotifies() {
        client = uuid -> assigned("10.0.0.5", 25565);
        when(proxy.getServer("bw-game-1")).thenReturn(Optional.of(targetServer));
        when(player.createConnectionRequest(targetServer)).thenReturn(requestBuilder);
        ConnectionRequestBuilder.Result failed = mock(ConnectionRequestBuilder.Result.class);
        when(failed.isSuccessful()).thenReturn(false);
        when(requestBuilder.connect()).thenReturn(CompletableFuture.completedFuture(failed));

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.ROUTED, outcome); // 已发起，连接阶段失败
        verify(player).sendMessage(GateRouter.MSG_CONNECT_FAILED); // 留在大厅并给提示
    }

    @Test
    void unregisteredTargetServerKeepsLobbyAndNotifies() {
        client = uuid -> assigned("10.0.0.5", 25565);
        when(proxy.getServer("bw-game-1")).thenReturn(Optional.empty());

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.TARGET_SERVER_MISSING, outcome);
        verify(player).sendMessage(GateRouter.MSG_TARGET_SERVER_MISSING);
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void unmappedTargetKeepsLobbyAndNotifies() {
        client = uuid -> assigned("10.9.9.9", 25565); // 不在映射表

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.UNKNOWN_TARGET, outcome);
        verify(player).sendMessage(GateRouter.MSG_UNKNOWN_TARGET);
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void assignmentWithoutJoinTargetKeepsLobbyAndNotifies() {
        client = uuid -> AssignmentResult.fromJson(com.google.gson.JsonParser.parseString(
                "{\"assigned\":true,\"group_id\":5,\"match_type\":\"1v1\",\"team_id\":\"B\"}")
                .getAsJsonObject());

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.NO_JOIN_TARGET, outcome);
        verify(player).sendMessage(GateRouter.MSG_NO_JOIN_TARGET);
    }

    // ── 会话语义：每会话一次；断线后可重新路由；非大厅跳过 ────────────
    @Test
    void secondRouteInSameSessionIsSkippedUntilForget() {
        client = uuid -> AssignmentResult.none();
        GateRouter router = router();

        assertEquals(GateRouter.Outcome.NO_ASSIGNMENT, router.route(player).join());
        assertEquals(GateRouter.Outcome.SKIPPED, router.route(player).join());

        router.forget(PLAYER_UUID);
        assertEquals(GateRouter.Outcome.NO_ASSIGNMENT, router.route(player).join());
    }

    @Test
    void playerOutsideLobbyIsSkipped() {
        client = uuid -> assigned("10.0.0.5", 25565);
        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("bw-game-1");
        when(connection.getServerInfo()).thenReturn(info);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));

        GateRouter.Outcome outcome = router().route(player).join();

        assertEquals(GateRouter.Outcome.SKIPPED, outcome);
    }
}
