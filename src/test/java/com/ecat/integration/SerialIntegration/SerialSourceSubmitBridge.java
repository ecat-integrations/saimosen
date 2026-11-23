package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 消费仓测试桥：{@code SerialSource#submitTransaction} 是 serial 域包私有方法（P1 每口
 * 串行执行者落地后事务提交唯一缝，{@code SerialTransactionStrategy} 两入口都经它入队），
 * 设备仓测试包无法直接引用该方法做 stub。本桥落在同包下（plain classpath 跨 jar 同包
 * 合法），代设备仓测试完成 stub 装配。各档与 serial 仓契约测试
 * {@code SerialTransactionStrategyAsyncContractTest} 的 {@code newMockSource()} 同款。
 *
 * <p>全部档位用 {@code doAnswer().when(mock)} 形态而非 {@code when(mock.method())}：
 * 测试共享 setUp 已桩、单个用例再重桩时，when 形态的重桩调用会先命中旧 answer
 * （以 null body 内联执行即 NPE），do 形态的重桩调用不触发旧桩。
 *
 * <ul>
 *   <li>{@link #inlineSubmitTransaction}：提交即在本线程执行事务体——等价旧 mock 装配的
 *       「acquire 立即授予」，供协议解析/生命周期逻辑测试（被测面不含端口仲裁）；</li>
 *   <li>{@link #skipWithLockBusy}：提交点返回 {@link LockBusySkippedException} 失败
 *       future——新模型 executePolling 等待预算耗尽时真源的真实返回形态，轮询链按
 *       「本轮跳过」内化，供锁忙降级回归测试；</li>
 *   <li>{@link #raw}：透传自定义 Answer（写闸等需按优先级/互斥状态分支的语义测试）。</li>
 * </ul>
 *
 * @author coffee
 */
public final class SerialSourceSubmitBridge {

    private SerialSourceSubmitBridge() {
    }

    /**
     * inline 事务语义：stub submitTransaction 使事务体立即执行并透传其 future
     * （无队列、无端口执行者，与本桥测试的设备逻辑无交涉）。
     */
    public static void inlineSubmitTransaction(SerialSource mock) {
        inlineSubmitTransaction(mock, null, null);
    }

    /**
     * inline 事务语义 + 提交点探针：每次事务提交（=新模型一场对话的开端）回调
     * {@code onTxSubmit}，供「轮已发射」「块事务数」类观测。
     */
    public static void inlineSubmitTransaction(SerialSource mock, Runnable onTxSubmit) {
        inlineSubmitTransaction(mock, onTxSubmit, null);
    }

    /**
     * inline 事务语义 + 双探针：提交点回调 {@code onTxSubmit}；事务 future 完成点
     * （inline 语义下=事务体结清，等价旧模型的 release 事件）回调 {@code onTxComplete}。
     */
    public static void inlineSubmitTransaction(SerialSource mock, Runnable onTxSubmit, Runnable onTxComplete) {
        Mockito.doAnswer(inv -> {
                    if (onTxSubmit != null) {
                        onTxSubmit.run();
                    }
                    Function<SerialSource, CompletableFuture<Boolean>> body =
                            (Function<SerialSource, CompletableFuture<Boolean>>) inv.getArgument(0);
                    CompletableFuture<Boolean> result = body.apply(mock);
                    if (onTxComplete != null) {
                        result.whenComplete((r, ex) -> onTxComplete.run());
                    }
                    return result;
                }).when(mock).submitTransaction(any(), any(), anyLong(), anyLong());
    }

    /**
     * 锁忙跳过语义：每次提交均以 {@link LockBusySkippedException} 失败完成（本轮跳过信号）；
     * {@code onSubmit} 为提交点探针（观测轮询确已发起到事务入口，可为 null）。
     */
    public static void skipWithLockBusy(SerialSource mock, Runnable onSubmit) {
        Mockito.doAnswer(inv -> {
                    if (onSubmit != null) {
                        onSubmit.run();
                    }
                    CompletableFuture<Boolean> skipped = new CompletableFuture<>();
                    skipped.completeExceptionally(new LockBusySkippedException(
                            "Polling transaction skipped: lock-busy stubbed for test"));
                    return skipped;
                }).when(mock).submitTransaction(any(), any(), anyLong(), anyLong());
    }

    /**
     * raw 档：透传自定义 Answer——写闸类语义测试（写持锁窗内轮询按优先级拒绝、
     * 事件序采集）在测试侧自持互斥状态装配；参数序 {@code (body, priority, waitMs,
     * execTimeoutMs)}，priority 仅以 toString 形态判别（枚举类型包私有，测试侧不可命名）。
     */
    public static void raw(SerialSource mock, Answer<CompletableFuture<Boolean>> answer) {
        Mockito.doAnswer(answer).when(mock).submitTransaction(any(), any(), anyLong(), anyLong());
    }
}
