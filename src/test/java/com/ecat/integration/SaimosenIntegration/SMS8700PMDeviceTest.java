package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.StateManager;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Tools;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SMS8700PMDevice 单元测试（Modbus 对外输出协议 v1.2 示例帧）。
 */
public class SMS8700PMDeviceTest {

    private SMS8700PMDevice device;
    private AutoCloseable mockitoCloseable;

    @Mock private ScheduledExecutorService mockExecutor;
    @Mock private ScheduledFuture<?> mockScheduledFuture;
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        ResourceLoader.setLoadI18nResources(false);

        device = new SMS8700PMDevice(createTestEntry());

        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        when(mockTaskManager.getExecutorService()).thenReturn(mockExecutor);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        IntegrationRegistry mockIntegrationRegistry = mock(IntegrationRegistry.class);
        when(mockEcatCore.getIntegrationRegistry()).thenReturn(mockIntegrationRegistry);
        when(mockIntegrationRegistry.getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);

        device.load(mockEcatCore);
        setPrivateField(device, "core", mockEcatCore);
        setPrivateField(device, "modbusSource", mockModbusSource);
        setPrivateField(device, "modbusIntegration", mockModbusIntegration);
        device.init();

        when(mockEcatCore.getStateManager()).thenReturn(mock(StateManager.class));
        device.markReady();
    }

    @After
    public void tearDown() throws Exception {
        ResourceLoader.setLoadI18nResources(true);
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "SMS8700测试设备");
        config.put("class", "air.monitor.pm");
        config.put("model", "SMS8700");
        config.put("modbus_protocol", "RTU");

        Map<String, Object> serialSettings = new HashMap<>();
        serialSettings.put("serial_port", "COM3");
        serialSettings.put("baudrate", "9600");
        serialSettings.put("data_bits", "8");
        serialSettings.put("stop_bits", "1");
        serialSettings.put("parity", "NONE");
        serialSettings.put("timeout", 2000);

        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("serial_settings", serialSettings);
        commSettings.put("slave_id", 1);
        config.put("comm_settings", commSettings);

        return new ConfigEntry.Builder()
                .entryId("test-entry-sms8700")
                .coordinate("com.ecat:integration-saimosen")
                .uniqueId("saimosen_air.monitor.pm_SMS8700")
                .title("SMS8700测试设备")
                .data(config)
                .build();
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
            if (superClass == null) {
                throw e;
            }
            return findField(superClass, fieldName);
        }
    }

    /** 协议文档 5.1 / 5.2 / 5.3 节示例数据。 */
    static short[] protocolExampleRegisters() {
        short[] regs = new short[SMS8700PMDevice.REG_BLOCK_COUNT];
        putFloat(regs, SMS8700PMDevice.IDX_PM1, 3.58f);
        putFloat(regs, SMS8700PMDevice.IDX_PM25, 5.98f);
        putFloat(regs, SMS8700PMDevice.IDX_PM4, 6.39f);
        putFloat(regs, SMS8700PMDevice.IDX_PM10, 20.81f);
        putFloat(regs, SMS8700PMDevice.IDX_PMTOT, 29.22f);
        regs[SMS8700PMDevice.IDX_DATA_VALID] = 1;
        regs[SMS8700PMDevice.IDX_SECONDS_SINCE_PM] = 3;
        regs[SMS8700PMDevice.IDX_MAP_VERSION] = 3;
        regs[SMS8700PMDevice.IDX_DEVICE_STATUS] = 3; // bit0 + bit1
        putFloat(regs, SMS8700PMDevice.IDX_SAMPLE_FLOW, 4.6f);
        putFloat(regs, SMS8700PMDevice.IDX_SAMPLE_TEMP, 20.81f);
        putFloat(regs, SMS8700PMDevice.IDX_SAMPLE_HUMI, 40.60f);
        putFloat(regs, SMS8700PMDevice.IDX_ENV_TEMP, 25.00f);
        putFloat(regs, SMS8700PMDevice.IDX_ENV_HUMI, 40.60f);
        putFloat(regs, SMS8700PMDevice.IDX_PRESSURE, 1001.0f);
        regs[SMS8700PMDevice.IDX_SLAVE_ADDR] = 1;
        regs[27] = 0;
        return regs;
    }

    private static void putFloat(short[] regs, int index, float value) {
        short[] words = Tools.convertFloatToBigEndianShorts(value);
        regs[index] = words[0];
        regs[index + 1] = words[1];
    }

    private void verifyFloat(String attrId, double expected) {
        NumericAttribute attr = (NumericAttribute) device.getAttrs().get(attrId);
        assertNotNull(attrId + " should exist", attr);
        assertNotNull(attrId + " value", attr.getState().getValue());
        assertEquals(attrId, expected, ((Number) attr.getState().getValue()).doubleValue(), 0.02);
    }

    @Test
    public void testInit_CreatesAttributes() {
        assertNotNull(device.getAttrs().get("pm1"));
        assertNotNull(device.getAttrs().get("pm2_5"));
        assertNotNull(device.getAttrs().get("pm4"));
        assertNotNull(device.getAttrs().get("pm10"));
        assertNotNull(device.getAttrs().get("pm_tot"));
        assertNotNull(device.getAttrs().get("sample_flow"));
        assertNotNull(device.getAttrs().get("ambient_temperature"));
        assertNotNull(device.getAttrs().get("ambient_humidity"));
        assertNotNull(device.getAttrs().get("barometric_pressure"));
        assertNotNull(device.getAttrs().get("pm_manual_status"));
        assertNotNull(device.getAttrs().get("pm_status"));
        assertNotNull(device.getAttrs().get("general_alarm"));
    }

    @Test
    public void testApplyRegisters_ProtocolExample() {
        assertTrue(device.applyRegisters(protocolExampleRegisters()));

        verifyFloat("pm1", 3.58);
        verifyFloat("pm2_5", 5.98);
        verifyFloat("pm4", 6.39);
        verifyFloat("pm10", 20.81);
        verifyFloat("pm_tot", 29.22);
        verifyFloat("sample_flow", 4.6);
        verifyFloat("sample_temp", 20.81);
        verifyFloat("sample_humidity", 40.60);
        verifyFloat("ambient_temperature", 25.00);
        verifyFloat("ambient_humidity", 40.60);
        verifyFloat("barometric_pressure", 1001.0);
        verifyFloat("data_valid", 1);
        verifyFloat("seconds_since_pm", 3);
        verifyFloat("map_version", 3);
        verifyFloat("device_status_bits", 3);
        verifyFloat("slave_addr_echo", 1);

        assertEquals(AttributeStatus.NORMAL, device.getAttrs().get("pm10").getState().getStatus());
        assertEquals(AttributeStatus.NORMAL, device.getAttrs().get("pm2_5").getState().getStatus());
    }

    @Test
    public void testApplyRegisters_InvalidPmData() {
        short[] regs = protocolExampleRegisters();
        regs[SMS8700PMDevice.IDX_DATA_VALID] = 0;
        regs[SMS8700PMDevice.IDX_DEVICE_STATUS] = (short) (SMS8700PMDevice.STATUS_BIT_PM_VALID
                | SMS8700PMDevice.STATUS_BIT_CTRL_VALID
                | SMS8700PMDevice.STATUS_BIT_PM_STALE);

        assertTrue(device.applyRegisters(regs));
        assertEquals(AttributeStatus.MALFUNCTION, device.getAttrs().get("pm10").getState().getStatus());
        // 控制板仍有效：工况保持 NORMAL
        assertEquals(AttributeStatus.NORMAL, device.getAttrs().get("sample_flow").getState().getStatus());
        verifyFloat("sample_flow", 4.6);
    }

    @Test
    public void testApplyRegisters_TooShort() {
        assertFalse(device.applyRegisters(new short[10]));
        assertEquals(AttributeStatus.MALFUNCTION, device.getAttrs().get("pm10").getState().getStatus());
    }

    @Test
    public void testReadFloatBe_MatchesProtocolHex() {
        // PM2.5 = 5.98 → 40BF5C29 → regs 16575, 23593
        short hi = (short) 16575;
        short lo = (short) 23593;
        float v = Tools.convertBigEndianToFloat(hi, lo);
        assertEquals(5.98f, v, 0.01f);
    }

    @Test
    public void testStart_SchedulesPoll() {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(10L), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenAnswer(inv -> mockScheduledFuture);
        device.start();
        verify(mockExecutor).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(10L), eq(java.util.concurrent.TimeUnit.SECONDS));
        device.stop();
        verify(mockScheduledFuture).cancel(true);
    }

    @Test
    public void testReadAndUpdate_ViaModbus() throws Exception {
        short[] regs = protocolExampleRegisters();
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        when(response.getShortData()).thenReturn(regs);
        when(mockModbusSource.readHoldingRegisters(
                SMS8700PMDevice.REG_BLOCK_START, SMS8700PMDevice.REG_BLOCK_COUNT))
                .thenReturn(CompletableFuture.completedFuture(response));

        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(10L), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenAnswer(inv -> {
                    Runnable r = inv.getArgument(0);
                    r.run();
                    return mockScheduledFuture;
                });

        device.start();
        Thread.sleep(100);
        verifyFloat("pm10", 20.81);
        verifyFloat("pm2_5", 5.98);
    }
}
