package io.github.corematch.velocitygate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 基于 JDK HttpClient 的 MCA 客户端：
 * GET {base}/api/matchmaking/assignment/{uuid}，Bearer 使用 OPS 服务令牌。
 *
 * <p>语义：200 → 按信封解析（含 assigned=false）；404（uuid 不在
 * HRPAuth）→ 视为无分配；其余非 2xx、超时与网络错误 →
 * {@link McaUnavailableException}。</p>
 */
public final class HttpMcaClient implements McaClient {

    /** Minecraft UUID（带/不带连字符）。 */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$");

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;

    public HttpMcaClient(String baseUrl, String token, int timeoutMillis) {
        this(baseUrl, token, timeoutMillis,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build());
    }

    HttpMcaClient(String baseUrl, String token, int timeoutMillis, HttpClient http) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.http = http;
    }

    @Override
    public AssignmentResult queryAssignment(String uuid) throws McaUnavailableException {
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches()) {
            throw new McaUnavailableException("非法玩家 UUID，拒绝发起查询");
        }
        URI uri = URI.create(baseUrl + "/api/matchmaking/assignment/"
                + URLEncoder.encode(uuid, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new McaUnavailableException("MCA 不可达: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McaUnavailableException("MCA 查询被中断", e);
        }

        int status = response.statusCode();
        if (status == 404) {
            // uuid 不在 HRPAuth：对该代理而言等价于"无分配"。
            return AssignmentResult.none();
        }
        if (status != 200) {
            throw new McaUnavailableException("MCA 返回非预期状态码: " + status);
        }

        try {
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!envelope.has("success") || !envelope.get("success").getAsBoolean()) {
                throw new McaUnavailableException("MCA 响应信封 success != true");
            }
            if (!envelope.has("data") || !envelope.get("data").isJsonObject()) {
                throw new McaUnavailableException("MCA 响应缺少 data 对象");
            }
            return AssignmentResult.fromJson(envelope.getAsJsonObject("data"));
        } catch (McaUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new McaUnavailableException("MCA 响应解析失败: " + e.getMessage(), e);
        }
    }
}
