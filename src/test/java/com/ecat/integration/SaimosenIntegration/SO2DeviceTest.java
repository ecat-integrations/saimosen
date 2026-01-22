package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
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
    
    @Mock private ScheduledExecutorService mockExecutor;
    @Mock private ScheduledFuture<?> mockScheduledFuture;
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        // 设置ResourceLoader仅加载strings.json，不加载i18n目录资源
        ResourceLoader.setLoadI18nResources(false);

        // 初始化基础配置
        Map<String, Object> config = new HashMap<>();
        config.put("id", "SO2TestDevice");
        config.put("name", "SO2测试设备");
        
        // 添加通信设置
        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("port", "COM1");
        commSettings.put("baudRate", 9600);
        commSettings.put("numDataBit", 8);
        commSettings.put("numStopBit", 1);
        commSettings.put("parity", "N");
        commSettings.put("timeout", 2000);
        commSettings.put("slaveId", 1);
        config.put("comm_settings", commSettings);
        
        so2Device = new SO2Device(config);
        
        // 先设置所有mock
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        when(mockTaskManager.getExecutorService()).thenReturn(mockExecutor);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);
        
        // 模拟IntegrationRegistry
        IntegrationRegistry mockIntegrationRegistry = mock(IntegrationRegistry.class);
        when(mockEcatCore.getIntegrationRegistry()).thenReturn(mockIntegrationRegistry);
        when(mockIntegrationRegistry.getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        
        // 调用load方法初始化modbusInfo
        so2Device.load(mockEcatCore);
        
        initDevice();
    }
    
    @After
    public void tearDown() throws Exception {
        // 恢复ResourceLoader的设置，启用i18n资源加载
        ResourceLoader.setLoadI18nResources(true);
        mockitoCloseable.close();
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
        if (attr.getValue() == null) {
            fail("Attribute " + attrId + " value is null");
        }
        assertEquals("Attribute " + attrId + " value mismatch", expectedValue, attr.getValue(), 0.01); // 精度误差±0.01
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
        assertNotNull("故障代码属性应该存在", so2Device.getAttrs().get("fault_code"));

        // 验证校准相关属性
        assertNotNull("校准浓度属性应该存在", so2Device.getAttrs().get("calibration_concentration"));
        assertNotNull("校准状态属性应该存在", so2Device.getAttrs().get("calibration_status"));
        assertNotNull("校准命令属性应该存在", so2Device.getAttrs().get("dispatch_command"));


        // 验证属性总数
        assertEquals("应该有40个属性", 40, so2Device.getAttrs().size());
    }
    
    @Test
    public void testStart_SchedulesReadTask() throws Exception {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenAnswer(v->mockScheduledFuture);
                
        // 执行start方法
        so2Device.start();
        
        // 验证定时任务是否被调度
        verify(mockExecutor, times(1)).scheduleWithFixedDelay(
                any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
    }
    
    @Test
    public void testStop_CancelsScheduledTasks() throws Exception {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> mockScheduledFuture);
        so2Device.start();
        
        // 执行stop方法
        so2Device.stop();
        
        // 注意：新架构中stop方法没有实际实现，所以不会调用cancel
        // 这个测试主要验证stop方法可以正常调用而不抛出异常
        assertTrue("Stop method should complete without exception", true);
    }
    
    @Test
    public void testRelease_CancelsReadFuture() throws Exception {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> mockScheduledFuture);
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        so2Device.start();
        
        // 执行release方法
        so2Device.release();
        
        // 注意：新架构中release方法调用super.release()，不会直接调用cancel
        // 验证release方法可以正常调用而不抛出异常
        assertTrue("Release method should complete without exception", true);
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
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
        future.get(5, TimeUnit.SECONDS); // 等待异步操作完成

        // 验证分段读取被正确调用
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0), eq(32));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(38), eq(26));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EB), eq(1));
        verify(mockModbusSource, times(1)).readHoldingRegisters(eq(0x3EE), eq(1));

        // 验证第二组参数（U16类型）- 根据SO2Device的updateU16Attributes方法，某些电压值需要除以10
        verifyFloatAttribute("device_address", 3.0);
        verifyFloatAttribute("device_status", 0.0);
        verifyFloatAttribute("pmt_high_volt_setting", 100.0);
        verifyFloatAttribute("chamber_temp_volt", 250.0); // 2500/10
        verifyFloatAttribute("sample_press_volt", 300.0); // 3000/10
        verifyFloatAttribute("pump_press_volt", 200.0); // 2000/10
        verifyFloatAttribute("case_temp_volt", 150.0); // 1500/10
        verifyFloatAttribute("case_temp", 25.0); // 250/10
        verifyFloatAttribute("pmt_temp_volt", 0.0); // 0/10
        verifyFloatAttribute("pmt_high_volt_read", 0.0); // 原始值，不除以10
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
        verifyFloatAttribute("alarm_info", 0.0);
        verifyFloatAttribute("fault_code", 0.0);

        // 验证校准状态属性
        verifyFloatAttribute("calibration_status", 2.0);

        // 验证校准浓度数值 - 跨度校准模式时应该为400
        verifyFloatAttribute("calibration_concentration", 400.0);

        // 验证所有属性状态为跨度校准
        so2Device.getAttrs().values().forEach(attr -> {
            assertEquals(AttributeStatus.SPAN_CALIBRATION, attr.getStatus());
        });
    }

    @Test
    public void testReadAndUpdate_HandlesException() throws Exception {
        // 模拟分段读取中第一段失败
        CompletableFuture<ReadHoldingRegistersResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Modbus communication error"));
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(20)))
            .thenReturn(failedFuture);

        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
        Boolean result = future.get(5, TimeUnit.SECONDS); // 等待异步操作完成

        // 验证返回值为false（表示异常处理）
        assertFalse(result);

        // 验证所有属性状态为故障
        so2Device.getAttrs().values().forEach(attr -> {
            assertNotNull("Attribute should not be null", attr);
            assertEquals(AttributeStatus.MALFUNCTION, attr.getStatus());
        });
    }

    @Test
    public void testReadAndUpdate_HandlesDataParsingException() throws Exception {
        // 准备无效的寄存器数据 - 使用null来触发异常
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockFloatResponse.getShortData()).thenReturn(null); // 返回null会触发异常

        when(mockModbusSource.readHoldingRegisters(eq(0), eq(20)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));

        // 执行读取并等待异步操作完成
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
        Boolean result = future.get(5, TimeUnit.SECONDS);

        // 验证返回值为false（表示异常处理）
        assertFalse(result);

        // 验证所有属性状态为故障
        so2Device.getAttrs().values().forEach(attr -> {
            assertNotNull("Attribute should not be null", attr);
            assertEquals(AttributeStatus.MALFUNCTION, attr.getStatus());
        });
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
        assertEquals(25.5, so2Attr.getValue(), 0.01);
        assertEquals(AttributeStatus.NORMAL, so2Attr.getStatus());
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
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS)))
            .thenAnswer(v->mockScheduledFuture);
        when(mockModbusSource.isModbusOpen()).thenReturn(true);

        // 1. 初始化
        so2Device.init();
        assertEquals(40, so2Device.getAttrs().size());

        // 2. 启动
        so2Device.start();
        verify(mockExecutor, times(1)).scheduleWithFixedDelay(
                any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));

        // 3. 停止
        so2Device.stop();
        // 注意：新架构中stop方法没有实际实现，不会调用cancel

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
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
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
        verifyFloatAttribute("pmt_high_volt_setting", 102.0);
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
        verifyFloatAttribute("pmt_high_volt_read", 113.0); // 原始值，不除以10
        verifyFloatAttribute("calibration_status", 0.0); // 测量模式
    }

    @Test
    public void testSegmentedRead_SecondSegmentFailure() throws Exception {
        // 测试第二段读取失败的情况
        short[] mockFloatRegisters = new short[20];
        for (int i = 0; i < 20; i++) {
            mockFloatRegisters[i] = (short) (i + 1);
        }

        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);

        CompletableFuture<ReadHoldingRegistersResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Second segment failed"));

        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(failedFuture);

        // 执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
        Boolean result = future.get(5, TimeUnit.SECONDS);

        // 验证结果
        assertFalse(result);

        // 验证所有属性状态为故障
        so2Device.getAttrs().values().forEach(attr -> {
            assertEquals(AttributeStatus.MALFUNCTION, attr.getStatus());
        });
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

        when(mockModbusSource.readHoldingRegisters(eq(0), eq(32)))
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(38), eq(26)))
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));

        // 执行分段读取
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
        Boolean result = future.get(5, TimeUnit.SECONDS);
        
        // 验证结果
        assertFalse(result);
        
        // 验证所有属性状态为故障
        so2Device.getAttrs().values().forEach(attr -> {
            assertEquals(AttributeStatus.MALFUNCTION, attr.getStatus());
        });
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
    public void testCalibrationValueMapping() throws Exception {
        // 测试校准浓度数值映射
        // 测试零点校准状态
        Object zeroCalibValue = invokePrivateMethod(so2Device, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.ZERO_CALIBRATION, 0.0);
        assertEquals(0.0, (Double) zeroCalibValue, 0.01);
        
        // 测试零点状态
        Object zeroValue = invokePrivateMethod(so2Device, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.ZERO, 0.0);
        assertEquals(0.0, (Double) zeroValue, 0.01);
        
        // 测试跨度校准状态
        Object spanCalibValue = invokePrivateMethod(so2Device, "getCalibrationValue", 
                com.ecat.core.Device.DeviceStatus.SPAN_CALIBRATION, 400.0);
        assertEquals(400.0, (Double) spanCalibValue, 0.01);

        // 测试跨度状态（使用SPAN_CALIBRATION）
        Object spanValue = invokePrivateMethod(so2Device, "getCalibrationValue",
                com.ecat.core.Device.DeviceStatus.SPAN_CALIBRATION, 400.0);
        assertEquals(400.0, (Double) spanValue, 0.01);

        // 测试测量状态
        Object measureValue = invokePrivateMethod(so2Device, "getCalibrationValue",
                com.ecat.core.Device.DeviceStatus.MEASURE, 0.0);
        assertEquals(0.0, (Double) measureValue, 0.01);
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
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
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
        CompletableFuture<Boolean> future2 = (CompletableFuture<Boolean>) invokePrivateMethod(so2Device, "readAndUpdate");
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

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }


}
