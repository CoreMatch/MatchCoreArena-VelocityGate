package io.github.corematch.velocitygate;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用 JDK 内嵌 HTTP 服务桩验证 MCA 客户端语义。 */
class HttpMcaClientTest {

    private HttpServer server;

    private void start(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/matchmaking/assignment/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpMcaClient client() {
        return new HttpMcaClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok", 2000);
    }

    private static String envelope(String data) {
        return "{\"success\":true,\"message\":\"ok\",\"data\":" + data + "}";
    }

    @Test
    void parsesValidAssignment() throws Exception {
        start(200, envelope("{\"assigned\":true,\"group_id\":5,\"match_type\":\"2v2\","
                + "\"formed_at\":\"2026-11-08T10:00:00Z\",\"team_id\":\"A\","
                + "\"join_target\":{\"host\":\"10.0.0.5\",\"port\":25565}}"));
        AssignmentResult result = client().queryAssignment("00000000-0000-0000-0000-000000000001");
        assertTrue(result.isAssigned());
        assertEquals(5, result.groupId());
        assertEquals("2v2", result.matchType());
        assertEquals("A", result.teamId());
        assertEquals("10.0.0.5:25565", result.joinTargetKey());
    }

    @Test
    void parsesNoAssignment() throws Exception {
        start(200, envelope("{\"assigned\":false}"));
        AssignmentResult result = client().queryAssignment("00000000-0000-0000-0000-000000000001");
        assertFalse(result.isAssigned());
    }

    @Test
    void missingJoinTargetYieldsNullKey() throws Exception {
        start(200, envelope("{\"assigned\":true,\"group_id\":5,\"match_type\":\"1v1\",\"team_id\":\"B\"}"));
        AssignmentResult result = client().queryAssignment("00000000000000000000000000000001");
        assertTrue(result.isAssigned());
        assertEquals(null, result.joinTargetKey());
    }

    @Test
    void notFoundMeansNoAssignment() throws Exception {
        start(404, "{\"success\":false,\"code\":\"mca_user_not_found\"}");
        AssignmentResult result = client().queryAssignment("00000000-0000-0000-0000-000000000001");
        assertFalse(result.isAssigned());
    }

    @Test
    void serverErrorIsUnavailable() throws Exception {
        start(500, "boom");
        assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void unreachableIsUnavailable() {
        //  closed port → connection refused.
        HttpMcaClient closed = new HttpMcaClient("http://127.0.0.1:1", "tok", 500);
        assertThrows(McaUnavailableException.class,
                () -> closed.queryAssignment("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void malformedEnvelopeIsUnavailable() throws Exception {
        start(200, "{\"success\":true}");
        assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void invalidUuidRejectedWithoutNetwork() {
        HttpMcaClient closed = new HttpMcaClient("http://127.0.0.1:1", "tok", 500);
        assertThrows(McaUnavailableException.class, () -> closed.queryAssignment("not-a-uuid"));
    }
}
