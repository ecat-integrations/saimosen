package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;

/**
 * SampleTube设备类的单元测试
 * 基于最新协议版本（地址0x0000-0x000A，共11个寄存器）
 * 
 * @version V1.1
 */
public class SampleTubeTest {

    @Mock
    private EcatCore mockCore;
    
    @Mock
    private ModbusIntegration mockModbusIntegration;
    
    @Mock
    private ModbusSource mockModbusSource;
    
    @Mock
    private ScheduledExecutorService mockExecutor;
    
    @Mock
    private ReadHoldingRegistersResponse mockReadResponse;
    
    @Mock
    private WriteRegisterResponse mockWriteResponse;
    @Mock 
    private BusRegistry mockBusRegistry;

    private SampleTube sampleTube;
    private Map<String, Object> config;

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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // 创建测试配置
        config = new HashMap<>();
        config.put("id", "sample-tube-001");
        config.put("name", "采样管加热器");
        config.put("class", "sample.tube");
        config.put("sn", "ST-001");
        config.put("vendor", "赛默森环保");
        config.put("model", "SMS-D-H");
        
        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("port", "COM1");
        commSettings.put("baudRate", 9600);
        commSettings.put("numDataBit", 8);
        commSettings.put("numStopBit", 1);
        commSettings.put("parity", "N");
        commSettings.put("slaveId", 1);
        commSettings.put("timeout", 2000);
        config.put("comm_settings", commSettings);

        // 创建SampleTube实例
        sampleTube = new SampleTube(config);
    }

    @Test
    public void testConstructor() {
        assertNotNull(sampleTube);
        assertEquals("sample-tube-001", sampleTube.getId());
        assertEquals("采样管加热器", sampleTube.getName());
        assertEquals("ST-001", sampleTube.getSn());
        assertEquals("赛默森环保", sampleTube.getVendor());
        assertEquals("SMS-D-H", sampleTube.getModel());
    }

    @Test
    public void testLoad() {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        
        sampleTube.load(mockCore);
        
        // 验证设备已加载
        assertNotNull(sampleTube.getCore());
    }

    @Test
    public void testInit() {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 验证设备已初始化
        assertNotNull(sampleTube.getAttrs());
        assertTrue(sampleTube.getAttrs().size() > 0);
        
        // 验证关键属性已创建（根据新协议）
        assertTrue(sampleTube.getAttrs().containsKey("humidity"));
        assertTrue(sampleTube.getAttrs().containsKey("sample_gas_temperature"));
        assertTrue(sampleTube.getAttrs().containsKey("heating_tube_target_temp"));
        assertTrue(sampleTube.getAttrs().containsKey("heating_tube_actual_temp"));
        assertTrue(sampleTube.getAttrs().containsKey("device_address"));
    }

    @Test
    public void testStartAndStop() {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockCore.getTaskManager()).thenReturn(mock(TaskManager.class));
        when(mockCore.getTaskManager().getExecutorService()).thenReturn(mockExecutor);
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 测试启动
        sampleTube.start();
        verify(mockExecutor, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        
        // 测试停止
        sampleTube.stop();
        // 注意：这里无法直接验证cancel调用，因为ScheduledFuture是mock的
    }

    @Test
    public void testReadRegisters() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        // 模拟读取响应（根据新协议，共11个寄存器）
        short[] mockData = new short[11];
        mockData[0] = 350;  // 样气湿度 35.0%
        mockData[1] = 250;  // 样气温度 25.0°C
        mockData[2] = 0;    // 校准状态
        mockData[3] = 0;    // 保留
        mockData[4] = 1;    // 设备地址
        mockData[5] = 150;  // 样气流速 15.0 L/min
        mockData[6] = 450;  // 加热管实际温度 45.0°C
        mockData[7] = 50;   // 风机功率 5.0W
        mockData[8] = 100;  // 加热带功率 10.0W
        mockData[9] = 0;    // 未使用
        mockData[10] = 450; // 加热管设置温度 45.0°C
        
        when(mockReadResponse.getShortData()).thenReturn(mockData);
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(mockReadResponse));
        when(mockModbusSource.acquire()).thenReturn("testKey");

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockCore.getBusRegistry()).thenReturn(mockBusRegistry);
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 执行读取操作（通过反射调用私有方法）
        try {
            java.lang.reflect.Method readMethod = SampleTube.class.getDeclaredMethod("readRegisters");
            readMethod.setAccessible(true);
            readMethod.invoke(sampleTube);
            
            // 等待异步操作完成
            Thread.sleep(100);
            
            // 验证读取调用（读取11个寄存器）
            verify(mockModbusSource, times(1)).readHoldingRegisters(0, 11);
            
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }

    @Test
    public void testSetHeatingTubeTargetTemp() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(mockWriteResponse));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 验证属性已创建
        assertNotNull("heating_tube_target_temp属性应该存在", sampleTube.getAttrs().get("heating_tube_target_temp"));
        
        // 测试设置加热管目标温度
        float targetTemp = 45.5f;
        sampleTube.setHeatingTubeTargetTemp(targetTemp);
        
        // 等待异步操作完成
        Thread.sleep(100);
        
        // 验证写入调用（45.5 * 10 = 455，写入地址10）
        verify(mockModbusSource, times(1)).writeRegister(10, 455);
    }

    @Test
    public void testSetHeatingTubeActualTemp() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(mockWriteResponse));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 测试设置加热管实际温度
        float actualTemp = 40.0f;
        sampleTube.setHeatingTubeActualTemp(actualTemp);
        
        // 等待异步操作完成
        Thread.sleep(100);
        
        // 验证写入调用（40.0 * 10 = 400，写入地址6）
        verify(mockModbusSource, times(1)).writeRegister(6, 400);
    }

    @Test
    public void testSetDeviceAddress() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(mockWriteResponse));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 测试设置设备地址
        sampleTube.setDeviceAddress(5);
        Thread.sleep(100);
        verify(mockModbusSource, times(1)).writeRegister(4, 5);
        
        // 测试设置无效地址（超出范围）
        sampleTube.setDeviceAddress(256);
        Thread.sleep(100);
        // 无效地址不应该写入，仍然只有1次调用
        verify(mockModbusSource, times(1)).writeRegister(anyInt(), anyInt());
    }

    @Test
    public void testSetCalibrationStatus() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(mockWriteResponse));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 测试设置校准状态
        sampleTube.setCalibrationStatus(0);
        
        // 等待异步操作完成
        Thread.sleep(100);
        
        // 验证写入调用（写入地址2，值为0）
        verify(mockModbusSource, times(1)).writeRegister(2, 0);
    }

    @Test
    public void testRelease() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockCore.getTaskManager()).thenReturn(mock(TaskManager.class));
        when(mockCore.getTaskManager().getExecutorService()).thenReturn(mockExecutor);
        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        sampleTube.start();
        sampleTube.release();
        
        // 验证资源释放
        verify(mockModbusSource, times(1)).closeModbus();
    }

    @Test
    public void testAttributeCreation() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 验证所有必要的属性都已创建（根据新协议）
        Map<String, com.ecat.core.State.AttributeBase<?>> attrs = sampleTube.getAttrs();
        
        assertTrue("样气湿度属性应该存在", attrs.containsKey("humidity"));
        assertTrue("样气温度属性应该存在", attrs.containsKey("sample_gas_temperature"));
        assertTrue("校准状态属性应该存在", attrs.containsKey("calibration_status"));
        assertTrue("保留字段3应该存在", attrs.containsKey("reserved_3"));
        assertTrue("设备地址属性应该存在", attrs.containsKey("device_address"));
        assertTrue("样气流速属性应该存在", attrs.containsKey("gas_flow_rate"));
        assertTrue("加热管实际温度属性应该存在", attrs.containsKey("heating_tube_actual_temp"));
        assertTrue("风机功率属性应该存在", attrs.containsKey("fan_power"));
        assertTrue("加热带功率属性应该存在", attrs.containsKey("heating_belt_power"));
        assertTrue("未使用字段9应该存在", attrs.containsKey("reserved_9"));
        assertTrue("加热管设置温度属性应该存在", attrs.containsKey("heating_tube_target_temp"));
        
        // 验证属性总数（11个寄存器对应11个属性）
        assertEquals("应该有11个属性", 11, attrs.size());
    }

    @Test
    public void testErrorHandling() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        
        // 模拟读取失败
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("通信失败");
                }));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        
        sampleTube.load(mockCore);
        sampleTube.init();
        
        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        
        // 执行读取操作（通过反射调用私有方法）
        try {
            java.lang.reflect.Method readMethod = SampleTube.class.getDeclaredMethod("readRegisters");
            readMethod.setAccessible(true);
            readMethod.invoke(sampleTube);
            
            // 等待异步操作完成
            Thread.sleep(100);
            
            // 验证错误处理
            // 注意：由于异步执行，这里主要验证方法调用不会抛出异常
            
        } catch (Exception e) {
            fail("错误处理测试失败: " + e.getMessage());
        }
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testSampleTubeI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 模拟Core和Integration
            when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
            when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
            when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
            when(mockModbusSource.acquire()).thenReturn("testKey");

            sampleTube.load(mockCore);
            sampleTube.init();

            // 验证环境监测属性
            TestTools.assertAttributeDisplayName(sampleTube, "humidity", "样气湿度");
            TestTools.assertAttributeDisplayName(sampleTube, "sample_gas_temperature", "样气温度");
            TestTools.assertAttributeDisplayName(sampleTube, "gas_flow_rate", "样气流速");

            // 验证加热管控制属性
            TestTools.assertAttributeDisplayName(sampleTube, "heating_tube_actual_temp", "加热管实际温度");
            TestTools.assertAttributeDisplayName(sampleTube, "heating_tube_target_temp", "加热管设置温度");

            // 验证功率监测属性
            TestTools.assertAttributeDisplayName(sampleTube, "fan_power", "风机功率");
            TestTools.assertAttributeDisplayName(sampleTube, "heating_belt_power", "加热带功率");

            // 验证设备控制属性
            TestTools.assertAttributeDisplayName(sampleTube, "device_address", "设备地址");
            TestTools.assertAttributeDisplayName(sampleTube, "calibration_status", "校准状态");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testSampleTubeI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 模拟Core和Integration
            when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
            when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
            when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
            when(mockModbusSource.acquire()).thenReturn("testKey");

            sampleTube.load(mockCore);
            sampleTube.init();

            // 验证绑定设备后的displayname仍然正确
            TestTools.assertAttributeDisplayName(sampleTube, "humidity", "样气湿度");
            TestTools.assertAttributeDisplayName(sampleTube, "sample_gas_temperature", "样气温度");
            TestTools.assertAttributeDisplayName(sampleTube, "heating_tube_target_temp", "加热管设置温度");
            TestTools.assertAttributeDisplayName(sampleTube, "device_address", "设备地址");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }
} 
