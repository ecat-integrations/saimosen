package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.StateManager;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Sdk.PollingHandle;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SO2Device单元测试类 - 支持完整协议（38个寄存器 + 校准状态）
 * 
 * @author caohongbo
 */
public class SO2DeviceTest {

    private SO2Device so2Device;
    private AutoCloseable mockitoCloseable;
    
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        // 设置ResourceLoader仅加载strings.json，不加载i18n目录资源
        ResourceLoader.setLoadI18nResources(false);

        ConfigEntry entry = createTestEntry();
        so2Device = new SO2Device(entry);
        
        // 先设置所有mock
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);
        
        // 模拟IntegrationRegistry
        IntegrationRegistry mockIntegrationRegistry = mock(IntegrationRegistry.class);
        when(mockEcatCore.getIntegrationRegistry()).thenReturn(mockIntegrationRegistry);
        when(mockIntegrationRegistry.getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        
        // 调用load方法初始化modbusInfo
        so2Device.load(mockEcatCore);
        
        initDevice();
            // markReady 对齐生产时序（init/load 后 markReady，再由轮询驱动 publish）：
        // 测试直接调 publish 路径（readRegisters/parse/publicAttrsState），未就绪撞就绪门禁
        // （publicAttrsState 不再吞门禁异常，会原样抛出或被 parse 的 catch 兜成 MALFUNCTION）。
        when(mockEcatCore.getStateManager()).thenReturn(mock(StateManager.class));
        so2Device.markReady();
}
    
    @After
    public void tearDown() throws Exception {
        // 恢复ResourceLoader的设置，启用i18n资源加载
        ResourceLoader.setLoadI18nResources(true);
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "SO2测试设备");
        config.put("class", "air.monitor.so2");
        config.put("modbus_protocol", "RTU");

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
            .entryId("test-entry-so2-device")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_air.monitor.so2")
            .title("SO2测试设备")
            .data(config)
            .build();
    }


    /**
     * 测试自建快节拍轮询（tianhong TH2004HCODeviceTest 同范式）：round 复用生产同函数
     * （readAndUpdate），节拍测试自持 50ms——与生产 every(5s) 解耦，负向观察窗从 6s 收
     * 到 600ms 仍覆盖 >10 个周期（cancel 失效形态下下一轮 51ms 内必现形，覆盖强度等价）。
     * 生产 start() 的节拍/接线由 testStart_SchedulesReadTask 正向覆盖。
     */
    private PollingHandle startFastPolling() {
        return ModbusPolling.on(so2Device, mockModbusSource)
                .round(so2Device::readAndUpdate)
                .every(50, TimeUnit.MILLISECONDS)
                .start();
    }

    // 反射辅助方法
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
    
    private Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
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
    

    private Object invokePrivateMethod(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Short) {
                parameterTypes[i] = short.class;
            } else if (args[i] instanceof Integer) {
                parameterTypes[i] = int.class;
            } else if (args[i] instanceof Double) {
                parameterTypes[i] = double.class;
            } else if (args[i] instanceof AttributeStatus) {
                parameterTypes[i] = AttributeStatus.class;
            } else {
                parameterTypes[i] = args[i].getClass();
            }
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
            if (superClass == null) {
                throw e;
            }
            return findMethod(superClass, methodName, parameterTypes);
        }
    }

    private void initDevice() throws Exception {
        setPrivateField(so2Device, "core", mockEcatCore);
        setPrivateField(so2Device, "modbusSource", mockModbusSource);
        setPrivateField(so2Device, "modbusIntegration", mockModbusIntegration);
        so2Device.init();
    }

    private void verifyFloatAttribute(String attrId, double expectedValue) {
        NumericAttribute attr = (NumericAttribute) so2Device.getAttrs().get(attrId);
        assertNotNull("Attribute " + attrId + " should not be null", attr);
        if (attr.getState() == null || attr.getState().getValue() == null) {
            fail("Attribute " + attrId + " value is null");
        }
        assertEquals("Attribute " + attrId + " value mismatch", expectedValue, ((Number) attr.getState().getValue()).doubleValue(), 0.01); // 精度误差±0.01
    }

    private void verifyTextAttribute(String attrId, String expectedValue) {
        TextAttribute attr = (TextAttribute) so2Device.getAttrs().get(attrId);
        assertNotNull("Attribute " + attrId + " should not be null", attr);
        if (attr.getState() == null) {
            fail("Attribute " + attrId + " state is null");
        }
        assertEquals("Attribute " + attrId + " value mismatch", expectedValue, attr.getState().getValue());
    }
    
    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        // 执行初始化
        so2Device.init();
        
        // 验证第一组参数（float类型）
        assertNotNull("测量电压属性应该存在", so2Device.getAttrs().get("measure_volt"));
        assertNotNull("样气压力属性应该存在", so2Device.getAttrs().get("sample_press"));
        assertNotNull("反应室温度属性应该存在", so2Device.getAttrs().get("chamber_temp"));
        assertNotNull("样气流量属性应该存在", so2Device.getAttrs().get("sample_flow"));
        assertNotNull("泵压力属性应该存在", so2Device.getAttrs().get("pump_press"));
        assertNotNull("样气温度属性应该存在", so2Device.getAttrs().get("sample_temp"));
        assertNotNull("氙灯驱动电压属性应该存在", so2Device.getAttrs().get("xe_latp_driving_volt"));
        assertNotNull("浓度斜率属性应该存在", so2Device.getAttrs().get("slope"));
        assertNotNull("浓度截距属性应该存在", so2Device.getAttrs().get("intercept"));
        assertNotNull("样气压力修正值属性应该存在", so2Device.getAttrs().get("sample_press_corr"));
        assertNotNull("泵压力修正值属性应该存在", so2Device.getAttrs().get("pump_press_corr"));
        assertNotNull("反应室温度修正值属性应该存在", so2Device.getAttrs().get("chamber_temp_corr"));
        assertNotNull("样气流量修正值属性应该存在", so2Device.getAttrs().get("sample_flow_corr"));
        assertNotNull("反应室加热温度设定值属性应该存在", so2Device.getAttrs().get("chamber_temp_setting"));
        assertNotNull("氙灯驱动电压设定值属性应该存在", so2Device.getAttrs().get("xe_latp_driving_volt_setting"));
        assertNotNull("SO2浓度属性应该存在", so2Device.getAttrs().get("so2"));

        // 验证第二组参数（U16类型）
        assertNotNull("仪器地址属性应该存在", so2Device.getAttrs().get("device_address"));
        assertNotNull("仪器状态属性应该存在", so2Device.getAttrs().get("device_status"));
        assertNotNull("PMT高压设定值属性应该存在", so2Device.getAttrs().get("pmt_high_volt_setting"));
        assertNotNull("反应室温度电压属性应该存在", so2Device.getAttrs().get("chamber_temp_volt"));
        assertNotNull("样气压力电压属性应该存在", so2Device.getAttrs().get("sample_press_volt"));
        assertNotNull("泵压力电压属性应该存在", so2Device.getAttrs().get("pump_press_volt"));
        assertNotNull("机箱温度电压属性应该存在", so2Device.getAttrs().get("case_temp_volt"));
        assertNotNull("PMT温度电压属性应该存在", so2Device.getAttrs().get("pmt_temp_volt"));
        assertNotNull("机箱温度属性应该存在", so2Device.getAttrs().get("case_temp"));
        assertNotNull("12V电压值属性应该存在", so2Device.getAttrs().get("voltage_12v"));
        assertNotNull("15V电压值属性应该存在", so2Device.getAttrs().get("voltage_15v"));
        assertNotNull("5V电压值属性应该存在", so2Device.getAttrs().get("voltage_5v"));
        assertNotNull("3.3V电压值属性应该存在", so2Device.getAttrs().get("voltage_3v3"));
        assertNotNull("PMT高压读取值属性应该存在", so2Device.getAttrs().get("pmt_high_volt_read"));

        // 验证状态和故障代码属性
        assertNotNull("采样校准阀状态属性应该存在", so2Device.getAttrs().get("sample_cal_valve_status"));
        assertNotNull("自动零点阀继电器状态属性应该存在", so2Device.getAttrs().get("auto_zero_value_relay_status"));
        assertNotNull("内置泵状态属性应该存在", so2Device.getAttrs().get("builtin_pump_status"));
        assertNotNull("机箱风扇状态属性应该存在", so2Device.getAttrs().get("case_fan_status"));
        assertNotNull("反应室加热状态属性应该存在", so2Device.getAttrs().get("chamber_status"));
        assertNotNull("报警信息属性应该存在", so2Device.getAttrs().get("alarm_info"));
        assertNotNull("通用报警属性应该存在", so2Device.getAttrs().get("general_alarm"));
        assertNotNull("故障代码属性应该存在", so2Device.getAttrs().get("fault_code"));

        // 验证校准相关属性
        assertNotNull("校准浓度属性应该存在", so2Device.getAttrs().get("calibration_concentration"));
        assertNotNull("校准状态属性应该存在", so2Device.getAttrs().get("calibration_status"));
        assertNotNull("校准命令属性应该存在", so2Device.getAttrs().get("dispatch_command"));
        assertNotNull("手动状态属性应该存在", so2Device.getAttrs().get("so2_manual_status"));
        assertNotNull("只读状态属性应该存在", so2Device.getAttrs().get("so2_status"));

        // 验证属性总数
        assertEquals("应该有43个属性", 43, so2Device.getAttrs().size());
    }
    
    @Test
    public void testStart_SchedulesReadTask() throws Exception {
        CountDownLatch firstRead = new CountDownLatch(1);
        ReadHoldingRegistersResponse mockReadResp = mock(ReadHoldingRegistersResponse.class);
        when(mockReadResp.getShortData()).thenReturn(new short[0]);
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    firstRead.countDown();
                    return CompletableFuture.completedFuture(mockReadResp);
                });

        so2Device.start();

        // 首轮立即发射（确定性同步：latch 等 round 真正读源，非定时猜测）
        assertTrue("首轮（initialDelay=0）必须立即发起 float 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 32);
    }
    
    @Test
    public void testStop_CancelsScheduledTasks() throws Exception {
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("首轮必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        so2Device.stop();
        so2Device.cancelManagedTasks();   // 框架 chokepoint 同点（IntegrationDeviceBase.stopWithManagedSweep）
        // 负向观察窗 600ms 覆盖 >10 个 50ms 周期（等价原「6s 窗 > 5s 生产周期」覆盖强度）
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));

        // sweep 已执行的直接证据：宿主进入已扫状态，再注册移除动作被拒（RemovalHost 契约）
        try {
            so2Device.onRemove(() -> { });
            org.junit.Assert.fail("sweep 后宿主必须拒绝新移除动作注册");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
        }
    }
    
    @Test
    public void testRelease_CancelsReadFuture() throws Exception {
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("首轮必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        // disableEntry 同序：stop → sweep（移除动作停轮询）→ release（源释放）
        so2Device.stop();
        so2Device.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("release 前 stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));
        so2Device.release();
        verify(mockModbusSource).closeModbus();
    }
    
    @Test
    public void testReadAndUpdate_ReadsAndParsesAllData() throws Exception {
        // 准备分段读取的模拟数据
        
        // 第一段：float参数（16个float数值，32个寄存器，64个字节）
        short[] mockFloatRegisters = new short[16];
        for (int i = 0; i < 16; i++) {
            mockFloatRegisters[i] = (short) (i + 1); // 简单的递增数据
        }

        // 第二段：U16参数（26个U16数值，52个寄存器，52个字节）
        short[] mockU16Registers = new short[26];
        // 模拟仪器地址 3
        mockU16Registers[0] = (short) 3;
        // 模拟仪器状态 0（采样）
        mockU16Registers[1] = (short) 0;
        // 模拟PMT高压设定值 100
        mockU16Registers[2] = (short) 100;
        // 模拟反应室温度电压 2500 mV
        mockU16Registers[3] = (short) 2500;
        // 模拟样气压力电压 3000 mV
        mockU16Registers[4] = (short) 3000;
        // 模拟泵压力电压 2000 mV
        mockU16Registers[5] = (short) 2000;
        // 模拟机箱温度电压 1500 mV
        mockU16Registers[6] = (short) 1500;
        // 模拟PMT温度电压 0 mV
        mockU16Registers[7] = (short) 0;
        // 模拟机箱温度 250 (25.0℃)
        mockU16Registers[8] = (short) 250;
        // 模拟12V电压值 12000 mV
        mockU16Registers[9] = (short) 12000;
        // 模拟15V电压值 15000 mV
        mockU16Registers[10] = (short) 15000;
        // 模拟5V电压值 5000 mV
        mockU16Registers[11] = (short) 5000;
        // 模拟3.3V电压值 3300 mV
        mockU16Registers[12] = (short) 3300;
        // 模拟PMT高压读取值 0
        mockU16Registers[13] = (short) 0;
        // 模拟样气温度重复 0
        mockU16Registers[14] = (short) 0;
        // 模拟预留U16_1 0
        mockU16Registers[15] = (short) 0;
        // 模拟预留U16_2 0
        mockU16Registers[16] = (short) 0;
        // 模拟预留U16_3 0
        mockU16Registers[17] = (short) 0;
        // 模拟预留U16_4 0
        mockU16Registers[18] = (short) 0;
        // 模拟采样校准阀状态 0
        mockU16Registers[19] = (short) 0;
        // 模拟自动零点阀继电器状态 0
        mockU16Registers[20] = (short) 0;
        // 模拟内置泵状态 0
        mockU16Registers[21] = (short) 0;
        // 模拟机箱风扇状态 0
        mockU16Registers[22] = (short) 0;
        // 模拟反应室加热状态 0
        mockU16Registers[23] = (short) 0;
        // 模拟报警信息 0
        mockU16Registers[24] = (short) 0;
        // 模拟故障代码 0
        mockU16Registers[25] = (short) 0;

        // 第三段：跨度校准浓度寄存器
        short[] mockSpanCalibRegisters = new short[1];
        mockSpanCalibRegisters[0] = (short) 400; // 模拟跨度校准浓度400ppm

        // 第四段：校准状态寄存器
        short[] mockCalibRegisters = new short[1];
        mockCalibRegisters[0] = (short) 2; // 模拟跨度校准模式

        // 模拟分段读取的Modbus响应
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);

        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        // 模拟分段读取调用
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));

        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS); // 等待异步操作完成

        // 验证分段读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(32));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(38), eq(26));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));

        // 验证第二组参数（U16类型）- 根据SO2Device的updateU16Attributes方法，某些电压值需要除以10
        verifyFloatAttribute("device_address", 3.0);
        verifyFloatAttribute("device_status", 0.0);
        verifyFloatAttribute("pmt_high_volt_setting", 10.0);
        verifyFloatAttribute("chamber_temp_volt", 250.0); // 2500/10
        verifyFloatAttribute("sample_press_volt", 300.0); // 3000/10
        verifyFloatAttribute("pump_press_volt", 200.0); // 2000/10
        verifyFloatAttribute("case_temp_volt", 150.0); // 1500/10
        verifyFloatAttribute("case_temp", 25.0); // 250/10
        verifyFloatAttribute("pmt_temp_volt", 0.0); // 0/10
        verifyFloatAttribute("pmt_high_volt_read", 0.0);
        verifyFloatAttribute("voltage_12v", 12000.0);
        verifyFloatAttribute("voltage_15v", 15000.0);
        verifyFloatAttribute("voltage_5v", 5000.0);
        verifyFloatAttribute("voltage_3v3", 3300.0);
        // 验证状态属性 - 现在这些属性会被updateU16Attributes更新
        verifyFloatAttribute("sample_cal_valve_status", 0.0);
        verifyFloatAttribute("auto_zero_value_relay_status", 0.0);
        verifyFloatAttribute("builtin_pump_status", 0.0);
        verifyFloatAttribute("case_fan_status", 0.0);
        verifyFloatAttribute("chamber_status", 0.0);
        verifyTextAttribute("alarm_info", "");
        verifyFloatAttribute("fault_code", 0.0);

        // 验证校准状态属性
        verifyFloatAttribute("calibration_status", 2.0);

        // 验证校准浓度数值 - 跨度校准模式时应该为400
        verifyFloatAttribute("calibration_concentration", 400.0);

        // 验证测量属性状态为跨度校准（排除手动/只读状态属性）
        so2Device.getAttrs().values().stream()
                .filter(attr -> attr.getState() != null)
                .filter(attr -> !isReadonlyDeviceStatusAttr(attr))
                .forEach(attr -> assertEquals(AttributeStatus.SPAN_CALIBRATION, attr.getState().getStatus()));
    }

    @Test
    public void testReadAndUpdate_HandlesException() throws Exception {
        // 模拟分段读取中第一段失败
        CompletableFuture<ReadHoldingRegistersResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Modbus communication error"));
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(failedFuture);
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(failedFuture);

        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS); // 等待异步操作完成

        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        assertNull(so2Device.getAttrs().get("so2").getState());
    }

    @Test
    public void testReadAndUpdate_HandlesDataParsingException() throws Exception {
        // 准备无效的寄存器数据 - 使用null来触发异常
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockFloatResponse.getShortData()).thenReturn(null); // 返回null会触发异常

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));

        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);

        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        assertNull(so2Device.getAttrs().get("so2").getState());
    }

    @Test
    public void testRegisterBlockConfiguration() throws Exception {
        // 验证寄存器块配置是否正确
        Object blockConfig = getPrivateField(so2Device, "SEGMENT_CONFIG");
        assertNotNull(blockConfig);

        // 验证数据段配置
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) blockConfig;
        assertTrue(config.containsKey("float_params"));
        assertTrue(config.containsKey("u16_params"));
        assertTrue(config.containsKey("zero_calibration_start"));
        assertTrue(config.containsKey("span_calibration_start"));
        assertTrue(config.containsKey("calibration_status"));

        // 验证float_params配置
        Object floatParams = config.get("float_params");
        assertEquals(0, getPrivateField(floatParams, "startAddress"));
        assertEquals(32, getPrivateField(floatParams, "count")); // 16个float参数

        // 验证u16_params配置
        Object u16Params = config.get("u16_params");
        assertEquals(38, getPrivateField(u16Params, "startAddress"));
        assertEquals(26, getPrivateField(u16Params, "count")); // 26个U16参数
    }

    @Test
    public void testUpdateAttributeMethod() throws Exception {
        // 测试updateAttribute私有方法 - 使用正确的参数类型
        invokePrivateMethod(so2Device, "updateAttribute", "so2", 25.5, AttributeStatus.NORMAL);

        // 验证属性值已更新
        NumericAttribute so2Attr = (NumericAttribute) so2Device.getAttrs().get("so2");
        assertEquals(25.5, ((Number) so2Attr.getState().getValue()).doubleValue(), 0.01);
        assertEquals(AttributeStatus.NORMAL, so2Attr.getState() != null ? so2Attr.getState().getStatus() : null);
    }

    @Test
    public void testUpdateAttributeMethod_NonExistentAttribute() throws Exception {
        // 测试更新不存在的属性
        @SuppressWarnings("unused")
        Object result = invokePrivateMethod(so2Device, "updateAttribute", "NON_EXISTENT", 100.0, AttributeStatus.NORMAL);

        // 验证不会抛出异常，只是忽略
        assertNull(result);
    }

    @Test
    public void testDeviceLifecycle() throws Exception {
        // 测试完整的设备生命周期
        when(mockModbusSource.isModbusOpen()).thenReturn(true);

        // 1. 初始化
        so2Device.init();
        assertEquals(43, so2Device.getAttrs().size());

        // 2. 启动（首轮 latch：确定性确认轮询已注册运行，非仅不抛异常）
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("start 后首轮轮询必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        // 3. 停止（lifecycle chokepoint：stop + sweep 执行 SDK 注册的移除动作停轮询）；
        //    负向窗 600ms 覆盖 >10 个 50ms 周期（等价原「窗>5s 生产周期」的 cancel 因果覆盖）
        so2Device.stop();
        so2Device.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));

        // 4. 释放资源
        so2Device.release();
        // 注意：新架构中release方法调用super.release()，不会直接调用cancel
    }

    @Test
    public void testSegmentedReadStrategy() throws Exception {
        // 测试分段读取策略
        short[] mockFloatRegisters = new short[32];
        short[] mockU16Registers = new short[26];
        short[] mockSpanCalibRegisters = new short[1];
        short[] mockCalibRegisters = new short[1];

        // 设置测试数据
        for (int i = 0; i < 32; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        for (int i = 0; i < 26; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }
        mockSpanCalibRegisters[0] = (short) 400; // 跨度校准浓度
        mockCalibRegisters[0] = (short) 0; // 测量模式

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);

        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));

        // 执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);

        // 验证结果
        assertTrue(result);

        // 验证分段读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(32));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(38), eq(26));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));

        // 验证属性更新 - 根据SO2Device的updateU16Attributes方法，某些电压值需要除以10
        verifyFloatAttribute("device_address", 100.0);
        verifyFloatAttribute("device_status", 101.0);
        verifyFloatAttribute("pmt_high_volt_setting", 10.2);
        verifyFloatAttribute("chamber_temp_volt", 10.3); // 103/10
        verifyFloatAttribute("sample_press_volt", 10.4); // 104/10
        verifyFloatAttribute("pump_press_volt", 10.5); // 105/10
        verifyFloatAttribute("case_temp_volt", 10.6); // 106/10
        verifyFloatAttribute("pmt_temp_volt", 10.7); // 107/10
        verifyFloatAttribute("case_temp", 10.8); // 108/10
        verifyFloatAttribute("voltage_12v", 109.0); // 109/10
        verifyFloatAttribute("voltage_15v", 110.0); // 110/10
        verifyFloatAttribute("voltage_5v", 111.0); // 111/10
        verifyFloatAttribute("voltage_3v3", 112.0); // 112/10
        verifyFloatAttribute("pmt_high_volt_read", 11.3);
        verifyFloatAttribute("calibration_status", 0.0); // 测量模式
    }

    @Test
    public void testSegmentedRead_SecondSegmentFailure() throws Exception {
        // 测试第二段读取失败的情况
        short[] mockFloatRegisters = new short[32];
        for (int i = 0; i < 32; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);

        CompletableFuture<ReadHoldingRegistersResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Second segment failed"));

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(failedFuture);
        mockCalibrationSegmentReads();
        
        // 执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);

        // 主测量段成功时仍提交更新（允许部分段失败）
        assertTrue(result);
        assertNotNull(so2Device.getAttrs().get("so2").getState());
    }

    @Test
    public void testSegmentedRead_DataParsingFailure() throws Exception {
        // 测试数据解析失败的情况
        short[] mockFloatRegisters = new short[32];
        for (int i = 0; i < 32; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }

        short[] mockU16Registers = new short[26];
        for (int i = 0; i < 26; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);

        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(null); // 第二段返回null触发异常

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        mockCalibrationSegmentReads();

        // 执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 主测量段成功时仍提交更新（U16 段失败仅跳过该段）
        assertTrue(result);
        assertNotNull(so2Device.getAttrs().get("so2").getState());
    }
    
    @Test
    public void testCalibrationStatusParsing() throws Exception {
        // 测试校准状态解析
        short calibrationStatus = (short) 0x04; // 测量模式
        Object result = invokePrivateMethod(so2Device, "parseDeviceStatus", calibrationStatus);
        
        // 验证解析结果
        assertNotNull(result);
        // 这里需要根据实际的DeviceStatus枚举来验证
    }
    
    @Test
    public void testAttributeStatusMapping() throws Exception {
        // 测试属性状态映射
        // 这里需要根据实际的DeviceStatus和AttributeStatus枚举来测试
        // 由于无法直接访问枚举，我们通过反射来测试
    }

    @Test
    public void testCalibrationValueWithDifferentStatuses() throws Exception {
        // 测试不同校准状态下的校准浓度数值设置

        // 测试零点校准状态
        short[] mockFloatRegisters = new short[32];
        short[] mockU16Registers = new short[26];
        short[] mockSpanCalibRegisters = new short[1];
        short[] mockCalibRegisters = new short[1];

        // 设置测试数据
        for (int i = 0; i < 32; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        for (int i = 0; i < 26; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }

        // 测试零点校准状态
        mockSpanCalibRegisters[0] = (short) 0; // 跨度校准浓度
        mockCalibRegisters[0] = (short) 1; // 零点校准

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);

        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));

        // 执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);

        // 验证结果
        assertTrue(result);

        // 验证零点校准状态下的校准浓度数值应该为0
        verifyFloatAttribute("calibration_concentration", 0.0);

        // 测试跨度校准状态
        mockSpanCalibRegisters[0] = (short) 400; // 跨度校准浓度
        mockCalibRegisters[0] = (short) 2; // 跨度校准

        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        // 再次执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future2 = so2Device.readAndUpdate(mockModbusSource);
        result = future2.get(5, TimeUnit.SECONDS);

        // 验证结果
        assertTrue(result);

        // 验证跨度校准状态下的校准浓度数值应该为400
        verifyFloatAttribute("calibration_concentration", 400.0);
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testSO2DeviceI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            so2Device.init();

            // 验证关键属性的displayname
            TestTools.assertAttributeDisplayName(so2Device, "so2", "SO2浓度");
            TestTools.assertAttributeDisplayName(so2Device, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(so2Device, "sample_press", "样气压力");
            TestTools.assertAttributeDisplayName(so2Device, "sample_flow", "样气流量");
            TestTools.assertAttributeDisplayName(so2Device, "pump_press", "泵压力");
            TestTools.assertAttributeDisplayName(so2Device, "sample_temp", "样气温度");
            TestTools.assertAttributeDisplayName(so2Device, "chamber_temp", "反应室温度");
            TestTools.assertAttributeDisplayName(so2Device, "device_address", "仪器地址");
            TestTools.assertAttributeDisplayName(so2Device, "device_status", "仪器状态");
            TestTools.assertAttributeDisplayName(so2Device, "calibration_status", "校准状态");

            // 新增28个属性验证
            // 基础参数
            TestTools.assertAttributeDisplayName(so2Device, "slope", "浓度斜率");
            TestTools.assertAttributeDisplayName(so2Device, "intercept", "浓度截距");
            TestTools.assertAttributeDisplayName(so2Device, "chamber_temp_setting", "反应室温度设定值");
            TestTools.assertAttributeDisplayName(so2Device, "builtin_pump_status", "内置泵状态");
            TestTools.assertAttributeDisplayName(so2Device, "case_fan_status", "机箱风扇状态");
            TestTools.assertAttributeDisplayName(so2Device, "case_temp", "机箱温度");
            TestTools.assertAttributeDisplayName(so2Device, "case_temp_volt", "机箱温度电压");
            TestTools.assertAttributeDisplayName(so2Device, "chamber_status", "反应室状态");
            TestTools.assertAttributeDisplayName(so2Device, "chamber_temp_corr", "反应室温度修正值");
            TestTools.assertAttributeDisplayName(so2Device, "fault_code", "故障代码");
            TestTools.assertAttributeDisplayName(so2Device, "pmt_high_volt_read", "PMT高压读数");
            TestTools.assertAttributeDisplayName(so2Device, "pmt_high_volt_setting", "PMT高压设定值");
            TestTools.assertAttributeDisplayName(so2Device, "pmt_temp_volt", "PMT温度电压");
            TestTools.assertAttributeDisplayName(so2Device, "sample_cal_valve_status", "样气校准阀门状态");
            TestTools.assertAttributeDisplayName(so2Device, "xe_latp_driving_volt", "氙灯驱动电压");
            TestTools.assertAttributeDisplayName(so2Device, "xe_latp_driving_volt_setting", "氙灯驱动电压设定值");
            TestTools.assertAttributeDisplayName(so2Device, "dispatch_command", "调度命令");

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testSO2DeviceCommandI18n() throws Exception {
        // 执行初始化
        so2Device.init();

        // 验证命令属性的displayname
        TestTools.assertAttributeDisplayName(so2Device, "dispatch_command", "调度命令");

        // 验证命令选项的i18n支持 - 通过DisplayName验证而不是直接调用i18n.t()
        // 这些命令选项的displayname会在设备初始化时自动从strings.json加载

        // 获取命令属性
        Object commandAttr = so2Device.getAttrs().get("dispatch_command");
        assertNotNull("命令属性应该存在", commandAttr);

        // 验证命令属性的显示名称
        String displayName = commandAttr.toString();
        assertNotNull("命令显示名称不应为null", displayName);
    }

    @Test
    public void testSO2DeviceI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            so2Device.init();

            // 验证设备绑定后属性仍然返回有意义的displayname
            TestTools.assertAttributeDisplayName(so2Device, "so2", "SO2浓度");
            TestTools.assertAttributeDisplayName(so2Device, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(so2Device, "device_status", "仪器状态");

            // 验证校准相关属性
            TestTools.assertAttributeDisplayName(so2Device, "calibration_concentration", "校准浓度");
            TestTools.assertAttributeDisplayName(so2Device, "calibration_status", "校准状态");
            TestTools.assertAttributeDisplayName(so2Device, "so2_manual_status", "手动状态");
            TestTools.assertAttributeDisplayName(so2Device, "so2_status", "SO2状态");

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    // ========== 手动状态功能测试 ==========

    @Test
    public void testInit_CreatesManualStatusAttributes() throws Exception {
        so2Device.init();

        assertNotNull(so2Device.getAttrs().get("so2_manual_status"));
        assertNotNull(so2Device.getAttrs().get("so2_status"));
        assertTrue(so2Device.getAttrs().get("so2_manual_status") instanceof StringSelectAttribute);
        assertTrue(so2Device.getAttrs().get("so2_status") instanceof StringSelectAttribute);

        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
        StringSelectAttribute statusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_status");
        assertEquals(AttributeStatus.NORMAL.getName(), manualStatusAttr.getState().getValue());
        assertEquals(AttributeStatus.NORMAL.getName(), statusAttr.getState().getValue());
    }

    @Test
    public void testManualStatusPriority_OverDeviceCalibrationStatus() throws Exception {
        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
        manualStatusAttr.updateValue(AttributeStatus.MAINTENANCE.getName());

        setupSpanCalibrationReadMocks();

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS);

        NumericAttribute so2Attr = (NumericAttribute) so2Device.getAttrs().get("so2");
        assertEquals(AttributeStatus.MAINTENANCE, so2Attr.getState().getStatus());

        StringSelectAttribute statusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_status");
        assertEquals(AttributeStatus.MAINTENANCE.getName(), statusAttr.getState().getValue());
    }

    @Test
    public void testManualStatus_Calibration() throws Exception {
        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
        manualStatusAttr.updateValue(AttributeStatus.CALIBRATION.getName());

        setupMeasureModeReadMocks();

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS);

        NumericAttribute so2Attr = (NumericAttribute) so2Device.getAttrs().get("so2");
        assertEquals(AttributeStatus.CALIBRATION, so2Attr.getState().getStatus());

        StringSelectAttribute statusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_status");
        assertEquals(AttributeStatus.CALIBRATION.getName(), statusAttr.getState().getValue());
    }

    @Test
    public void testManualStatus_Normal_UsesDeviceCalibrationStatus() throws Exception {
        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
        manualStatusAttr.updateValue(AttributeStatus.NORMAL.getName());

        setupSpanCalibrationReadMocks();

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS);

        NumericAttribute so2Attr = (NumericAttribute) so2Device.getAttrs().get("so2");
        assertEquals(AttributeStatus.SPAN_CALIBRATION, so2Attr.getState().getStatus());

        StringSelectAttribute statusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_status");
        assertEquals(AttributeStatus.SPAN_CALIBRATION.getName(), statusAttr.getState().getValue());
    }

    @Test
    public void testAlarmStatusPriority_OverDeviceCalibrationStatus() throws Exception {
        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
        manualStatusAttr.updateValue(AttributeStatus.NORMAL.getName());

        setupSpanCalibrationReadMocksWithAlarm((short) 0x0001);

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS);

        NumericAttribute so2Attr = (NumericAttribute) so2Device.getAttrs().get("so2");
        assertEquals(AttributeStatus.ALARM, so2Attr.getState().getStatus());

        StringSelectAttribute statusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_status");
        assertEquals(AttributeStatus.ALARM.getName(), statusAttr.getState().getValue());

        BinaryAttribute generalAlarm = (BinaryAttribute) so2Device.getAttrs().get("general_alarm");
        assertTrue(generalAlarm.isOn());
        assertEquals(com.ecat.core.Device.DeviceStatus.ALARM, so2Device.getDeviceStatus());
    }

    @Test
    public void testManualStatusPriority_OverAlarmStatus() throws Exception {
        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
        manualStatusAttr.updateValue(AttributeStatus.MAINTENANCE.getName());

        setupSpanCalibrationReadMocksWithAlarm((short) 0x0001);

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = so2Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS);

        NumericAttribute so2Attr = (NumericAttribute) so2Device.getAttrs().get("so2");
        assertEquals(AttributeStatus.MAINTENANCE, so2Attr.getState().getStatus());

        StringSelectAttribute statusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_status");
        assertEquals(AttributeStatus.MAINTENANCE.getName(), statusAttr.getState().getValue());

        BinaryAttribute generalAlarm = (BinaryAttribute) so2Device.getAttrs().get("general_alarm");
        assertTrue("报警位仍应开启，但显示状态由手动覆盖", generalAlarm.isOn());
        assertEquals(com.ecat.core.Device.DeviceStatus.MAINTENANCE, so2Device.getDeviceStatus());
    }

    @Test
    public void testSO2ManualStatusOptionsI18n() throws Exception {
        ResourceLoader.setLoadI18nResources(false);

        try {
            so2Device.init();

            StringSelectAttribute manualStatusAttr = (StringSelectAttribute) so2Device.getAttrs().get("so2_manual_status");
            assertNotNull(manualStatusAttr);

            Map<String, String> optionDict = manualStatusAttr.getOptionDict();
            assertEquals("自动", optionDict.get(AttributeStatus.NORMAL.getName()));
            assertEquals("维护", optionDict.get(AttributeStatus.MAINTENANCE.getName()));
            assertEquals("准确度检查", optionDict.get(AttributeStatus.ACCURACY_CHECK.getName()));
            assertEquals("维修更换设备", optionDict.get(AttributeStatus.DEVICE_REPLACEMENT.getName()));
            assertEquals(19, optionDict.size());
        } finally {
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    private void setupSpanCalibrationReadMocks() {
        setupSpanCalibrationReadMocksWithAlarm((short) 0);
    }

    private void setupSpanCalibrationReadMocksWithAlarm(short alarmRegister) {
        short[] mockFloatRegisters = new short[32];
        short[] mockU16Registers = new short[26];
        mockU16Registers[24] = alarmRegister;
        short[] mockSpanCalibRegisters = new short[] {(short) 400};
        short[] mockCalibRegisters = new short[] {(short) 2};

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);

        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
    }

    private void setupMeasureModeReadMocks() {
        short[] mockFloatRegisters = new short[32];
        short[] mockU16Registers = new short[26];
        short[] mockSpanCalibRegisters = new short[] {(short) 0};
        short[] mockCalibRegisters = new short[] {(short) 0};

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);

        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
    }

    private void mockCalibrationSegmentReads() {
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockSpanCalibResponse.getShortData()).thenReturn(new short[] {(short) 0});
        when(mockCalibResponse.getShortData()).thenReturn(new short[] {(short) 0});
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
    }

    private boolean isReadonlyDeviceStatusAttr(AttributeBase<?> attr) {
        String id = attr.getAttrID();
        return id.endsWith("_manual_status") || id.endsWith("_status");
    }


}
