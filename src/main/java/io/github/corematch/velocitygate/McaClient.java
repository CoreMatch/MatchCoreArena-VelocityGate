package io.github.corematch.velocitygate;

/**
 * MCA 成局分配查询客户端。实现必须保证：查询失败（服务不可达、超时、
 * 非预期响应）抛 {@link McaUnavailableException}，绝不静默吞掉后返回
 * 伪造的"无分配"——路由侧对不可用与无分配的处置虽然同为留在大厅，
 * 但日志语义不同。
 */
public interface McaClient {

    /**
     * 按玩家 UUID 查询当前有效成局分配。
     *
     * @throws McaUnavailableException MCA 不可达或响应不可用
     */
    AssignmentResult queryAssignment(String uuid) throws McaUnavailableException;
}
