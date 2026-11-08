package io.github.corematch.velocitygate;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MatchCoreArena VelocityGate：把"已成局的玩家"送进对应后端游戏服。
 *
 * <p>玩家连上代理 → 初始服务器固定为大厅 → 落在大厅后按 UUID 查一次
 * MCA 成局分配（OPS 只读端点）→ 查到且可映射则转向对应后端服，其余
 * 一律留在大厅。客户端零改动；后端服只对代理可达，不暴露公网。</p>
 *
 * <p>配置缺失或仍是占位符时，插件整体降级为无操作（所有人留在大厅），
 * 绝不阻断登录。</p>
 */
@Plugin(
        id = "velocitygate",
        name = "MatchCoreArena VelocityGate",
        version = "1.0.0",
        authors = {"CoreMatch"},
        description = "Routes players with a valid MCA formed-group assignment to the matching backend server."
)
public class VelocityGatePlugin {

    private static final String CONFIG_FILE_NAME = "velocitygate.json";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private GateConfig config;
    private GateRouter router;
    private ExecutorService executor;

    @Inject
    public VelocityGatePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Path configFile = dataDir.resolve(CONFIG_FILE_NAME);
        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(dataDir);
                Files.writeString(configFile, template(), StandardCharsets.UTF_8);
                logger.warn("已生成配置模板 {}，填写 mca_token 与 servers 映射后重启代理生效；"
                        + "此前插件不做任何路由。", configFile);
                return;
            }
            GateConfig parsed = GateConfig.parse(Files.readString(configFile, StandardCharsets.UTF_8));
            if (parsed.mcaToken().startsWith("<")) {
                logger.warn("mca_token 仍为占位符，VelocityGate 保持禁用（所有人留在大厅）。");
                return;
            }
            this.config = parsed;
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "velocitygate-query");
                thread.setDaemon(true);
                return thread;
            });
            McaClient client = new HttpMcaClient(
                    parsed.mcaBaseUrl(), parsed.mcaToken(), parsed.queryTimeoutMs());
            this.router = new GateRouter(proxy, parsed, client, executor, logger);
            logger.info("VelocityGate 已启用：MCA={}，大厅={}，映射 {} 条，查询超时 {}ms",
                    parsed.mcaBaseUrl(), parsed.lobbyServer(),
                    parsed.serverMapSize(), parsed.queryTimeoutMs());
        } catch (IOException | IllegalArgumentException e) {
            logger.error("VelocityGate 配置加载失败，插件保持禁用（所有人留在大厅）: {}", e.getMessage());
        }
    }

    /** 初始服务器固定为大厅：一切路由以大厅为基线，失败随时可退回。 */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (config == null) {
            return;
        }
        proxy.getServer(config.lobbyServer()).ifPresent(event::setInitialServer);
    }

    /** 玩家落在服务器后：仅大厅触发一次分配查询与可能的转向。 */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (router != null) {
            router.route(event.getPlayer());
        }
    }

    /** 断线清除会话标记，允许重连后重新路由。 */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (router != null) {
            router.forget(event.getPlayer().getUniqueId());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String template() {
        return """
                {
                  "mca_base_url": "https://arena.mcnb.dev",
                  "mca_token": "<OPS 服务令牌：MCA tokens.yaml 中的 match-reporter 或专用令牌>",
                  "lobby_server": "lobby",
                  "query_timeout_ms": 2500,
                  "servers": [
                    {"target": "127.0.0.1:25565", "server": "bw-game-1"}
                  ]
                }
                """;
    }
}
