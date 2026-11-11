package io.github.corematch.velocitygate;

/**
 * MCA 服务不可达或响应不可用（超时、网络错误、非预期状态码、坏信封）。
 *
 * <p>每次失败都携带 {@link Category} 分类：路由侧对任何分类的处置都是
 * "玩家留在大厅、不阻断登录"，但日志语义不同——分类让运维能直接回答
 * "为什么这个玩家没被送走"，而不是只看到一句 generic failure。</p>
 */
public class McaUnavailableException extends Exception {

    /** 失败分类（日志与排障用；不改变"留大厅"的路由处置）。 */
    public enum Category {
        /** 连接被拒 / DNS 失败等网络不可达。 */
        UNREACHABLE,
        /** 连接或读取超时。 */
        TIMEOUT,
        /** 401/403：令牌无效或不是 OPS 级凭据（检查 mca_token）。 */
        AUTH_REJECTED,
        /** 404：路径不存在——几乎必然是 mca_base_url 配置错误。 */
        NOT_FOUND,
        /** 502 oauth_hrpauth_down：HRPAuth 不可达，无法解析玩家身份。 */
        HRP_AUTH_DOWN,
        /** 其余非 2xx 状态码。 */
        HTTP_ERROR,
        /** 200 但信封不可解析（success != true、缺 data、非法 JSON）。 */
        BAD_RESPONSE
    }

    private final Category category;

    public McaUnavailableException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public McaUnavailableException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    /** 兼容构造：未指明分类按网络不可达处理。 */
    public McaUnavailableException(String message) {
        this(Category.UNREACHABLE, message);
    }

    /** 兼容构造：未指明分类按网络不可达处理。 */
    public McaUnavailableException(String message, Throwable cause) {
        this(Category.UNREACHABLE, message, cause);
    }

    public Category category() {
        return category;
    }
}
