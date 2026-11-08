package io.github.corematch.velocitygate;

/** MCA 服务不可达或响应不可用（超时、网络错误、非预期状态码）。 */
public class McaUnavailableException extends Exception {

    public McaUnavailableException(String message) {
        super(message);
    }

    public McaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
