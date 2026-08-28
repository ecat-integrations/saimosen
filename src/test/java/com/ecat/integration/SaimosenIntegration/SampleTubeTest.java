package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.State.StateManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.PollingHandle;
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
    private ReadHoldingRegistersResponse mockReadResponse;

    @Mock
    private WriteRegisterResponse mockWriteResponse;
    @Mock
    private BusRegistry mockBusRegistry;

    private SampleTube sampleTube;


    /**
     * 测试自建快节拍轮询（tianhong TH2004HCODeviceTest 同范式）：round 复用生产同函数
     * （sampleTube::readRegisters），节拍测试自持 50ms——与生产 every(5s) 解耦，负向观察窗从 6s 收
     * 到 600ms 仍覆盖 >10 个周期（cancel 失效形态下下一轮 51ms 内必现形，覆盖强度等价）。
     * 生产 start() 的节拍/接线由 testStart_SchedulesProductionPolling 正向覆盖。
     */
    private PollingHandle startFastPolling() {
        return ModbusPolling.on(sampleTube, mockModbusSource)
                .round(sampleTube::readRegisters)
                .every(50, TimeUnit.MILLISECONDS)
                .start();
    }

    @Test
    public void testStart_SchedulesProductionPolling() throws Exception {
        // 生产 start() 接线回归（快节拍改造后 startAndStop/release 测试不再走 start()，
        // 接线覆盖归本测试）：首轮 latch 等 round 真正读源 + DEFAULT 块参数 verify；
        // 生产节拍 5s，测试 ms 级收尾。setup 同 testStartAndStop（modbusSource 显式注入，
        // 与类序解耦）
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockCore.getTaskManager()).thenReturn(mock(TaskManager.class));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        when(mockCore.getStateManager()).thenReturn(mock(StateManager.class));
        sampleTube.markReady();

        CountDownLatch firstRead = new CountDownLatch(1);
        ReadHoldingRegistersResponse mockReadResp = mock(ReadHoldingRegistersResponse.class);
        when(mockReadResp.getShortData()).thenReturn(new short[0]);
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    firstRead.countDown();
                    return CompletableFuture.completedFuture(mockReadResp);
                });

        sampleTube.start();
        assertTrue("首轮（initialDelay=0）必须立即发起 DEFAULT 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 11);

        // 生产轮询生命周期收尾（不 sweep 会泄漏至类外，bugs/bug-record-20260828-224500）
        sampleTube.stop();
        sampleTube.cancelManagedTasks();
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
            if (superClass == null) {
                throw e;
            }
            return findField(superClass, fieldName);
        }
    }

    @After
    public void tearDown() {
        sampleTube.stop();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        ConfigEntry entry = createTestEntry();

        // 创建SampleTube实例
        sampleTube = new SampleTube(entry);
    }

    private ConfigEntry createTestEntry() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "采样管加热器");
        config.put("class", "sample.tube");
        config.put("sn", "ST-001");
        config.put("vendor", "赛默森环保");
        config.put("model", "SMS-D-H");
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
            .entryId("test-entry-sample-tube")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_sample.tube_SNST-001")
            .title("采样管加热器")
            .data(config)
            .build();
    }

    @Test
    public void testConstructor() {
        assertNotNull(sampleTube);
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
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

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
    public void testStartAndStop() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockCore.getTaskManager()).thenReturn(mock(TaskManager.class));
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();
        // 直接设置modbusSource（同 testRelease 形态）：init() 的 register 走静态
        // modbusIntegration（前序测试类遗留的已关 mock），轮询会拿到 null 源——显式注入
        // 使本测试与类序解耦，round 确定打到本测试的 mock 上
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);
        // markReady 对齐生产时序（未就绪撞 publicAttrsState 门禁，parse 被兜成 MALFUNCTION）
        when(mockCore.getStateManager()).thenReturn(mock(com.ecat.core.State.StateManager.class));
        sampleTube.markReady();

        // 测试启动：首轮 latch + 块参数 verify（latch 等 round 真正读源，非定时猜测）
        CountDownLatch firstRead = new CountDownLatch(1);
        ReadHoldingRegistersResponse mockReadResp = mock(ReadHoldingRegistersResponse.class);
        when(mockReadResp.getShortData()).thenReturn(new short[0]);
        when(mockModbusSource.readHoldingRegisters(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    firstRead.countDown();
                    return CompletableFuture.completedFuture(mockReadResp);
                });
        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("首轮（initialDelay=0）必须立即发起 DEFAULT 块读",
                firstRead.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 11);

        // 测试停止（lifecycle chokepoint：stop + sweep 执行移除动作）；
        // 负向观察窗 600ms 覆盖 >10 个 50ms 周期（等价原「6s 窗 > 5s 生产周期」覆盖强度）
        sampleTube.stop();
        sampleTube.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));

        // 已扫状态=再注册被拒（RemovalHost 契约）
        try {
            sampleTube.onRemove(() -> { });
            org.junit.Assert.fail("sweep 后宿主必须拒绝新移除动作注册");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
        }
    }

    @Test
    public void testReadRegisters() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

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
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockCore.getBusRegistry()).thenReturn(mockBusRegistry);

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直调 round 须就绪（publicAttrsState 门禁；旧反射+丢 CF 形态把门禁 ISE 静默吞掉，
        // 直调取结果后显形——补 StateManager 桩 + markReady 对齐生产时序）
        when(mockCore.getStateManager()).thenReturn(mock(com.ecat.core.State.StateManager.class));
        sampleTube.markReady();
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // 直调 round（同包可见）：get 有界护栏即回，零 sleep
        sampleTube.readRegisters(mockModbusSource).get(2, TimeUnit.SECONDS);

        // 验证读取调用（读取11个寄存器）
        verify(mockModbusSource, times(1)).readHoldingRegisters(0, 11);
    }

    @Test
    public void testSetHeatingTubeTargetTemp() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        // 确定性同步（替代 sleep）：写事务体在调用线程内联执行（executeHeld 内
        // lambda.apply 直发），latch 挂 writeRegister 入口——验证「写已发生」而非睡后猜测
        CountDownLatch writeDone = new CountDownLatch(1);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    writeDone.countDown();
                    return CompletableFuture.completedFuture(mockWriteResponse);
                });
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // 验证属性已创建
        assertNotNull("heating_tube_target_temp属性应该存在", sampleTube.getAttrs().get("heating_tube_target_temp"));

        // 测试设置加热管目标温度
        float targetTemp = 45.5f;
        sampleTube.setHeatingTubeTargetTemp(targetTemp);

        assertTrue("写入事务必须发起", writeDone.await(5, TimeUnit.SECONDS));

        // 验证写入调用（45.5 * 10 = 455，写入地址10）
        verify(mockModbusSource, times(1)).writeRegister(10, 455);
    }

    @Test
    public void testSetHeatingTubeActualTemp() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        // 确定性同步（替代 sleep）：latch 挂 writeRegister 入口，验证「写已发生」
        CountDownLatch writeDone = new CountDownLatch(1);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    writeDone.countDown();
                    return CompletableFuture.completedFuture(mockWriteResponse);
                });
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // 测试设置加热管实际温度
        float actualTemp = 40.0f;
        sampleTube.setHeatingTubeActualTemp(actualTemp);

        assertTrue("写入事务必须发起", writeDone.await(5, TimeUnit.SECONDS));

        // 验证写入调用（40.0 * 10 = 400，写入地址6）
        verify(mockModbusSource, times(1)).writeRegister(6, 400);
    }

    @Test
    public void testSetDeviceAddress() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        // 确定性同步（替代 sleep）：latch 挂 writeRegister 入口，验证「写已发生」
        CountDownLatch writeDone = new CountDownLatch(1);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    writeDone.countDown();
                    return CompletableFuture.completedFuture(mockWriteResponse);
                });
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // 测试设置设备地址
        sampleTube.setDeviceAddress(5);
        assertTrue("写入事务必须发起", writeDone.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, times(1)).writeRegister(4, 5);

        // 测试设置无效地址（超出范围）：setter 入口直接拒绝（写事务在调用线程内联，
        // 拒绝路径零写入——无需等待即可断言总写入数不变）
        sampleTube.setDeviceAddress(256);
        // 无效地址不应该写入，仍然只有1次调用
        verify(mockModbusSource, times(1)).writeRegister(anyInt(), anyInt());
    }

    @Test
    public void testSetCalibrationStatus() throws Exception {
        // 模拟Core和Integration
        when(mockCore.getIntegrationRegistry()).thenReturn(mock(com.ecat.core.Integration.IntegrationRegistry.class));
        when(mockCore.getIntegrationRegistry().getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
        when(mockModbusIntegration.register(any(), anyString())).thenReturn(mockModbusSource);
        // 确定性同步（替代 sleep）：latch 挂 writeRegister 入口，验证「写已发生」
        CountDownLatch writeDone = new CountDownLatch(1);
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    writeDone.countDown();
                    return CompletableFuture.completedFuture(mockWriteResponse);
                });
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // 测试设置校准状态
        sampleTube.setCalibrationStatus(0);

        assertTrue("写入事务必须发起", writeDone.await(5, TimeUnit.SECONDS));

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
        when(mockModbusSource.isModbusOpen()).thenReturn(true);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // markReady 对齐生产时序（未就绪撞 publicAttrsState 门禁，parse 被兜成 MALFUNCTION）
        when(mockCore.getStateManager()).thenReturn(mock(com.ecat.core.State.StateManager.class));
        sampleTube.markReady();

        RoundEntryProbe probe = RoundEntryProbe.on(mockModbusSource);
        startFastPolling();
        assertTrue("首轮必须发起", probe.firstRound.await(8, TimeUnit.SECONDS));

        // disableEntry 同序：stop → sweep（移除动作停轮询）→ release（源释放）
        sampleTube.stop();
        sampleTube.cancelManagedTasks();
        probe.armStrayDetector();
        assertFalse("release 前 stop+sweep 后不得再发起下一轮", probe.strayRound.await(600, TimeUnit.MILLISECONDS));
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
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

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

        // 验证属性总数（11个寄存器对应11个属性 + 2个配置属性 tube_length/tube_inner_diameter）
        assertEquals("应该有13个属性", 13, attrs.size());
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
        when(mockModbusSource.tryAcquire()).thenReturn("testKey");

        sampleTube.load(mockCore);
        sampleTube.init();

        // 直接设置modbusSource
        setPrivateField(sampleTube, "modbusSource", mockModbusSource);

        // 直调 round（同包可见）：传输失败（读 future 异常完成）原样异常完成——旧反射+丢 CF
        // 形态把它静默吞掉，直调取结果后显形为 ExecutionException（SDK 生产路径归类 FAILED
        // 统一 error 日志、轮询不注销；解析失败才走 thenApply 内 catch → 业务 false）
        try {
            sampleTube.readRegisters(mockModbusSource).get(2, TimeUnit.SECONDS);
            org.junit.Assert.fail("传输失败轮必须异常完成");
        } catch (java.util.concurrent.ExecutionException expected) {
            org.junit.Assert.assertTrue("根因应为模拟的通信失败",
                    expected.getCause() instanceof RuntimeException);
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
            when(mockModbusSource.tryAcquire()).thenReturn("testKey");

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
            when(mockModbusSource.tryAcquire()).thenReturn("testKey");

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
