// Copyright (c) ecat
package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QCV2Device 单元测试：验证相对 QCDevice 新增的智能稳压电源四路 U/I/P 接入。
 */
public class QCV2DeviceTest {

    private QCV2Device device;
    private AutoCloseable mockitoCloseable;

    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    private ScheduledExecutorService realExecutor;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        realExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

        device = new QCV2Device(createTestEntry());

        setPrivateField(device, "core", mockEcatCore);
        setPrivateField(device, "modbusSource", mockModbusSource);
        setPrivateField(device, "modbusIntegration", mockModbusIntegration);

        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        when(mockTaskManager.getExecutorService()).thenReturn(realExecutor);

        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        device.init();
    }

    @After
    public void tearDown() throws Exception {
        if (realExecutor != null) {
            realExecutor.shutdownNow();
        }
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
    public void testInit_CreatesSmartPowerSupplyAttributes() {
        // 继承原版关键属性
        assertNotNull(device.getAttrs().get("system_state"));
        assertNotNull(device.getAttrs().get("pm2_5_working_flow"));

        // V2 新增智能稳压电源四路 U/I/P
        for (int i = 1; i <= 4; i++) {
            assertNotNull("voltage_l" + i, device.getAttrs().get("voltage_l" + i));
            assertNotNull("current_l" + i, device.getAttrs().get("current_l" + i));
            assertNotNull("power_l" + i, device.getAttrs().get("power_l" + i));
        }
    }

    @Test
    public void testThirdBlockCoversPowerSupplyRegisters() throws Exception {
        Method start = QCDevice.class.getDeclaredMethod("getThirdBlockStart");
        Method count = QCDevice.class.getDeclaredMethod("getThirdBlockRegisterCount");
        start.setAccessible(true);
        count.setAccessible(true);
        assertEquals(233, ((Integer) start.invoke(device)).intValue());
        assertEquals(12, ((Integer) count.invoke(device)).intValue());
    }

    @Test
    public void testRegisterExtendedAttributeMap_AddsPowerSupplyAttrs() throws Exception {
        Method m = QCDevice.class.getDeclaredMethod("registerExtendedAttributeMap");
        m.setAccessible(true);
        m.invoke(device);
        for (int i = 1; i <= 4; i++) {
            assertNotNull(device.getAttrs().get("voltage_l" + i));
            assertNotNull(device.getAttrs().get("current_l" + i));
            assertNotNull(device.getAttrs().get("power_l" + i));
        }
    }

    @Test
    public void testReadRegisters_ParsesSmartPowerSupplyData() throws Exception {
        // 第一块：110 寄存器
        String hexData1 = "01 03 DC 00 00 41 CC CC CD 42 3F 33 33 41 FF 33 33 42 28 00 00 3F A6 66 66 00 00 00 00 00 00 43 65 19 9A 43 64 80 00 43 66 CC CD 40 98 51 EC 40 93 D7 0A 3E C7 AE 14 44 19 00 00 44 47 80 00 42 8E 00 00 C4 31 C0 00 C1 C8 00 00 C2 04 00 00 3E E8 F5 C3 3F 7F BE 77 3F 65 A1 CB 42 48 0A 3D 00 01 00 00 00 18 00 01 00 01 00 17 00 01 00 00 00 00 00 19 00 00 00 01 00 17 00 00 46 1B 9E 13 46 69 35 D0 44 E1 43 78 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 01 00 01 00 01 00 01 00 01 00 01 00 00 00 00 43 67 19 9A 43 5C 33 33 00 0A 42 48 00 00 00 00 00 00 00 00 00 00 00 00 00 07 00 11 3C A3 D7 0A 00 05 00 00 00 00 3D CC CC CD 81 7C";

        // 第二块：123 寄存器（全 0，byteCount=0xF6）
        StringBuilder hex2 = new StringBuilder("01 03 F6 ");
        for (int i = 0; i < 123; i++) {
            hex2.append("00 00 ");
        }
        hex2.append("00 00");

        // 第三块：12 个 U16（233~244），byteCount=0x18
        // U 寄存器 2200..2203（显示 220.0~220.3V），I=10..13，P=1000..1003
        String hex3 = "01 03 18 "
                + "08 98 08 99 08 9A 08 9B "
                + "00 0A 00 0B 00 0C 00 0D "
                + "03 E8 03 E9 03 EA 03 EB "
                + "00 00";

        short[] registers1 = QCDeviceTest.parseModbusResponse(
                QCDeviceTest.hexStringToByteArray(hexData1.replaceAll(" ", "")));
        short[] registers2 = QCDeviceTest.parseModbusResponse(
                QCDeviceTest.hexStringToByteArray(hex2.toString().replaceAll(" ", "")));
        short[] registers3 = QCDeviceTest.parseModbusResponse(
                QCDeviceTest.hexStringToByteArray(hex3.replaceAll(" ", "")));

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
        when(mockModbusSource.readHoldingRegisters(eq(233), eq(12)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse3));

        invokePrivateMethod(device, "readRegisters");

        waitForAsyncOperation(() -> {
            for (int i = 1; i <= 4; i++) {
                AttributeBase<?> v = device.getAttrs().get("voltage_l" + i);
                AttributeBase<?> c = device.getAttrs().get("current_l" + i);
                AttributeBase<?> p = device.getAttrs().get("power_l" + i);
                if (v == null || v.getState() == null || v.getState().getValue() == null) return false;
                if (c == null || c.getState() == null || c.getState().getValue() == null) return false;
                if (p == null || p.getState() == null || p.getState().getValue() == null) return false;
            }
            return true;
        }, 3000);

        assertEquals(220.0f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l1")).getState().getValue(), 0.01f);
        assertEquals(220.1f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l2")).getState().getValue(), 0.01f);
        assertEquals(220.2f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l3")).getState().getValue(), 0.01f);
        assertEquals(220.3f, (Float) ((ModbusScalableFloatSRAttribute) device.getAttrs().get("voltage_l4")).getState().getValue(), 0.01f);

        assertEquals(10, ((Number) ((ModbusShortAttribute) device.getAttrs().get("current_l1")).getState().getValue()).intValue());
        assertEquals(11, ((Number) ((ModbusShortAttribute) device.getAttrs().get("current_l2")).getState().getValue()).intValue());
        assertEquals(12, ((Number) ((ModbusShortAttribute) device.getAttrs().get("current_l3")).getState().getValue()).intValue());
        assertEquals(13, ((Number) ((ModbusShortAttribute) device.getAttrs().get("current_l4")).getState().getValue()).intValue());

        assertEquals(1000, ((Number) ((ModbusShortAttribute) device.getAttrs().get("power_l1")).getState().getValue()).intValue());
        assertEquals(1001, ((Number) ((ModbusShortAttribute) device.getAttrs().get("power_l2")).getState().getValue()).intValue());
        assertEquals(1002, ((Number) ((ModbusShortAttribute) device.getAttrs().get("power_l3")).getState().getValue()).intValue());
        assertEquals(1003, ((Number) ((ModbusShortAttribute) device.getAttrs().get("power_l4")).getState().getValue()).intValue());
    }

    @Test
    public void testQCV2DeviceI18nDisplayNames_ForPowerSupplyAttrs() throws Exception {
        ResourceLoader.setLoadI18nResources(false);
        try {
            device.init();
            TestTools.assertAttributeDisplayName(device, "voltage_l1", "第1路U");
            TestTools.assertAttributeDisplayName(device, "voltage_l4", "第4路U");
            TestTools.assertAttributeDisplayName(device, "current_l1", "第1路I");
            TestTools.assertAttributeDisplayName(device, "current_l4", "第4路I");
            TestTools.assertAttributeDisplayName(device, "power_l1", "第1路P");
            TestTools.assertAttributeDisplayName(device, "power_l4", "第4路P");
            TestTools.assertAttributeDisplayName(device, "bench_temp", "站房温度");
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

    private void waitForAsyncOperation(Supplier<Boolean> condition, long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (!condition.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new AssertionError("异步操作超时，等待了" + timeoutMs + "ms");
            }
            Thread.sleep(50);
        }
    }
}
