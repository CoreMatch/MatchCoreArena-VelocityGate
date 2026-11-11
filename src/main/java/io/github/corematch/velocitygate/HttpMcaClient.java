package io.github.corematch.velocitygate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 基于 JDK HttpClient 的 MCA 客户端：
 * GET {base}/api/matchmaking/assignment/{uuid}，Bearer 使用 OPS 服务令牌。
 *
 * <p>状态码语义（对齐契约 matchcorearena-business.yaml 1.10.0 与服务端
 * 1.9.1+ 行为）：</p>
 * <ul>
 *   <li>200 → 按信封解析（含 assigned=false；未知 UUID 由服务端回
 *       {@code assigned:false}，调用方无需也不得依赖 404 分支）；</li>
 *   <li>404 → 显式失败（NOT_FOUND）：服务端对未知 UUID 恒回 200，
 *       出现 404 只说明路径不存在，几乎必然是 mca_base_url 配错，
 *       绝不能静默当成"无分配"；</li>
 *   <li>401/403 → 显式失败（AUTH_REJECTED）：令牌无效或不是 OPS 级凭据；</li>
 *   <li>502 且信封 code=oauth_hrpauth_down → HRP_AUTH_DOWN：HRPAuth
 *       不可达导致玩家身份解析失败（与"未绑定用户"严格区分：后者是
 *       200 assigned:false 的正常态）；</li>
 *   <li>其余非 2xx、超时、网络错误、坏信封 → 对应分类的
 *       {@link McaUnavailableException}。</li>
 * </ul>
 */
public final class HttpMcaClient implements McaClient {

    /** Minecraft UUID（带/不带连字符）。 */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$");

    /** 服务端在 502 时标识 HRPAuth 解析失败的错误码（契约 ErrorEnvelope.code）。 */
    private static final String CODE_HRP_AUTH_DOWN = "oauth_hrpauth_down";

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
            throw new McaUnavailableException(
                    McaUnavailableException.Category.BAD_RESPONSE, "非法玩家 UUID，拒绝发起查询");
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
        } catch (HttpTimeoutException e) {
            throw new McaUnavailableException(
                    McaUnavailableException.Category.TIMEOUT, "MCA 查询超时（>" + timeout.toMillis() + "ms）", e);
        } catch (IOException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new McaUnavailableException(
                    McaUnavailableException.Category.UNREACHABLE, "MCA 不可达: " + detail, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McaUnavailableException(
                    McaUnavailableException.Category.UNREACHABLE, "MCA 查询被中断", e);
        }

        int status = response.statusCode();
        if (status == 200) {
            return parseEnvelope(response.body());
        }
        if (status == 404) {
            throw new McaUnavailableException(McaUnavailableException.Category.NOT_FOUND,
                    "MCA 返回 404：分配端点对未知 UUID 恒回 200，出现 404 说明路径不存在，"
                            + "请检查 mca_base_url 是否配置正确");
        }
        if (status == 401 || status == 403) {
            throw new McaUnavailableException(McaUnavailableException.Category.AUTH_REJECTED,
                    "MCA 拒绝凭据（HTTP " + status + "）：请检查 mca_token 是否为 OPS 级服务令牌");
        }
        if (status == 502 && bodyHasCode(response.body(), CODE_HRP_AUTH_DOWN)) {
            throw new McaUnavailableException(McaUnavailableException.Category.HRP_AUTH_DOWN,
                    "HRPAuth 不可达（502 oauth_hrpauth_down）：无法解析玩家身份，本次留大厅");
        }
        throw new McaUnavailableException(McaUnavailableException.Category.HTTP_ERROR,
                "MCA 返回非预期状态码: " + status);
    }

    private AssignmentResult parseEnvelope(String body) throws McaUnavailableException {
        try {
            JsonObject envelope = JsonParser.parseString(body).getAsJsonObject();
            if (!envelope.has("success") || !envelope.get("success").getAsBoolean()) {
                throw new McaUnavailableException(
                        McaUnavailableException.Category.BAD_RESPONSE, "MCA 响应信封 success != true");
            }
            if (!envelope.has("data") || !envelope.get("data").isJsonObject()) {
                throw new McaUnavailableException(
                        McaUnavailableException.Category.BAD_RESPONSE, "MCA 响应缺少 data 对象");
            }
            return AssignmentResult.fromJson(envelope.getAsJsonObject("data"));
        } catch (McaUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new McaUnavailableException(
                    McaUnavailableException.Category.BAD_RESPONSE, "MCA 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 尽力解析错误信封的 code 字段；解析失败返回 false，不影响主分类。 */
    private static boolean bodyHasCode(String body, String code) {
        try {
            JsonObject envelope = JsonParser.parseString(body).getAsJsonObject();
            return envelope.has("code") && code.equals(envelope.get("code").getAsString());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
