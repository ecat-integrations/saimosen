package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.I18n.I18nHelper;
import com.ecat.core.I18n.I18nProxy;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AQAttribute;
import com.ecat.core.State.StateManager;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SMS8600V2Device 单元测试类
 * 
 * 测试覆盖范围：
 * 1. 设备初始化和属性创建
 * 2. 设备启动、停止和释放
 * 3. 实时数据处理（O3浓度）
 * 4. 分钟浓度数据处理
 * 5. 状态数据解析（23字段协议）
 * 6. 气体设置数据解析
 * 7. 校准命令响应处理
 * 8. 钢瓶气浓度更新
 * 9. 工作状态映射（正常、零点、跨度、报警）
 * 10. 占位符值处理（"-"和"---"）
 * 11. 标气流量虚拟属性映射
 * 12. 国际化（i18n）支持
 */
public class SMS8600V2DeviceTest {

    private SMS8600V2Device sms8600v2Device;
    private AutoCloseable mockitoCloseable;

    @Mock
    private SerialSource mockSerialSource;

    @Mock
    private EcatCore mockEcatCore;

    @Mock
    private SerialIntegration mockSerialIntegration;

    @Mock
    private ByteResponseHandlingContext<byte[]> context;

    private ByteArrayOutputStream realReceiveBuffer;

    @Mock
    private ByteResponseHandlerStrategy<byte[]> mockResponseHandlerStrategy;

    @Mock
    private BusRegistry mockBusRegistry;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        // 设置 ResourceLoader 仅加载 strings.json，不加载 i18n 目录资源
        ResourceLoader.setLoadI18nResources(false);

        Map<String, Object> data = new HashMap<>();
        ConfigEntry entry = new ConfigEntry.Builder()
            .entryId("test-entry-sms8600v2")
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("SMS8600V2TestDevice")
            .data(data)
            .build();
        sms8600v2Device = new SMS8600V2Device(entry);

        setPrivateField(sms8600v2Device, "core", mockEcatCore);
        setPrivateField(sms8600v2Device, "serialSource", mockSerialSource);
        setPrivateField(sms8600v2Device, "serialIntegration", mockSerialIntegration);
        when(mockSerialIntegration.register(any(), anyString())).thenReturn(mockSerialSource);
        when(mockSerialSource.getPortName()).thenReturn("/dev/ttyUSB0");   // 域自持周期链链名（29 号 v2 S1）：mock 默认 null 须补
        when(mockSerialSource.getTimeout()).thenReturn(500);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        initSMS8600V2Device();

        when(mockResponseHandlerStrategy.handleResponse(any())).thenAnswer(invocation -> {
            ByteResponseHandlingContext<byte[]> ctx = invocation.getArgument(0);
            return CompletableFuture.completedFuture(invokePrivateMethod(sms8600v2Device, "processResponse", ctx));
        });

        realReceiveBuffer = new ByteArrayOutputStream();
        when(context.getReceiveBuffer()).thenReturn(realReceiveBuffer);
            // markReady 对齐生产时序（init/load 后 markReady，再由轮询驱动 publish）：
        // 测试直接调 publish 路径（readRegisters/parse/publicAttrsState），未就绪撞就绪门禁
        // （publicAttrsState 不再吞门禁异常，会原样抛出或被 parse 的 catch 兜成 MALFUNCTION）。
        when(mockEcatCore.getStateManager()).thenReturn(mock(StateManager.class));
        sms8600v2Device.markReady();
}

    @After
    public void tearDown() throws Exception {
        sms8600v2Device.stop();
        // 恢复 ResourceLoader 的设置，启用 i18n 资源加载
        ResourceLoader.setLoadI18nResources(true);
        mockitoCloseable.close();
    }

    // ==================== 反射辅助方法 ====================

    /**
     * 通过反射设置私有字段
     */
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 通过反射获取私有字段
     */
    private Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 设置 ByteResponseHandlingContext 的 receiveBuffer 内容
     */
    private void setupContextReceiveBuffer(ByteResponseHandlingContext<byte[]> context, String data) throws Exception {
        realReceiveBuffer.reset();
        realReceiveBuffer.write(data.getBytes());
    }

    /**
     * 递归查找字段（包括父类）
     */
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

    /**
     * 通过反射调用私有方法
     */
    private Object invokePrivateMethod(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = args[i].getClass();
        }

        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        method.setAccessible(true);

        return method.invoke(target, args);
    }

    /**
     * 递归查找方法（包括父类）
     */
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

    /**
     * 初始化设备并设置必要的mock对象
     */
    private void initSMS8600V2Device() throws Exception {
        sms8600v2Device.init();
        setPrivateField(sms8600v2Device, "core", mockEcatCore);
        setPrivateField(sms8600v2Device, "serialSource", mockSerialSource);
        setPrivateField(sms8600v2Device, "serialIntegration", mockSerialIntegration);
        when(mockSerialIntegration.register(any(), anyString())).thenReturn(mockSerialSource);
        when(mockSerialSource.getTimeout()).thenReturn(500);
        setPrivateField(sms8600v2Device, "responseHandlerStrategy", mockResponseHandlerStrategy);
    }

    // ==================== 设备生命周期测试 ====================

    /**
     * 测试设备初始化时是否正确创建所有属性
     * 
     * 验证内容：
     * 1. 基本监测属性（o3、o3_minute 等 24 个）是否存在
     * 2. 虚拟属性（co_span_flow、so2_span_flow、no2_span_flow）是否存在
     * 3. 钢瓶气浓度属性（gas_so2_cylinder_gas_conc 等 3 个）是否存在
     * 4. 通道气体配置属性（channel_1_gas 等 4 个）是否存在
     * 5. 校准配置属性（calibration_flow_config 等 5 个）是否存在
     * 6. 校准命令属性（calibration_cmd）是否存在
     * 7. 属性类型是否正确（AQAttribute、NumericAttribute、StringSelectAttribute 等）
     */
    @Test
    public void testInit_CreatesCorrectAttributes() throws Exception {
        sms8600v2Device.init();
        
        // 验证基本监测属性
        assertNotNull("o3属性应该存在", sms8600v2Device.getAttrs().get("o3"));
        assertNotNull("o3_minute属性应该存在", sms8600v2Device.getAttrs().get("o3_minute"));
        assertNotNull("pmt_v属性应该存在", sms8600v2Device.getAttrs().get("pmt_v"));
        assertNotNull("canbi_v属性应该存在", sms8600v2Device.getAttrs().get("canbi_v"));
        assertNotNull("yuliu1属性应该存在", sms8600v2Device.getAttrs().get("yuliu1"));
        assertNotNull("power_component属性应该存在", sms8600v2Device.getAttrs().get("power_component"));
        assertNotNull("photometer_press属性应该存在", sms8600v2Device.getAttrs().get("photometer_press"));
        assertNotNull("photometer_flow属性应该存在", sms8600v2Device.getAttrs().get("photometer_flow"));
        assertNotNull("photometer_temp属性应该存在", sms8600v2Device.getAttrs().get("photometer_temp"));
        assertNotNull("case_temp属性应该存在", sms8600v2Device.getAttrs().get("case_temp"));
        assertNotNull("photometer_lamp_temp属性应该存在", sms8600v2Device.getAttrs().get("photometer_lamp_temp"));
        assertNotNull("yuliu2属性应该存在", sms8600v2Device.getAttrs().get("yuliu2"));
        assertNotNull("o3_generator_temp属性应该存在", sms8600v2Device.getAttrs().get("o3_generator_temp"));
        assertNotNull("zero_press属性应该存在", sms8600v2Device.getAttrs().get("zero_press"));
        assertNotNull("span_press属性应该存在", sms8600v2Device.getAttrs().get("span_press"));
        assertNotNull("valve_press属性应该存在", sms8600v2Device.getAttrs().get("valve_press"));
        assertNotNull("zero_flow属性应该存在", sms8600v2Device.getAttrs().get("zero_flow"));
        assertNotNull("span_flow属性应该存在", sms8600v2Device.getAttrs().get("span_flow"));
        assertNotNull("o3_drive_v属性应该存在", sms8600v2Device.getAttrs().get("o3_drive_v"));
        assertNotNull("o3_output_v属性应该存在", sms8600v2Device.getAttrs().get("o3_output_v"));
        assertNotNull("o3_generator_flow属性应该存在", sms8600v2Device.getAttrs().get("o3_generator_flow"));
        assertNotNull("slope属性应该存在", sms8600v2Device.getAttrs().get("slope"));
        assertNotNull("intercept属性应该存在", sms8600v2Device.getAttrs().get("intercept"));
        assertNotNull("alarm_code属性应该存在", sms8600v2Device.getAttrs().get("alarm_code"));
        assertNotNull("work_status属性应该存在", sms8600v2Device.getAttrs().get("work_status"));
        
        // 验证虚拟属性（标气流量）
        assertNotNull("co_span_flow虚拟属性应该存在", sms8600v2Device.getAttrs().get("co_span_flow"));
        assertNotNull("so2_span_flow虚拟属性应该存在", sms8600v2Device.getAttrs().get("so2_span_flow"));
        assertNotNull("no2_span_flow虚拟属性应该存在", sms8600v2Device.getAttrs().get("no2_span_flow"));
        
        // 验证钢瓶气浓度属性
        assertNotNull("gas_so2_cylinder_gas_conc属性应该存在", sms8600v2Device.getAttrs().get("gas_so2_cylinder_gas_conc"));
        assertNotNull("gas_co_cylinder_gas_conc属性应该存在", sms8600v2Device.getAttrs().get("gas_co_cylinder_gas_conc"));
        assertNotNull("gas_no_cylinder_gas_conc属性应该存在", sms8600v2Device.getAttrs().get("gas_no_cylinder_gas_conc"));
        
        // 验证通道气体配置属性
        assertNotNull("channel_1_gas属性应该存在", sms8600v2Device.getAttrs().get("channel_1_gas"));
        assertNotNull("channel_2_gas属性应该存在", sms8600v2Device.getAttrs().get("channel_2_gas"));
        assertNotNull("channel_3_gas属性应该存在", sms8600v2Device.getAttrs().get("channel_3_gas"));
        assertNotNull("channel_4_gas属性应该存在", sms8600v2Device.getAttrs().get("channel_4_gas"));
        
        // 验证校准配置属性
        assertNotNull("calibration_flow_config属性应该存在", sms8600v2Device.getAttrs().get("calibration_flow_config"));
        assertNotNull("calibration_gas_config属性应该存在", sms8600v2Device.getAttrs().get("calibration_gas_config"));
        assertNotNull("calibration_concentration_config属性应该存在", sms8600v2Device.getAttrs().get("calibration_concentration_config"));
        assertNotNull("calibration_gpt_no_concentration_config属性应该存在", sms8600v2Device.getAttrs().get("calibration_gpt_no_concentration_config"));
        assertNotNull("calibration_gpt_o3_concentration_config属性应该存在", sms8600v2Device.getAttrs().get("calibration_gpt_o3_concentration_config"));
        assertNotNull("calibration_concentration_unit_config属性应该存在", sms8600v2Device.getAttrs().get("calibration_concentration_unit_config"));
        assertNotNull("calibration_cmd属性应该存在", sms8600v2Device.getAttrs().get("calibration_cmd"));
        
        // 验证属性类型
        assertTrue("o3应该是AQAttribute类型", sms8600v2Device.getAttrs().get("o3") instanceof AQAttribute);
        assertTrue("o3_minute应该是AQAttribute类型", sms8600v2Device.getAttrs().get("o3_minute") instanceof AQAttribute);
        assertTrue("work_status应该是StringSelectAttribute类型", sms8600v2Device.getAttrs().get("work_status") instanceof StringSelectAttribute);
        assertTrue("calibration_cmd应该是SMS8600V2DeviceCommandAttribute类型", 
                   sms8600v2Device.getAttrs().get("calibration_cmd") instanceof SMS8600V2DeviceCommandAttribute);
    }

    @Test
    public void testStart_SchedulesReadTasks() throws Exception {
        // 18 号迁移后句柄不经设备持有（SDK 内绑 onRemove）：以 round 入口探针证轮询已注册运行
        CountDownLatch firstRound = pollingRoundProbe(1);
        sms8600v2Device.start();
        assertTrue("start 后首轮轮询必须发起（探针=executePolling 首访 tryAcquire）",
                firstRound.await(8, TimeUnit.SECONDS));
    }

    @Test
    public void testStop_CancelsScheduledTasks() throws Exception {
        CountDownLatch firstRound = pollingRoundProbe(1);
        // 单测注入短轮询周期（生产默认 5s）：负向窗 300ms ≥ 2 拍×150ms，走生产 start() 真实接线
        sms8600v2Device.pollPeriodMs = 150L;
        sms8600v2Device.start();
        assertTrue("首轮必须发起", firstRound.await(8, TimeUnit.SECONDS));

        sms8600v2Device.stop();
        sms8600v2Device.cancelManagedTasks();   // 框架 chokepoint 同点（IntegrationDeviceBase.stopWithManagedSweep）
        // 负向探针须换新 latch（旧 latch 已计数永真）：新探针只计其后再发起的 round。阈值 2：
        // 容至多 1 个 stop 前在飞轮迟到入口（cancel 不打断在飞轮；被抢占的迟到入口发起于
        // stop 前，不是新轮）；真「轮询未停」按 150ms 节拍 300ms 窗内 ≥2 次入口立即红
        CountDownLatch nextRound = pollingRoundProbe(2);

        assertFalse("stop+sweep 后不得再发起下一轮（容至多 1 个 stop 前在飞轮迟到入口，阈值 2）",
                nextRound.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRelease_ClosesSerialPortAndCancelsTasks() throws Exception {
        when(mockSerialSource.isPortOpen()).thenReturn(true);
        CountDownLatch firstRound = pollingRoundProbe(1);
        // 单测注入短轮询周期（生产默认 5s）：负向窗 300ms ≥ 2 拍×150ms，走生产 start() 真实接线
        sms8600v2Device.pollPeriodMs = 150L;
        sms8600v2Device.start();
        assertTrue("首轮必须发起", firstRound.await(8, TimeUnit.SECONDS));

        sms8600v2Device.release();
        sms8600v2Device.cancelManagedTasks();   // 框架 chokepoint 同点（IntegrationDeviceBase.onRelease 先 sweep 后 release）
        // 同 stop 测：阈值 2 容至多 1 个 stop 前在飞轮迟到入口；真「轮询未停」300ms 窗内 ≥2 次入口立即红
        CountDownLatch nextRound = pollingRoundProbe(2);

        assertFalse("release+sweep 后不得再发起下一轮（容至多 1 个 stop 前在飞轮迟到入口，阈值 2）",
                nextRound.await(300, TimeUnit.MILLISECONDS));
        verify(mockSerialSource, times(1)).closePort();
    }

    @Test
    public void testLoadAcceptsConfigFlowNumericTimeoutAsBigDecimal() throws Exception {
        // ConfigFlow numeric 字段落 entry yml 为 BigDecimal（timeout: 500.0）；load 须收敛为 int，
        // 不能 (int) Object 硬转——线上建条目即 ClassCastException（bug-record-20260829-080117）。
        // serialIntegration 已由 setUp 注入 mock，load(null) 在 DeviceBase 侧测试安全跳过 registry。
        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("serial_port", "ttyUSB201");
        commSettings.put("baudrate", "9600");
        commSettings.put("data_bits", "8");
        commSettings.put("stop_bits", "1");
        commSettings.put("parity", "None");
        commSettings.put("flow_control", "0");
        commSettings.put("timeout", new java.math.BigDecimal("500.0"));
        Map<String, Object> data = new HashMap<>();
        data.put("comm_settings", commSettings);

        ConfigEntry entry = new ConfigEntry.Builder()
                .entryId("test-entry-sms8600v2-load")
                .coordinate("com.ecat:integration-saimosen")
                .uniqueId("SMS8600V2LoadTest")
                .data(data)
                .build();
        SMS8600V2Device loadDevice = new SMS8600V2Device(entry);
        loadDevice.load(null);
        assertNotNull("load 须产出 serialInfo（BigDecimal timeout 不得炸）", loadDevice.serialInfo);
        assertTrue("timeout 须收敛为 500ms: " + loadDevice.serialInfo,
                loadDevice.serialInfo.toString().contains("timeout=500"));
    }

    // ==================== 实时数据处理测试 ====================

    /**
     * 测试发送命令时是否构造正确的命令格式
     * 
     * 测试数据：
     * - 命令："calochr$"（实时数据查询命令）
     * - 响应："*O3=500.0PPB$"（带*前缀的臭氧浓度 500.0 PPB）
     * 
     * 测试流程：
     * 1. 模拟串口发送数据并返回成功
     * 2. 重置响应处理器并设置 mock 行为
     * 3. 通过反射调用 sendCommand 方法
     * 
     * 验证内容：
     * 1. asyncSendData 是否使用正确的命令字节数组调用
     * 2. 命令执行后 o3 属性值是否为 500.0
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testSendCommand_ConstructsCorrectCommand() throws Exception {
        initSMS8600V2Device();
        when(mockSerialSource.asyncSendData(any(byte[].class))).thenReturn(CompletableFuture.completedFuture(true));

        reset(mockResponseHandlerStrategy);
        when(mockResponseHandlerStrategy.handleResponse(any())).thenAnswer(invocation -> {
            ByteResponseHandlingContext<byte[]> ctx = invocation.getArgument(0);
            // 直接设置 receiveBuffer 的内容
            try {
                java.lang.reflect.Field field = ByteResponseHandlingContext.class.getDeclaredField("receiveBuffer");
                field.setAccessible(true);
                ByteArrayOutputStream buffer = (ByteArrayOutputStream) field.get(ctx);
                buffer.reset();
                buffer.write("*O3=500.0PPB$".getBytes());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture(invokePrivateMethod(sms8600v2Device, "processResponse", ctx));
        });

        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) invokePrivateMethod(sms8600v2Device, "sendCommand", "calochr$".getBytes());
        assertTrue("命令执行应该成功", future.get());

        verify(mockSerialSource, times(1)).asyncSendData("calochr$".getBytes());

        AQAttribute o3Attr = (AQAttribute) sms8600v2Device.getAttrs().get("o3");
        assertNotNull("o3属性不应该为空", o3Attr);
        assertEquals("o3浓度值应该为500.0", 500.0, ((Number) o3Attr.getState().getValue()).doubleValue(), 0.01);
    }

    /**
     * 测试实时O3浓度数据处理功能（基于实际串口数据格式）
     * 
     * 测试数据：
     * - 命令："calochr$"（实时数据命令）
     * - 响应："*O3=500.0PPB$"（带*前缀的臭氧浓度数据，表示校准状态）
     * 
     * 测试流程：
     * 1. 设置 context 的 receiveBuffer 包含响应数据
     * 2. 设置 context 的 newValue 为命令字节数组
     * 3. 通过反射调用 processResponse 方法
     * 
     * 预期结果：
     * 1. processResponse 返回 true（处理成功）
     * 2. o3 属性值更新为 500.0
     * 3. o3 属性状态为 CALIBRATION（因为*前缀表示校准状态）
     */
    @Test
    public void testProcessResponse_HandlesRealData_O3() throws Exception {
        initSMS8600V2Device();

        // 测试实际串口测试数据：*O3=500.0PPB$
        setupContextReceiveBuffer(context, "*O3=500.0PPB$");
        when(context.getNewValue()).thenReturn("calochr$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("实时数据处理应该成功", result);
        AQAttribute o3Attr = (AQAttribute) sms8600v2Device.getAttrs().get("o3");
        assertNotNull("o3属性不应该为空", o3Attr);
        assertEquals("o3浓度值应该为500.0", 500.0, ((Number) o3Attr.getState().getValue()).doubleValue(), 0.01);
        // determineDataStatus 会检查 work_status 属性，由于没有设置手动状态，默认返回 CALIBRATION
        assertEquals("o3状态应该为CALIBRATION", AttributeStatus.CALIBRATION, o3Attr.getState() != null ? o3Attr.getState().getStatus() : null);
    }

    /**
     * 测试实时O3浓度数据处理功能（无前缀的正常数据）
     * 
     * 测试数据：
     * - 命令："calochr$"
     * - 响应："O3=350.5PPB$"（无前缀的正常数据）
     * 
     * 预期结果：
     * 1. o3 属性值更新为 350.5
     * 2. o3 属性状态为 NORMAL
     */
    @Test
    public void testProcessResponse_HandlesRealData_O3_Normal() throws Exception {
        initSMS8600V2Device();

        setupContextReceiveBuffer(context, "O3=350.5PPB$");
        when(context.getNewValue()).thenReturn("calochr$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("实时数据处理应该成功", result);
        AQAttribute o3Attr = (AQAttribute) sms8600v2Device.getAttrs().get("o3");
        assertEquals("o3浓度值应该为350.5", 350.5, ((Number) o3Attr.getState().getValue()).doubleValue(), 0.01);
        assertEquals("o3状态应该为NORMAL", AttributeStatus.NORMAL, o3Attr.getState() != null ? o3Attr.getState().getStatus() : null);
    }

    /**
     * 测试分钟浓度数据处理功能
     * 
     * 测试数据：
     * - 命令："calocha$"（分钟数据命令）
     * - 响应："*O3=450.5PPB$"（带*前缀的臭氧分钟浓度数据）
     * 
     * 测试流程：
     * 1. 设置 context 的 receiveBuffer 包含响应数据
     * 2. 设置 context 的 newValue 为命令字节数组
     * 3. 通过反射调用 processResponse 方法
     * 
     * 预期结果：
     * 1. processResponse 返回 true（处理成功）
     * 2. o3_minute 属性值更新为 450.5
     * 3. o3_minute 属性状态为 NORMAL（因为分钟数据使用getDataStatus，不检查前缀）
     */
    @Test
    public void testProcessResponse_HandlesMinuteData() throws Exception {
        initSMS8600V2Device();

        // 测试分钟浓度数据：*O3=450.5PPB$
        setupContextReceiveBuffer(context, "*O3=450.5PPB$");
        when(context.getNewValue()).thenReturn("calocha$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("分钟数据处理应该成功", result);
        AQAttribute o3MinuteAttr = (AQAttribute) sms8600v2Device.getAttrs().get("o3_minute");
        assertNotNull("o3_minute属性不应该为空", o3MinuteAttr);
        assertEquals("o3_minute浓度值应该为450.5", 450.5, ((Number) o3MinuteAttr.getState().getValue()).doubleValue(), 0.01);
        // 注意：分钟数据使用getDataStatus方法，只检查手动状态，不检查数据前缀，所以默认为NORMAL
        assertEquals("o3_minute状态应该为NORMAL", AttributeStatus.NORMAL, o3MinuteAttr.getState() != null ? o3MinuteAttr.getState().getStatus() : null);
    }

    // ==================== 状态数据解析测试 ====================

    /**
     * 测试状态数据处理功能（23 字段完整协议 - 正常工作状态）
     * 
     * 测试数据：
     * - 命令："calotwc$"（状态数据命令）
     * - 响应：23 个逗号分隔的字段，包括：
     *   [0]pmt_v=3000.0, [1]canbi_v=3001.0, [2]yuliu1=0, [3]power_component=2500.0,
     *   [4]photometer_press=101.3, [5]photometer_flow=500.0, [6]photometer_temp=25.0,
     *   [7]case_temp=26.0, [8]photometer_lamp_temp=45.0, [9]yuliu2=0,
     *   [10]o3_generator_temp=35.0, [11]zero_press=100.0, [12]span_press=100.0,
     *   [13]valve_press=100.0, [14]zero_flow=5.0, [15]span_flow=0,
     *   [16]o3_drive_v=12.0, [17]o3_output_v=13.0, [18]o3_generator_flow=100.0,
     *   [19]slope=1.0, [20]intercept=0.0, [21]alarm_code=0000, [22]work_status=0
     * 
     * 状态解析：
     * - alarm_code=0000（无报警）
     * - work_status=0（正常工作状态）
     * 
     * 预期结果：
     * 1. 所有数值属性正确解析
     * 2. work_status 值为"Normal"（英文名），状态为 NORMAL
     */
    @Test
    public void testProcessResponse_HandlesStatusData_Normal() throws Exception {
        initSMS8600V2Device();

        // 测试状态数据（23 个字段，正常工作状态）
        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,0,12.0,13.0,100.0,1.0,0.0,0000,0$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("状态数据处理应该成功", result);
        
        NumericAttribute pmtV = (NumericAttribute) sms8600v2Device.getAttrs().get("pmt_v");
        NumericAttribute canbiV = (NumericAttribute) sms8600v2Device.getAttrs().get("canbi_v");
        NumericAttribute powerComponent = (NumericAttribute) sms8600v2Device.getAttrs().get("power_component");
        NumericAttribute photometerPress = (NumericAttribute) sms8600v2Device.getAttrs().get("photometer_press");
        NumericAttribute photometerFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("photometer_flow");
        NumericAttribute photometerTemp = (NumericAttribute) sms8600v2Device.getAttrs().get("photometer_temp");
        NumericAttribute caseTemp = (NumericAttribute) sms8600v2Device.getAttrs().get("case_temp");
        NumericAttribute slope = (NumericAttribute) sms8600v2Device.getAttrs().get("slope");
        NumericAttribute intercept = (NumericAttribute) sms8600v2Device.getAttrs().get("intercept");
        TextAttribute alarmCode = (TextAttribute) sms8600v2Device.getAttrs().get("alarm_code");
        StringSelectAttribute workStatus = (StringSelectAttribute) sms8600v2Device.getAttrs().get("work_status");

        assertEquals("pmt_v应该为3000.0", 3000.0, ((Number) pmtV.getState().getValue()).doubleValue(), 0.01);
        assertEquals("canbi_v应该为3001.0", 3001.0, ((Number) canbiV.getState().getValue()).doubleValue(), 0.01);
        assertEquals("power_component应该为2500.0", 2500.0, ((Number) powerComponent.getState().getValue()).doubleValue(), 0.01);
        assertEquals("photometer_press应该为101.3", 101.3, ((Number) photometerPress.getState().getValue()).doubleValue(), 0.01);
        assertEquals("photometer_flow应该为500.0", 500.0, ((Number) photometerFlow.getState().getValue()).doubleValue(), 0.01);
        assertEquals("photometer_temp应该为25.0", 25.0, ((Number) photometerTemp.getState().getValue()).doubleValue(), 0.01);
        assertEquals("case_temp应该为26.0", 26.0, ((Number) caseTemp.getState().getValue()).doubleValue(), 0.01);
        assertEquals("slope应该为1.0", 1.0, ((Number) slope.getState().getValue()).doubleValue(), 0.00001);
        assertEquals("intercept应该为0.0", 0.0, ((Number) intercept.getState().getValue()).doubleValue(), 0.001);
        assertEquals("alarm_code应该为0000", "0000", alarmCode.getState() != null ? alarmCode.getState().getValue() : null);
        assertEquals("work_status值应该为Normal", AttributeStatus.NORMAL.getName(), workStatus.getState() != null ? workStatus.getState().getValue() : null);
        assertEquals("work_status状态应该为NORMAL", AttributeStatus.NORMAL, workStatus.getState() != null ? workStatus.getState().getStatus() : null);
    }

    /**
     * 测试零点检查状态的处理
     * 
     * 测试数据：
     * - 命令："calotwc$"
     * - 响应：work_status=1（零点检查状态），其他字段与正常状态相同
     * 
     * 状态解析：
     * - alarm_code=0000（无报警）
     * - work_status=1（零点检查）
     * 
     * 预期结果：
     * 1. work_status 值为"ZeroCheck"（英文名）
     * 2. work_status 状态为 ZERO_CHECK 枚举值
     */
    @Test
    public void testProcessResponse_HandlesStatusData_ZeroCheck() throws Exception {
        initSMS8600V2Device();

        // 测试零点检查状态（work_status=1）
        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,0,12.0,13.0,100.0,1.0,0.0,0000,1$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("状态数据处理应该成功", result);
        
        StringSelectAttribute workStatus = (StringSelectAttribute) sms8600v2Device.getAttrs().get("work_status");
        assertEquals("work_status值应该为ZeroCheck", AttributeStatus.ZERO_CHECK.getName(), workStatus.getState() != null ? workStatus.getState().getValue() : null);
        assertEquals("work_status状态应该为ZERO_CHECK", AttributeStatus.ZERO_CHECK, workStatus.getState() != null ? workStatus.getState().getStatus() : null);
    }

    /**
     * 测试跨度检查状态的处理
     * 
     * 测试数据：
     * - 命令："calotwc$"
     * - 响应：work_status=2（跨度检查状态），span_flow=500（有流量）
     * 
     * 状态解析：
     * - alarm_code=0000（无报警）
     * - work_status=2（跨度检查）
     * 
     * 预期结果：
     * 1. work_status 值为"SpanCheck"（英文名）
     * 2. work_status 状态为 SPAN_CHECK 枚举值
     */
    @Test
    public void testProcessResponse_HandlesStatusData_SpanCheck() throws Exception {
        initSMS8600V2Device();

        // 测试跨度检查状态（work_status=2）
        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,500,12.0,13.0,100.0,1.0,0.0,0000,2$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("状态数据处理应该成功", result);
        
        StringSelectAttribute workStatus = (StringSelectAttribute) sms8600v2Device.getAttrs().get("work_status");
        assertEquals("work_status值应该为SpanCheck", AttributeStatus.SPAN_CHECK.getName(), workStatus.getState() != null ? workStatus.getState().getValue() : null);
        assertEquals("work_status状态应该为SPAN_CHECK", AttributeStatus.SPAN_CHECK, workStatus.getState() != null ? workStatus.getState().getStatus() : null);
    }

    /**
     * 测试报警状态的处理
     * 
     * 测试数据：
     * - 命令："calotwc$"
     * - 响应：alarm_code=0001（有报警），work_status=0（正常工作位）
     * 
     * 状态解析：
     * - alarm_code!=0000（有报警）
     * - 根据优先级，报警状态覆盖工作状态
     * 
     * 预期结果：
     * 1. work_status 值为"Alarm"（英文名）
     * 2. work_status 状态为 ALARM 枚举值
     */
    @Test
    public void testProcessResponse_HandlesStatusData_Alarm() throws Exception {
        initSMS8600V2Device();

        // 测试报警状态（alarm_code!=0000）
        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,0,12.0,13.0,100.0,1.0,0.0,0001,0$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("状态数据处理应该成功", result);
        
        StringSelectAttribute workStatus = (StringSelectAttribute) sms8600v2Device.getAttrs().get("work_status");
        assertEquals("work_status值应该为Alarm", AttributeStatus.ALARM.getName(), workStatus.getState() != null ? workStatus.getState().getValue() : null);
        assertEquals("work_status状态应该为ALARM", AttributeStatus.ALARM, workStatus.getState() != null ? workStatus.getState().getStatus() : null);
    }

    /**
     * 测试状态数据字段不足时的容错处理
     * 
     * 测试数据：
     * - 命令："calotwc$"
     * - 响应："3000.0,3001.0,0,2500.0$"（只有 4 个字段，远少于要求的 23 个）
     * 
     * 测试流程：
     * 1. parseStatusResponse 检测到字段数不足
     * 2. 输出警告日志并直接 return
     * 3. processResponse 仍返回 true（因为进入了 if 语句块）
     * 
     * 预期结果：
     * - processResponse 返回 true（但实际未处理任何数据）
     */
    @Test
    public void testProcessResponse_HandlesStatusData_InsufficientFields() throws Exception {
        initSMS8600V2Device();

        // 测试字段不足的情况（少于 23 个字段）
        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0$"); // 只有 4 个字段
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        // 字段不足时 parseStatusResponse 会直接 return，但 processResponse 仍返回 true（因为进入了 if 语句）
        assertTrue("即使字段不足，processResponse也应返回true", result);
    }

    // ==================== 气体设置数据解析测试 ====================

    /**
     * 测试气体设置响应解析功能
     * 
     * 测试数据：
     * - 命令："calppm,?$"
     * - 响应："calppm,1,100,2,200,3,300,4,400$"
     *   含义：通道1=SO2(1),浓度100; 通道2=NO(2),浓度200; 通道3=CO(3),浓度300; 通道4=GAS4(4),浓度400
     * 
     * 预期结果：
     * 1. channel_1_gas 更新为 "1"（SO2）
     * 2. channel_2_gas 更新为 "2"（NO）
     * 3. channel_3_gas 更新为 "3"（CO）
     * 4. channel_4_gas 更新为 "4"（GAS4）
     * 5. 钢瓶气浓度属性根据通道ID更新对应值
     */
    @Test
    public void testParseGasSettingResponse_Success() throws Exception {
        initSMS8600V2Device();

        setupContextReceiveBuffer(context, "calppm,1,100,2,200,3,300,4,400$");
        when(context.getNewValue()).thenReturn("calppm,?$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("气体设置解析应该成功", result);
        
        StringSelectAttribute channel1Gas = (StringSelectAttribute) sms8600v2Device.getAttrs().get("channel_1_gas");
        StringSelectAttribute channel2Gas = (StringSelectAttribute) sms8600v2Device.getAttrs().get("channel_2_gas");
        StringSelectAttribute channel3Gas = (StringSelectAttribute) sms8600v2Device.getAttrs().get("channel_3_gas");
        StringSelectAttribute channel4Gas = (StringSelectAttribute) sms8600v2Device.getAttrs().get("channel_4_gas");

        assertEquals("channel_1_gas应该为1", "1", channel1Gas.getState() != null ? channel1Gas.getState().getValue() : null);
        assertEquals("channel_2_gas应该为2", "2", channel2Gas.getState() != null ? channel2Gas.getState().getValue() : null);
        assertEquals("channel_3_gas应该为3", "3", channel3Gas.getState() != null ? channel3Gas.getState().getValue() : null);
        assertEquals("channel_4_gas应该为4", "4", channel4Gas.getState() != null ? channel4Gas.getState().getValue() : null);
    }

    /**
     * 测试气体设置响应字段不足时的处理
     * 
     * 测试数据：
     * - 命令："calppm,?$"
     * - 响应："calppm,1,100,2$"（只有 4 个字段，少于要求的 8 个）
     * 
     * 预期结果：
     * - processResponse 返回 true，但不会更新任何属性
     */
    @Test
    public void testParseGasSettingResponse_InsufficientFields() throws Exception {
        initSMS8600V2Device();

        setupContextReceiveBuffer(context, "calppm,1,100,2$");
        when(context.getNewValue()).thenReturn("calppm,?$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("即使字段不足，processResponse也应返回true", result);
    }

    // ==================== 校准命令响应测试 ====================

    /**
     * 测试校准气体通道设置响应（calcha命令）
     * 
     * 测试数据：
     * - 命令："calcha,1,1$"（设置通道1为气体1）
     * - 响应："calchaok$"（成功响应）
     * 
     * 预期结果：
     * 1. processResponse 返回 true
     * 2. channel_1_gas 更新为 "1"
     */
    @Test
    public void testParseCalchaResponse_Success() throws Exception {
        initSMS8600V2Device();

        setupContextReceiveBuffer(context, "calchaok$");
        when(context.getNewValue()).thenReturn("calcha,1,1$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("校准气体通道设置应该成功", result);
        
        StringSelectAttribute channel1Gas = (StringSelectAttribute) sms8600v2Device.getAttrs().get("channel_1_gas");
        assertEquals("channel_1_gas应该为1", "1", channel1Gas.getState() != null ? channel1Gas.getState().getValue() : null);
    }

    /**
     * 测试校准气体浓度设置响应（calppm命令）
     * 
     * 测试数据：
     * - 命令："calppm,1,150$"（设置通道1浓度为150）
     * - 响应："calppmok$"（成功响应）
     * 
     * 预期结果：
     * 1. processResponse 返回 true
     * 2. 对应通道的钢瓶气浓度属性更新为 150.0
     */
    @Test
    public void testParseCalppmResponse_Success() throws Exception {
        initSMS8600V2Device();

        // 先设置通道1的气体类型为SO2，并设置channelId
        StringSelectAttribute channel1Gas = (StringSelectAttribute) sms8600v2Device.getAttrs().get("channel_1_gas");
        channel1Gas.updateValue("1");
        
        // 设置所有气体属性的channelId，避免NullPointerException
        SMS8600V2CylinderGasNumericAttribute so2Attr = 
            (SMS8600V2CylinderGasNumericAttribute) sms8600v2Device.getAttrs().get("gas_so2_cylinder_gas_conc");
        so2Attr.setChannelId("1");
        
        SMS8600V2CylinderGasNumericAttribute noAttr = 
            (SMS8600V2CylinderGasNumericAttribute) sms8600v2Device.getAttrs().get("gas_no_cylinder_gas_conc");
        noAttr.setChannelId("2");
        
        SMS8600V2CylinderGasNumericAttribute coAttr = 
            (SMS8600V2CylinderGasNumericAttribute) sms8600v2Device.getAttrs().get("gas_co_cylinder_gas_conc");
        coAttr.setChannelId("3");

        setupContextReceiveBuffer(context, "calppmok$");
        when(context.getNewValue()).thenReturn("calppm,1,150$".getBytes());

        Boolean result = (Boolean) invokePrivateMethod(sms8600v2Device, "processResponse", context);

        assertTrue("校准气体浓度设置应该成功", result);
        assertEquals("SO2钢瓶气浓度应该为150.0", 150.0, ((Number) so2Attr.getState().getValue()).doubleValue(), 0.01);
    }

    // ==================== 占位符值处理测试 ====================

    /**
     * 测试占位符值（"-"和"---"）的处理
     * 
     * 测试场景：
     * 当传感器返回"-"或"---"表示无效数据时，属性应设置为 EMPTY 状态
     * 
     * 测试流程：
     * 1. 通过反射调用 updateAttribute 方法
     * 2. 传入"-"作为 power_component 的值
     * 3. 传入"---"作为 span_flow 的值
     * 
     * 预期结果：
     * 1. power_component 的状态变为 EMPTY
     * 2. span_flow 的状态变为 EMPTY
     * 注意：占位符不会修改属性的数值，只改变状态
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void testUpdateAttribute_HandlesDashValue() throws Exception {
        initSMS8600V2Device();

        // 通过反射获取 AttributeType 枚举值
        Class<?> serialDeviceBaseClass = SerialDeviceBase.class;
        Class<?> attributeTypeClass = null;
        for (Class<?> innerClass : serialDeviceBaseClass.getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("AttributeType")) {
                attributeTypeClass = innerClass;
                break;
            }
        }
        assertNotNull("AttributeType enum应该存在", attributeTypeClass);
        Object numericEnum = Enum.valueOf((Class<Enum>) attributeTypeClass, "NUMERIC");

        // 测试处理单个 "-" 的情况
        invokePrivateMethod(sms8600v2Device, "updateAttribute", "power_component", numericEnum, "-", AttributeStatus.NORMAL);
        NumericAttribute powerComponent = (NumericAttribute) sms8600v2Device.getAttrs().get("power_component");
        // "-" 是占位符，updateAttribute 内部直接 return 不更新属性；重构后该属性从未 updateValue，
        // state 为 null（无 lastState）。验证占位符未触发任何数据写入：state 仍为 null（未变更）。
        assertNull("power_component 收到占位符 '-' 不应被更新，state 应保持 null", powerComponent.getState());

        // 测试处理 "---" 的情况
        invokePrivateMethod(sms8600v2Device, "updateAttribute", "span_flow", numericEnum, "---", AttributeStatus.NORMAL);
        NumericAttribute spanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("span_flow");
        assertNull("span_flow 收到占位符 '---' 不应被更新，state 应保持 null", spanFlow.getState());
    }

    // ==================== 标气流量映射测试 ====================

    /**
     * 测试标气流量映射功能 - SO2气体
     * 
     * 测试场景：
     * 根据 calibration_gas_config 配置，将 span_flow 映射到对应气体的虚拟属性
     * 
     * 测试数据：
     * - calibration_gas_config="S"（SO2 气体）
     * - span_flow=500
     * 
     * 测试流程：
     * 1. 设置气体配置为"S"（SO2）
     * 2. 处理包含 span_flow=500 的状态数据
     * 3. 验证 so2_span_flow 虚拟属性是否被正确设置
     * 
     * 预期结果：
     * - so2_span_flow 的值应为 500.0
     */
    @Test
    public void testUpdateAttribute_HandlesSpanFlow_SO2() throws Exception {
        initSMS8600V2Device();

        // 测试标气流量映射到 SO2
        StringSelectAttribute gasConfig = (StringSelectAttribute) sms8600v2Device.getAttrs().get("calibration_gas_config");
        gasConfig.updateValue("S");

        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,500,12.0,13.0,100.0,1.0,0.0,0000,2$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        invokePrivateMethod(sms8600v2Device, "processResponse", context);

        NumericAttribute so2SpanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("so2_span_flow");
        assertEquals("so2_span_flow应该为500.0", 500.0, ((Number) so2SpanFlow.getState().getValue()).doubleValue(), 0.01);
    }

    /**
     * 测试标气流量映射功能 - NO气体
     * 
     * 测试数据：
     * - calibration_gas_config="N"（NO 气体）
     * - span_flow=600
     * 
     * 预期结果：
     * - no2_span_flow 的值应为 600.0
     */
    @Test
    public void testUpdateAttribute_HandlesSpanFlow_NO() throws Exception {
        initSMS8600V2Device();

        StringSelectAttribute gasConfig = (StringSelectAttribute) sms8600v2Device.getAttrs().get("calibration_gas_config");
        gasConfig.updateValue("N");

        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,600,12.0,13.0,100.0,1.0,0.0,0000,2$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        invokePrivateMethod(sms8600v2Device, "processResponse", context);

        NumericAttribute no2SpanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("no2_span_flow");
        assertEquals("no2_span_flow应该为600.0", 600.0, ((Number) no2SpanFlow.getState().getValue()).doubleValue(), 0.01);
    }

    /**
     * 测试标气流量映射功能 - CO气体
     * 
     * 测试数据：
     * - calibration_gas_config="C"（CO 气体）
     * - span_flow=700
     * 
     * 预期结果：
     * - co_span_flow 的值应为 700.0
     */
    @Test
    public void testUpdateAttribute_HandlesSpanFlow_CO() throws Exception {
        initSMS8600V2Device();

        StringSelectAttribute gasConfig = (StringSelectAttribute) sms8600v2Device.getAttrs().get("calibration_gas_config");
        gasConfig.updateValue("C");

        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,700,12.0,13.0,100.0,1.0,0.0,0000,2$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        invokePrivateMethod(sms8600v2Device, "processResponse", context);

        NumericAttribute coSpanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("co_span_flow");
        assertEquals("co_span_flow应该为700.0", 700.0, ((Number) coSpanFlow.getState().getValue()).doubleValue(), 0.01);
    }

    /**
     * 测试标气流量为0时清空所有虚拟流量属性
     * 
     * 测试数据：
     * - calibration_gas_config="S"
     * - span_flow=0（流量为0）
     * 
     * 预期结果：
     * - co_span_flow、so2_span_flow、no2_span_flow 都应为 0.0
     */
    @Test
    public void testUpdateAttribute_HandlesSpanFlow_Zero() throws Exception {
        initSMS8600V2Device();

        StringSelectAttribute gasConfig = (StringSelectAttribute) sms8600v2Device.getAttrs().get("calibration_gas_config");
        gasConfig.updateValue("S");

        setupContextReceiveBuffer(context, "3000.0,3001.0,0,2500.0,101.3,500.0,25.0,26.0,45.0,0,35.0,100.0,100.0,100.0,5.0,0,12.0,13.0,100.0,1.0,0.0,0000,0$");
        when(context.getNewValue()).thenReturn("calotwc$".getBytes());

        invokePrivateMethod(sms8600v2Device, "processResponse", context);

        NumericAttribute coSpanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("co_span_flow");
        NumericAttribute so2SpanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("so2_span_flow");
        NumericAttribute no2SpanFlow = (NumericAttribute) sms8600v2Device.getAttrs().get("no2_span_flow");

        assertEquals("co_span_flow应该为0.0", 0.0, ((Number) coSpanFlow.getState().getValue()).doubleValue(), 0.01);
        assertEquals("so2_span_flow应该为0.0", 0.0, ((Number) so2SpanFlow.getState().getValue()).doubleValue(), 0.01);
        assertEquals("no2_span_flow应该为0.0", 0.0, ((Number) no2SpanFlow.getState().getValue()).doubleValue(), 0.01);
    }

    // ==================== I18n 国际化测试 ====================

    /**
     * 测试属性显示名称的国际化（i18n）
     * 
     * 测试目的：
     * 验证 strings.json 中配置的属性显示名称是否正确加载
     * 
     * 测试流程：
     * 1. 禁用 i18n 资源加载（仅使用 strings.json）
     * 2. 重新初始化设备
     * 3. 使用 TestTools.assertAttributeDisplayName 验证各属性显示名
     * 
     * 验证的属性（部分示例）：
     * - o3: "实时臭氧浓度"
     * - o3_minute: "分钟臭氧浓度"
     * - pmt_v: "测量电压"
     * - work_status: "工作状态"
     */
    @Test
    public void testSMS8600V2DeviceI18nDisplayNames() throws Exception {
        ResourceLoader.setLoadI18nResources(false);

        try {
            sms8600v2Device.init();

            // 验证主要属性的显示名
            TestTools.assertAttributeDisplayName(sms8600v2Device, "o3", "实时臭氧浓度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "o3_minute", "分钟臭氧浓度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "pmt_v", "测量电压");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "canbi_v", "参比电压");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "power_component", "电源组件");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "photometer_press", "光度计样气压力");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "photometer_flow", "光度计氧气流量");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "photometer_temp", "光度计样气温度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "case_temp", "机箱温度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "photometer_lamp_temp", "光度计灯温度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "o3_generator_temp", "臭氧发生器温度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "slope", "斜率");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "intercept", "截距");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "alarm_code", "报警代码");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "work_status", "工作状态");
        } finally {
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    /**
     * 测试校准命令子命令的国际化（i18n）
     * 
     * 测试目的：
     * 验证 calibration_cmd_commands 下各子命令的显示名称
     * 
     * 测试数据：
     * - zero_start: "零点校准开始"
     * - zero_end: "零点校准结束"
     * - span_start: "跨度校准开始"
     * - span_end: "跨度校准结束"
     * - gpt_start: "GPT校准开始"
     * - gpt_end: "GPT校准结束"
     * 
     * 测试流程：
     * 1. 创建 I18nProxy 代理对象
     * 2. 遍历所有命令键并获取翻译
     * 3. 断言实际值与期望值匹配
     */
    @Test
    public void testSMS8600V2DeviceCommandSubCommandsI18n() throws Exception {
        ResourceLoader.setLoadI18nResources(false);

        try {
            I18nProxy i18n = I18nHelper.createProxy("integration-saimosen", SMS8600V2Device.class);

            // 验证所有子命令的 i18n 支持
            String[] commands = {
                "zero_start", "zero_end",
                "span_start", "span_end",
                "gpt_start", "gpt_end"
            };

            String[] expectedNames = {
                "零点校准开始", "零点校准结束",
                "跨度校准开始", "跨度校准结束",
                "GPT校准开始", "GPT校准结束"
            };

            for (int i = 0; i < commands.length; i++) {
                String key = "devices.sms8600v2device.calibration_cmd_commands." + commands[i];
                String actualName = i18n.t(key);
                assertEquals("Command " + commands[i] + " 应该有正确的i18n名称: " + key,
                             expectedNames[i], actualName);
            }
        } finally {
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    /**
     * 测试设备绑定后的国际化显示名称
     * 
     * 测试目的：
     * 验证设备实例化后，属性的 displayname 仍然正确显示
     * 
     * 测试流程：
     * 1. 禁用 i18n 资源加载
     * 2. 初始化设备实例
     * 3. 验证关键属性的显示名称
     * 
     * 验证的属性：
     * - o3: "实时臭氧浓度"
     * - o3_minute: "分钟臭氧浓度"
     * - work_status: "工作状态"
     */
    @Test
    public void testSMS8600V2DeviceI18nWithDeviceBinding() throws Exception {
        ResourceLoader.setLoadI18nResources(false);

        try {
            sms8600v2Device.init();

            // 验证绑定设备后的 displayname 仍然正确
            TestTools.assertAttributeDisplayName(sms8600v2Device, "o3", "实时臭氧浓度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "o3_minute", "分钟臭氧浓度");
            TestTools.assertAttributeDisplayName(sms8600v2Device, "work_status", "工作状态");
        } finally {
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    /**
     * 测试工作状态选项的国际化（i18n）
     * 
     * 测试目的：
     * 验证 work_status_options 下各状态选项的显示名称
     * 
     * 测试数据（8 个选项）：
     * - normal: "数据有效"
     * - alarm: "传感器报警"
     * - calibration: "校准 (质控)"
     * - maintenance: "维护"
     * - malfunction: "运行不良"
     * - zerocheck: "零点检查"
     * - spancheck: "跨度检查"
     * - flowcheck: "流量检查"
     * 
     * 测试流程：
     * 1. 创建 I18nProxy 代理对象
     * 2. 遍历所有选项键并获取翻译
     * 3. 特别验证 normal 选项显示为"数据有效"
     */
    @Test
    public void testWorkStatusOptionsI18n() throws Exception {
        ResourceLoader.setLoadI18nResources(false);

        try {
            I18nProxy i18n = I18nHelper.createProxy("integration-saimosen", SMS8600V2Device.class);

            // 验证所有选项的 i18n 支持
            String[] options = {"normal", "alarm", "calibration", "maintenance", "malfunction",
                                "zerocheck", "spancheck", "flowcheck"};

            // 根据 strings.json 中的实际配置设置期望值
            String[] expectedNames = {"数据有效", "传感器报警", "校准 (质控)", "维护", "运行不良",
                                      "零点检查", "跨度检查", "流量检查"};

            for (int i = 0; i < options.length; i++) {
                String key = "devices.sms8600v2device.work_status_options." + options[i];
                String actualName = i18n.t(key);
                assertEquals("Option " + options[i] + " 应该有正确的i18n名称: " + key,
                             expectedNames[i], actualName);
            }

            // 特别验证 "normal" 显示为 "数据有效"
            String normalKey = "devices.sms8600v2device.work_status_options.normal";
            String normalName = i18n.t(normalKey);
            assertEquals("'normal'选项应该显示为'数据有效'", "数据有效", normalName);

        } finally {
            ResourceLoader.setLoadI18nResources(true);
        }
    }
    // ==================== 轮询观测垫片（18 号迁移：句柄字段已删，经 round 入口探针观测） ====================

    /** round 入口探针：executePolling 每轮首访 tryAcquire——返回 null=锁忙跳过（零业务副作用）。 */
    private CountDownLatch pollingRoundProbe(int rounds) {
        CountDownLatch latch = new CountDownLatch(rounds);
        when(mockSerialSource.tryAcquire()).thenAnswer(inv -> {
            latch.countDown();
            return null;
        });
        return latch;
    }
}
