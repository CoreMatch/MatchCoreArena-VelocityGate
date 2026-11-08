package io.github.corematch.velocitygate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GateConfigTest {

    private static final String VALID = """
            {
              "mca_base_url": "https://arena.mcnb.dev/",
              "mca_token": "tok-123",
              "lobby_server": "lobby",
              "query_timeout_ms": 1500,
              "servers": [
                {"target": "10.0.0.5:25565", "server": "bw-game-1"},
                {"target": "Game.Example.COM:25566", "server": "bw-game-2"}
              ]
            }
            """;

    @Test
    void parsesValidConfigAndNormalizesTargets() {
        GateConfig config = GateConfig.parse(VALID);
        assertEquals("https://arena.mcnb.dev", config.mcaBaseUrl());
        assertEquals("tok-123", config.mcaToken());
        assertEquals("lobby", config.lobbyServer());
        assertEquals(1500, config.queryTimeoutMs());
        assertEquals(2, config.serverMapSize());

        assertEquals("bw-game-1", config.serverFor("10.0.0.5", 25565));
        // host 大小写不敏感。
        assertEquals("bw-game-2", config.serverFor("game.example.com", 25566));
        assertEquals("bw-game-2", config.serverFor("GAME.EXAMPLE.COM", 25566));
        // 未登记的目标返回 null。
        assertNull(config.serverFor("10.9.9.9", 25565));
        assertNull(config.serverFor("10.0.0.5", 25566));
        assertNull(config.serverFor(null, 25565));
    }

    @Test
    void timeoutDefaultsWhenAbsent() {
        GateConfig config = GateConfig.parse("""
                {"mca_base_url": "http://mca", "mca_token": "t", "lobby_server": "lobby"}
                """);
        assertEquals(GateConfig.DEFAULT_QUERY_TIMEOUT_MS, config.queryTimeoutMs());
        assertEquals(0, config.serverMapSize());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> GateConfig.parse("{\"mca_token\": \"t\", \"lobby_server\": \"lobby\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> GateConfig.parse("{\"mca_base_url\": \"http://mca\", \"lobby_server\": \"lobby\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> GateConfig.parse("{\"mca_base_url\": \"http://mca\", \"mca_token\": \"t\"}"));
        assertThrows(IllegalArgumentException.class, () -> GateConfig.parse("not json"));
    }

    @Test
    void rejectsInvalidServerEntries() {
        assertThrows(IllegalArgumentException.class, () -> GateConfig.parse("""
                {"mca_base_url": "http://mca", "mca_token": "t", "lobby_server": "lobby",
                 "servers": [{"target": "no-port", "server": "x"}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> GateConfig.parse("""
                {"mca_base_url": "http://mca", "mca_token": "t", "lobby_server": "lobby",
                 "servers": [{"target": "10.0.0.5:25565", "server": " "}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> GateConfig.parse("""
                {"mca_base_url": "http://mca", "mca_token": "t", "lobby_server": "lobby",
                 "query_timeout_ms": 0}
                """));
    }
}
