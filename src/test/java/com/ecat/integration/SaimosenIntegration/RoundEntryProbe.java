package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ecat.integration.ModbusIntegration.ModbusSource;

import static org.mockito.Mockito.when;

/**
 * round 入口探针（modbus 族设备测试共用；单次改桩、定向计数）：firstRound=首轮确认；
 * strayRound 只计 {@link #armStrayDetector()} 之后（stop+sweep 完成、测试线程武装后）
 * 的 round 入口——负向窗内「至多一个」入口=链已停。快节拍（50ms）下旧「阈值-2 全程计数」
 * 形态不可用：sweep 前的正常周期同样计数、与真 stray 无法区分，改为 sweep 后武装的
 * 定向计数窗（600ms 窗覆盖 >10 个 50ms 周期，等价原「6s 窗 > 5s 生产周期」覆盖强度）。
 * 锚在轮询入口（executePolling 每轮首访 tryAcquire）而非块读：在飞轮次的迟到续块
 * （块间 delay 不随 sweep 撤销）不会误报。
 *
 * <p>stray 阈值为 2（011000 负载 flake 修正）：周期链为单发自排（一次至多一个在飞轮），
 * cancel 语义「不打断在飞轮」（文档化）——stop+sweep 时已在飞的轮次，其线程若在
 * shot 出队→tryAcquire 之间被抢占，恢复后会在武装<b>之后</b>迟到触入口。该迟到入口
 * 是合法在飞轮（发起于 stop 前）不是新轮，容 1 个；真「链未停」形态按 50ms 节拍在
 * 600ms 窗内 ≥10 次入口，2 阈值立即红，检出力不变。
 *
 * <p>tryAcquire 首访放行真轮、其后返 null（锁忙跳过轮不触源读，SDK 内部消化）——
 * verify(times(1)) 块参数断言对 50ms 快节拍保持确定性（无第二轮真实读竞争）。
 * 改桩在 start 前一次完成：活动轮询线程并发调 mock 时二次改桩会错绑。
 */
final class RoundEntryProbe {
    final CountDownLatch firstRound = new CountDownLatch(1);
    /** 武装后的 round 入口计数，阈值 2（容忍至多一个在飞轮的迟到入口，见类注释）。 */
    final CountDownLatch strayRound = new CountDownLatch(2);
    private volatile boolean strayArmed;
    private final AtomicBoolean granted = new AtomicBoolean();

    private RoundEntryProbe() {
    }

    /** 挂到 mock 源的 tryAcquire 入口（须在轮询 start 前一次完成改桩）。 */
    static RoundEntryProbe on(ModbusSource mockSource) {
        RoundEntryProbe probe = new RoundEntryProbe();
        when(mockSource.tryAcquire()).thenAnswer(inv -> {
            probe.onRoundEntry();
            return probe.grant();
        });
        return probe;
    }

    /** stop+sweep 完成后武装：此后每个 round 入口都计入 strayRound。 */
    void armStrayDetector() {
        strayArmed = true;
    }

    private void onRoundEntry() {
        firstRound.countDown();
        if (strayArmed) {
            strayRound.countDown();
        }
    }

    /** 首访放行（round 体真实执行一次），其后锁忙跳过（不触源读、不产生块读竞争）。 */
    private String grant() {
        return granted.compareAndSet(false, true) ? "testKey" : null;
    }
}
