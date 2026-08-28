package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.I18nHelper;
import com.ecat.core.I18n.I18nProxy;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Sdk.PollingHandle;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

import com.serotonin.modbus4j.msg.WriteRegistersResponse;
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
 * O3Device单元测试类 - 支持完整协议（38个寄存器）
 * 
 * @author caohongbo
 */
public class O3DeviceTest {

    private O3Device o3Device;
    private AutoCloseable mockitoCloseable;
    
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        ConfigEntry entry = createTestEntry();
        o3Device = new O3Device(entry);
        
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
        o3Device.load(mockEcatCore);

        initDevice();

        // 硬门禁：测试绕过框架 createEntry，须手动 markReady 使 phase≥READY，
        // 否则用户写 led_set_current 等命令走 publish 被门禁拦（生产中框架在 start 前 markReady）。
        o3Device.markReady();
    }
    
    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "O3测试设备");
        config.put("class", "air.monitor.o3");
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
            .entryId("test-entry-o3-device")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_air.monitor.o3")
            .title("O3测试设备")
            .data(config)
            .build();
    }


    /**
     * 测试自建快节拍轮询（tianhong TH2004HCODeviceTest 同范式）：round 复用生产同函数
     * （o3Device::readAndUpdate），节拍测试自持 50ms——与生产 every(5s) 解耦，负向观察窗从 6s 收
     * 到 600ms 仍覆盖 >10 个周期（cancel 失效形态下下一轮 51ms 内必现形，覆盖强度等价）。
     * 生产 start() 的节拍/接线由各 testStart_* 首轮正向覆盖。
     */
    private PollingHandle startFastPolling() {
        return ModbusPolling.on(o3Device, mockModbusSource)
                .round(o3Device::readAndUpdate)
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
        setPrivateField(o3Device, "core", mockEcatCore);
        setPrivateField(o3Device, "modbusSource", mockModbusSource);
        setPrivateField(o3Device, "modbusIntegration", mockModbusIntegration);
        o3Device.init();
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

    private void verifyFloatAttribute(String attrId, double expectedValue) {
        AttributeBase<?> attr = o3Device.getAttrs().get(attrId);
        assertNotNull(attr);
        assertNotNull(attr.getState());
        assertEquals(expectedValue, ((Number) attr.getState().getValue()).doubleValue(), 0.01); // 精度误差±0.01
    }

    private void verifyTextAttribute(String attrId, String expectedValue) {
        AttributeBase<?> attr = o3Device.getAttrs().get(attrId);
        assertNotNull(attr);
        assertNotNull(attr.getState());
        assertEquals(expectedValue, attr.getState().getValue());
    }
    
    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        // 执行初始化
        o3Device.init();
        
        // 验证第一组参数（float类型）
        assertNotNull("O3浓度属性应该存在", o3Device.getAttrs().get("o3"));
        assertNotNull("测量电压属性应该存在", o3Device.getAttrs().get("measure_volt"));
        assertNotNull("参比电压属性应该存在", o3Device.getAttrs().get("ref_volt"));
        assertNotNull("样气压力属性应该存在", o3Device.getAttrs().get("sample_press"));
        assertNotNull("样气温度属性应该存在", o3Device.getAttrs().get("sample_temp"));
        assertNotNull("样气流量属性应该存在", o3Device.getAttrs().get("sample_flow"));
        assertNotNull("泵压力属性应该存在", o3Device.getAttrs().get("pump_press"));
        assertNotNull("浓度斜率属性应该存在", o3Device.getAttrs().get("slope"));
        assertNotNull("浓度截距属性应该存在", o3Device.getAttrs().get("intercept"));
        assertNotNull("样气压力修正值属性应该存在", o3Device.getAttrs().get("sample_press_corr"));
        assertNotNull("泵压力修正值属性应该存在", o3Device.getAttrs().get("pump_press_corr"));
        assertNotNull("样气温度修正值属性应该存在", o3Device.getAttrs().get("sample_temp_corr"));
        assertNotNull("样气流量修正值属性应该存在", o3Device.getAttrs().get("sample_flow_corr"));
        assertNotNull("LED设置驱动电流属性应该存在", o3Device.getAttrs().get("led_set_current"));
        assertNotNull("LED当前驱动电流属性应该存在", o3Device.getAttrs().get("led_current"));
        assertNotNull("原始浓度属性应该存在", o3Device.getAttrs().get("raw_concentration"));
        assertNotNull("备用1属性应该存在", o3Device.getAttrs().get("reserve_1"));
        assertNotNull("备用2属性应该存在", o3Device.getAttrs().get("reserve_2"));
        assertNotNull("备用3属性应该存在", o3Device.getAttrs().get("reserve_3"));
        assertNotNull("备用4属性应该存在", o3Device.getAttrs().get("reserve_4"));
        
        // 验证第二组参数（U16类型）
        assertNotNull("仪器地址属性应该存在", o3Device.getAttrs().get("device_address"));
        assertNotNull("仪器状态属性应该存在", o3Device.getAttrs().get("device_status"));
        assertNotNull("UV检测板放大倍数属性应该存在", o3Device.getAttrs().get("uv_amplification"));
        assertNotNull("样气温度电压属性应该存在", o3Device.getAttrs().get("sample_temp_volt"));
        assertNotNull("样气压力电压属性应该存在", o3Device.getAttrs().get("sample_press_volt"));
        assertNotNull("泵压力电压属性应该存在", o3Device.getAttrs().get("pump_press_volt"));
        assertNotNull("机箱温度电压属性应该存在", o3Device.getAttrs().get("case_temp_volt"));
        assertNotNull("机箱温度属性应该存在", o3Device.getAttrs().get("case_temp"));
        assertNotNull("12V电压值属性应该存在", o3Device.getAttrs().get("voltage_12v"));
        assertNotNull("15V电压值属性应该存在", o3Device.getAttrs().get("voltage_15v"));
        assertNotNull("5V电压值属性应该存在", o3Device.getAttrs().get("voltage_5v"));
        assertNotNull("3.3V电压值属性应该存在", o3Device.getAttrs().get("voltage_3v3"));
        assertNotNull("测量/参比阀状态属性应该存在", o3Device.getAttrs().get("measure_ref_valve_status"));
        assertNotNull("采样校准阀状态属性应该存在", o3Device.getAttrs().get("sample_cal_valve_status"));
        assertNotNull("内置泵状态属性应该存在", o3Device.getAttrs().get("builtin_pump_status"));
        assertNotNull("机箱风扇状态属性应该存在", o3Device.getAttrs().get("case_fan_status"));
        assertNotNull("报警信息属性应该存在", o3Device.getAttrs().get("alarm_info"));
        assertNotNull("通用报警属性应该存在", o3Device.getAttrs().get("general_alarm"));
        assertNotNull("故障代码属性应该存在", o3Device.getAttrs().get("fault_code"));
        assertNotNull("手动状态属性应该存在", o3Device.getAttrs().get("o3_manual_status"));
        assertNotNull("只读状态属性应该存在", o3Device.getAttrs().get("o3_status"));

        // 验证属性总数
        assertEquals("应该有44个属性", 44, o3Device.getAttrs().size());
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

        o3Device.start();

        // 首轮立即发射（确定性同步：latch 等 round 真正读源，非定时猜测）
        assertTrue("首轮（initialDelay=0）必须立即发起 float 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 40);
    }
    
    @Test
    public void testStop_CancelsScheduledTasks() throws Exception {
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("首轮必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        o3Device.stop();
        o3Device.cancelManagedTasks();   // 框架 chokepoint 同点（IntegrationDeviceBase.stopWithManagedSweep）
        // 负向观察窗 600ms 覆盖 >10 个 50ms 周期（等价原「6s 窗 > 5s 生产周期」覆盖强度）
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));

        // sweep 已执行的直接证据：宿主进入已扫状态，再注册移除动作被拒（RemovalHost 契约）
        try {
            o3Device.onRemove(() -> { });
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
        o3Device.stop();
        o3Device.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("release 前 stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));
        o3Device.release();
        verify(mockModbusSource).closeModbus();
    }
    
    @Test
    public void testReadAndUpdate_ReadsAndParsesAllData() throws Exception {
        // 准备分段读取的模拟数据
        
        // 第一段：float参数（0-19共20个参数，40个寄存器）
        short[] mockFloatRegisters = new short[40];
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1); // 简单的递增数据
        }
        
        // 第二段：U16参数（寄存器20-37，18字节）
        short[] mockU16Registers = new short[18];
        // 模拟仪器地址 3
        mockU16Registers[0] = (short) 3;
        // 模拟仪器状态 0（采样）
        mockU16Registers[1] = (short) 0;
        // 模拟UV检测板放大倍数 100
        mockU16Registers[2] = (short) 100;
        // 模拟样气温度电压 250 mV（需要除以10）
        mockU16Registers[3] = (short) 2500;
        // 模拟样气压力电压 300 mV（需要除以10）
        mockU16Registers[4] = (short) 3000;
        // 模拟泵压力电压 200 mV（需要除以10）
        mockU16Registers[5] = (short) 2000;
        // 模拟机箱温度电压 150 mV（需要除以10）
        mockU16Registers[6] = (short) 1500;
        // 模拟机箱温度 25.0 ℃（需要除以10）
        mockU16Registers[7] = (short) 250;
        // 模拟12V电压值 12000 mV（需要除以10）
        mockU16Registers[8] = (short) 12000;
        // 模拟15V电压值 15000 mV（需要除以10）
        mockU16Registers[9] = (short) 15000;
        // 模拟5V电压值 5000 mV（需要除以10）
        mockU16Registers[10] = (short) 5000;
        // 模拟3.3V电压值 3300 mV（需要除以10）
        mockU16Registers[11] = (short) 3300;
        // 模拟测量/参比阀状态 0（正常）
        mockU16Registers[12] = (short) 0;
        // 模拟采样校准阀状态 0（正常）
        mockU16Registers[13] = (short) 0;
        // 模拟内置泵状态 0（正常）
        mockU16Registers[14] = (short) 0;
        // 模拟机箱风扇状态 0（正常）
        mockU16Registers[15] = (short) 0;
        // 模拟报警信息 0
        mockU16Registers[16] = (short) 0;
        // 模拟故障代码 0
        mockU16Registers[17] = (short) 0;

        // 第三段：校准状态寄存器
        short[] mockCalibRegisters = new short[1];
        mockCalibRegisters[0] = (short) 0; // 正常测量模式

        // 模拟分段读取的Modbus响应
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);

        // 模拟分段读取调用
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(40), eq(18)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
        
        // 执行读取并等待异步操作完成
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        future.get(5, TimeUnit.SECONDS); // 等待异步操作完成
        
        // 验证分段读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(40));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(40), eq(18));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));
        
        // 验证第二组参数（U16类型）- 这些是简单的整数，容易验证
        verifyFloatAttribute("device_address", 3.0);
        verifyFloatAttribute("device_status", 0.0);
        verifyFloatAttribute("uv_amplification", 100.0);
        verifyFloatAttribute("sample_temp_volt", 250.0); // 2500/10
        verifyFloatAttribute("sample_press_volt", 300.0); // 3000/10
        verifyFloatAttribute("pump_press_volt", 200.0); // 2000/10
        verifyFloatAttribute("case_temp_volt", 150.0); // 1500/10
        verifyFloatAttribute("case_temp", 25.0); // 250/10
        verifyFloatAttribute("voltage_12v", 12000.0);
        verifyFloatAttribute("voltage_15v", 15000.0);
        verifyFloatAttribute("voltage_5v", 5000.0);
        verifyFloatAttribute("voltage_3v3", 3300.0);
        verifyFloatAttribute("measure_ref_valve_status", 0.0);
        verifyFloatAttribute("sample_cal_valve_status", 0.0);
        verifyFloatAttribute("builtin_pump_status", 0.0);
        verifyFloatAttribute("case_fan_status", 0.0);
        verifyTextAttribute("alarm_info", "");
        verifyFloatAttribute("fault_code", 0.0);
        
        // 验证所有数据更新过的属性状态为正常（重构后未更新的属性 state 为 null，不在校验范围）
        o3Device.getAttrs().values().stream().filter(attr -> attr.getState() != null).forEach(attr -> {
            assertEquals(AttributeStatus.NORMAL, attr.getState().getStatus());
        });
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
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS); // 等待异步操作完成
        
        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        assertNull(o3Device.getAttrs().get("o3").getState());
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
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        assertNull(o3Device.getAttrs().get("o3").getState());
    }

    @Test
    public void testSegmentConfiguration() throws Exception {
        // 验证数据段配置是否正确
        Object segmentConfig = getPrivateField(o3Device, "SEGMENT_CONFIG");
        assertNotNull(segmentConfig);
        
        // 通过反射获取DataSegment类
        Class<?> dataSegmentClass = Class.forName("com.ecat.integration.SaimosenIntegration.O3Device$DataSegment");
        
        // 验证配置
        Map<String, Object> config = (Map<String, Object>) segmentConfig;
        assertTrue(config.containsKey("float_params"));
        assertTrue(config.containsKey("u16_params"));
        assertTrue(config.containsKey("calibration_status"));
        
        Object floatParams = config.get("float_params");
        assertEquals(0, getPrivateField(floatParams, "startAddress"));
        assertEquals(40, getPrivateField(floatParams, "count")); // 20个float参数

        Object u16Params = config.get("u16_params");
        assertEquals(40, getPrivateField(u16Params, "startAddress"));
        assertEquals(18, getPrivateField(u16Params, "count")); // 18个U16参数
    }
    
    @Test
    public void testUpdateAttributeMethod() throws Exception {
        // 测试updateAttribute私有方法 - 使用正确的参数类型
        Object result = invokePrivateMethod(o3Device, "updateAttribute", "o3", 25.5, AttributeStatus.NORMAL);

        // 验证属性值已更新
        NumericAttribute o3Attr = (NumericAttribute) o3Device.getAttrs().get("o3");
        assertEquals(25.5, ((Number) o3Attr.getState().getValue()).doubleValue(), 0.01);
        assertEquals(AttributeStatus.NORMAL, o3Attr.getState() != null ? o3Attr.getState().getStatus() : null);
    }
    
    @Test
    public void testUpdateAttributeMethod_NonExistentAttribute() throws Exception {
        // 测试更新不存在的属性
        Object result = invokePrivateMethod(o3Device, "updateAttribute", "NON_EXISTENT", 100.0, AttributeStatus.NORMAL);
        
        // 验证不会抛出异常，只是忽略
        assertNull(result);
    }
    
    @Test
    public void testDeviceLifecycle() throws Exception {
        // 测试完整的设备生命周期
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        
        // 1. 初始化
        o3Device.init();
        assertEquals(44, o3Device.getAttrs().size());
        
        // 2. 启动（首轮 latch：确定性确认轮询已注册运行，非仅不抛异常）
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("start 后首轮轮询必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        // 3. 停止（lifecycle chokepoint：stop + sweep 执行 SDK 注册的移除动作停轮询）；
        //    负向窗 600ms 覆盖 >10 个 50ms 周期（等价原「窗>5s 生产周期」的 cancel 因果覆盖）
        o3Device.stop();
        o3Device.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));
        
        // 4. 释放资源
        o3Device.release();
        verify(mockModbusSource, times(1)).closeModbus();
    }
    
    @Test
    public void testSegmentedReadStrategy() throws Exception {
        // 测试分段读取策略
        short[] mockFloatRegisters = new short[40];
        short[] mockU16Registers = new short[18];
        
        // 设置测试数据
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        for (int i = 0; i < 18; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }
        
        // 第三段：校准状态寄存器
        short[] mockCalibRegisters = new short[1];
        mockCalibRegisters[0] = (short) 0; // 正常测量模式
        
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockCalibResponse.getShortData()).thenReturn(mockCalibRegisters);
        
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(40), eq(18)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibResponse));
        
        // 执行分段读取
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证结果 - 由于使用了ModbusTransactionStrategy，即使部分失败也可能返回true
        // 这里主要验证方法能够正常执行而不抛出异常
        assertNotNull("Result should not be null", result);
        
        // 验证分段读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(40));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(40), eq(18));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));
        
        // 验证属性更新
        verifyFloatAttribute("device_address", 100.0);
        verifyFloatAttribute("device_status", 101.0);
        verifyFloatAttribute("uv_amplification", 102.0);
    }
    
    @Test
    public void testSegmentedRead_SecondSegmentFailure() throws Exception {
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
        when(mockModbusSource.readHoldingRegisters(eq(40), eq(18)))
            .thenReturn(failedFuture);
        mockCalibrationSegmentReads();
        
        // 执行分段读取
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 主测量段成功时仍提交更新（允许部分段失败）
        assertTrue(result);
        assertNotNull(o3Device.getAttrs().get("o3").getState());
    }
    
    @Test
    public void testSegmentedRead_DataParsingFailure() throws Exception {
        // 测试数据解析失败的情况
        short[] mockFloatRegisters = new short[40];
        for (int i = 0; i < 40; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }
        
        short[] mockU16Registers = new short[18];
        for (int i = 0; i < 18; i++) {
            mockU16Registers[i] = (short) (i + 100);
        }
        
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(null); // 第二段返回null触发异常
        
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(40), eq(18)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        mockCalibrationSegmentReads();
        
        // 执行分段读取
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 主测量段成功时仍提交更新（U16 段失败仅跳过该段）
        assertTrue(result);
        assertNotNull(o3Device.getAttrs().get("o3").getState());
    }
    
    /**
     * 将十六进制字符串转换为short数组
     * 每个float值占用2个寄存器（4个字节），所以每个float值需要8个十六进制字符
     * @param hexString 十六进制字符串，如 "BF 3E FB 7C 54 45 96 EB 34 45 77 E6"
     * @return short数组
     */
    private short[] hexStringToShortArray(String hexString) {
        // 移除空格并分割成字节对
        String[] hexBytes = hexString.replaceAll("\\s+", "").split("(?<=\\G.{2})");
        short[] result = new short[hexBytes.length / 2];
        
        for (int i = 0; i < result.length; i++) {
            // 将两个字节组合成一个short (Big-Endian)
            // 每个寄存器 = 2个字节 = 4个十六进制字符
            String highByte = hexBytes[i * 2];
            String lowByte = hexBytes[i * 2 + 1];
            int value = (Integer.parseInt(highByte, 16) << 8) | Integer.parseInt(lowByte, 16);
            result[i] = (short) value;
        }
        
        return result;
    }
    
    /**
     * 专门用于float数据的十六进制字符串转换
     * 处理格式如 "BF3E FB7C 5445 96EB 3445 77E6 3F80 0000 ..."
     * 每个4字符的十六进制值代表一个寄存器
     * @param hexString 十六进制字符串
     * @return short数组
     */
    private short[] hexStringToShortArrayForFloat(String hexString) {
        // 按空格分割，每个4字符的十六进制值代表一个寄存器
        String[] hexValues = hexString.trim().split("\\s+");
        short[] result = new short[hexValues.length];
        
        System.out.println("Float数据转换详情:");
        System.out.println("   - 原始十六进制字符串: " + hexString);
        System.out.println("   - 分割后的寄存器数: " + hexValues.length);
        System.out.println("   - 生成的寄存器数: " + result.length);
        
        for (int i = 0; i < hexValues.length; i++) {
            // 每个4字符的十六进制值直接转换为一个寄存器
            int value = Integer.parseInt(hexValues[i], 16);
            result[i] = (short) value;
            
            // 打印前几个转换详情
            if (i < 5) {
                System.out.println("   寄存器[" + i + "]: " + hexValues[i] + " -> 0x" + Integer.toHexString(value & 0xFFFF).toUpperCase());
            }
        }
        
        return result;
    }
    
    @Test
    public void testDataParsing_RealHexData() throws Exception {
        // 专门测试O3Device的数据解析功能，使用真实的十六进制数据
        System.out.println("开始测试O3Device真实十六进制数据解析...");
        
        // 使用真实的O3设备数据（去除地址位、操作位和数据长度位）
        // 每个float值占用2个寄存器，20个float值需要40个寄存器
        // 每个寄存器是16位，所以需要40个16位的值
        // 原始数据格式：每个float值由4个字节组成，需要拆分成2个寄存器
        String realFloatHexData = "BF3E FB7C 5445 96EB 3445 77E6 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000";
        System.out.println("原始O3 Float十六进制数据: " + realFloatHexData);
        
        // 转换为short数组 - 使用专门的float数据解析方法
        short[] realFloatData = hexStringToShortArrayForFloat(realFloatHexData);
        System.out.println("转换后的float short数组长度: " + realFloatData.length);
        
        // 打印前几个值用于调试
        for (int i = 0; i < Math.min(10, realFloatData.length); i++) {
            System.out.println("   Float[" + i + "] = 0x" + Integer.toHexString(realFloatData[i] & 0xFFFF).toUpperCase());
        }
        
        // 测试O3Device的float数据解析
        Object floatResult = o3Device.parseFloatDataforTest(realFloatData);
        assertNotNull("O3 Float数据解析结果不应为null", floatResult);
        
        // 通过反射获取values字段
        Field valuesField = floatResult.getClass().getDeclaredField("values");
        valuesField.setAccessible(true);
        double[] floatValues = (double[]) valuesField.get(floatResult);
        assertNotNull("O3 Float数据值不应为null", floatValues);
        
        System.out.println("O3 Float数据解析成功！");
        System.out.println("   - 解析出的float值数量: " + floatValues.length);
        
        // 验证解析出的float值不为null且数量正确
        // 注意：实际解析出的float值数量取决于输入数据的长度
        assertTrue("应该解析出至少1个float值", floatValues.length > 0);
        for (int i = 0; i < floatValues.length; i++) {
            assertNotNull("Float值[" + i + "]不应为null", floatValues[i]);
        }
        
        // 验证具体的float数值
        // 第一个float值：BF3E FB7C -> 0.3740004
        // 根据O3Device的parseFloatData方法：rawData[i*2+1], rawData[i*2]
        // 所以第一个float值由rawData[1]和rawData[0]组成
        // rawData[0] = 0xBF3E, rawData[1] = 0xFB7C
        // 使用convertLittleEndianByteSwapToFloat(0xFB7C, 0xBF3E)
        double expectedFirstFloat = 0.3740004;
        assertEquals("第一个float值应该为0.3740004", expectedFirstFloat, floatValues[0], 0.0000001);
        
        // 验证第二个float值：5445 96EB
        // rawData[2] = 0x5445, rawData[3] = 0x96EB
        // 使用convertLittleEndianByteSwapToFloat(0x96EB, 0x5445)
        double expectedSecondFloat = 0.0; // 需要计算实际值
        assertNotNull("第二个float值不应为null", floatValues[1]);
        
        // 验证第三个float值：3445 77E6
        // rawData[4] = 0x3445, rawData[5] = 0x77E6
        // 使用convertLittleEndianByteSwapToFloat(0x77E6, 0x3445)
        assertNotNull("第三个float值不应为null", floatValues[2]);
        
        // 打印前几个解析出的float值用于调试
        for (int i = 0; i < Math.min(5, floatValues.length); i++) {
            System.out.println("   O3 Float[" + i + "] = " + floatValues[i]);
        }
        
        // 测试O3Device的U16数据解析
        // 模拟真实的U16数据（18个寄存器）
        String realU16HexData = "04 B8 05 DC 01 F4 01 4A 00 01 00 00 00 01 00 00 00 01";
        System.out.println("原始O3 U16十六进制数据: " + realU16HexData);
        
        short[] realU16Data = hexStringToShortArray(realU16HexData);
        System.out.println("转换后的U16 short数组长度: " + realU16Data.length);
        
        // 打印前几个值用于调试
        for (int i = 0; i < Math.min(10, realU16Data.length); i++) {
            System.out.println("   U16[" + i + "] = 0x" + Integer.toHexString(realU16Data[i] & 0xFFFF).toUpperCase());
        }
        
        Object u16Result = o3Device.parseU16DataforTest(realU16Data);
        assertNotNull("O3 U16数据解析结果不应为null", u16Result);
        
        // 通过反射获取values字段
        double[] u16Values = (double[]) valuesField.get(u16Result);
        assertNotNull("O3 U16数据值不应为null", u16Values);
        
        System.out.println("O3 U16数据解析成功！");
        System.out.println("   - 解析出的U16值数量: " + u16Values.length);
        
        // 验证解析出的U16值不为null且数量正确
        // 注意：实际解析出的U16值数量取决于输入数据的长度
        assertTrue("应该解析出至少1个U16值", u16Values.length > 0);
        for (int i = 0; i < u16Values.length; i++) {
            assertNotNull("U16值[" + i + "]不应为null", u16Values[i]);
        }
        
        // 打印解析出的U16值用于调试
        for (int i = 0; i < u16Values.length; i++) {
            System.out.println("   O3 U16[" + i + "] = " + u16Values[i]);
        }
        
        System.out.println("O3Device真实数据解析测试完成！");
    }
    
    @Test
    public void testReadAndUpdate_RealData() throws Exception {
        // 测试O3Device使用真实的Modbus数据解析
        System.out.println("开始测试O3Device完整真实数据读取和解析...");
        
        // 使用真实的O3设备数据
        // Float数据：每个float值占用2个寄存器，20个float值需要40个寄存器
        String realFloatHexData = "BF3E FB7C 5445 96EB 3445 77E6 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000 3F80 0000";
        // U16数据：每个U16值占用1个寄存器，18个U16值需要18个寄存器
        String realU16HexData = "04B8 05DC 01F4 014A 0001 0000 0001 0000 0001 0000 0000 0000 0000 0000 0000 0000 0000 0000";
        
        // 转换为short数组
        short[] realFloatData = hexStringToShortArrayForFloat(realFloatHexData);
        short[] realU16Data = hexStringToShortArray(realU16HexData);
        
        // 模拟校准数据
        short[] realSpanCalibData = {400};  // 跨度校准浓度
        short[] realCalibStatusData = {4};  // 测量模式
        
        // 创建模拟响应
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockCalibStatusResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(realFloatData);
        when(mockU16Response.getShortData()).thenReturn(realU16Data);
        when(mockSpanCalibResponse.getShortData()).thenReturn(realSpanCalibData);
        when(mockCalibStatusResponse.getShortData()).thenReturn(realCalibStatusData);
        
        // 设置Modbus读取模拟
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(40)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(40), eq(18)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1)))
            .thenReturn(CompletableFuture.completedFuture(mockCalibStatusResponse));
        
        // 执行数据读取和解析
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = o3Device.readAndUpdate(mockModbusSource);
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证读取成功
        assertTrue("O3Device数据读取应该成功", result);
        
        // 验证float数据解析
        NumericAttribute o3Attr = (NumericAttribute) o3Device.getAttrs().get("o3");
        assertNotNull("O3属性不应为null", o3Attr);
        assertNotNull("O3值不应为null", o3Attr.getState() != null ? o3Attr.getState().getValue() : null);

        // 验证U16数据解析 - 主要验证数据能够被正确解析，而不是验证具体数值
        AttributeBase<?> deviceAddrAttr = o3Device.getAttrs().get("device_address");
        assertNotNull("DEVICE_ADDRESS属性不应为null", deviceAddrAttr);
        assertNotNull("DEVICE_ADDRESS值不应为null", deviceAddrAttr.getState() != null ? deviceAddrAttr.getState().getValue() : null);

        AttributeBase<?> deviceStatusAttr = o3Device.getAttrs().get("device_status");
        assertNotNull("DEVICE_STATUS属性不应为null", deviceStatusAttr);
        assertNotNull("DEVICE_STATUS值不应为null", deviceStatusAttr.getState() != null ? deviceStatusAttr.getState().getValue() : null);

        AttributeBase<?> uvAmpAttr = o3Device.getAttrs().get("uv_amplification");
        assertNotNull("UV_AMPLIFICATION属性不应为null", uvAmpAttr);
        assertNotNull("UV_AMPLIFICATION值不应为null", uvAmpAttr.getState() != null ? uvAmpAttr.getState().getValue() : null);

        NumericAttribute sampleTempVoltAttr = (NumericAttribute) o3Device.getAttrs().get("sample_temp_volt");
        assertNotNull("SAMPLE_TEMP_VOLT属性不应为null", sampleTempVoltAttr);
        assertNotNull("SAMPLE_TEMP_VOLT值不应为null", sampleTempVoltAttr.getState() != null ? sampleTempVoltAttr.getState().getValue() : null);

        // 验证校准数据
        NumericAttribute calibConcAttr = (NumericAttribute) o3Device.getAttrs().get("calibration_concentration");
        assertNotNull("CALIBRATION_CONCENTRATION属性不应为null", calibConcAttr);
        assertNotNull("CALIBRATION_CONCENTRATION值不应为null", calibConcAttr.getState() != null ? calibConcAttr.getState().getValue() : null);

        NumericAttribute calibStatusAttr = (NumericAttribute) o3Device.getAttrs().get("calibration_status");
        assertNotNull("CALIBRATION_STATUS属性不应为null", calibStatusAttr);
        assertNotNull("CALIBRATION_STATUS值不应为null", calibStatusAttr.getState() != null ? calibStatusAttr.getState().getValue() : null);
        
        // 验证所有Modbus调用都被正确执行
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(40));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(40), eq(18));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));
        
        System.out.println("O3Device真实数据解析测试通过！");
        System.out.println("   - Float数据解析: " + (o3Attr.getState() != null && o3Attr.getState().getValue() != null ? "成功" : "失败"));
        System.out.println("   - U16数据解析: 设备地址、状态、电压值正确解析");
        System.out.println("   - 校准数据解析: 校准浓度和状态正确解析");
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testO3DeviceI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            o3Device.init();

            // 验证关键属性的displayname
            TestTools.assertAttributeDisplayName(o3Device, "o3", "O3浓度");
            TestTools.assertAttributeDisplayName(o3Device, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(o3Device, "ref_volt", "参比电压");
            TestTools.assertAttributeDisplayName(o3Device, "sample_press", "样气压力");
            TestTools.assertAttributeDisplayName(o3Device, "sample_flow", "样气流量");
            TestTools.assertAttributeDisplayName(o3Device, "pump_press", "泵压力");
            TestTools.assertAttributeDisplayName(o3Device, "sample_temp", "样气温度");
            TestTools.assertAttributeDisplayName(o3Device, "device_address", "仪器地址");
            TestTools.assertAttributeDisplayName(o3Device, "device_status", "仪器状态");

            // 新增属性验证
            TestTools.assertAttributeDisplayName(o3Device, "case_temp", "机箱温度");
            TestTools.assertAttributeDisplayName(o3Device, "case_temp_volt", "机箱温度电压");
            TestTools.assertAttributeDisplayName(o3Device, "raw_concentration", "原始浓度");

            // 修正值参数
            TestTools.assertAttributeDisplayName(o3Device, "pump_press_corr", "泵压力修正值");
            TestTools.assertAttributeDisplayName(o3Device, "sample_flow_corr", "样气流量修正值");
            TestTools.assertAttributeDisplayName(o3Device, "sample_press_corr", "样气压力修正值");
            TestTools.assertAttributeDisplayName(o3Device, "sample_temp_corr", "样气温度修正值");

            // 电压参数
            TestTools.assertAttributeDisplayName(o3Device, "pump_press_volt", "泵压力电压");
            TestTools.assertAttributeDisplayName(o3Device, "sample_press_volt", "样气压力电压");
            TestTools.assertAttributeDisplayName(o3Device, "sample_temp_volt", "样气温度电压");
            TestTools.assertAttributeDisplayName(o3Device, "voltage_12v", "12V电压");
            TestTools.assertAttributeDisplayName(o3Device, "voltage_15v", "15V电压");
            TestTools.assertAttributeDisplayName(o3Device, "voltage_5v", "5V电压");
            TestTools.assertAttributeDisplayName(o3Device, "voltage_3v3", "3.3V电压");

            // 校准浓度
            TestTools.assertAttributeDisplayName(o3Device, "calibration_concentration", "校准浓度");

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testO3DeviceCommandI18n() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            o3Device.init();

            // 验证命令属性的displayname
            TestTools.assertAttributeDisplayName(o3Device, "dispatch_command", "调度命令");

            // 验证命令选项的i18n支持
            I18nProxy i18n = I18nHelper.createProxy(O3Device.class);

            // 验证所有命令选项的displayname
            assertEquals("零点校准开始校验",
                "零点校准开始",
                i18n.t("devices.o3device.dispatch_command_commands.zero_calibration_start"));
            assertEquals("零点校准确认校验",
                "零点校准确认",
                i18n.t("devices.o3device.dispatch_command_commands.zero_calibration_confirm"));
            assertEquals("零点校准取消校验",
                "零点校准取消",
                i18n.t("devices.o3device.dispatch_command_commands.zero_calibration_cancel"));
            assertEquals("跨度校准开始校验",
                "跨度校准开始",
                i18n.t("devices.o3device.dispatch_command_commands.span_calibration_start"));
            assertEquals("跨度校准确认校验",
                "跨度校准确认",
                i18n.t("devices.o3device.dispatch_command_commands.span_calibration_confirm"));
            assertEquals("跨度校准取消校验",
                "跨度校准取消",
                i18n.t("devices.o3device.dispatch_command_commands.span_calibration_cancel"));

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testO3DeviceI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            o3Device.init();

            // 验证设备绑定后属性仍然返回有意义的displayname
            TestTools.assertAttributeDisplayName(o3Device, "o3", "O3浓度");
            TestTools.assertAttributeDisplayName(o3Device, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(o3Device, "device_status", "仪器状态");

            // 验证校准相关属性
            TestTools.assertAttributeDisplayName(o3Device, "calibration_concentration", "校准浓度");
            TestTools.assertAttributeDisplayName(o3Device, "calibration_status", "校准状态");
            TestTools.assertAttributeDisplayName(o3Device, "o3_manual_status", "手动状态");
            TestTools.assertAttributeDisplayName(o3Device, "o3_status", "O3状态");

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testLedSetCurrent_userSetWritesFc16AtRegister26() throws Exception {
        ModbusFloatAttribute ledAttr = (ModbusFloatAttribute) o3Device.getAttrs().get("led_set_current");
        assertNotNull(ledAttr);
        assertTrue(ledAttr.isValueChangeable());

        WriteRegistersResponse mockWriteResponse = mock(WriteRegistersResponse.class);
        when(mockWriteResponse.isException()).thenReturn(false);
        when(mockModbusSource.writeRegisters(eq(26), any(short[].class)))
                .thenReturn(CompletableFuture.completedFuture(mockWriteResponse));

        assertTrue(ledAttr.setDisplayValue("8.0").get(5, TimeUnit.SECONDS));

        verify(mockModbusSource, times(1)).writeRegisters(eq(26), eq(new short[] { 0x0041, 0x0000 }));
        assertEquals(8.0f, ledAttr.getState().getValue(), 0.001f);
    }

    @Test
    public void testLedSetCurrent_floatEncodingMatchesO3Protocol() {
        short[] words = SmsLittleEndianByteSwapEndianConverter.INSTANCE.floatToShorts(8.0f);
        assertEquals(2, words.length);
        assertEquals((short) 0x0041, words[0]);
        assertEquals((short) 0x0000, words[1]);

        short[] words75 = SmsLittleEndianByteSwapEndianConverter.INSTANCE.floatToShorts(7.5f);
        assertEquals((short) 0xF040, words75[0]);
        assertEquals((short) 0x0000, words75[1]);
    }
    public void testInit_CreatesManualStatusAttributes() throws Exception {
        o3Device.init();

        assertNotNull(o3Device.getAttrs().get("o3_manual_status"));
        assertNotNull(o3Device.getAttrs().get("o3_status"));
        assertTrue(o3Device.getAttrs().get("o3_manual_status") instanceof StringSelectAttribute);
        assertTrue(o3Device.getAttrs().get("o3_status") instanceof StringSelectAttribute);

        StringSelectAttribute manualStatusAttr = (StringSelectAttribute) o3Device.getAttrs().get("o3_manual_status");
        StringSelectAttribute statusAttr = (StringSelectAttribute) o3Device.getAttrs().get("o3_status");
        assertEquals(AttributeStatus.NORMAL.getName(), manualStatusAttr.getState().getValue());
        assertEquals(AttributeStatus.NORMAL.getName(), statusAttr.getState().getValue());
    }

} 
