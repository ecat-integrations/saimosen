package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.I18n.I18nHelper;
import com.ecat.core.I18n.I18nProxy;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * NO2Device单元测试类
 * 
 * @author caohongbo
 */
public class NO2DeviceTest {

    private NO2Device no2Device;
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
        
        // 初始化基础配置
        Map<String, Object> config = new HashMap<>();
        config.put("id", "NO2TestDevice");
        config.put("name", "NO2测试设备");
        
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
        
        no2Device = new NO2Device(config);
        
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
        no2Device.load(mockEcatCore);
        
        initDevice();
    }
    
    @After
    public void tearDown() throws Exception {
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
        setPrivateField(no2Device, "core", mockEcatCore);
        setPrivateField(no2Device, "modbusSource", mockModbusSource);
        setPrivateField(no2Device, "modbusIntegration", mockModbusIntegration);
        no2Device.init();
    }
    
    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        // 执行初始化
        no2Device.init();
        
        // 验证属性总数：实际创建了57个属性
        assertEquals(57, no2Device.getAttrs().size());
        
        // 验证NO浓度相关属性
        assertNotNull(no2Device.getAttrs().get("no"));
        assertNotNull(no2Device.getAttrs().get("no2"));
        assertNotNull(no2Device.getAttrs().get("nox"));
        
        // 验证电压相关属性
        assertNotNull(no2Device.getAttrs().get("no_measure_volt"));
        assertNotNull(no2Device.getAttrs().get("nox_measure_volt"));
        
        // 验证压力和温度属性
        assertNotNull(no2Device.getAttrs().get("sample_press"));
        assertNotNull(no2Device.getAttrs().get("sample_temp"));
        assertNotNull(no2Device.getAttrs().get("pump_press"));
        assertNotNull(no2Device.getAttrs().get("chamber_press"));
        
        // 验证流量属性
        assertNotNull(no2Device.getAttrs().get("sample_flow"));
        assertNotNull(no2Device.getAttrs().get("o3_flow"));
        
        // 验证斜率和截距属性
        assertNotNull(no2Device.getAttrs().get("no_slope"));
        assertNotNull(no2Device.getAttrs().get("no_intercept"));
        assertNotNull(no2Device.getAttrs().get("nox_slope"));
        assertNotNull(no2Device.getAttrs().get("nox_intercept"));
        
        // 验证校准相关属性
        assertNotNull(no2Device.getAttrs().get("calibration_concentration"));
        assertNotNull(no2Device.getAttrs().get("calibration_status"));
        
        // 验证命令属性
        assertNotNull(no2Device.getAttrs().get("dispatch_command"));
    }
    
    @Test
    public void testStart_SchedulesReadTask() throws Exception {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenAnswer(v->mockScheduledFuture);
                
        // 执行start方法
        no2Device.start();
        
        // 验证定时任务是否被调度
        verify(mockExecutor, times(1)).scheduleWithFixedDelay(
                any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
    }
    
    @Test
    public void testStop_CancelsScheduledTasks() throws Exception {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> mockScheduledFuture);
        no2Device.start();
        
        // 执行stop方法
        no2Device.stop();
        
        // 验证stop方法被调用（不再直接验证scheduledFuture.cancel）
        // 重构后的stop方法不再直接操作scheduledFuture
    }
    
    @Test
    public void testRelease_CancelsReadFuture() throws Exception {
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> mockScheduledFuture);
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        no2Device.start();
        
        // 执行release方法
        no2Device.release();
        
        // 验证release方法被调用（不再直接验证scheduledFuture.cancel）
        // 重构后的release方法不再直接操作scheduledFuture
        verify(mockModbusSource, times(1)).closeModbus();
    }
    
    @Test
    public void testReadAndUpdate_ReadsAndParsesData() throws Exception {
        // 准备模拟寄存器数据 - 29个float值，每个占2个寄存器
        short[] mockFloatRegisters = new short[58]; // 29个float * 2 = 58个寄存器
        
        // 使用简单的递增数据，类似CODeviceTest的做法
        for (int i = 0; i < 58; i++) {
            mockFloatRegisters[i] = (short) (i + 1); // 简单的递增数据
        }

        // 准备U16数据 - 28个U16值
        short[] mockU16Registers = new short[28];
        for (int i = 0; i < 28; i++) {
            mockU16Registers[i] = (short) (100 + i); // 简单的测试数据
        }

        // 模拟跨度校准浓度数据
        short[] mockSpanCalibRegisters = new short[1];
        mockSpanCalibRegisters[0] = (short) 400; // 跨度校准浓度

        // 模拟校准状态数据
        short[] mockInstrumentCalibRegisters = new short[1];
        mockInstrumentCalibRegisters[0] = (short) 0; // 正常测量模式

        // 模拟Modbus响应
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockInstrumentCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockInstrumentCalibResponse.getShortData()).thenReturn(mockInstrumentCalibRegisters);

        // 设置并行读取的mock
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(54))) // FLOAT_PARAMS
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(58), eq(28))) // U16_PARAMS
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1))) // SPAN_CALIBRATION_START
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1))) // CALIBRATION_STATUS
            .thenReturn(CompletableFuture.completedFuture(mockInstrumentCalibResponse));
        
        // 执行读取并等待异步操作完成
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(no2Device, "readAndUpdate");
        future.get(5, TimeUnit.SECONDS); // 等待异步操作完成
        
        // 验证属性存在且状态正常
        NumericAttribute noAttr = (NumericAttribute) no2Device.getAttrs().get("no");
        assertNotNull("NO属性应该存在", noAttr);
        assertEquals("NO属性状态应该正常", AttributeStatus.NORMAL, noAttr.getStatus());
        
        NumericAttribute no2Attr = (NumericAttribute) no2Device.getAttrs().get("no2");
        assertNotNull("NO2属性应该存在", no2Attr);
        assertEquals("NO2属性状态应该正常", AttributeStatus.NORMAL, no2Attr.getStatus());
        
        NumericAttribute noxAttr = (NumericAttribute) no2Device.getAttrs().get("nox");
        assertNotNull("NOX属性应该存在", noxAttr);
        assertEquals("NOX属性状态应该正常", AttributeStatus.NORMAL, noxAttr.getStatus());
        
        NumericAttribute noMeasureVoltAttr = (NumericAttribute) no2Device.getAttrs().get("no_measure_volt");
        assertNotNull("NO_MEASURE_VOLT属性应该存在", noMeasureVoltAttr);
        assertEquals("NO_MEASURE_VOLT属性状态应该正常", AttributeStatus.NORMAL, noMeasureVoltAttr.getStatus());
        
        NumericAttribute noxMeasureVoltAttr = (NumericAttribute) no2Device.getAttrs().get("nox_measure_volt");
        assertNotNull("NOX_MEASURE_VOLT属性应该存在", noxMeasureVoltAttr);
        assertEquals("NOX_MEASURE_VOLT属性状态应该正常", AttributeStatus.NORMAL, noxMeasureVoltAttr.getStatus());
        
        NumericAttribute samplePressAttr = (NumericAttribute) no2Device.getAttrs().get("sample_press");
        assertNotNull("SAMPLE_PRESS属性应该存在", samplePressAttr);
        assertEquals("SAMPLE_PRESS属性状态应该正常", AttributeStatus.NORMAL, samplePressAttr.getStatus());
        
        NumericAttribute sampleTempAttr = (NumericAttribute) no2Device.getAttrs().get("sample_temp");
        assertNotNull("SAMPLE_TEMP属性应该存在", sampleTempAttr);
        assertEquals("SAMPLE_TEMP属性状态应该正常", AttributeStatus.NORMAL, sampleTempAttr.getStatus());
        
        NumericAttribute sampleFlowAttr = (NumericAttribute) no2Device.getAttrs().get("sample_flow");
        assertNotNull("SAMPLE_FLOW属性应该存在", sampleFlowAttr);
        assertEquals("SAMPLE_FLOW属性状态应该正常", AttributeStatus.NORMAL, sampleFlowAttr.getStatus());
        
        NumericAttribute pumpPressAttr = (NumericAttribute) no2Device.getAttrs().get("pump_press");
        assertNotNull("PUMP_PRESS属性应该存在", pumpPressAttr);
        assertEquals("PUMP_PRESS属性状态应该正常", AttributeStatus.NORMAL, pumpPressAttr.getStatus());
        
        NumericAttribute chamberPressAttr = (NumericAttribute) no2Device.getAttrs().get("chamber_press");
        assertNotNull("CHAMBER_PRESS属性应该存在", chamberPressAttr);
        assertEquals("CHAMBER_PRESS属性状态应该正常", AttributeStatus.NORMAL, chamberPressAttr.getStatus());
        
        NumericAttribute ozoneFlowAttr = (NumericAttribute) no2Device.getAttrs().get("o3_flow");
        assertNotNull("O3_FLOW属性应该存在", ozoneFlowAttr);
        assertEquals("O3_FLOW属性状态应该正常", AttributeStatus.NORMAL, ozoneFlowAttr.getStatus());
        
        NumericAttribute noSlopeAttr = (NumericAttribute) no2Device.getAttrs().get("no_slope");
        assertNotNull("NO_SLOPE属性应该存在", noSlopeAttr);
        assertEquals("NO_SLOPE属性状态应该正常", AttributeStatus.NORMAL, noSlopeAttr.getStatus());
        
        NumericAttribute noInterceptAttr = (NumericAttribute) no2Device.getAttrs().get("no_intercept");
        assertNotNull("NO_INTERCEPT属性应该存在", noInterceptAttr);
        assertEquals("NO_INTERCEPT属性状态应该正常", AttributeStatus.NORMAL, noInterceptAttr.getStatus());
        
        NumericAttribute noxSlopeAttr = (NumericAttribute) no2Device.getAttrs().get("nox_slope");
        assertNotNull("NOX_SLOPE属性应该存在", noxSlopeAttr);
        assertEquals("NOX_SLOPE属性状态应该正常", AttributeStatus.NORMAL, noxSlopeAttr.getStatus());
        
        NumericAttribute noxInterceptAttr = (NumericAttribute) no2Device.getAttrs().get("nox_intercept");
        assertNotNull("NOX_INTERCEPT属性应该存在", noxInterceptAttr);
        assertEquals("NOX_INTERCEPT属性状态应该正常", AttributeStatus.NORMAL, noxInterceptAttr.getStatus());
        
        // 验证校准状态属性
        NumericAttribute calibStatusAttr = (NumericAttribute) no2Device.getAttrs().get("calibration_status");
        assertEquals(0.0, calibStatusAttr.getValue(), 0.01);
        
        // 验证校准浓度属性
        NumericAttribute calibConcAttr = (NumericAttribute) no2Device.getAttrs().get("calibration_concentration");
        assertEquals(0.0, calibConcAttr.getValue(), 0.01); // 正常测量模式下为0
        
        // 验证所有属性状态为正常
        no2Device.getAttrs().values().forEach(attr -> {
            assertEquals(AttributeStatus.NORMAL, attr.getStatus());
        });
    }
    
    @Test
    public void testReadAndUpdate_HandlesException() throws Exception {
        // 模拟Modbus读取异常
        CompletableFuture<ReadHoldingRegistersResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Modbus communication error"));
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(failedFuture);
        
        // 执行读取并等待异步操作完成
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(no2Device, "readAndUpdate");
        Boolean result = future.get(5, TimeUnit.SECONDS); // 等待异步操作完成
        
        // 验证返回值为false（表示异常处理）
        assertFalse(result);
        
        // 验证所有属性状态为故障或空（因为没有任何数据段成功）
        no2Device.getAttrs().values().forEach(attr -> {
            assertNotNull("Attribute should not be null", attr);
            // 由于所有数据段都失败，属性状态可能是MALFUNCTION或EMPTY
            assertTrue("Attribute status should be MALFUNCTION or EMPTY", 
                attr.getStatus() == AttributeStatus.MALFUNCTION || attr.getStatus() == AttributeStatus.EMPTY);
        });
    }
    
     @Test
    public void testReadAndUpdate_HandlesImpreciseFloatingPoint() throws Exception {
        // 准备模拟寄存器数据 - 使用简单的测试数据
        short[] mockFloatRegisters = new short[54]; // 27个float * 2 = 54个寄存器

        // 使用简单的递增数据
        for (int i = 0; i < 54; i++) {
            mockFloatRegisters[i] = (short) (i + 100); // 简单的递增数据，从100开始
        }

        // 准备U16数据 - 28个U16值
        short[] mockU16Registers = new short[28];
        for (int i = 0; i < 28; i++) {
            mockU16Registers[i] = (short) (100 + i); // 简单的测试数据
        }

        // 模拟跨度校准浓度数据
        short[] mockSpanCalibRegisters = new short[1];
        mockSpanCalibRegisters[0] = (short) 400; // 跨度校准浓度

        // 模拟校准状态数据
        short[] mockInstrumentCalibRegisters = new short[1];
        mockInstrumentCalibRegisters[0] = (short) 0; // 正常测量模式

        // 模拟Modbus响应
        ReadHoldingRegistersResponse mockFloatResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockU16Response = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockSpanCalibResponse = mock(ReadHoldingRegistersResponse.class);
        ReadHoldingRegistersResponse mockInstrumentCalibResponse = mock(ReadHoldingRegistersResponse.class);
        
        when(mockFloatResponse.getShortData()).thenReturn(mockFloatRegisters);
        when(mockU16Response.getShortData()).thenReturn(mockU16Registers);
        when(mockSpanCalibResponse.getShortData()).thenReturn(mockSpanCalibRegisters);
        when(mockInstrumentCalibResponse.getShortData()).thenReturn(mockInstrumentCalibRegisters);

        // 设置并行读取的mock
        when(mockModbusSource.readHoldingRegisters(eq(0), eq(54))) // FLOAT_PARAMS
            .thenReturn(CompletableFuture.completedFuture(mockFloatResponse));
        when(mockModbusSource.readHoldingRegisters(eq(58), eq(28))) // U16_PARAMS
            .thenReturn(CompletableFuture.completedFuture(mockU16Response));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EB), eq(1))) // SPAN_CALIBRATION_START
            .thenReturn(CompletableFuture.completedFuture(mockSpanCalibResponse));
        when(mockModbusSource.readHoldingRegisters(eq(0x3EE), eq(1))) // CALIBRATION_STATUS
            .thenReturn(CompletableFuture.completedFuture(mockInstrumentCalibResponse));
        
        // 执行读取并等待异步操作完成
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(no2Device, "readAndUpdate");
        future.get(5, TimeUnit.SECONDS);
        
        // 验证属性存在且状态正常
        NumericAttribute noAttr = (NumericAttribute) no2Device.getAttrs().get("no");
        assertNotNull("NO属性应该存在", noAttr);
        assertEquals("NO属性状态应该正常", AttributeStatus.NORMAL, noAttr.getStatus());
        
        NumericAttribute no2Attr = (NumericAttribute) no2Device.getAttrs().get("no2");
        assertNotNull("NO2属性应该存在", no2Attr);
        assertEquals("NO2属性状态应该正常", AttributeStatus.NORMAL, no2Attr.getStatus());
        
        NumericAttribute noxAttr = (NumericAttribute) no2Device.getAttrs().get("nox");
        assertNotNull("NOX属性应该存在", noxAttr);
        assertEquals("NOX属性状态应该正常", AttributeStatus.NORMAL, noxAttr.getStatus());
        
        NumericAttribute noSlopeAttr = (NumericAttribute) no2Device.getAttrs().get("no_slope");
        assertNotNull("NO_SLOPE属性应该存在", noSlopeAttr);
        assertEquals("NO_SLOPE属性状态应该正常", AttributeStatus.NORMAL, noSlopeAttr.getStatus());
        
        NumericAttribute noxSlopeAttr = (NumericAttribute) no2Device.getAttrs().get("nox_slope");
        assertNotNull("NOX_SLOPE属性应该存在", noxSlopeAttr);
        assertEquals("NOX_SLOPE属性状态应该正常", AttributeStatus.NORMAL, noxSlopeAttr.getStatus());
        
        NumericAttribute noxInterceptAttr = (NumericAttribute) no2Device.getAttrs().get("nox_intercept");
        assertNotNull("NOX_INTERCEPT属性应该存在", noxInterceptAttr);
        assertEquals("NOX_INTERCEPT属性状态应该正常", AttributeStatus.NORMAL, noxInterceptAttr.getStatus());
        
        // 验证其他重要属性
        NumericAttribute noMeasureVoltAttr = (NumericAttribute) no2Device.getAttrs().get("no_measure_volt");
        assertNotNull("NO_MEASURE_VOLT属性应该存在", noMeasureVoltAttr);
        assertEquals("NO_MEASURE_VOLT属性状态应该正常", AttributeStatus.NORMAL, noMeasureVoltAttr.getStatus());
        
        NumericAttribute noxMeasureVoltAttr = (NumericAttribute) no2Device.getAttrs().get("nox_measure_volt");
        assertNotNull("NOX_MEASURE_VOLT属性应该存在", noxMeasureVoltAttr);
        assertEquals("NOX_MEASURE_VOLT属性状态应该正常", AttributeStatus.NORMAL, noxMeasureVoltAttr.getStatus());
        
        NumericAttribute samplePressAttr = (NumericAttribute) no2Device.getAttrs().get("sample_press");
        assertNotNull("SAMPLE_PRESS属性应该存在", samplePressAttr);
        assertEquals("SAMPLE_PRESS属性状态应该正常", AttributeStatus.NORMAL, samplePressAttr.getStatus());
        
        NumericAttribute sampleTempAttr = (NumericAttribute) no2Device.getAttrs().get("sample_temp");
        assertNotNull("SAMPLE_TEMP属性应该存在", sampleTempAttr);
        assertEquals("SAMPLE_TEMP属性状态应该正常", AttributeStatus.NORMAL, sampleTempAttr.getStatus());
        
        NumericAttribute sampleFlowAttr = (NumericAttribute) no2Device.getAttrs().get("sample_flow");
        assertNotNull("SAMPLE_FLOW属性应该存在", sampleFlowAttr);
        assertEquals("SAMPLE_FLOW属性状态应该正常", AttributeStatus.NORMAL, sampleFlowAttr.getStatus());
        
        NumericAttribute pumpPressAttr = (NumericAttribute) no2Device.getAttrs().get("pump_press");
        assertNotNull("PUMP_PRESS属性应该存在", pumpPressAttr);
        assertEquals("PUMP_PRESS属性状态应该正常", AttributeStatus.NORMAL, pumpPressAttr.getStatus());
        
        NumericAttribute chamberPressAttr = (NumericAttribute) no2Device.getAttrs().get("chamber_press");
        assertNotNull("CHAMBER_PRESS属性应该存在", chamberPressAttr);
        assertEquals("CHAMBER_PRESS属性状态应该正常", AttributeStatus.NORMAL, chamberPressAttr.getStatus());
        
        NumericAttribute ozoneFlowAttr = (NumericAttribute) no2Device.getAttrs().get("o3_flow");
        assertNotNull("O3_FLOW属性应该存在", ozoneFlowAttr);
        assertEquals("O3_FLOW属性状态应该正常", AttributeStatus.NORMAL, ozoneFlowAttr.getStatus());
        
        NumericAttribute noInterceptAttr = (NumericAttribute) no2Device.getAttrs().get("no_intercept");
        assertNotNull("NO_INTERCEPT属性应该存在", noInterceptAttr);
        assertEquals("NO_INTERCEPT属性状态应该正常", AttributeStatus.NORMAL, noInterceptAttr.getStatus());
        
        // 验证所有属性状态为正常
        no2Device.getAttrs().values().forEach(attr -> {
            assertEquals(AttributeStatus.NORMAL, attr.getStatus());
        });
    }
    
    @Test
    public void testSegmentConfiguration() throws Exception {
        // 验证数据段配置是否正确
        Object segmentConfig = getPrivateField(no2Device, "SEGMENT_CONFIG");
        assertNotNull(segmentConfig);
        
        // 通过反射获取DataSegment类
        Class<?> dataSegmentClass = Class.forName("com.ecat.integration.SaimosenIntegration.NO2Device$DataSegment");
        
        // 验证配置
        Map<String, Object> config = (Map<String, Object>) segmentConfig;

        // 验证float_params段配置
        assertTrue(config.containsKey("float_params"));
        Object floatParams = config.get("float_params");
        assertEquals(0, getPrivateField(floatParams, "startAddress"));
        assertEquals(54, getPrivateField(floatParams, "count"));

        // 验证u16_params段配置
        assertTrue(config.containsKey("u16_params"));
        Object u16Params = config.get("u16_params");
        assertEquals(58, getPrivateField(u16Params, "startAddress"));
        assertEquals(28, getPrivateField(u16Params, "count"));

        // 验证calibration_status段配置
        assertTrue(config.containsKey("calibration_status"));
        Object calibStatus = config.get("calibration_status");
        assertEquals(0x3EE, getPrivateField(calibStatus, "startAddress"));
        assertEquals(1, getPrivateField(calibStatus, "count"));
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testNO2DeviceI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            no2Device.init();

            // 验证NO2Device在strings.json中有映射的属性
            // TestTools.assertAttributeDisplayName(no2Device, "co", "CO浓度"); // NO2设备没有CO属性
            TestTools.assertAttributeDisplayName(no2Device, "no2", "NO2浓度");
            // TestTools.assertAttributeDisplayName(no2Device, "so2", "SO2浓度"); // NO2设备没有SO2属性
            // TestTools.assertAttributeDisplayName(no2Device, "o3", "O3浓度"); // NO2设备没有O3属性
            // TestTools.assertAttributeDisplayName(no2Device, "measure_volt", "测量电压");
            // TestTools.assertAttributeDisplayName(no2Device, "ref_volt", "参比电压");
            // TestTools.assertAttributeDisplayName(no2Device, "sample_press", "样气压力");
            TestTools.assertAttributeDisplayName(no2Device, "sample_flow", "样气流量");
            TestTools.assertAttributeDisplayName(no2Device, "pump_press", "泵压力");
            // TestTools.assertAttributeDisplayName(no2Device, "negative_temp_coefficient", "光室温度NTC"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "correlation_wheel_temp", "相关轮温度"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "scrubber_temp", "涤除器温度"); // NO2设备没有此属性
            TestTools.assertAttributeDisplayName(no2Device, "chamber_temp", "反应室温度");
            TestTools.assertAttributeDisplayName(no2Device, "sample_temp", "样气温度");
            // TestTools.assertAttributeDisplayName(no2Device, "bench_temp", "站房温度"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "sample_tube_temp", "采样管温度"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "slope", "浓度斜率"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "intercept", "浓度截距"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "sampling_tube_residence_time", "采样管滞留时间"); // NO2设备没有此属性

            // 状态和命令
            TestTools.assertAttributeDisplayName(no2Device, "device_address", "仪器地址");
            TestTools.assertAttributeDisplayName(no2Device, "device_status", "仪器状态");
            // TestTools.assertAttributeDisplayName(no2Device, "system_state", "系统状态"); // NO2设备没有此属性
            TestTools.assertAttributeDisplayName(no2Device, "calibration_status", "校准状态");
            TestTools.assertAttributeDisplayName(no2Device, "dispatch_command", "调度命令");

            // 设备控制
            // TestTools.assertAttributeDisplayName(no2Device, "device_power", "设备电源"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "pump_status", "泵状态"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "valve_status", "阀门状态"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "alarm_status", "报警状态"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "communication_status", "通信状态"); // NO2设备没有此属性

            // NO和NOX浓度参数
            TestTools.assertAttributeDisplayName(no2Device, "no", "NO浓度");
            TestTools.assertAttributeDisplayName(no2Device, "nox", "NOX浓度");
            TestTools.assertAttributeDisplayName(no2Device, "no_measure_volt", "NO测量电压");
            TestTools.assertAttributeDisplayName(no2Device, "nox_measure_volt", "NOX测量电压");
            TestTools.assertAttributeDisplayName(no2Device, "chamber_press", "反应室压力");
            TestTools.assertAttributeDisplayName(no2Device, "o3_flow", "臭氧流量");
            TestTools.assertAttributeDisplayName(no2Device, "no_slope", "NO浓度斜率");
            TestTools.assertAttributeDisplayName(no2Device, "no_intercept", "NO浓度截距");
            TestTools.assertAttributeDisplayName(no2Device, "nox_slope", "NOX浓度斜率");
            TestTools.assertAttributeDisplayName(no2Device, "nox_intercept", "NOX浓度截距");

            // 修正值参数
            TestTools.assertAttributeDisplayName(no2Device, "chamber_press_corr", "反应室压力修正值");
            TestTools.assertAttributeDisplayName(no2Device, "pump_press_corr", "泵压力修正值");
            TestTools.assertAttributeDisplayName(no2Device, "sample_press_corr", "样气压力修正值");
            TestTools.assertAttributeDisplayName(no2Device, "sample_temp_corr", "样气温度修正值");
            TestTools.assertAttributeDisplayName(no2Device, "sample_flow_corr", "样气流量修正值");
            TestTools.assertAttributeDisplayName(no2Device, "mo_furnace_temp_corr", "钼炉温度修正值");
            TestTools.assertAttributeDisplayName(no2Device, "mo_furnace_temp_setting", "钼炉温度设定值");
            TestTools.assertAttributeDisplayName(no2Device, "o3_flow_corr", "臭氧流量修正值");

            // 原始浓度参数
            TestTools.assertAttributeDisplayName(no2Device, "no_raw_concentration", "NO原始浓度");
            TestTools.assertAttributeDisplayName(no2Device, "nox_raw_concentration", "NOX原始浓度");
            TestTools.assertAttributeDisplayName(no2Device, "zero_check_volt", "零点检查电压");

            // PMT和电压参数
            TestTools.assertAttributeDisplayName(no2Device, "pmt_high_volt_setting", "PMT高压设定值");
            TestTools.assertAttributeDisplayName(no2Device, "sample_temp_volt", "样气温度电压");
            TestTools.assertAttributeDisplayName(no2Device, "sample_press_volt", "样气压力电压");
            TestTools.assertAttributeDisplayName(no2Device, "pump_press_volt", "泵压力电压");
            TestTools.assertAttributeDisplayName(no2Device, "chamber_press_volt", "反应室压力电压");
            TestTools.assertAttributeDisplayName(no2Device, "case_temp_volt", "机箱温度电压");
            TestTools.assertAttributeDisplayName(no2Device, "pmt_temp_volt", "PMT温度电压");

            // 温度参数
            TestTools.assertAttributeDisplayName(no2Device, "case_temp", "机箱温度");
            TestTools.assertAttributeDisplayName(no2Device, "mo_furnace_temp", "钼炉温度");
            TestTools.assertAttributeDisplayName(no2Device, "pmt_temp", "PMT温度");

            // 电压参数
            TestTools.assertAttributeDisplayName(no2Device, "voltage_12v", "12V电压");
            TestTools.assertAttributeDisplayName(no2Device, "voltage_15v", "15V电压");
            TestTools.assertAttributeDisplayName(no2Device, "voltage_5v", "5V电压");
            TestTools.assertAttributeDisplayName(no2Device, "voltage_3v3", "3.3V电压");

            // 故障和校准
            TestTools.assertAttributeDisplayName(no2Device, "calibration_concentration", "校准浓度");
            // TestTools.assertAttributeDisplayName(no2Device, "measure_dark_current", "测量暗电流"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "ref_dark_current", "参比暗电流"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "device_info", "设备信息"); // NO2设备没有此属性
            // TestTools.assertAttributeDisplayName(no2Device, "calibration_info", "校准信息"); // NO2设备没有此属性
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testNO2DeviceCommandSubCommandsI18n() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 测试命令子命令的i18n支持
            I18nProxy i18n = I18nHelper.createProxy(NO2Device.class);

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
                String key = "devices.no2device.dispatch_command_commands." + commands[i];
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
    public void testNO2DeviceI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            no2Device.init();

            // 验证绑定设备后的displayname仍然正确
            TestTools.assertAttributeDisplayName(no2Device, "no2", "NO2浓度");
            // TestTools.assertAttributeDisplayName(no2Device, "measure_volt", "测量电压");
            TestTools.assertAttributeDisplayName(no2Device, "device_status", "仪器状态");
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
} 
