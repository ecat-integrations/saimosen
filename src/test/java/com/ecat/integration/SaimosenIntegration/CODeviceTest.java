package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.I18nHelper;
import com.ecat.core.I18n.I18nProxy;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.StateManager;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
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

import static com.ecat.integration.ModbusIntegration.Tools.convertLittleEndianByteSwapToFloat;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CODevice单元测试类 - 支持完整协议（38个寄存器 + 校准状态）
 * 
 * @author caohongbo
 */
public class CODeviceTest {

    private CODevice coDevice;
    private AutoCloseable mockitoCloseable;
    
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        ConfigEntry entry = createTestEntry();
        coDevice = new CODevice(entry);
        
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
        coDevice.load(mockEcatCore);
        
        initDevice();
            // markReady 对齐生产时序（init/load 后 markReady，再由轮询驱动 publish）：
        // 测试直接调 publish 路径（readRegisters/parse/publicAttrsState），未就绪撞就绪门禁
        // （publicAttrsState 不再吞门禁异常，会原样抛出或被 parse 的 catch 兜成 MALFUNCTION）。
        when(mockEcatCore.getStateManager()).thenReturn(mock(StateManager.class));
        coDevice.markReady();
}
    
    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "CO测试设备");
        config.put("class", "air.monitor.co");
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
            .entryId("test-entry-co-device")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_air.monitor.co")
            .title("CO测试设备")
            .data(config)
            .build();
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
        setPrivateField(coDevice, "core", mockEcatCore);
        setPrivateField(coDevice, "modbusSource", mockModbusSource);
        setPrivateField(coDevice, "modbusIntegration", mockModbusIntegration);
        coDevice.init();
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

    private void verifyFloatAttribute(String attrId, double expectedValue) {
        NumericAttribute attr = (NumericAttribute) coDevice.getAttrs().get(attrId);
        assertNotNull("属性 " + attrId + " 应该存在", attr);
        if (attr != null) {
            assertEquals("属性 " + attrId + " 的值应该正确", expectedValue, ((Number) attr.getState().getValue()).doubleValue(), 0.01); // 精度误差±0.01
        }
    }

    private void verifyTextAttribute(String attrId, String expectedValue) {
        TextAttribute attr = (TextAttribute) coDevice.getAttrs().get(attrId);
        assertNotNull("属性 " + attrId + " 应该存在", attr);
        if (attr != null) {
            assertEquals("属性 " + attrId + " 的值应该正确", expectedValue, attr.getState().getValue());
        }
    }
    
    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        // 执行初始化
        coDevice.init();
        
        // 验证第一组参数（float类型）- 20个参数
        assertNotNull("CO浓度属性应该存在", coDevice.getAttrs().get("co"));
        assertNotNull("测量电压属性应该存在", coDevice.getAttrs().get("measure_volt"));
        assertNotNull("参比电压属性应该存在", coDevice.getAttrs().get("ref_volt"));
        assertNotNull("测量暗电流属性应该存在", coDevice.getAttrs().get("measure_dark_current"));
        assertNotNull("参比暗电流属性应该存在", coDevice.getAttrs().get("ref_dark_current"));
        assertNotNull("浓度斜率属性应该存在", coDevice.getAttrs().get("slope"));
        assertNotNull("浓度截距属性应该存在", coDevice.getAttrs().get("intercept"));
        assertNotNull("样气压力属性应该存在", coDevice.getAttrs().get("sample_press"));
        assertNotNull("泵压力属性应该存在", coDevice.getAttrs().get("pump_press"));
        assertNotNull("样气流量属性应该存在", coDevice.getAttrs().get("sample_flow"));
        assertNotNull("光室温度NTC属性应该存在", coDevice.getAttrs().get("negative_temp_coefficient"));
        assertNotNull("相关轮温度属性应该存在", coDevice.getAttrs().get("correlation_wheel_temp"));
        assertNotNull("涤除器温度属性应该存在", coDevice.getAttrs().get("scrubber_temp"));
        assertNotNull("光室温度修正值属性应该存在", coDevice.getAttrs().get("negative_temp_coefficient_corr"));
        assertNotNull("相关轮温度修正值属性应该存在", coDevice.getAttrs().get("correlation_wheel_temp_corr"));
        assertNotNull("涤除器温度修正值属性应该存在", coDevice.getAttrs().get("scrubber_temp_corr"));
        assertNotNull("样气压力修正值属性应该存在", coDevice.getAttrs().get("sample_press_corr"));
        assertNotNull("泵压力修正值属性应该存在", coDevice.getAttrs().get("pump_press_corr"));
        assertNotNull("样气流量修正值属性应该存在", coDevice.getAttrs().get("sample_flow_corr"));
        assertNotNull("上位机计算的测量参比率属性应该存在", coDevice.getAttrs().get("host_calc_measure_ref"));
        
        // 验证第二组参数（U16类型）- 13个参数
        assertNotNull("12V电压值属性应该存在", coDevice.getAttrs().get("voltage_12v"));
        assertNotNull("15V电压值属性应该存在", coDevice.getAttrs().get("voltage_15v"));
        assertNotNull("5V电压值属性应该存在", coDevice.getAttrs().get("voltage_5v"));
        assertNotNull("3.3V电压值属性应该存在", coDevice.getAttrs().get("voltage_3v3"));
        assertNotNull("光室继电器状态属性应该存在", coDevice.getAttrs().get("optical_chamber_relay_status"));
        assertNotNull("涤除器继电器状态属性应该存在", coDevice.getAttrs().get("scrubber_relay_status"));
        assertNotNull("相关轮继电器状态属性应该存在", coDevice.getAttrs().get("correlation_wheel_relay_status"));
        assertNotNull("采样校准继电器状态属性应该存在", coDevice.getAttrs().get("sample_cal_relay_status"));
        assertNotNull("自动零点阀继电器状态属性应该存在", coDevice.getAttrs().get("auto_zero_value_relay_status"));
        assertNotNull("启动暗电流测试属性应该存在", coDevice.getAttrs().get("start_dark_current_test"));
        assertNotNull("启动暗电流参数存储属性应该存在", coDevice.getAttrs().get("start_dark_current_param_storage"));
        assertNotNull("采样校准状态属性应该存在", coDevice.getAttrs().get("sample_cal_status"));
        assertNotNull("报警信息属性应该存在", coDevice.getAttrs().get("alarm_info"));
        assertNotNull("通用报警属性应该存在", coDevice.getAttrs().get("general_alarm"));
        assertNotNull("故障信息属性应该存在", coDevice.getAttrs().get("fault_code"));

        // 验证校准相关属性
        assertNotNull("校准浓度属性应该存在", coDevice.getAttrs().get("calibration_concentration"));
        assertNotNull("校准状态属性应该存在", coDevice.getAttrs().get("calibration_status"));
        assertNotNull("校准命令属性应该存在", coDevice.getAttrs().get("gas_device_command"));
        assertNotNull("手动状态属性应该存在", coDevice.getAttrs().get("co_manual_status"));
        assertNotNull("只读状态属性应该存在", coDevice.getAttrs().get("co_status"));

        // 验证属性总数
        assertEquals("应该有40个属性", 40, coDevice.getAttrs().size());
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

        coDevice.start();

        // 首轮立即发射（确定性同步：latch 等 round 真正读源，非定时猜测）
        assertTrue("首轮（initialDelay=0）必须立即发起 float 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 40);
    }
    
    @Test
    public void testStop_CancelsScheduledTasks() throws Exception {
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        // 单测注入短轮询周期（生产默认 5s）：负向窗 300ms ≥ 2 拍×150ms，走生产 start() 真实接线
        coDevice.pollPeriodMs = 150L;
        coDevice.start();
        assertTrue("首轮必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        coDevice.stop();
        coDevice.cancelManagedTasks();   // 框架 chokepoint 同点（IntegrationDeviceBase.stopWithManagedSweep）
        // 负向观察窗 300ms ≥ 2 拍×150ms（生产 start() 注入节拍；cancel 失效形态下下一拍必现形）
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮（容至多 1 个 stop 前在飞轮迟到入口，阈值 2）", probe.strayRound.await(300, TimeUnit.MILLISECONDS));

        // sweep 已执行的直接证据：宿主进入已扫状态，再注册移除动作被拒（RemovalHost 契约）
        try {
            coDevice.onRemove(() -> { });
            org.junit.Assert.fail("sweep 后宿主必须拒绝新移除动作注册");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
        }
    }
    
    @Test
    public void testRelease_CancelsReadFuture() throws Exception {
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        // 单测注入短轮询周期（生产默认 5s）：负向窗 300ms ≥ 2 拍×150ms，走生产 start() 真实接线
        coDevice.pollPeriodMs = 150L;
        coDevice.start();
        assertTrue("首轮必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        // disableEntry 同序：stop → sweep（移除动作停轮询）→ release（源释放）
        coDevice.stop();
        coDevice.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("release 前 stop+sweep 后不得再发起下一轮（容至多 1 个 stop 前在飞轮迟到入口，阈值 2）", probe.strayRound.await(300, TimeUnit.MILLISECONDS));
        coDevice.release();
        verify(mockModbusSource).closeModbus();
    }

    
    @Test
    public void testReadAndUpdate_ReadsAndParsesAllData() throws Exception {
        // 准备分段读取的模拟数据
        
        // 第一段：float参数（20个float数  40个寄存器，80个字节）
        short[] mockFloatRegisters = new short[40];
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1); // 简单的递增数据
        }
        
        // 第二段：U16参数（寄存器30-43，26字节）
        short[] mockU16Registers = new short[13];
        // 模拟12V电压值 12000 mV（需要除以10）
        mockU16Registers[0] = (short) 12000;
        // 模拟15V电压值 15000 mV（需要除以10）
        mockU16Registers[1] = (short) 15000;
        // 模拟3.3V电压值 3300 mV
        mockU16Registers[2] = (short) 3300;
        // 模拟5V电压值 5000 mV
        mockU16Registers[3] = (short) 5000;
        // 模拟光室继电器状态 0（正常）
        mockU16Registers[4] = (short) 0;
        // 模拟涤除器继电器状态 0（正常）
        mockU16Registers[5] = (short) 0;
        // 模拟相关轮继电器状态 0（正常）
        mockU16Registers[6] = (short) 0;
        // 模拟采样校准继电器状态 0（正常）
        mockU16Registers[7] = (short) 0;
        // 模拟自动零点阀继电器状态 0（正常）
        mockU16Registers[8] = (short) 0;
        // 模拟启动暗电流测试 0
        mockU16Registers[9] = (short) 0;
        // 模拟启动暗电流参数存储 0
        mockU16Registers[10] = (short) 0;
        // 模拟采样校准状态 0（采样中）
        mockU16Registers[11] = (short) 0;
        // 模拟故障信息 0
        mockU16Registers[12] = (short) 0;

        // 第三段：跨度校准浓度寄存器
        short[] mockSpanCalibRegisters = new short[1];
        mockSpanCalibRegisters[0] = (short) 400; // 模拟跨度校准浓度
        
        // 第四段：校准状态寄存器
        short[] mockInstrumentCalibRegisters = new short[1];
        mockInstrumentCalibRegisters[0] = (short) 2; // 模拟跨度校准模式

        // 模拟分段读取的Modbus响应
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockInstrumentCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockInstrumentCalibResponse.getShortData()).thenReturn(mockInstrumentCalibRegisters);

        // 模拟分段读取调用
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(60), eq(13)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockInstrumentCalibResponse));
        
        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS); // 等待异步操作完成
        
        // 验证分段读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(40));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(60), eq(13));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));
        
        // 验证第二组参数（U16类型）- 这些是简单的整数，容易验证
        verifyFloatAttribute("voltage_12v", 12000.0);
        verifyFloatAttribute("voltage_15v", 15000.0);
        verifyFloatAttribute("voltage_3v3", 3300.0);
        verifyFloatAttribute("voltage_5v", 5000.0);
        verifyFloatAttribute("optical_chamber_relay_status", 0.0);
        verifyFloatAttribute("scrubber_relay_status", 0.0);
        verifyFloatAttribute("correlation_wheel_relay_status", 0.0);
        verifyFloatAttribute("sample_cal_relay_status", 0.0);
        verifyFloatAttribute("auto_zero_value_relay_status", 0.0);
        verifyFloatAttribute("start_dark_current_test", 0.0);
        verifyFloatAttribute("start_dark_current_param_storage", 0.0);
        verifyFloatAttribute("sample_cal_status", 0.0);
        verifyFloatAttribute("fault_code", 0.0);
        verifyTextAttribute("alarm_info", "");
        
        // 验证校准状态属性
        verifyFloatAttribute("calibration_status", 2.0);

        // 验证校准浓度数值 - 跨度校准模式时应该为400
        verifyFloatAttribute("calibration_concentration", 400.0);
        
        // 验证测量属性状态为跨度校准（排除手动/只读状态属性）
        coDevice.getAttrs().values().stream()
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
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(failedFuture);
        
        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS); // 等待异步操作完成
        
        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        
        // 通讯失败时不更新测量属性
        assertNull(coDevice.getAttrs().get("co").getState());
    }

    @Test
    public void testReadAndUpdate_HandlesDataParsingException() throws Exception {
        // 准备无效的寄存器数据 - 使用null来触发异常
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockFloatResponse.getShortData()).thenReturn(null); // 返回null会触发异常

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        
        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        
        // 通讯失败时不更新测量属性
        assertNull(coDevice.getAttrs().get("co").getState());
    }

    @Test
    public void testSegmentConfiguration() throws Exception {
        // 验证数据段配置是否正确
        Object segmentConfig = getPrivateField(coDevice, "SEGMENT_CONFIG");
        assertNotNull(segmentConfig);
        
        // 验证配置不为空
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) segmentConfig;
        assertTrue("配置应该包含FLOAT_PARAMS", config.containsKey("float_params"));
        assertTrue("配置应该包含U16_PARAMS", config.containsKey("u16_params"));
        assertTrue("配置应该包含CALIBRATION_STATUS", config.containsKey("calibration_status"));
    }
    
    @Test
    public void testUpdateAttributeMethod() throws Exception {
        // 测试updateAttribute私有方法 - 使用正确的参数类型
        invokePrivateMethod(coDevice, "updateAttribute", "co", 25.5, AttributeStatus.NORMAL);

        // 验证属性值已更新
        NumericAttribute coAttr = (NumericAttribute) coDevice.getAttrs().get("co");
        assertEquals(25.5, ((Number) coAttr.getState().getValue()).doubleValue(), 0.01);
        assertEquals(AttributeStatus.NORMAL, coAttr.getState() != null ? coAttr.getState().getStatus() : null);
    }
    
    @Test
    public void testUpdateAttributeMethod_NonExistentAttribute() throws Exception {
        // 测试更新不存在的属性
        Object result = invokePrivateMethod(coDevice, "updateAttribute", "NON_EXISTENT", 100.0, AttributeStatus.NORMAL);
        
        // 验证不会抛出异常，只是忽略
        assertNull(result);
    }
    
    @Test
    public void testDeviceLifecycle() throws Exception {
        // 测试完整的设备生命周期
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        
        // 1. 初始化
        coDevice.init();
        assertEquals(40, coDevice.getAttrs().size());

        // 2. 启动（首轮 latch：确定性确认轮询已注册运行，非仅不抛异常）
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        // 单测注入短轮询周期（生产默认 5s）：负向窗 300ms ≥ 2 拍×150ms，走生产 start() 真实接线
        coDevice.pollPeriodMs = 150L;
        coDevice.start();
        assertTrue("start 后首轮轮询必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        // 3. 停止（lifecycle chokepoint：stop + sweep 执行 SDK 注册的移除动作停轮询）；
        //    负向窗 300ms ≥ 2 拍×150ms（cancel 失效形态下下一拍必现形）
        coDevice.stop();
        coDevice.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮（容至多 1 个 stop 前在飞轮迟到入口，阈值 2）", probe.strayRound.await(300, TimeUnit.MILLISECONDS));

        // 4. 释放资源
        coDevice.release();
        verify(mockModbusSource, times(1)).closeModbus();
    }
    
    @Test
    public void testParallelReadStrategy() throws Exception {
        // 测试并行读取策略
        short[] mockFloatRegisters = new short[40];
        short[] mockU16Registers = new short[13];
        // 数组 new 无法独立成句，按原行恢复（警告回归可接受）
        short[] mockCalibRegisters = new short[1];
        
        // 设置测试数据
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        for (int i = 0; i < 13; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }
        // 跨度校准浓度
        short[] mockSpanCalibRegisters = new short[1];
        mockSpanCalibRegisters[0] = (short) 400; // 跨度校准浓度
        
        // 校准状态
        short[] mockInstrumentCalibRegisters = new short[1];
        mockInstrumentCalibRegisters[0] = (short) 0x02; // 跨度校准
        
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockInstrumentCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockInstrumentCalibResponse.getShortData()).thenReturn(mockInstrumentCalibRegisters);
        
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(60), eq(13)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockInstrumentCalibResponse));
        
        // 执行并行读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证结果
        assertTrue(result);
        
        // 验证并行读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(40));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(60), eq(13));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));
        
        // 验证属性更新
        verifyFloatAttribute("voltage_12v", 100.0);
        verifyFloatAttribute("voltage_15v", 101.0);
        verifyFloatAttribute("voltage_3v3", 102.0);
        verifyFloatAttribute("voltage_5v", 103.0);
        verifyFloatAttribute("calibration_status", 2.0);
    }
    
    @Test
    public void testParallelRead_SecondSegmentFailure() throws Exception {
        // 测试第二段读取失败的情况
        short[] mockFloatRegisters = new short[40];
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        
        CompletableFuture<ReadHoldingRegistersResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Second segment failed"));
        
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(60), eq(13)))
            .thenReturn(failedFuture);
        mockCalibrationSegmentReads();
        
        // 执行并行读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 主测量段成功时仍提交更新（允许部分段失败）
        assertTrue(result);
        assertNotNull(coDevice.getAttrs().get("co").getState());
    }
    
    @Test
    public void testParallelRead_DataParsingFailure() throws Exception {
        // 测试数据解析失败的情况
        short[] mockFloatRegisters = new short[40];
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        
        short[] mockU16Registers = new short[13];
        for (int i = 0; i < 13; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }
        
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(null); // 第二段返回null触发异常
        
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(60), eq(13)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        mockCalibrationSegmentReads();
        
        // 执行并行读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 主测量段成功时仍提交更新（U16 段失败仅跳过该段）
        assertTrue(result);
        assertNotNull(coDevice.getAttrs().get("co").getState());
    }
    
    @Test
    public void testCalibrationStatusParsing() throws Exception {
        // 测试校准状态解析
        short calibrationStatus = (short) 0x04; // 测量模式
        Object result = invokePrivateMethod(coDevice, "parseDeviceStatus", calibrationStatus);
        
        // 验证解析结果
        assertNotNull(result);
        // 这里需要根据实际的DeviceStatus枚举来验证
    }
    
    @Test
    public void testCalibrationValueMapping() throws Exception {
        // 测试校准浓度数值映射
        // 测试零点校准状态
        Object zeroCalibValue = invokePrivateMethod(coDevice, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.ZERO_CALIBRATION, 0.0);
        assertEquals(0.0, (Double) zeroCalibValue, 0.01);
        
        // 测试零点状态
        Object zeroValue = invokePrivateMethod(coDevice, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.ZERO, 0.0);
        assertEquals(0.0, (Double) zeroValue, 0.01);
        
        // 测试跨度校准状态 - 使用实际的跨度校准浓度值
        Object spanCalibValue = invokePrivateMethod(coDevice, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.SPAN_CALIBRATION, 400.0);
        assertEquals(400.0, (Double) spanCalibValue, 0.01);
        
        // 测试跨度状态 - 使用实际的跨度校准浓度值
        Object spanValue = invokePrivateMethod(coDevice, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.SPAN, 400.0);
        assertEquals(400.0, (Double) spanValue, 0.01);
        
        // 测试测量状态
        Object measureValue = invokePrivateMethod(coDevice, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.MEASURE, 0.0);
        assertEquals(0.0, (Double) measureValue, 0.01);
        
        // 测试跨度校准状态 - 当跨度校准浓度为0时，应该返回默认值400.0
        Object spanCalibValueDefault = invokePrivateMethod(coDevice, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.SPAN_CALIBRATION, 0.0);
        assertEquals(400.0, (Double) spanCalibValueDefault, 0.01);
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
        short[] mockFloatRegisters = new short[40];
        short[] mockU16Registers = new short[13];
        short[] mockSpanCalibRegisters = new short[1];
        short[] mockInstrumentCalibRegisters = new short[1];
        
        // 设置测试数据
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        for (int i = 0; i < 13; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }
        mockU16Registers[12] = 0; // 报警寄存器无报警，避免覆盖跨度校准显示/校准浓度计算
        
        // 测试零点校准状态
        mockSpanCalibRegisters[0] = (short) 0; // 跨度校准浓度（零点校准时为0）
        mockInstrumentCalibRegisters[0] = (short) 1; // 零点校准
        
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockInstrumentCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockInstrumentCalibResponse.getShortData()).thenReturn(mockInstrumentCalibRegisters);
        
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(60), eq(13)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockInstrumentCalibResponse));
        
        // 执行并行读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = coDevice.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证结果
        assertTrue(result);
        
        // 验证零点校准状态下的校准浓度数值应该为0
        verifyFloatAttribute("calibration_concentration", 0.0);
        
        // 测试跨度校准状态
        mockSpanCalibRegisters[0] = (short) 400; // 跨度校准浓度
        mockInstrumentCalibRegisters[0] = (short) 2; // 跨度校准
        
        when(mockInstrumentCalibResponse.getShortData()).thenReturn(mockInstrumentCalibRegisters);
        
        // 再次执行并行读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future2 = coDevice.readAndUpdate(mockModbusSource);
        result = future2.get(5, TimeUnit.SECONDS);
        
        // 验证结果
        assertTrue(result);
        
        // 验证跨度校准状态下的校准浓度数值应该为400
        verifyFloatAttribute("calibration_concentration", 400.0);
    }
    
    /**
     * 将十六进制字符串转换为short数组
     * @param hexString 十六进制字符串，如 "01 03 70 BF 3E FB 7C 54 45 96 EB 34 45 77 E6"
     * @return short数组
     */
    private short[] hexStringToShortArray(String hexString) {
        // 移除空格并分割
        String[] hexBytes = hexString.replaceAll("\\s+", "").split("(?<=\\G.{2})");
        short[] result = new short[hexBytes.length / 2];
        
        for (int i = 0; i < result.length; i++) {
            // 将两个字节组合成一个short (Big-Endian)
            String highByte = hexBytes[i * 2];
            String lowByte = hexBytes[i * 2 + 1];
            int value = (Integer.parseInt(highByte, 16) << 8) | Integer.parseInt(lowByte, 16);
            result[i] = (short) value;
        }
        
        return result;
    }

    private short[] skipModbusHeaderAndGetData(String hexString) {
        // 移除空格并分割为字节
        String[] hexBytes = hexString.replaceAll("\\s+", "").split("(?<=\\G.{2})");
        
        // 跳过前3个字节（01 03 70）
        String[] dataBytes = new String[hexBytes.length - 3];
        System.arraycopy(hexBytes, 3, dataBytes, 0, dataBytes.length);
        
        // 将剩余字节转换为short数组
        short[] result = new short[dataBytes.length / 2];
        for (int i = 0; i < result.length; i++) {
            String highByte = dataBytes[i * 2];
            String lowByte = dataBytes[i * 2 + 1];
            int value = (Integer.parseInt(highByte, 16) << 8) | Integer.parseInt(lowByte, 16);
            result[i] = (short) value;
        }
        
        return result;
    }
    
    /**
     * 计算期望的float值，使用与CODevice相同的转换逻辑
     * @param lowWord 低位寄存器值
     * @param highWord 高位寄存器值
     * @return 转换后的float值
     */
    private double calculateExpectedFloat(short lowWord, short highWord) {
        return convertLittleEndianByteSwapToFloat(lowWord, highWord);
    }

    @Test
    public void testDataParsing_RealHexData() throws Exception {
        // 专门测试数据解析功能，使用真实的十六进制数据
        System.out.println("开始测试CO真实十六进制数据解析...");
        
        // 使用您提供的真实数据 - 修改这个值来测试不同的数据
        // String realHexData = "01 03 70 A5 3E 2F A9 55 45 1E 34 35 45 66 20 F6 42 29 F8 FD 42 EA 3A 6E C4 F3 E0 97 3F F0 E7 C5 42 35 59 9B 41 FC E3 45 44 39 F2 34 42 6B 02 5C 42 7E 00 9F 42 ED 36 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 97 3F AD D6 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 B8 C6";
        String realHexData = "01 03 70 9A 41 00 D6 97 3F F0 E7 C5 42 E9 5B 9A 41 2C E3 46 44 03 44 34 42 54 01 5C 42 CD 02 9A 42 16 BB 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 97 3F FD D3 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 CC 54";
        System.out.println("原始十六进制数据: " + realHexData);
        
        // 跳过前3个字节（01 03 70），从实际数据开始
        // 原始数据: 01 03 70 BF 3E FB 7C 54 45 96 EB 34 45 77 E6 ...
        // 跳过后: BF 3E FB 7C 54 45 96 EB 34 45 77 E6 ...
        short[] realData = skipModbusHeaderAndGetData(realHexData);
        System.out.println("跳过协议头后的数据长度: " + realData.length);
        
        // 打印前几个值用于调试
        for (int i = 0; i < Math.min(10, realData.length); i++) {
            System.out.println("   [" + i + "] = 0x" + Integer.toHexString(realData[i] & 0xFFFF).toUpperCase());
        }
        
        // 测试float数据解析
        Object floatResult = coDevice.parseFloatDataforTest(realData);
        assertNotNull("Float数据解析结果不应为null", floatResult);
        
        // 通过反射获取values字段
        Field valuesField = floatResult.getClass().getDeclaredField("values");
        valuesField.setAccessible(true);
        double[] floatValues = (double[]) valuesField.get(floatResult);
        assertNotNull("Float数据值不应为null", floatValues);
        
        System.out.println("Float数据解析成功！");
        System.out.println("   - 解析出的float值数量: " + floatValues.length);
        
        // 验证所有20个float值的具体数值
        // 根据CODevice的parseFloatData方法：rawData[i*2+1], rawData[i*2]
        // 每个float值由两个连续的寄存器组成
        
        // 动态计算所有期望的float值
        // 根据CODevice的parseFloatData方法：rawData[i*2+1], rawData[i*2]
        // 每个float值由两个连续的寄存器组成
        double[] expectedFloats = new double[20];
        for (int i = 0; i < 20; i++) {
            if (i * 2 + 1 < realData.length) {
                // 根据parseFloatData的逻辑：rawData[i*2+1], rawData[i*2]
                expectedFloats[i] = calculateExpectedFloat(realData[i*2+1], realData[i*2]);
                System.out.println("   Float[" + i + "] 期望值计算: rawData[" + (i*2+1) + "]=" + 
                    Integer.toHexString(realData[i*2+1] & 0xFFFF).toUpperCase() + 
                    ", rawData[" + (i*2) + "]=" + 
                    Integer.toHexString(realData[i*2] & 0xFFFF).toUpperCase() + 
                    " -> " + String.format("%.4f", expectedFloats[i]));
            } else {
                expectedFloats[i] = 0.0;
            }
        }
        
        // 先打印实际解析出的值，然后验证
        System.out.println("实际解析出的Float值:");
        for (int i = 0; i < Math.min(10, floatValues.length); i++) {
            System.out.println("   Float[" + i + "] = " + String.format("%.4f", floatValues[i]));
        }
        
        // 验证解析出的值不为null
        assertTrue("应该解析出至少1个float值", floatValues.length > 0);
        for (int i = 0; i < Math.min(5, floatValues.length); i++) {
            assertNotNull("Float值[" + i + "]不应为null", floatValues[i]);
        }
        
        // 动态验证所有float值
        System.out.println("开始验证所有Float值:");
        for (int i = 0; i < Math.min(expectedFloats.length, floatValues.length); i++) {
            assertEquals("Float[" + i + "]应该为" + String.format("%.4f", expectedFloats[i]), 
                        expectedFloats[i], floatValues[i], 0.0000001);
            System.out.println("   Float[" + i + "] 验证通过: " + String.format("%.4f", floatValues[i]));
        }
        
        // 打印所有解析出的float值用于调试
        System.out.println("所有Float值验证:");
        for (int i = 0; i < Math.min(20, floatValues.length); i++) {
            System.out.println("   Float[" + i + "] = " + String.format("%.4f", floatValues[i]) + 
                             " (期望: " + String.format("%.4f", expectedFloats[i]) + ")");
        }
        
        // 打印前几个解析出的float值用于调试
        for (int i = 0; i < Math.min(5, floatValues.length); i++) {
            System.out.println("   Float[" + i + "] = " + String.format("%.4f", floatValues[i]));
        }
        
        // 测试U16数据解析
        short[] u16TestData = {1200, 1500, 500, 330, 1, 0, 1, 0, 1, 0, 0, 0, 0};
        Object u16Result = coDevice.parseU16DataforTest(u16TestData);
        assertNotNull("U16数据解析结果不应为null", u16Result);
        
        // 通过反射获取values字段
        double[] u16Values = (double[]) valuesField.get(u16Result);
        assertNotNull("U16数据值不应为null", u16Values);
        
        System.out.println("U16数据解析成功！");
        System.out.println("   - 解析出的U16值数量: " + u16Values.length);
        
        // 打印解析出的U16值
        for (int i = 0; i < u16Values.length; i++) {
            System.out.println("   U16[" + i + "] = " + u16Values[i]);
        }
  
        System.out.println("CO真实数据解析测试完成！");
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testCODeviceI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            coDevice.init();

            // 验证CODevice在strings.json中有映射的属性
            TestTools.assertAttributeDisplayName(coDevice, "co", "CO浓度");
            TestTools.assertAttributeDisplayName(coDevice, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(coDevice, "ref_volt", "参比电压");
            TestTools.assertAttributeDisplayName(coDevice, "measure_dark_current", "测量暗电流");
            TestTools.assertAttributeDisplayName(coDevice, "ref_dark_current", "参比暗电流");
            TestTools.assertAttributeDisplayName(coDevice, "slope", "浓度斜率");
            TestTools.assertAttributeDisplayName(coDevice, "intercept", "浓度截距");
            TestTools.assertAttributeDisplayName(coDevice, "sample_press", "样气压力");
            TestTools.assertAttributeDisplayName(coDevice, "pump_press", "泵压力");
            TestTools.assertAttributeDisplayName(coDevice, "sample_flow", "样气流量");
            TestTools.assertAttributeDisplayName(coDevice, "negative_temp_coefficient", "光室温度NTC");
            TestTools.assertAttributeDisplayName(coDevice, "correlation_wheel_temp", "相关轮温度");
            TestTools.assertAttributeDisplayName(coDevice, "scrubber_temp", "涤除器温度");

            // 修正值参数
            TestTools.assertAttributeDisplayName(coDevice, "negative_temp_coefficient_corr", "光室温度NTC修正值");
            TestTools.assertAttributeDisplayName(coDevice, "correlation_wheel_temp_corr", "相关轮温度修正值");
            TestTools.assertAttributeDisplayName(coDevice, "scrubber_temp_corr", "涤除器温度修正值");
            TestTools.assertAttributeDisplayName(coDevice, "sample_press_corr", "样气压力修正值");
            TestTools.assertAttributeDisplayName(coDevice, "pump_press_corr", "泵压力修正值");
            TestTools.assertAttributeDisplayName(coDevice, "sample_flow_corr", "样气流量修正值");
            TestTools.assertAttributeDisplayName(coDevice, "host_calc_measure_ref", "主机计算测量参比值");

            // U16电压参数
            TestTools.assertAttributeDisplayName(coDevice, "voltage_12v", "12V电压");
            TestTools.assertAttributeDisplayName(coDevice, "voltage_15v", "15V电压");
            TestTools.assertAttributeDisplayName(coDevice, "voltage_5v", "5V电压");
            TestTools.assertAttributeDisplayName(coDevice, "voltage_3v3", "3.3V电压");

            // 继电器状态参数
            TestTools.assertAttributeDisplayName(coDevice, "optical_chamber_relay_status", "光室继电器状态");
            TestTools.assertAttributeDisplayName(coDevice, "scrubber_relay_status", "涤除器继电器状态");
            TestTools.assertAttributeDisplayName(coDevice, "correlation_wheel_relay_status", "相关轮继电器状态");
            TestTools.assertAttributeDisplayName(coDevice, "sample_cal_relay_status", "样气校准继电器状态");
            TestTools.assertAttributeDisplayName(coDevice, "auto_zero_value_relay_status", "自动零点值继电器状态");

            // 暗电流测试参数
            TestTools.assertAttributeDisplayName(coDevice, "start_dark_current_test", "启动暗电流测试");
            TestTools.assertAttributeDisplayName(coDevice, "start_dark_current_param_storage", "启动暗电流参数存储");
            TestTools.assertAttributeDisplayName(coDevice, "sample_cal_status", "采样校准状态");
            TestTools.assertAttributeDisplayName(coDevice, "alarm_info", "报警信息");
            TestTools.assertAttributeDisplayName(coDevice, "fault_code", "故障信息");

            // 校准相关属性
            TestTools.assertAttributeDisplayName(coDevice, "calibration_concentration", "校准浓度");
            TestTools.assertAttributeDisplayName(coDevice, "calibration_status", "校准状态");
            TestTools.assertAttributeDisplayName(coDevice, "gas_device_command", "气体设备命令");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testCODeviceCommandSubCommandsI18n() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 测试命令子命令的i18n支持
            I18nProxy i18n = I18nHelper.createProxy(CODevice.class);

            // 验证所有子命令的i18n支持
            String[] commands = {
                "zero_calibration_start", "zero_calibration_confirm", "zero_calibration_cancel",
                "span_calibration_start", "span_calibration_confirm", "span_calibration_cancel"
            };

            String[] expectedNames = {
                "零点校准开始", "零点校准确认", "零点校准取消",
                "跨度校准开始", "跨度校准确认", "跨度校准取消"
            };

            for (int i = 0; i < commands.length; i++) {
                String key = "devices.codevice.gas_device_command_commands." + commands[i];
                String actualName = i18n.t(key);
                assertEqualsString("Command " + commands[i] + " should have correct i18n name: " + key,
                             expectedNames[i], actualName);
            }
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testCODeviceI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            coDevice.init();

            // 验证绑定设备后的displayname仍然正确
            TestTools.assertAttributeDisplayName(coDevice, "co", "CO浓度");
            TestTools.assertAttributeDisplayName(coDevice, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(coDevice, "calibration_status", "校准状态");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }


    /**
     * 自定义断言方法
     */
    private void assertEqualsString(String message, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected:<" + expected + "> but was:<" + actual + ">");
        }
    }

    @Test
    public void testInit_CreatesManualStatusAttributes() throws Exception {
        coDevice.init();

        assertNotNull(coDevice.getAttrs().get("co_manual_status"));
        assertNotNull(coDevice.getAttrs().get("co_status"));
        assertTrue(coDevice.getAttrs().get("co_manual_status") instanceof StringSelectAttribute);
        assertTrue(coDevice.getAttrs().get("co_status") instanceof StringSelectAttribute);

        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) coDevice.getAttrs().get("co_manual_status");
        StringSelectAttribute statusAttr = (StringSelectAttribute) coDevice.getAttrs().get("co_status");
        assertEquals(AttributeStatus.NORMAL.getName(), manualStatusAttr.getState().getValue());
        assertEquals(AttributeStatus.NORMAL.getName(), statusAttr.getState().getValue());
    }
}
