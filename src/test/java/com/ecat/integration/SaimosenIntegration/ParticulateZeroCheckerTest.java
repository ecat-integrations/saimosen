package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 颗粒物零点检查设备单元测试类
 * 
 * @author coffee
 */
public class ParticulateZeroCheckerTest {
    
    private ParticulateZeroChecker checker;
    private AutoCloseable mockitoCloseable;

    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;
    @Mock private SaimosenIntegration mockSaimosenIntegration;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        ConfigEntry entry = createTestEntry();
        checker = new ParticulateZeroChecker(entry);

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

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("class", "air.monitor.pm.qc");
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
            .entryId("test-entry-pm-qc")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_air.monitor.pm.qc")
            .title("颗粒物零点校验仪")
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
    public void testStop() throws Exception {
        // 延迟常量注入 50ms（生产 8s/10s）：真因果验证「stop + sweep 后待发关阀拍被撤销」——
        // 未撤销形态下 50ms 后关阀写必发生（负向窗 > 注入延迟），无需等生产量级延迟
        ParticulateZeroChecker.pm25AutoCloseDelayMs = 50;
        ParticulateZeroChecker.pm10AutoCloseDelayMs = 50;
        try {
            // 改桩在 start 前一次完成（彼时无并发在飞）：Mockito when() 绑定「最近一次调用」，
            // 对被并发调用的 mock 二次改桩会把 answer 错绑到其它 invocation
            CountDownLatch valveWrite = new CountDownLatch(1);
            com.serotonin.modbus4j.msg.WriteRegisterResponse okResp = mock(WriteRegisterResponse.class);
            when(okResp.isException()).thenReturn(false);
            when(mockModbusSource.writeRegister(anyInt(), anyInt())).thenAnswer(inv -> {
                valveWrite.countDown();
                return CompletableFuture.completedFuture(okResp);
            });

            checker.start();
            checker.stop();
            checker.cancelManagedTasks();   // 框架 chokepoint 同点（IntegrationDeviceBase.stopWithManagedSweep）

            // 负向窗（窗>50ms 注入延迟）：撤销失败形态下 50ms 后必发阀写
            assertFalse("stop+sweep 后待发关阀拍必须被撤销，不得再写阀寄存器",
                    valveWrite.await(300, TimeUnit.MILLISECONDS));
            // never 断言兜住「sweep 前拍已漏发」的旁路（mock 全程记账，不受改桩时点影响）
            verify(mockModbusSource, never()).writeRegister(anyInt(), anyInt());
        } finally {
            ParticulateZeroChecker.pm25AutoCloseDelayMs = 8_000;
            ParticulateZeroChecker.pm10AutoCloseDelayMs = 10_000;
        }
    }

    @Test
    public void testRelease() throws Exception {
        checker.release();
        verify(mockModbusSource, never()).closeModbus();
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
