package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.integration.SerialIntegration.SerialSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钢瓶气浓度属性写闸（22 号 setValue final 化 W2，D-22-7 ①）：用户经 setDisplayValue 下发
 * calppm 标定命令，IO（发帧+calppmok$ 应答确认）成功后必须本地收尾——updateValue+publicState，
 * 总线即时可见；失败/false 不发布不残留。
 *
 * <p>被测因果链一环：「IO 成功 → 平台发布」。此前该属性自拼 CF 只做 IO 不做任何本地收尾：
 * 设备已改、平台属性值/总线事件纹丝不动（ADM 监控/告警/下游绑定逻辑属性失明到下一轮询）。
 * mocked SerialSource（acquire/release/send/read 受控），legacy 响应模式（测试栈自动侦测），
 * 确定性同步（future.get + 受控 read future，禁 sleep 等待）。
 */
public class SMS8600V2CylinderGasWriteGateTest {

    private SerialSource mockSerialSource;
    private DeviceBase mockDevice;
    private EcatCore mockCore;
    private BusRegistry mockBus;
    private final List<BusEvent<?>> published = new ArrayList<>();
    private AutoCloseable mocks;

    /** read 应答闸门：非空 = asyncReadDataBytes 返回该 future（测试受控放行，确定性）。 */
    private CompletableFuture<byte[]> readGate;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        mockSerialSource = mock(SerialSource.class);
        mockDevice = mock(DeviceBase.class);
        mockCore = mock(EcatCore.class);
        mockBus = mock(BusRegistry.class);
        when(mockDevice.getId()).thenReturn("sms-cyl-1");
        when(mockDevice.isReady()).thenReturn(true);
        when(mockDevice.getCore()).thenReturn(mockCore);
        when(mockCore.getBusRegistry()).thenReturn(mockBus);
        doAnswer(inv -> {
            published.add(inv.getArgument(0));
            return null;
        }).when(mockBus).publish(any());

        // 写事务锁：executeWithLambda 走 acquire/release（mocked 源直接授予）
        when(mockSerialSource.acquire()).thenReturn("write-key");
        when(mockSerialSource.release(anyString())).thenReturn(true);
        when(mockSerialSource.asyncSendData(any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));
        when(mockSerialSource.asyncReadDataBytes()).thenAnswer(inv ->
                readGate != null ? readGate : CompletableFuture.completedFuture(new byte[0]));
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    private SMS8600V2CylinderGasNumericAttribute newAttr() {
        SMS8600V2CylinderGasNumericAttribute attr = new SMS8600V2CylinderGasNumericAttribute(
                "gas_so2_cylinder_gas_conc", AttributeClass.OTHER_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, AirVolumeUnit.PPM, 3, true, true, mockSerialSource);
        attr.setChannelId("1");
        attr.setDevice(mockDevice);
        return attr;
    }

    /** IO 确认成功（calppmok$）→ future=true、值更新为新浓度、总线发布恰一次（写后即见）。 */
    @Test
    public void confirmedWritePublishesNewValue() throws Exception {
        SMS8600V2CylinderGasNumericAttribute attr = newAttr();
        attr.updateValue(100.0, AttributeStatus.NORMAL);
        int publishedBefore = published.size();
        readGate = CompletableFuture.completedFuture("calppmok$".getBytes());

        Boolean ok = attr.setDisplayValue("500").get(5, TimeUnit.SECONDS);

        assertTrue("calppmok$ 确认后写应成功", ok);
        assertEquals("IO 成功后本地值必须更新（平台不得对已发生的设备写入失明）",
                Double.valueOf(500.0), ((Number) attr.getState().getValue()).doubleValue(), 0.001);
        assertEquals("IO 成功后必须 publicState（总线/下游即时可见）",
                publishedBefore + 1, published.size());
    }

    /** 设备驳回（非 calppmok$ 应答）→ future=false、值不变、零发布。 */
    @Test
    public void rejectedWriteDoesNotPublishOrResidue() throws Exception {
        SMS8600V2CylinderGasNumericAttribute attr = newAttr();
        attr.updateValue(100.0, AttributeStatus.NORMAL);
        int publishedBefore = published.size();
        readGate = CompletableFuture.completedFuture("calppmerr$".getBytes());

        Boolean ok = attr.setDisplayValue("500").get(5, TimeUnit.SECONDS);

        assertEquals("驳回应答（非 calppmok$）写应失败", Boolean.FALSE, ok);
        assertEquals("失败写不得残留新值", Double.valueOf(100.0),
                ((Number) attr.getState().getValue()).doubleValue(), 0.001);
        assertEquals("失败写零发布", publishedBefore, published.size());
    }
}
