package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.SerialIntegration.SerialIntegration;

/**
 * 采集间隔配置（顶层键 poll_interval_sec）load() 兜底回归：
 * <ol>
 *   <li>缺字段 → 回退默认 5s（收编前各机型硬编码 5s，默认行为零变化）；</li>
 *   <li>合法自定义 10 秒 → 换算 10000ms（解析与秒→ms 换算收敛在 resolvePollIntervalMs 一处）；</li>
 *   <li>非法（非数字 "abc"、下越界 0 与 0.005、上越界 61）→ 回退默认 5s；</li>
 *   <li>小数秒支持（表单 range(0.01,60)）：2.5 → 2500ms、字符串 "2.5" → 2500ms、下边界 0.01 → 10ms；</li>
 *   <li>SMS8700PM 出厂节律 10s：缺键/非法回退 10000ms 而非 base 的 5000ms，合法自定义仍生效；</li>
 *   <li>SmsV2GasDeviceBase 串口支路的 load() 覆写同样生效。</li>
 * </ol>
 * 喂配置方式：ConfigEntry.data 顶层键 + 完整 comm_settings（load 通讯解析的前置输入，与被测逻辑正交）；
 * 确定性同步：纯 load() 断言字段值，无异步等待。
 */
public class PollIntervalConfigTest {

    private EcatCore mockCore;

    @Before
    public void setUp() {
        mockCore = mock(EcatCore.class);
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        when(mockCore.getIntegrationRegistry()).thenReturn(registry);
        when(registry.getIntegration("integration-modbus")).thenReturn(mock(ModbusIntegration.class));
        when(registry.getIntegration("integration-serial")).thenReturn(mock(SerialIntegration.class));
    }

    @After
    public void tearDown() {
        // 重置两条 base 的静态 integration 引用，防止 mock 泄漏影响其他测试类（静态字段全 base 共享）
        SmsDeviceBase.modbusIntegration = null;
        SerialDeviceBase.serialIntegration = null;
    }

    /** 构造 ConfigEntry（顶层键即设备配置 data）。 */
    private ConfigEntry newEntry(String uniqueId, Map<String, Object> data) {
        return new ConfigEntry.Builder()
                .entryId(uniqueId)
                .coordinate("com.ecat:integration-saimosen")
                .uniqueId(uniqueId)
                .data(data)
                .build();
    }

    /** Modbus RTU 通讯段（SmsDeviceBase.load 的 RTU 解析前置输入）。 */
    private Map<String, Object> modbusRtuCommSettings() {
        Map<String, Object> serialSettings = new HashMap<>();
        serialSettings.put("serial_port", "COM1");
        serialSettings.put("baudrate", "9600");
        serialSettings.put("data_bits", "8");
        serialSettings.put("stop_bits", "1");
        serialSettings.put("parity", "None");
        serialSettings.put("timeout", 2000);
        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("serial_settings", serialSettings);
        commSettings.put("slave_id", 1);
        return commSettings;
    }

    /**
     * 构造 CO 设备（Modbus 支路），可选追加顶层 poll_interval_sec 后执行 load()。
     */
    private CODevice newLoadedCoDevice(Object pollIntervalSec) {
        Map<String, Object> data = new HashMap<>();
        data.put("class", "air.monitor.co");
        data.put("modbus_protocol", "RTU");
        data.put("comm_settings", modbusRtuCommSettings());
        if (pollIntervalSec != null) {
            data.put("poll_interval_sec", pollIntervalSec);
        }
        CODevice device = new CODevice(newEntry("test-poll-interval-co", data));
        device.load(mockCore);
        return device;
    }

    /**
     * 构造 SMS8700 颗粒物设备（出厂节律 10s 机型），可选追加顶层 poll_interval_sec 后执行 load()。
     */
    private SMS8700PMDevice newLoadedPmDevice(Object pollIntervalSec) {
        Map<String, Object> data = new HashMap<>();
        data.put("class", "air.monitor.pm");
        data.put("modbus_protocol", "RTU");
        data.put("comm_settings", modbusRtuCommSettings());
        if (pollIntervalSec != null) {
            data.put("poll_interval_sec", pollIntervalSec);
        }
        SMS8700PMDevice device = new SMS8700PMDevice(newEntry("test-poll-interval-pm", data));
        device.load(mockCore);
        return device;
    }

    /**
     * 构造 SMS8200V2 设备（SmsV2GasDeviceBase 串口支路），可选追加顶层 poll_interval_sec 后执行 load()。
     */
    private SMS8200V2Device newLoadedSms8200V2Device(Object pollIntervalSec) {
        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("serial_port", "COM2");
        commSettings.put("baudrate", "9600");
        commSettings.put("data_bits", "8");
        commSettings.put("stop_bits", "1");
        commSettings.put("parity", "N");
        commSettings.put("flow_control", "0");
        commSettings.put("timeout", 500);
        Map<String, Object> data = new HashMap<>();
        data.put("comm_settings", commSettings);
        if (pollIntervalSec != null) {
            data.put("poll_interval_sec", pollIntervalSec);
        }
        SMS8200V2Device device = new SMS8200V2Device(newEntry("test-poll-interval-sms8200v2", data));
        device.load(mockCore);
        return device;
    }

    /**
     * 构造 SMS8600V2 设备（直连 SerialDeviceBase 支路），可选追加顶层 poll_interval_sec 后执行 load()。
     */
    private SMS8600V2Device newLoadedSms8600V2Device(Object pollIntervalSec) {
        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("serial_port", "COM3");
        commSettings.put("baudrate", "9600");
        commSettings.put("data_bits", "8");
        commSettings.put("stop_bits", "1");
        commSettings.put("parity", "N");
        commSettings.put("flow_control", "0");
        commSettings.put("timeout", 500);
        Map<String, Object> data = new HashMap<>();
        data.put("comm_settings", commSettings);
        if (pollIntervalSec != null) {
            data.put("poll_interval_sec", pollIntervalSec);
        }
        SMS8600V2Device device = new SMS8600V2Device(newEntry("test-poll-interval-sms8600v2", data));
        device.load(mockCore);
        return device;
    }

    @Test
    public void testLoad_PollIntervalSecMissing_FallsBackToDefault() {
        assertEquals("缺 poll_interval_sec 必须回退默认 5s", 5000L, newLoadedCoDevice(null).pollIntervalMs);
    }

    @Test
    public void testLoad_PollIntervalSec10_AppliesAs10000Ms() {
        assertEquals("合法自定义 10 秒必须换算为 10000ms", 10000L, newLoadedCoDevice(10).pollIntervalMs);
    }

    @Test
    public void testLoad_PollIntervalSecIllegal_FallsBackToDefault() {
        assertEquals("非数字 \"abc\" 必须回退默认 5s", 5000L, newLoadedCoDevice("abc").pollIntervalMs);
        assertEquals("越界 0（<0.01）必须回退默认 5s", 5000L, newLoadedCoDevice(0).pollIntervalMs);
        assertEquals("小数 0.005（换算 5ms < 下界 10ms）必须回退默认 5s", 5000L, newLoadedCoDevice(0.005).pollIntervalMs);
        assertEquals("越界 61（>60）必须回退默认 5s", 5000L, newLoadedCoDevice(61).pollIntervalMs);
    }

    @Test
    public void testLoad_PollIntervalSecDecimal_AppliesAsMs() {
        assertEquals("小数 2.5 秒必须换算为 2500ms", 2500L, newLoadedCoDevice(2.5).pollIntervalMs);
        assertEquals("字符串 \"2.5\" 必须换算为 2500ms", 2500L, newLoadedCoDevice("2.5").pollIntervalMs);
        assertEquals("下边界 0.01 秒（=表单 range 下界）必须生效为 10ms", 10L, newLoadedCoDevice(0.01).pollIntervalMs);
        assertEquals("SMS8700PM 小数 1.5 秒同样生效为 1500ms", 1500L, newLoadedPmDevice(1.5).pollIntervalMs);
    }

    @Test
    public void testLoad_Sms8700Pm_MissingOrIllegal_KeepsFactoryTenSeconds() {
        assertEquals("SMS8700PM 缺 poll_interval_sec 必须保持出厂 10s（而非 base 默认 5s）",
                10000L, newLoadedPmDevice(null).pollIntervalMs);
        assertEquals("SMS8700PM 非法 poll_interval_sec 必须回退出厂 10s",
                10000L, newLoadedPmDevice("abc").pollIntervalMs);
        assertEquals("SMS8700PM 合法自定义 20 秒仍须生效",
                20000L, newLoadedPmDevice(20).pollIntervalMs);
    }

    @Test
    public void testLoad_V2SerialBranch_MissingAndCustom() {
        assertEquals("V2 串口支路缺 poll_interval_sec 必须回退默认 5s",
                5000L, newLoadedSms8200V2Device(null).pollIntervalMs);
        assertEquals("V2 串口支路合法自定义 10 秒必须换算为 10000ms",
                10000L, newLoadedSms8200V2Device(10).pollIntervalMs);
    }

    @Test
    public void testLoad_Sms8600V2_MissingIllegalCustomAndDecimal() {
        // SMS8600V2（直连 SerialDeviceBase 支路）四态：缺键回退 5s、合法自定义生效、
        // 非数字回退、小数秒换算。该间隔同时是 logicdevice-airstation 标气消耗公式
        // 的折算同源键（poll_interval_sec），两侧防线必须一致。
        assertEquals("SMS8600V2 缺 poll_interval_sec 必须回退默认 5s",
                5000L, newLoadedSms8600V2Device(null).pollIntervalMs);
        assertEquals("SMS8600V2 合法自定义 10 秒必须换算为 10000ms",
                10000L, newLoadedSms8600V2Device(10).pollIntervalMs);
        assertEquals("SMS8600V2 非数字 poll_interval_sec 必须回退默认 5s",
                5000L, newLoadedSms8600V2Device("abc").pollIntervalMs);
        assertEquals("SMS8600V2 小数 2.5 秒必须换算为 2500ms",
                2500L, newLoadedSms8600V2Device(2.5).pollIntervalMs);
    }
}
