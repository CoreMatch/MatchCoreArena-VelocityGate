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

/** 用 JDK 内嵌 HTTP 服务桩验证 MCA 客户端语义与失败分类。 */
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
        assertEquals(null, result.reason());
    }

    @Test
    void parsesNoRoomAvailableReason() throws Exception {
        start(200, envelope("{\"assigned\":false,\"reason\":\"no_room_available\"}"));
        AssignmentResult result = client().queryAssignment("00000000-0000-0000-0000-000000000001");
        assertFalse(result.isAssigned());
        assertEquals("no_room_available", result.reason());
    }

    @Test
    void missingJoinTargetYieldsNullKey() throws Exception {
        start(200, envelope("{\"assigned\":true,\"group_id\":5,\"match_type\":\"1v1\",\"team_id\":\"B\"}"));
        AssignmentResult result = client().queryAssignment("00000000000000000000000000000001");
        assertTrue(result.isAssigned());
        assertEquals(null, result.joinTargetKey());
    }

    /** 1.9.1 起服务端对未知 UUID 恒回 200 assigned:false；404 只可能是路径配错，必须显式失败。 */
    @Test
    void notFoundIsExplicitFailure() throws Exception {
        start(404, "{\"success\":false,\"code\":\"not_found\"}");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.NOT_FOUND, e.category());
    }

    @Test
    void serverErrorIsHttpError() throws Exception {
        start(500, "boom");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.HTTP_ERROR, e.category());
    }

    /** 502 + oauth_hrpauth_down：HRPAuth 不可达，与"未绑定用户"（200 assigned:false）严格区分。 */
    @Test
    void hrpAuthDownIsClassified() throws Exception {
        start(502, "{\"success\":false,\"message\":\"查询成局分配失败\","
                + "\"code\":\"oauth_hrpauth_down\",\"error\":\"oauth_hrpauth_down\"}");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.HRP_AUTH_DOWN, e.category());
    }

    /** 502 但错误码不是 oauth_hrpauth_down：按其余状态码处理，不误判。 */
    @Test
    void otherBadGatewayIsHttpError() throws Exception {
        start(502, "{\"success\":false,\"code\":\"internal_error\"}");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.HTTP_ERROR, e.category());
    }

    /** 502 且响应体不是 JSON：分类不受影响。 */
    @Test
    void badGatewayWithGarbageBodyIsHttpError() throws Exception {
        start(502, "<html>bad gateway</html>");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.HTTP_ERROR, e.category());
    }

    @Test
    void authRejectedIsClassified() throws Exception {
        start(403, "{\"success\":false,\"code\":\"oauth_insufficient_scope\"}");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.AUTH_REJECTED, e.category());
    }

    @Test
    void timeoutIsClassified() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/matchmaking/assignment/", exchange -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        HttpMcaClient slow = new HttpMcaClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "tok", 100);
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> slow.queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.TIMEOUT, e.category());
    }

    @Test
    void unreachableIsUnreachable() {
        //  closed port → connection refused.
        HttpMcaClient closed = new HttpMcaClient("http://127.0.0.1:1", "tok", 500);
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> closed.queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.UNREACHABLE, e.category());
    }

    @Test
    void malformedEnvelopeIsBadResponse() throws Exception {
        start(200, "{\"success\":true}");
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> client().queryAssignment("00000000-0000-0000-0000-000000000001"));
        assertEquals(McaUnavailableException.Category.BAD_RESPONSE, e.category());
    }

    @Test
    void invalidUuidRejectedWithoutNetwork() {
        HttpMcaClient closed = new HttpMcaClient("http://127.0.0.1:1", "tok", 500);
        McaUnavailableException e = assertThrows(McaUnavailableException.class,
                () -> closed.queryAssignment("not-a-uuid"));
        assertEquals(McaUnavailableException.Category.BAD_RESPONSE, e.category());
    }
}
