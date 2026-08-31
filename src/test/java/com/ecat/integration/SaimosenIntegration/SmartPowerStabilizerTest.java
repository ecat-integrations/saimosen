package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
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
 * 智能电力稳压器单元测试类
 * 
 * @author coffee
 */
public class SmartPowerStabilizerTest {

    private SmartPowerStabilizer stabilizer;
    private AutoCloseable mockitoCloseable;
    
    @Mock private ModbusSource mockModbusSource;
    @Mock private ModbusIntegration mockModbusIntegration;
    @Mock private EcatCore mockEcatCore;
    @Mock private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        ConfigEntry entry = createTestEntry();
        stabilizer = new SmartPowerStabilizer(entry);
        
        // 注入依赖
        setPrivateField(stabilizer, "core", mockEcatCore);
        setPrivateField(stabilizer, "modbusSource", mockModbusSource);
        setPrivateField(stabilizer, "modbusIntegration", mockModbusIntegration);

        initDevice();

        // mock modbusSource 的 acquire() 函数
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);
    }
    
    @After
    public void tearDown() throws Exception {
        stabilizer.stop();
        mockitoCloseable.close();
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("class", "power.supply.stabilizer");
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
            .entryId("test-entry-stabilizer")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_power.supply.stabilizer")
            .title("智能稳压电源")
            .data(config)
            .build();
    }


    @Test
    public void testStart_SchedulesProductionPolling() throws Exception {
        // 生产 start() 接线回归：首轮 latch 等 round 真正读源 + DEFAULT 块参数 verify（需桩
        // 读响应，与注入短节拍的负向测试分立）；
        // 生产节拍 5s，测试 ms 级收尾
        CountDownLatch firstRead = new CountDownLatch(1);
        ReadHoldingRegistersResponse mockReadResp = mock(ReadHoldingRegistersResponse.class);
        when(mockReadResp.getShortData()).thenReturn(new short[0]);
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    firstRead.countDown();
                    return CompletableFuture.completedFuture(mockReadResp);
                });

        stabilizer.start();
        assertTrue("首轮（initialDelay=0）必须立即发起 DEFAULT 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 41);

        // 生产轮询生命周期收尾（不 sweep 会泄漏至类外，bugs/bug-record-20260828-224500）
        stabilizer.stop();
        stabilizer.cancelManagedTasks();
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
        stabilizer.init();
        // 直调 round 须就绪（publicAttrsState 门禁；旧反射+丢 CF 形态把门禁 ISE 静默吞掉，
        // 直调取结果后显形——补 StateManager 桩 + markReady 对齐生产时序）
        when(mockEcatCore.getStateManager()).thenReturn(mock(com.ecat.core.State.StateManager.class));
        stabilizer.markReady();
        setPrivateField(stabilizer, "core", mockEcatCore);
        setPrivateField(stabilizer, "modbusSource", mockModbusSource);
        setPrivateField(stabilizer, "modbusIntegration", mockModbusIntegration);
    }
    
    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        // 执行初始化
        stabilizer.init();
        
        // 验证电压属性
        assertNotNull(stabilizer.getAttrs().get("voltage_l1"));
        assertNotNull(stabilizer.getAttrs().get("voltage_l2"));
        assertNotNull(stabilizer.getAttrs().get("voltage_l3"));
        assertNotNull(stabilizer.getAttrs().get("voltage_l4"));

        // 验证电流属性
        assertNotNull(stabilizer.getAttrs().get("current_l1"));
        assertNotNull(stabilizer.getAttrs().get("current_l2"));
        assertNotNull(stabilizer.getAttrs().get("current_l3"));
        assertNotNull(stabilizer.getAttrs().get("current_l4"));

        // 验证继电器状态属性
        assertNotNull(stabilizer.getAttrs().get("relay_l1"));
        assertNotNull(stabilizer.getAttrs().get("relay_l2"));
        assertNotNull(stabilizer.getAttrs().get("relay_l3"));
        assertNotNull(stabilizer.getAttrs().get("relay_l4"));
    }
    
    @Test
    public void testStart_SchedulesReadTask() throws Exception {
        // 首轮 latch + 块参数 verify（抄 chko/cecep 形态）：latch 等 round 真正读源
        CountDownLatch firstRead = new CountDownLatch(1);
        ReadHoldingRegistersResponse mockReadResp = mock(ReadHoldingRegistersResponse.class);
        when(mockReadResp.getShortData()).thenReturn(new short[0]);
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    firstRead.countDown();
                    return CompletableFuture.completedFuture(mockReadResp);
                });

        // 轮询经 SDK 内绑宿主生命周期（on(this, source)，句柄不外泄）；首轮立即发射
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        // 单测注入短轮询周期（生产默认 5s）：负向窗 300ms ≥ 2 拍×150ms，走生产 start() 真实接线
        stabilizer.pollPeriodMs = 150L;
        stabilizer.start();
        assertTrue("首轮（initialDelay=0）必须立即发起 DEFAULT 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 41);

        // lifecycle chokepoint：stop + sweep 执行移除动作；
        // 负向观察窗 300ms ≥ 2 拍×150ms（生产 start() 注入节拍；cancel 失效形态下下一拍必现形）
        stabilizer.stop();
        stabilizer.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮（容至多 1 个 stop 前在飞轮迟到入口，阈值 2）", probe.strayRound.await(300, TimeUnit.MILLISECONDS));

        // 已扫状态=再注册被拒（RemovalHost 契约）
        try {
            stabilizer.onRemove(() -> { });
            org.junit.Assert.fail("sweep 后宿主必须拒绝新移除动作注册");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
        }
    }

    @Test
    public void testReadRegisters_ReadsAndParsesData() throws Exception {
        // 准备模拟寄存器数据（从真实命令数据解析）
        // byte[] responseData = {
        //     0x01, 0x03, 0x50, 0x01, 0x72, 0x00, 0x00, 0x01, (byte)0xD7, 0x00, (byte)0xED, 
        //     0x08, (byte)0x99, 0x08, (byte)0xA3, 0x08, (byte)0xCC, 0x08, (byte)0xD9, 0x00, 0x51, 0x00, 0x00, 0x00, 0x6A, 
        //     0x00, 0x35, 0x00, (byte)0xCF, 0x01, (byte)0x7D, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 
        //     0x00, 0x01, 0x01, (byte)0x90, 0x01, (byte)0x90, 0x01, (byte)0x90, 0x02, 0x58, 0x00, 0x64, 0x00, 0x64, 
        //     0x00, 0x64, (byte)0xFE, 0x0C, 0x00, 0x14, 0x00, 0x14, 0x00, 0x14, 0x00, 0x14, 0x01, 
        //     (byte)0xC2, 0x01, (byte)0xC2, 0x01, (byte)0xC2, 0x01, (byte)0xC2, 0x00, 0x00, 0x00, 
        //     0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, (byte)0xB5, 0x1B
        // };
        
        // short[] registers = new short[responseData.length / 2];
        // for (int i = 0; i < responseData.length; i += 2) {
        //     registers[i/2] = (short) ((responseData[i] << 8) | (responseData[i+1] & 0xFF));
        // }

        // 原始的Modbus响应数据（十六进制字符串）
        String hexData = "01 03 52 01 13 00 00 01 8D 00 DE 08 91 08 94 08 CC 08 D6 00 3C 00 00 00 59 00 32 00 F5 01 8B 00 01 00 01 00 01 00 01 01 90 01 90 01 90 02 58 00 64 00 64 00 64 FE 0C 00 14 00 14 00 14 00 00 01 C2 01 C2 01 C2 01 C2 00 00 00 00 00 00 00 00 00 01 00 01 00 01 3C 22";

        // 转换为字节数组
        byte[] bytes = hexStringToByteArray(hexData.replaceAll(" ", ""));
        
        // 解析Modbus响应并提取数据 - 大端序
        short[] registersBigEndian = parseModbusResponse(bytes, ByteOrder.BIG_ENDIAN);

        // 模拟Modbus响应
        ReadHoldingRegistersResponse mockResponse = mock(ReadHoldingRegistersResponse.class);
        when(mockResponse.getShortData()).thenReturn(registersBigEndian);

        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(mockResponse));
        
        // 注入模拟寄存器数据
        // Field responseField = findField(mockModbusSource.getClass(), "response");
        // responseField.setAccessible(true);
        // responseField.set(mockModbusSource, registers);
        
        // 执行读取
        // 直调 round：mock 读全为完成态，get 有界护栏即回
        stabilizer.readRegisters(mockModbusSource).get(2, TimeUnit.SECONDS);
        
        // 验证电压属性值（以第一路为例）
        ModbusScalableFloatSRAttribute voltageL1 =
                (ModbusScalableFloatSRAttribute) stabilizer.getAttrs().get("voltage_l1");
        assertEquals(219.3f, (Float) voltageL1.getState().getValue(), 0.01f); // 0x0172 = 370, 370/10 = 37.0V

        // 验证电流属性值（以第一路为例）
        ModbusScalableFloatSRAttribute currentL1 =
                (ModbusScalableFloatSRAttribute) stabilizer.getAttrs().get("current_l1");
        assertEquals(2.75f, (Float) currentL1.getState().getValue(), 0.01f); // 0x0000 = 0, 0/100 = 0.0A

        // 验证继电器状态（以第一路为例）
        ModbusScalableFloatSRAttribute relayL1 =
                (ModbusScalableFloatSRAttribute) stabilizer.getAttrs().get("relay_l1");
        assertEquals(1.0f, (Float) relayL1.getState().getValue(), 0.01f); // 0x0001 = 1，合闸状态
    }
    
    // @Test
    // public void testSetRelayStatus() throws Exception {
    //     // 执行设置继电器状态
    //     stabilizer.setRelayStatus(1, 0); // 设置第一路为跳闸状态
        
    //     // 验证Modbus写操作被调用
    //     verify(mockModbusSource, times(1)).writeRegister(eq((short)14), eq((short)0));
    // }
    
    // @Test
    // public void testSetDeviceAddress() throws Exception {
    //     // 执行设置设备地址
    //     stabilizer.setDeviceAddress(2);
        
    //     // 验证Modbus写操作被调用
    //     verify(mockModbusSource, times(1)).writeRegister(eq((short)40), eq((short)2));
    // }
    
    @Test
    public void testUpdateScalableAttribute() throws Exception {
        
        // 获取属性实例
        ModbusScalableFloatSRAttribute attr =
                (ModbusScalableFloatSRAttribute) stabilizer.getAttrs().get("voltage_l1");

        // 模拟寄存器值 (0x0172 = 370, 370/10 = 37.0V)
        short word1 = 0x01;

        // 调用更新方法
        invokePrivateMethod(stabilizer, "updateScalableAttribute", "voltage_l1", word1, AttributeStatus.NORMAL);
        
        // 验证属性值和状态
        assertEquals(0.1f, (Float) attr.getState().getValue(), 0.01f);
        assertEquals(AttributeStatus.NORMAL, (AttributeStatus)getPrivateField(attr, "status"));
    }

    // 字节序枚举
    public enum ByteOrder {
        BIG_ENDIAN,   // 大端序（默认）
        LITTLE_ENDIAN // 小端序
    }

    /**
     * 解析Modbus响应数据，提取寄存器值
     * @param response Modbus响应字节数组
     * @param byteOrder 字节序（大端序或小端序）
     * @return 解析后的寄存器值数组
     */
    public static short[] parseModbusResponse(byte[] response, ByteOrder byteOrder) {
        // 检查响应长度是否合法
        if (response.length < 7) {
            throw new IllegalArgumentException("Modbus响应长度不足");
        }
        
        int startAddress, registerCount;

        // 检查功能码是否为03
        if ((response[1] & 0xFF) == 0x03) {
            // 获取字节数
            int byteCount = response[2] & 0xFF;
            
            // 计算寄存器数量
            registerCount = byteCount / 2;
        }
        else if ((response[1] & 0xFF) == 0x06) {
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                // 大端序：高字节在前，低字节在后
                startAddress = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
                registerCount = ((response[4] & 0xFF) << 8) | (response[5] & 0xFF);
            } else {
                // 小端序：低字节在前，高字节在后
                startAddress = ((response[3] & 0xFF) << 8) | (response[2] & 0xFF);
                registerCount = ((response[5] & 0xFF) << 8) | (response[4] & 0xFF);
            }
        } else {
            throw new IllegalArgumentException("不支持的功能码: " + (response[1] & 0xFF));
        }

        
        // 创建short数组存储寄存器值
        short[] registers = new short[registerCount];
        
        // 提取寄存器值
        for (int i = 0; i < registerCount; i++) {
            int highByteIndex = 3 + i * 2;
            int lowByteIndex = 3 + i * 2 + 1;
            
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                // 大端序：高字节在前，低字节在后
                int highByte = response[highByteIndex] & 0xFF;
                int lowByte = response[lowByteIndex] & 0xFF;
                registers[i] = (short) ((highByte << 8) | lowByte);
            } else {
                // 小端序：低字节在前，高字节在后
                int lowByte = response[highByteIndex] & 0xFF;
                int highByte = response[lowByteIndex] & 0xFF;
                registers[i] = (short) ((highByte << 8) | lowByte);
            }
        }
        
        return registers;
    }
    
    /**
     * 将十六进制字符串转换为字节数组
     */
    public static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    // ========== I18n测试方法 ==========

    @Test
    public void testSmartPowerStabilizerI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            stabilizer.init();

            // 验证电气参数
            TestTools.assertAttributeDisplayName(stabilizer, "current_l1", "L1路电流");
            TestTools.assertAttributeDisplayName(stabilizer, "current_l2", "L2路电流");
            TestTools.assertAttributeDisplayName(stabilizer, "current_l3", "L3路电流");
            TestTools.assertAttributeDisplayName(stabilizer, "current_l4", "L4路电流");
            TestTools.assertAttributeDisplayName(stabilizer, "voltage_l1", "L1路电压");
            TestTools.assertAttributeDisplayName(stabilizer, "voltage_l2", "L2路电压");
            TestTools.assertAttributeDisplayName(stabilizer, "voltage_l3", "L3路电压");
            TestTools.assertAttributeDisplayName(stabilizer, "voltage_l4", "L4路电压");
            TestTools.assertAttributeDisplayName(stabilizer, "power_l1", "L1路功率");
            TestTools.assertAttributeDisplayName(stabilizer, "power_l2", "L2路功率");
            TestTools.assertAttributeDisplayName(stabilizer, "power_l3", "L3路功率");
            TestTools.assertAttributeDisplayName(stabilizer, "power_l4", "L4路功率");

            // 验证环境参数
            TestTools.assertAttributeDisplayName(stabilizer, "temperature", "温度");
            TestTools.assertAttributeDisplayName(stabilizer, "humidity", "湿度");

            // 验证继电器状态
            TestTools.assertAttributeDisplayName(stabilizer, "relay_l1", "L1路继电器状态");
            TestTools.assertAttributeDisplayName(stabilizer, "relay_l2", "L2路继电器状态");
            TestTools.assertAttributeDisplayName(stabilizer, "relay_l3", "L3路继电器状态");
            TestTools.assertAttributeDisplayName(stabilizer, "relay_l4", "L4路继电器状态");

            // 验证报警状态
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_high_l1", "L1路温度上限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_high_l2", "L2路温度上限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_high_l3", "L3路温度上限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_high_l4", "L4路温度上限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_low_l1", "L1路温度下限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_low_l2", "L2路温度下限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_low_l3", "L3路温度下限报警");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_alarm_low_l4", "L4路温度下限报警");

            // 验证开机启动延时
            TestTools.assertAttributeDisplayName(stabilizer, "startup_delay_l1", "L1路开机启动延时");
            TestTools.assertAttributeDisplayName(stabilizer, "startup_delay_l2", "L2路开机启动延时");
            TestTools.assertAttributeDisplayName(stabilizer, "startup_delay_l3", "L3路开机启动延时");
            TestTools.assertAttributeDisplayName(stabilizer, "startup_delay_l4", "L4路开机启动延时");

            // 验证温度跳闸上限
            TestTools.assertAttributeDisplayName(stabilizer, "temp_trip_high_l1", "L1路温度跳闸上限");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_trip_high_l2", "L2路温度跳闸上限");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_trip_high_l3", "L3路温度跳闸上限");
            TestTools.assertAttributeDisplayName(stabilizer, "temp_trip_high_l4", "L4路温度跳闸上限");

            // 验证超温保护状态
            TestTools.assertAttributeDisplayName(stabilizer, "over_temp_protection_l1", "L1路超温保护状态");
            TestTools.assertAttributeDisplayName(stabilizer, "over_temp_protection_l2", "L2路超温保护状态");
            TestTools.assertAttributeDisplayName(stabilizer, "over_temp_protection_l3", "L3路超温保护状态");
            TestTools.assertAttributeDisplayName(stabilizer, "over_temp_protection_l4", "L4路超温保护状态");

            // 验证通信状态和设备地址
            TestTools.assertAttributeDisplayName(stabilizer, "temp_humidity_comm_status", "温湿度通信状态");
            TestTools.assertAttributeDisplayName(stabilizer, "electric_param_comm_status", "电参数通信状态");
            TestTools.assertAttributeDisplayName(stabilizer, "device_address", "设备地址");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testSmartPowerStabilizerI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            stabilizer.init();

            // 验证绑定设备后的displayname仍然正确
            TestTools.assertAttributeDisplayName(stabilizer, "current_l1", "L1路电流");
            TestTools.assertAttributeDisplayName(stabilizer, "voltage_l1", "L1路电压");
            TestTools.assertAttributeDisplayName(stabilizer, "temperature", "温度");
            TestTools.assertAttributeDisplayName(stabilizer, "device_address", "设备地址");
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }
}
