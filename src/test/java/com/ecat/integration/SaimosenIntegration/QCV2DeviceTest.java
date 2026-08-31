// Copyright (c) ecat
package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.State.Unit.AirMassUnit;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusShortAttribute;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QCV2Device 单元测试：独立完整协议 V2（0~273），相对 QCDevice 新增的智能稳压电源协议（233~273）。
 */
public class QCV2DeviceTest {

    private QCV2Device device;
    private AutoCloseable mockitoCloseable;

    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    /** 调度桩说明：块间节拍走 polling.delay(ms)（ModbusSdkTimers 域池），
     *  轮询定时归域 SDK 自持，本测不再桩调度路由。 */

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        device = new QCV2Device(createTestEntry());

        setPrivateField(device, "core", mockEcatCore);
        setPrivateField(device, "modbusSource", mockModbusSource);
        setPrivateField(device, "modbusIntegration", mockModbusIntegration);

        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);

        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        device.init();
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("class", "air.monitor.qc");
        config.put("model", "SMS8910V2");
        config.put("modbus_protocol", "RTU");

        Map<String, Object> deviceSettings = new HashMap<>();
        deviceSettings.put("sampling_tube_length", 4.5);
        deviceSettings.put("sampling_tube_inner_diameter", 0.1);
        config.put("device_settings", deviceSettings);

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
        config.put("comm_settings", commSettings);

        return new ConfigEntry.Builder()
            .entryId("test-entry-qcv2-device")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_air.monitor.qc_v2")
            .title("质控设备V2")
            .data(config)
            .build();
    }

    @Test
    public void testDoesNotExtendQCDevice() {
        assertEquals(SmsDeviceBase.class, QCV2Device.class.getSuperclass());
        assertEquals(QCV2Device.class, device.getClass());
    }

    @Test
    public void testInit_CreatesProtocolAttributes() {
        assertNotNull(device.getAttrs().get("system_state"));
        assertNotNull(device.getAttrs().get("pm2_5_working_flow"));
        assertNotNull(device.getAttrs().get("so2_film_changer_addr"));
        assertNotNull(device.getAttrs().get("nox_film_changer_addr"));
        assertNotNull(device.getAttrs().get("co_film_changer_addr"));
        assertNotNull(device.getAttrs().get("o3_film_changer_addr"));
        assertNotNull(device.getAttrs().get("so2_film_changer_status"));
        assertNotNull(device.getAttrs().get("nox_film_changer_status"));
        assertNotNull(device.getAttrs().get("co_film_changer_status"));
        assertNotNull(device.getAttrs().get("o3_film_changer_status"));
        assertNotNull(device.getAttrs().get("so2_film_ch1_switch_time"));
        assertNotNull(device.getAttrs().get("so2_film_ch5_switch_time"));
        assertNotNull(device.getAttrs().get("nox_film_ch3_switch_time"));
        assertNotNull(device.getAttrs().get("nox_film_ch5_switch_time"));
        assertNotNull(device.getAttrs().get("co_film_ch1_switch_time"));
        assertNotNull(device.getAttrs().get("o3_film_ch5_switch_time"));
        assertNull(device.getAttrs().get("so2_film_ch1_year"));
        assertNull(device.getAttrs().get("nox_film_ch2_hour"));
        assertNull(device.getAttrs().get("nox_film_ch1_switch_time"));
        assertNull(device.getAttrs().get("nox_film_ch2_switch_time"));

        for (int i = 1; i <= 4; i++) {
            assertNotNull("voltage_l" + i, device.getAttrs().get("voltage_l" + i));
            assertNotNull("current_l" + i, device.getAttrs().get("current_l" + i));
            assertNotNull("power_l" + i, device.getAttrs().get("power_l" + i));
            assertNotNull("relay_l" + i, device.getAttrs().get("relay_l" + i));
            assertNotNull("temp_alarm_high_l" + i, device.getAttrs().get("temp_alarm_high_l" + i));
            assertNotNull("temp_alarm_low_l" + i, device.getAttrs().get("temp_alarm_low_l" + i));
            assertNotNull("startup_delay_l" + i, device.getAttrs().get("startup_delay_l" + i));
            assertNotNull("temp_trip_high_l" + i, device.getAttrs().get("temp_trip_high_l" + i));
            assertNotNull("over_temp_protection_l" + i, device.getAttrs().get("over_temp_protection_l" + i));
        }
        assertNotNull(device.getAttrs().get("temperature"));
        assertNotNull(device.getAttrs().get("humidity"));
        assertNotNull(device.getAttrs().get("temp_humidity_comm_status"));
        assertNotNull(device.getAttrs().get("electric_param_comm_status"));
        assertNotNull(device.getAttrs().get("device_address"));
        assertTrue(device.getAttrs().get("fan_control") instanceof BinaryAttribute);
        assertTrue(device.getAttrs().get("light_control") instanceof BinaryAttribute);
        assertTrue(device.getAttrs().get("infrared_status") instanceof StringSelectAttribute);
        assertTrue(device.getAttrs().get("smoke_detector1") instanceof StringSelectAttribute);
        assertTrue(device.getAttrs().get("smoke_detector2") instanceof StringSelectAttribute);
        assertTrue(device.getAttrs().get("temp_detector1") instanceof StringSelectAttribute);
        assertTrue(device.getAttrs().get("water_leak_detector") instanceof StringSelectAttribute);
        // 采样管漏水：硬件未实际返回，V2 暂不建属性（代码保留在 QCV2Device.initAttributeMap 注释中）
        assertNull(device.getAttrs().get("sample_tube_leak"));
        assertTrue(device.getAttrs().get("sample_tube_sampling_status") instanceof BinaryAttribute);
        assertTrue(device.getAttrs().get("zero_gas_relay") instanceof BinaryAttribute);
        for (int i = 1; i <= 4; i++) {
            assertTrue("relay_l" + i, device.getAttrs().get("relay_l" + i) instanceof BinaryAttribute);
            assertTrue("over_temp_protection_l" + i,
                    device.getAttrs().get("over_temp_protection_l" + i) instanceof BinaryAttribute);
        }
        assertTrue(device.getAttrs().get("temp_humidity_comm_status") instanceof StringSelectAttribute);
        assertTrue(device.getAttrs().get("electric_param_comm_status") instanceof StringSelectAttribute);
    }

    @Test
    public void testProtocolWritableFlags() {
        assertTrue(device.getAttrs().get("system_state").canValueChange());
        assertFalse(device.getAttrs().get("fan_power").canValueChange());
        assertFalse(device.getAttrs().get("heating_belt_power").canValueChange());
        assertTrue(device.getAttrs().get("heating_temp").canValueChange());
        assertTrue(device.getAttrs().get("so2_film_changer_addr").canValueChange());
        assertTrue(device.getAttrs().get("relay_l1").canValueChange());
        assertTrue(device.getAttrs().get("sample_tube_sampling_status").canValueChange());
        assertFalse(device.getAttrs().get("voltage_l1").canValueChange());
        assertFalse(device.getAttrs().get("temperature").canValueChange());
        assertTrue(device.getAttrs().get("device_address").canValueChange());
        assertTrue(device.getAttrs().get("over_temp_protection_l1").canValueChange());
        assertFalse(device.getAttrs().get("temp_humidity_comm_status").canValueChange());
        assertFalse(device.getAttrs().get("electric_param_comm_status").canValueChange());
        assertTrue(device.getAttrs().get("fan_control").canValueChange());
        assertTrue(device.getAttrs().get("light_control").canValueChange());
        assertTrue(device.getAttrs().get("zero_gas_relay").canValueChange());
        assertFalse(device.getAttrs().get("infrared_status").canValueChange());
        assertFalse(device.getAttrs().get("smoke_detector1").canValueChange());
    }

    @Test
    public void testGasConcentrationUnitsAreUgM3() {
        assertEquals(AirMassUnit.UGM3, device.getAttrs().get("o3_concentration_qc").getNativeUnit());
        assertEquals(AirMassUnit.UGM3, device.getAttrs().get("co_concentration_qc").getNativeUnit());
        assertEquals(AirMassUnit.UGM3, device.getAttrs().get("no2_concentration_qc").getNativeUnit());
        assertEquals(AirMassUnit.UGM3, device.getAttrs().get("so2_concentration_qc").getNativeUnit());
        assertEquals(AirMassUnit.UGM3, device.getAttrs().get("pm2_5_concentration").getNativeUnit());
        assertEquals(AirMassUnit.UGM3, device.getAttrs().get("pm10_concentration").getNativeUnit());
    }

    @Test
    public void testReadRegisters_ParsesSmartPowerSupplyAndFilmChangers() throws Exception {
        String hexData1 = "01 03 DC 00 00 41 CC CC CD 42 3F 33 33 41 FF 33 33 42 28 00 00 3F A6 66 66 00 00 00 00 00 00 43 65 19 9A 43 64 80 00 43 66 CC CD 40 98 51 EC 40 93 D7 0A 3E C7 AE 14 44 19 00 00 44 47 80 00 42 8E 00 00 C4 31 C0 00 C1 C8 00 00 C2 04 00 00 3E E8 F5 C3 3F 7F BE 77 3F 65 A1 CB 42 48 0A 3D 00 01 00 00 00 18 00 01 00 01 00 17 00 01 00 00 00 00 00 19 00 00 00 01 00 17 00 00 46 1B 9E 13 46 69 35 D0 44 E1 43 78 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 01 00 01 00 01 00 01 00 01 00 01 00 00 00 00 43 67 19 9A 43 5C 33 33 00 0A 42 48 00 00 00 00 00 00 00 00 00 00 00 00 00 07 00 11 3C A3 D7 0A 00 05 00 00 00 00 3D CC CC CD 81 7C";

        short[] registers2 = new short[123];
        registers2[111 - 110] = 1;
        registers2[115 - 110] = 1;
        registers2[116 - 110] = 11;
        registers2[117 - 110] = 2026;
        registers2[118 - 110] = 8;
        registers2[119 - 110] = 15;
        registers2[120 - 110] = 14;
        registers2[121 - 110] = 30;
        registers2[142 - 110] = 2;
        registers2[143 - 110] = 22;
        registers2[154 - 110] = 26;
        registers2[155 - 110] = 1;
        registers2[156 - 110] = 2;
        registers2[157 - 110] = 9;
        registers2[158 - 110] = 5;
        registers2[169 - 110] = 3;
        registers2[170 - 110] = 33;
        registers2[196 - 110] = 4;
        registers2[197 - 110] = 44;

        short[] registers3 = new short[41];
        registers3[0] = 2200;
        registers3[1] = 2201;
        registers3[2] = 2202;
        registers3[3] = 2203;
        registers3[4] = 275;
        registers3[5] = 276;
        registers3[6] = 277;
        registers3[7] = 278;
        registers3[8] = 59;
        registers3[9] = 60;
        registers3[10] = 61;
        registers3[11] = 62;
        registers3[12] = 255;
        registers3[13] = 600;
        registers3[14] = 1;
        registers3[15] = 0;
        registers3[16] = 1;
        registers3[17] = 1;
        registers3[18] = 400;
        registers3[22] = 100;
        registers3[26] = 20;
        registers3[30] = 450;
        registers3[34] = 1;
        registers3[38] = 1;
        registers3[39] = 1;
        registers3[40] = 2;

        short[] registers1 = QCDeviceTest.parseModbusResponse(
                QCDeviceTest.hexStringToByteArray(hexData1.replaceAll(" ", "")));

        ReadHoldingRegistersResponse mockResponse1 = mock(ReadHoldingRegistersResponse.class);
        when(mockResponse1.getShortData()).thenReturn(registers1);
        ReadHoldingRegistersResponse mockResponse2 = mock(ReadHoldingRegistersResponse.class);
        when(mockResponse2.getShortData()).thenReturn(registers2);
        ReadHoldingRegistersResponse mockResponse3 = mock(ReadHoldingRegistersResponse.class);
        when(mockResponse3.getShortData()).thenReturn(registers3);

        when(mockModbusSource.readHoldingRegisters(eq(0), eq(110)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse1));
        when(mockModbusSource.readHoldingRegisters(eq(110), eq(123)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse2));
        when(mockModbusSource.readHoldingRegisters(eq(233), eq(41)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse3));

        device.markReady();
        // 直调 round（同包可见）：整链（块间节拍 + 第三块 + finishReadCycle）完成即回。
        // 节拍经 polling.delay(ms)：同包直调须自备未 start 的构建器实例（生产由
        // start() 两步构建注入 round）。块间节拍注入 1ms（生产 1s/800ms：设备性能
        // 要求，链路语义与节拍正交——三块全读+finishReadCycle 断言不受影响）
        device.secondBlockGapMs = 1L;
        device.thirdBlockGapMs = 1L;
        device.readRegisters(ModbusPolling.on(device, mockModbusSource), mockModbusSource)
                .get(10, TimeUnit.SECONDS);

        // round 链已被上方 .get(10s) join：finishReadCycle（含 updateCalulateAttr 二次标定写）
        // 在最内层 thenApply 内先于 future 完成执行，全部 midState 写与 .get() 返回之间
        // happens-before——读即终态，就绪是直接可断言的不变量，无需轮询等待（历史形态
        // fire-and-forget 调 readRegisters 无 join，才需要 Thread.sleep(50) 条件轮询）。
        assertAllStatesReady(device,
                "voltage_l1", "voltage_l2", "voltage_l3", "voltage_l4",
                "current_l1", "current_l2", "current_l3", "current_l4",
                "power_l1", "power_l2", "power_l3", "power_l4",
                "so2_film_changer_status", "o3_film_changer_status", "so2_film_ch1_switch_time",
                "temperature", "device_address", "relay_l1", "sample_tube_sampling_status");

        verify(mockModbusSource).readHoldingRegisters(eq(233), eq(41));

        assertEquals(11, ((Number) ((ModbusShortAttribute) device.getAttrs().get("so2_film_changer_status")).getState().getValue()).intValue());
        assertEquals(22, ((Number) ((ModbusShortAttribute) device.getAttrs().get("nox_film_changer_status")).getState().getValue()).intValue());
        assertEquals(33, ((Number) ((ModbusShortAttribute) device.getAttrs().get("co_film_changer_status")).getState().getValue()).intValue());
        assertEquals(44, ((Number) ((ModbusShortAttribute) device.getAttrs().get("o3_film_changer_status")).getState().getValue()).intValue());
        assertEquals(1, ((Number) ((ModbusShortAttribute) device.getAttrs().get("so2_film_changer_addr")).getState().getValue()).intValue());
        assertEquals(2, ((Number) ((ModbusShortAttribute) device.getAttrs().get("nox_film_changer_addr")).getState().getValue()).intValue());
        assertEquals("2026-08-15 14:30", device.getAttrs().get("so2_film_ch1_switch_time").getDisplayValue());
        assertEquals("2026-01-02 09:05", device.getAttrs().get("nox_film_ch3_switch_time").getDisplayValue());

        assertEquals(220.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l1")).getState().getValue(), 0.01f);
        assertEquals(220.1f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l2")).getState().getValue(), 0.01f);
        assertEquals(220.2f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l3")).getState().getValue(), 0.01f);
        assertEquals(220.3f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l4")).getState().getValue(), 0.01f);

        assertEquals(2.75f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("current_l1")).getState().getValue(), 0.01f);
        assertEquals(2.76f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("current_l2")).getState().getValue(), 0.01f);
        assertEquals(2.77f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("current_l3")).getState().getValue(), 0.01f);
        assertEquals(2.78f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("current_l4")).getState().getValue(), 0.01f);

        assertEquals(0.59f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("power_l1")).getState().getValue(), 0.01f);
        assertEquals(0.60f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("power_l2")).getState().getValue(), 0.01f);
        assertEquals(0.61f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("power_l3")).getState().getValue(), 0.01f);
        assertEquals(0.62f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("power_l4")).getState().getValue(), 0.01f);

        assertEquals(25.5f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("temperature")).getState().getValue(), 0.01f);
        assertEquals(60.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("humidity")).getState().getValue(), 0.01f);
        assertEquals("合闸", ((BinaryAttribute) device.getAttrs().get("relay_l1")).getDisplayValue());
        assertEquals("跳闸", ((BinaryAttribute) device.getAttrs().get("relay_l2")).getDisplayValue());
        assertEquals("on", ((BinaryAttribute) device.getAttrs().get("relay_l1")).getI18nValue(null));
        assertEquals("off", ((BinaryAttribute) device.getAttrs().get("relay_l2")).getI18nValue(null));
        assertEquals("on", ((BinaryAttribute) device.getAttrs().get("sample_tube_sampling_status")).getI18nValue(null));
        assertEquals("采样", device.getAttrs().get("sample_tube_sampling_status").getDisplayValue());
        assertEquals(40.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("temp_alarm_high_l1")).getState().getValue(), 0.01f);
        assertEquals(10.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("temp_alarm_low_l1")).getState().getValue(), 0.01f);
        assertEquals(20.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("startup_delay_l1")).getState().getValue(), 0.01f);
        assertEquals(45.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("temp_trip_high_l1")).getState().getValue(), 0.01f);
        assertEquals("on", ((BinaryAttribute) device.getAttrs().get("over_temp_protection_l1")).getI18nValue(null));
        assertEquals("启动", device.getAttrs().get("over_temp_protection_l1").getDisplayValue());
        assertEquals("normal", device.getAttrs().get("temp_humidity_comm_status").getI18nValue(null));
        assertEquals("正常", device.getAttrs().get("temp_humidity_comm_status").getDisplayValue());
        assertEquals("normal", device.getAttrs().get("electric_param_comm_status").getI18nValue(null));
        assertEquals("正常", device.getAttrs().get("electric_param_comm_status").getDisplayValue());
        assertEquals(2.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("device_address")).getState().getValue(), 0.01f);
    }

    @Test
    public void testParseBinaryAndAlarmSelectRegisters() throws Exception {
        short[] registers = new short[110];
        registers[13] = 1;
        registers[46] = 0;
        registers[53] = 1;
        registers[73] = 1;
        registers[74] = 1;
        registers[80] = 0;
        registers[81] = 1;
        registers[82] = 0;
        registers[83] = 1;
        registers[84] = 0;
        registers[85] = 1;
        registers[86] = 0;

        invokePrivateMethod(device, "parseBlockData", registers, 0);

        BinaryAttribute fan = (BinaryAttribute) device.getAttrs().get("fan_control");
        BinaryAttribute light = (BinaryAttribute) device.getAttrs().get("light_control");
        BinaryAttribute zeroGas = (BinaryAttribute) device.getAttrs().get("zero_gas_relay");
        assertEquals("on", fan.getI18nValue(null));
        assertEquals("off", light.getI18nValue(null));
        assertEquals("on", zeroGas.getI18nValue(null));
        assertEquals("开", fan.getDisplayValue());
        assertEquals("关", light.getDisplayValue());
        assertEquals("开", zeroGas.getDisplayValue());
        assertEquals("0", device.getAttrs().get("ac1_power").getI18nValue(null));
        assertEquals("1", device.getAttrs().get("ac2_power").getI18nValue(null));

        assertEquals("normal", device.getAttrs().get("infrared_status").getI18nValue(null));
        assertEquals("alarm", device.getAttrs().get("smoke_detector1").getI18nValue(null));
        assertEquals("normal", device.getAttrs().get("smoke_detector2").getI18nValue(null));
        assertEquals("alarm", device.getAttrs().get("temp_detector1").getI18nValue(null));
        assertEquals("normal", device.getAttrs().get("temp_detector2").getI18nValue(null));
        assertEquals("alarm", device.getAttrs().get("water_leak_detector").getI18nValue(null));
        assertEquals("正常", device.getAttrs().get("infrared_status").getDisplayValue());
        assertEquals("报警", device.getAttrs().get("smoke_detector1").getDisplayValue());
        // 采样管漏水：硬件未实际返回，V2 暂不采集
        assertNull(device.getAttrs().get("sample_tube_leak"));
    }

    @Test
    public void testFormatFilmSwitchTime() {
        assertEquals("2026-08-15 14:30", QCV2Device.formatFilmSwitchTime(
                (short) 2026, (short) 8, (short) 15, (short) 14, (short) 30));
        assertEquals("2026-01-02 09:05", QCV2Device.formatFilmSwitchTime(
                (short) 26, (short) 1, (short) 2, (short) 9, (short) 5));
        assertEquals("", QCV2Device.formatFilmSwitchTime(
                (short) 0, (short) 0, (short) 0, (short) 0, (short) 0));
        assertEquals("", QCV2Device.formatFilmSwitchTime(
                (short) 2026, (short) 13, (short) 1, (short) 0, (short) 0));
    }

    @Test
    public void testQCV2DeviceI18nDisplayNames() throws Exception {
        ResourceLoader.setLoadI18nResources(false);
        try {
            device.init();
            TestTools.assertAttributeDisplayName(device, "voltage_l1", "第1路U");
            TestTools.assertAttributeDisplayName(device, "voltage_l4", "第4路U");
            TestTools.assertAttributeDisplayName(device, "current_l1", "第1路I");
            TestTools.assertAttributeDisplayName(device, "current_l4", "第4路I");
            TestTools.assertAttributeDisplayName(device, "power_l1", "第1路P");
            TestTools.assertAttributeDisplayName(device, "power_l4", "第4路P");
            TestTools.assertAttributeDisplayName(device, "temperature", "采集温度值");
            TestTools.assertAttributeDisplayName(device, "humidity", "采集湿度值");
            TestTools.assertAttributeDisplayName(device, "relay_l1", "第1路继电器状态");
            TestTools.assertAttributeDisplayName(device, "relay_l4", "第4路继电器状态");
            TestTools.assertAttributeDisplayName(device, "temp_alarm_high_l1", "第1路温度异常上限");
            TestTools.assertAttributeDisplayName(device, "startup_delay_l1", "第1路开机启动延时");
            TestTools.assertAttributeDisplayName(device, "device_address", "设备地址");
            TestTools.assertAttributeDisplayName(device, "bench_temp", "站房温度");
            TestTools.assertAttributeDisplayName(device, "light_control", "灯");
            TestTools.assertAttributeDisplayName(device, "fan_control", "风机控制");
            TestTools.assertAttributeDisplayName(device, "infrared_status", "红外状态");
            TestTools.assertAttributeDisplayName(device, "smoke_detector1", "烟感1状态");
            TestTools.assertAttributeDisplayName(device, "so2_film_changer_addr", "SO2换膜器地址");
            TestTools.assertAttributeDisplayName(device, "nox_film_changer_status", "NOx换膜器状态");
            TestTools.assertAttributeDisplayName(device, "so2_film_ch1_switch_time", "SO2换膜器通道1切换时间");
            TestTools.assertAttributeDisplayName(device, "o3_gas_temp", "O3支管温度");
        } finally {
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) throw e;
            return findField(superClass, fieldName);
        }
    }

    private Object invokePrivateMethod(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Short) parameterTypes[i] = short.class;
            else if (args[i] instanceof Integer) parameterTypes[i] = int.class;
            else if (args[i] instanceof AttributeStatus) parameterTypes[i] = AttributeStatus.class;
            else parameterTypes[i] = args[i].getClass();
        }
        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) throw e;
            return findMethod(superClass, methodName, parameterTypes);
        }
    }

    /**
     * 断言 round 链 join 完成后全部属性 state 已就绪：finishReadCycle 先于 future 完成执行，
     * midState 写与 .get() 返回之间 happens-before，就绪是确定不变量（替代旧 Thread.sleep(50)
     * 条件轮询——那是 fire-and-forget 无 join 时代的产物）。
     */
    private static void assertAllStatesReady(QCV2Device device, String... ids) {
        StringBuilder missing = new StringBuilder();
        for (String id : ids) {
            AttributeBase<?> a = device.getAttrs().get(id);
            if (a == null || a.getState() == null || a.getState().getValue() == null) {
                missing.append(id).append(' ');
            }
        }
        assertTrue("round join 后属性 state 须全部就绪，未就绪: " + missing, missing.length() == 0);
    }
}
