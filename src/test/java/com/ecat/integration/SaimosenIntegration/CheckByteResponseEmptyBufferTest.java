package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.HashMap;

import org.junit.Test;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.integration.SerialIntegration.SerialSource;

/**
 * checkByteResponse 空缓冲语义回归锁（2026-09-18 saimosen v2 运行期重加全离线事故第二层根因）。
 *
 * <p>legacy 轮询路径以「检查函数返回非 null」判定帧完整。空缓冲（null / 0 字节）= 帧未组完，
 * 必须返回 null 让轮询继续等待；旧实现复制体对空缓冲返回 new byte[0]（非 null），首读在写后
 * 立即发生（应答尚未到达）、空数组被当完整帧交付，造成每条命令
 * {@code Unprocessed response: }（空）刷屏、设备永不采数。四处复制体（SerialDeviceBase +
 * 三个 SMS8600V2 属性）各自锁死，不合并公共方法（局部修改纪律）。
 */
public class CheckByteResponseEmptyBufferTest {

    private final SerialSource mockSource = mock(SerialSource.class);

    /** SerialDeviceBase 抽象类的最小探针子类（构造零副作用，只为调用受保护的检查函数）。 */
    private static final class ProbeDevice extends SerialDeviceBase {
        ProbeDevice(ConfigEntry entry) {
            super(entry);
        }

        @Override
        public void start() {
            // DeviceControl 契约要求的空实现：探针不参与生命周期，只调检查函数
        }
    }

    private static ProbeDevice newProbeDevice() {
        return new ProbeDevice(new ConfigEntry.Builder()
                .entryId("probe-empty-buffer")
                .coordinate("com.ecat:integration-saimosen")
                .uniqueId("EmptyBufferProbe")
                .data(new HashMap<>())
                .build());
    }

    @Test
    public void serialDeviceBase_emptyOrNullBuffer_mustReturnNull() {
        ProbeDevice device = newProbeDevice();
        assertNull("null 缓冲=帧未组完，必须返回 null 继续等", device.checkByteResponse(null));
        assertNull("空缓冲=帧未组完，必须返回 null 继续等（旧 new byte[0] 被 legacy 轮询当完整帧交付）",
                device.checkByteResponse(new byte[0]));
    }

    @Test
    public void cylinderGasNumericAttribute_emptyOrNullBuffer_mustReturnNull() {
        SMS8600V2CylinderGasNumericAttribute attr = new SMS8600V2CylinderGasNumericAttribute(
                "gas_probe", AttributeClass.OTHER_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, AirVolumeUnit.PPM, 3, true, true, mockSource);
        assertNull("null 缓冲=帧未组完，必须返回 null 继续等", attr.checkByteResponse(null));
        assertNull("空缓冲=帧未组完，必须返回 null 继续等", attr.checkByteResponse(new byte[0]));
    }

    @Test
    public void stringSelectAttribute_emptyOrNullBuffer_mustReturnNull() {
        SMS8600V2DeviceStringSelectAttribute attr = new SMS8600V2DeviceStringSelectAttribute(
                "channel_probe", AttributeClass.VALUE, true,
                Arrays.asList("SO2", "NO2"),
                SMS8600V2DeviceStringSelectAttribute.ChannelNumber.CHANNEL_1, mockSource);
        assertNull("null 缓冲=帧未组完，必须返回 null 继续等", attr.checkByteResponse(null));
        assertNull("空缓冲=帧未组完，必须返回 null 继续等", attr.checkByteResponse(new byte[0]));
    }

    @Test
    public void deviceCommandAttribute_emptyOrNullBuffer_mustReturnNull() {
        SMS8600V2DeviceCommandAttribute attr = new SMS8600V2DeviceCommandAttribute(
                "cmd_probe", AttributeClass.DISPATCH_COMMAND, mockSource);
        assertNull("null 缓冲=帧未组完，必须返回 null 继续等", attr.checkByteResponse(null));
        assertNull("空缓冲=帧未组完，必须返回 null 继续等", attr.checkByteResponse(new byte[0]));
    }

    @Test
    public void dollarTerminatedFrame_completionJudgementUnchanged() {
        // 正控制：$（0x24）结尾完整帧原样返回，非 $ 结尾返回 null——修复只改空缓冲分支，
        // 完成判定契约不动
        ProbeDevice device = newProbeDevice();
        byte[] frame = "calochr$".getBytes();
        assertArrayEquals("$ 结尾的完整帧原样返回", frame, device.checkByteResponse(frame));
        assertNull("非 $ 结尾=帧未组完返回 null", device.checkByteResponse("caloch".getBytes()));
    }
}
