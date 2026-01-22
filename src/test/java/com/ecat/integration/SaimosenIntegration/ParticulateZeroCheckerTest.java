package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.I18n.I18nHelper;
import com.ecat.core.I18n.I18nProxy;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
// import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * 颗粒物零点检查设备单元测试类
 * 
 * @author coffee
 */
public class ParticulateZeroCheckerTest {
    
    private ParticulateZeroChecker checker;
    private AutoCloseable mockitoCloseable;

    @Mock private ScheduledExecutorService mockExecutor;
    @Mock private ScheduledFuture<Object> mockScheduledFuture;
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;
    @Mock private SaimosenIntegration mockSaimosenIntegration;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        // 初始化基础配置
        Map<String, Object> config = new HashMap<>();
        checker = new ParticulateZeroChecker(config);

        // 注入依赖
        setPrivateField(checker, "core", mockEcatCore);
        setPrivateField(checker, "modbusSource", mockModbusSource);
        setPrivateField(checker, "modbusIntegration", mockModbusIntegration);
        setPrivateField(checker, "integration", mockSaimosenIntegration);

        // mock modbusSource 的 acquire() 函数
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        // // mock readHoldingRegisters
        // ReadHoldingRegistersResponse mockReadResp = mock(ReadHoldingRegistersResponse.class);
        // when(mockReadResp.getShortData()).thenReturn(new short[]{0});
        // when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt())).thenReturn(
        //     CompletableFuture.completedFuture(mockReadResp)
        // );
        
        // mock writeRegister
        com.serotonin.modbus4j.msg.WriteRegisterResponse mockWriteResp = mock(WriteRegisterResponse.class);
        when(mockWriteResp.isException()).thenReturn(false);
        when(mockModbusSource.writeRegister(anyInt(), anyInt())).thenReturn(
            CompletableFuture.completedFuture(mockWriteResp)
        );

        // 初始化设备
        initDevice();

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        when(mockTaskManager.getExecutorService()).thenReturn(mockExecutor);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);
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

    private Object invokePrivateMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
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
        checker.init();
        setPrivateField(checker, "core", mockEcatCore);
        setPrivateField(checker, "modbusSource", mockModbusSource);
        setPrivateField(checker, "modbusIntegration", mockModbusIntegration);
    }

    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        // 执行初始化
        checker.init();

        // 验证PM10零点控制命令属性
        assertNotNull(checker.getAttrs().get("pm10_zero_check_command"));
        // 验证PM2.5零点控制命令属性
        assertNotNull(checker.getAttrs().get("pm2_5_zero_check_command"));
    }

    @Test
    public void testStart_WithDebugMode() throws Exception {
        // 设置调试模式
        setPrivateField(checker, "isDebug", true);

        when(mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(10L), eq(60L), eq(TimeUnit.SECONDS)))
            .thenAnswer(invocation-> mockScheduledFuture);

        // 执行start方法
        checker.start();

        // 验证定时任务是否被调度
        verify(mockExecutor, times(1)).scheduleWithFixedDelay(
            any(Runnable.class), eq(10L), eq(60L), eq(TimeUnit.SECONDS));

        ScheduledFuture<?> actualFuture = (ScheduledFuture<?>) getPrivateField(checker, "testControlFuture");
        assertEquals(mockScheduledFuture, actualFuture);
    }

    @Test
    public void testStart_WithoutDebugMode() throws Exception {
        // 设置非调试模式
        setPrivateField(checker, "isDebug", false);

        // 执行start方法
        checker.start();

        // 验证定时任务未被调度
        verify(mockExecutor, never()).scheduleWithFixedDelay(
            any(Runnable.class), any(Long.class), any(Long.class), any(TimeUnit.class));

        ScheduledFuture<?> actualFuture = (ScheduledFuture<?>) getPrivateField(checker, "testControlFuture");
        assertNull(actualFuture);
    }

    @Test
    public void testStop() throws Exception {
        // 设置模拟的ScheduledFuture
        setPrivateField(checker, "readFuture", mockScheduledFuture);
        setPrivateField(checker, "testControlFuture", mockScheduledFuture);

        // 执行stop方法
        checker.stop();

        // 验证任务是否被取消
        verify(mockScheduledFuture, times(2)).cancel(true);
    }

    @Test
    public void testRelease() throws Exception {
        // 设置模拟的ScheduledFuture
        setPrivateField(checker, "readFuture", mockScheduledFuture);
        setPrivateField(checker, "testControlFuture", mockScheduledFuture);

        // 执行release方法
        checker.release();

        // 验证任务是否被取消
        verify(mockScheduledFuture, times(2)).cancel(true);
    }

    @Test
    public void testGetNextCommand() throws Exception {
        // 准备测试数据
        List<String> commands = new ArrayList<>();
        commands.add("command1");
        commands.add("command2");
        commands.add("command3");

        // 测试从空lastCommand开始
        // Object result1 = invokePrivateMethod(checker, "getNextCommand", commands, null);
        // assertEquals("command1", result1);

        // 测试从列表中的命令开始
        Object result2 = invokePrivateMethod(checker, "getNextCommand", new Class<?>[]{List.class, String.class}, commands, "command1");
        assertEquals("command2", result2);

        // 测试从最后一个命令开始（应该循环到第一个）
        Object result3 = invokePrivateMethod(checker, "getNextCommand", new Class<?>[]{List.class, String.class}, commands, "command3");
        assertEquals("command1", result3);

        // 测试不存在的lastCommand
        Object result4 = invokePrivateMethod(checker, "getNextCommand", new Class<?>[]{List.class, String.class}, commands, "nonexistent");
        assertEquals("command1", result4);
    }

    @Test
    public void testControlMode() throws Exception {
        // initDevice();
        // 设置调试模式
        setPrivateField(checker, "isDebug", true);

        // 模拟设备列表
        List<DeviceBase> devices = new ArrayList<>();
        devices.add(checker);
        when(mockSaimosenIntegration.getAllDevices()).thenReturn(devices);

        // 执行controlMode方法
        invokePrivateMethod(checker, "controlMode", new Class<?>[]{}, new Object[0]);

        // 验证设备列表是否被获取
        verify(mockSaimosenIntegration, times(1)).getAllDevices();
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testParticulateZeroCheckerI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            checker.init();

            // 验证命令属性
            TestTools.assertAttributeDisplayName(checker, "pm10_zero_check_command", "PM10零点检查命令");
            TestTools.assertAttributeDisplayName(checker, "pm2_5_zero_check_command", "PM2.5零点检查命令");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testParticulateZeroCheckerCommandSubCommandsI18n() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 测试命令子命令的i18n支持
            I18nProxy i18n = I18nHelper.createProxy(ParticulateZeroChecker.class);

            // 验证PM10零点检查命令的子命令
            String[] pm10Commands = {"zero_check_start", "zero_check_stop"};
            String[] pm10ExpectedNames = {"零点检查开始", "零点检查停止"};

            for (int i = 0; i < pm10Commands.length; i++) {
                String key = "devices.particulate_zero_checker.pm10_zero_check_command_commands." + pm10Commands[i];
                String actualName = i18n.t(key);
                assertEqualsString("PM10 Command " + pm10Commands[i] + " should have correct i18n name: " + key,
                             pm10ExpectedNames[i], actualName);
            }

            // 验证PM2.5零点检查命令的子命令
            String[] pm25Commands = {"zero_check_start", "zero_check_stop"};
            String[] pm25ExpectedNames = {"零点检查开始", "零点检查停止"};

            for (int i = 0; i < pm25Commands.length; i++) {
                String key = "devices.particulate_zero_checker.pm2_5_zero_check_command_commands." + pm25Commands[i];
                String actualName = i18n.t(key);
                assertEqualsString("PM2.5 Command " + pm25Commands[i] + " should have correct i18n name: " + key,
                             pm25ExpectedNames[i], actualName);
            }
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testParticulateZeroCheckerI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            checker.init();

            // 验证绑定设备后的displayname仍然正确
            TestTools.assertAttributeDisplayName(checker, "pm10_zero_check_command", "PM10零点检查命令");
            TestTools.assertAttributeDisplayName(checker, "pm2_5_zero_check_command", "PM2.5零点检查命令");
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
