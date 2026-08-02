package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.EcatCore;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.SaimosenIntegration.ConfigSchemas.SaimosenDeviceConfigSchema;
import com.ecat.integration.SaimosenIntegration.SaimosenQCModels;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SaimosenIntegration 单元测试类
 * 测试 Saimosen 集成的 ConfigFlow 模式，覆盖全部 9 种设备类型
 *
 * @author coffee
 */
public class SaimosenIntegrationTest {

    private SaimosenIntegration integration;
    private AutoCloseable mockitoCloseable;

    @Mock
    private EcatCore mockCore;

    @Mock
    private IntegrationManager mockIntegrationManager;

    @Before
    public void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        integration = new SaimosenIntegration();

        // Setup mocks
        when(mockCore.getIntegrationManager()).thenReturn(mockIntegrationManager);
        when(mockIntegrationManager.loadConfig(anyString())).thenReturn(new HashMap<>());

        // Set core and integrationManager via reflection
        try {
            java.lang.reflect.Field coreField = integration.getClass().getSuperclass().getSuperclass().getDeclaredField("core");
            coreField.setAccessible(true);
            coreField.set(integration, mockCore);

            java.lang.reflect.Field integrationManagerField = integration.getClass().getSuperclass().getSuperclass().getDeclaredField("integrationManager");
            integrationManagerField.setAccessible(true);
            integrationManagerField.set(integration, mockIntegrationManager);
        } catch (Exception e) {
            // Ignore reflection errors in test
        }
    }

    @After
    public void tearDown() throws Exception {
        // 重置静态变量，防止影响其他测试类
        SmsDeviceBase.modbusIntegration = null;
        resetSerialIntegrationStatic();
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    // ==================== SMS8600V2 工厂越界 addDevice 测试 ====================

    /**
     * SMS8600V2 分支不应在 createDeviceFromEntry 内越界 addDevice。
     *
     * <p>createDeviceFromEntry 是工厂方法，职责仅"构造 + load + init"，设备注册（addDevice→getOrCreate
     * 解析稳定 id + registry + persist）由基类 createEntry 统一收口。SMS8600V2 分支曾在工厂内自行
     * load+init+addDevice+return，导致基类 createEntry 再 addDevice 一次 = 双 publish(DEVICE_LIFECYCLE CREATE)
     * + 双 persist（devices/registry/matchIndex 因幂等只单条，但事件与持久化重复）。
     *
     * <p>本测 spy 集成、stub addDevice 为 no-op（避免未接 registry 时 NPE 掩盖断言），调用工厂后
     * 断言 addDevice 从未被工厂调用（注册留给 createEntry）。RED：工厂内调了 addDevice（verify never 失败）。
     */
    @Test
    public void testCreateDeviceFromEntry_SMS8600V2_DoesNotRegisterInFactory() throws Exception {
        ConfigEntry entry = createSMS8600V2Entry();
        setupMockCoreForSerialDeviceCreation();
        resetSerialIntegrationStatic();

        SaimosenIntegration spy = spy(integration);
        // addDevice 返回 boolean；stub 为 no-op，避免越界调用真注册（registry 未接）NPE 掩盖 verify 断言
        doReturn(false).when(spy).addDevice(any(com.ecat.core.Device.DeviceBase.class));

        com.ecat.core.Device.DeviceBase device = spy.createDeviceFromEntry(entry);

        assertNotNull("SMS8600V2 entry 应创建出设备", device);
        assertTrue("应为 SMS8600V2Device 实例", device instanceof SMS8600V2Device);
        verify(spy, never()).addDevice(any(com.ecat.core.Device.DeviceBase.class));
    }

    @Test
    public void testIntegrationCreation() {
        assertNotNull("SaimosenIntegration should be created", integration);
        assertNotNull("Integration name should not be null", integration.getName());
    }

    @Test
    public void testGetConfigFlow() {
        AbstractConfigFlow configFlow = integration.getConfigFlow();
        assertNotNull("ConfigFlow should not be null", configFlow);
        assertTrue(configFlow.hasUserStep());
        assertTrue(configFlow.hasReconfigureStep());
    }

    // ==================== RTU 协议设备创建测试 ====================

    @Test
    public void testCreateDeviceFromEntry_CO_RTU() {
        ConfigEntry entry = createTestEntry("air.monitor.co", "CO分析仪", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be CODevice", device instanceof CODevice);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.co"));
    }

    // ==================== TCP 协议设备创建测试 ====================

    @Test
    public void testCreateDeviceFromEntry_CO_TCP() {
        ConfigEntry entry = createTestEntry("air.monitor.co", "CO分析仪", "TCP");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be CODevice", device instanceof CODevice);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.co"));
    }

    // ==================== 其余 8 种设备类型测试 ====================

    @Test
    public void testCreateDeviceFromEntry_NO2() {
        ConfigEntry entry = createTestEntry("air.monitor.no2", "NO2分析仪", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be NO2Device", device instanceof NO2Device);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.no2"));
    }

    @Test
    public void testCreateDeviceFromEntry_O3() {
        ConfigEntry entry = createTestEntry("air.monitor.o3", "O3分析仪", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be O3Device", device instanceof O3Device);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.o3"));
    }

    @Test
    public void testCreateDeviceFromEntry_SO2() {
        ConfigEntry entry = createTestEntry("air.monitor.so2", "SO2分析仪", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be SO2Device", device instanceof SO2Device);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.so2"));
    }

    @Test
    public void testCreateDeviceFromEntry_Calibrator() {
        ConfigEntry entry = createTestEntry("air.monitor.calibrator", "校准器", "RTU", "SMS8600V1");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be CalibratorDevice", device instanceof CalibratorDevice);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.calibrator"));
    }

    @Test
    public void testCreateDeviceFromEntry_QC() {
        // QCDevice requires additional device_settings with sampling_tube_length
        ConfigEntry entry = createTestEntry("air.monitor.qc", "质控仪", "RTU", "SMS8910");
        // Add device_settings required by QCDevice.parseConfig()
        Map<String, Object> data = entry.getData();
        Map<String, Object> deviceSettings = new HashMap<>();
        deviceSettings.put("sampling_tube_length", 3.0);
        deviceSettings.put("sampling_tube_inner_diameter", 0.1);
        data.put("device_settings", deviceSettings);

        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be QCDevice", device instanceof QCDevice);
        assertFalse("SMS8910 should not create QCV2Device", device instanceof QCV2Device);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.qc"));
    }

    @Test
    public void testCreateDeviceFromEntry_QCV2() {
        ConfigEntry entry = createTestEntry("air.monitor.qc", "质控仪V2", "RTU", "SMS8910V2");
        Map<String, Object> data = entry.getData();
        Map<String, Object> deviceSettings = new HashMap<>();
        deviceSettings.put("sampling_tube_length", 3.0);
        deviceSettings.put("sampling_tube_inner_diameter", 0.1);
        data.put("device_settings", deviceSettings);

        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be QCV2Device", device instanceof QCV2Device);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.qc"));
    }

    @Test
    public void testClassToModelMap_QC_ContainsV1AndV2() {
        Map<String, String> qcModels = SaimosenIntegration.classToModelMap("air.monitor.qc");
        assertTrue(qcModels.containsKey(SaimosenQCModels.SMS8910));
        assertTrue(qcModels.containsKey(SaimosenQCModels.SMS8910V2));
        assertTrue(qcModels.get(SaimosenQCModels.SMS8910).contains("完整协议 V1"));
        assertTrue(qcModels.get(SaimosenQCModels.SMS8910V2).contains("完整协议 V2"));
        assertEquals(SaimosenIntegration.Protocol.MODBUS.name(),
                SaimosenIntegration.getProtocolByMode(SaimosenQCModels.SMS8910V2));
    }

    @Test
    public void testCreateDeviceFromEntry_SmartPowerStabilizer() {
        ConfigEntry entry = createTestEntry("power.supply.stabilizer", "智能稳压电源", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be SmartPowerStabilizer", device instanceof SmartPowerStabilizer);
        assertTrue(device.getUniqueId().startsWith("saimosen_power.supply.stabilizer"));
    }

    @Test
    public void testCreateDeviceFromEntry_SampleTube() {
        ConfigEntry entry = createTestEntry("sample.tube", "采样管", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be SampleTube", device instanceof SampleTube);
        assertTrue(device.getUniqueId().startsWith("saimosen_sample.tube"));
    }

    @Test
    public void testCreateDeviceFromEntry_ParticulateZeroChecker() {
        ConfigEntry entry = createTestEntry("air.monitor.pm.qc", "颗粒物零点校验仪", "RTU");
        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be ParticulateZeroChecker", device instanceof ParticulateZeroChecker);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.pm.qc"));
    }

    // ==================== 异常场景测试 ====================

    @Test
    public void testCreateDeviceFromEntryWithUnsupportedClass() {
        // unsupported.class is not a valid DeviceClasses value
        // The schema EnumConfigItem only allows the 9 defined options,
        // so an invalid class will fail schema validation
        Map<String, Object> data = new HashMap<>();
        data.put("class", "unsupported.class");
        data.put("name", "Unsupported Device");
        data.put("vendor", "saimosen");
        data.put("modbus_protocol", "RTU");

        Map<String, Object> serialSettings = new HashMap<>();
        serialSettings.put("serial_port", "/dev/ttyUSB0");
        serialSettings.put("baudrate", 9600);
        serialSettings.put("data_bits", 8);
        serialSettings.put("stop_bits", 1);
        serialSettings.put("parity", "None");
        serialSettings.put("timeout", 2000);

        Map<String, Object> commSettings = new HashMap<>();
        commSettings.put("serial_settings", serialSettings);
        commSettings.put("slave_id", 1);

        data.put("comm_settings", commSettings);

        ConfigEntry entry = new ConfigEntry.Builder()
            .entryId("test-entry-unsupported")
            .coordinate("integration-saimosen")
            .uniqueId("saimosen_unsupported")
            .title("Unsupported Device")
            .data(data)
            .build();

        try {
            integration.createDeviceFromEntry(entry);
            fail("Should throw IllegalArgumentException for unsupported class");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Config validation failed"));
        }
    }

    @Test
    public void testCreateDeviceFromEntryWithInvalidEntry() {
        // Missing required fields (name and class)
        Map<String, Object> data = new HashMap<>();
        data.put("modbus_protocol", "RTU");

        ConfigEntry entry = new ConfigEntry.Builder()
            .entryId("test-entry-invalid")
            .coordinate("integration-saimosen")
            .uniqueId("saimosen_invalid")
            .title("Invalid Entry")
            .data(data)
            .build();

        try {
            integration.createDeviceFromEntry(entry);
            fail("Should throw IllegalArgumentException for invalid entry");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Config validation failed"));
        }
    }

    // ==================== Schema 验证测试 ====================

    @Test
    public void testSchemaValidationWithMissingRequiredFields() {
        // Test schema validation directly - missing 'name' and 'class'
        ConfigSchema schema = new SaimosenDeviceConfigSchema().createSchema();

        Map<String, Object> invalidData = new HashMap<>();
        Map<String, Object> errors = schema.validate(invalidData);

        assertFalse("Schema validation should find errors for missing required fields", errors.isEmpty());
        assertTrue(errors.containsKey("name"));
        assertTrue(errors.containsKey("class"));
    }

    @Test
    public void testSchemaValidationWithValidData() {
        ConfigSchema schema = new SaimosenDeviceConfigSchema().createSchema();

        Map<String, Object> validData = new HashMap<>();
        validData.put("class", "air.monitor.co");
        validData.put("name", "Test CO Device");
        validData.put("sn", "SN001");
        validData.put("vendor", "saimosen");

        Map<String, Object> errors = schema.validate(validData);
        assertTrue("Schema validation should pass with valid data", errors.isEmpty());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试用 ConfigEntry
     *
     * @param deviceClass 设备类型字符串，对应 DeviceClasses 枚举值
     * @param deviceName  设备名称
     * @param protocol    协议类型 "RTU" 或 "TCP"
     */
    private ConfigEntry createTestEntry(String deviceClass, String deviceName, String protocol) {
        Map<String, Object> data = new HashMap<>();
        data.put("class", deviceClass);
        data.put("name", deviceName);
        data.put("vendor", "saimosen");
        data.put("sn", "001");
        data.put("modbus_protocol", protocol);

        if ("TCP".equals(protocol)) {
            // TCP comm_settings: ip_address, port, slave_id
            Map<String, Object> tcpSettings = new HashMap<>();
            tcpSettings.put("ip_address", "192.168.1.100");
            tcpSettings.put("port", 502);

            Map<String, Object> commSettings = new HashMap<>();
            commSettings.put("ip_address", "192.168.1.100");
            commSettings.put("port", 502);
            commSettings.put("slave_id", 1);

            data.put("comm_settings", commSettings);
        } else {
            // RTU comm_settings with nested serial_settings (field names match ModbusRtuCommConfigSchema)
            Map<String, Object> serialSettings = new HashMap<>();
            serialSettings.put("serial_port", "/dev/ttyUSB0");
            serialSettings.put("baudrate", 9600);
            serialSettings.put("data_bits", 8);
            serialSettings.put("stop_bits", 1);
            serialSettings.put("parity", "None");
            serialSettings.put("timeout", 2000);

            Map<String, Object> commSettings = new HashMap<>();
            commSettings.put("serial_settings", serialSettings);
            commSettings.put("slave_id", 1);

            data.put("comm_settings", commSettings);
        }

        String uniqueId = "saimosen_" + deviceClass + "_SN001";

        return new ConfigEntry.Builder()
            .entryId("test-entry-" + deviceClass.replace(".", "-"))
            .coordinate("integration-saimosen")
            .uniqueId(uniqueId)
            .title(deviceName)
            .data(data)
            .build();
    }

    /**
     * 创建测试用 ConfigEntry
     *
     * @param deviceClass 设备类型字符串，对应 DeviceClasses 枚举值
     * @param deviceName  设备名称
     * @param protocol    协议类型 "RTU" 或 "TCP"
     */
    private ConfigEntry createTestEntry(String deviceClass, String deviceName, String protocol, String model) {
        Map<String, Object> data = new HashMap<>();
        data.put("class", deviceClass);
        data.put("name", deviceName);
        data.put("vendor", "saimosen");
        data.put("sn", "001");
        data.put("modbus_protocol", protocol);
        data.put("model", model);

        if ("TCP".equals(protocol)) {
            // TCP comm_settings: ip_address, port, slave_id
            Map<String, Object> tcpSettings = new HashMap<>();
            tcpSettings.put("ip_address", "192.168.1.100");
            tcpSettings.put("port", 502);

            Map<String, Object> commSettings = new HashMap<>();
            commSettings.put("ip_address", "192.168.1.100");
            commSettings.put("port", 502);
            commSettings.put("slave_id", 1);

            data.put("comm_settings", commSettings);
        } else {
            // RTU comm_settings with nested serial_settings (field names match ModbusRtuCommConfigSchema)
            Map<String, Object> serialSettings = new HashMap<>();
            serialSettings.put("serial_port", "/dev/ttyUSB0");
            serialSettings.put("baudrate", 9600);
            serialSettings.put("data_bits", 8);
            serialSettings.put("stop_bits", 1);
            serialSettings.put("parity", "None");
            serialSettings.put("timeout", 2000);

            Map<String, Object> commSettings = new HashMap<>();
            commSettings.put("serial_settings", serialSettings);
            commSettings.put("slave_id", 1);

            data.put("comm_settings", commSettings);
        }

        String uniqueId = "saimosen_" + deviceClass + "_SN001";

        return new ConfigEntry.Builder()
                .entryId("test-entry-" + deviceClass.replace(".", "-"))
                .coordinate("integration-saimosen")
                .uniqueId(uniqueId)
                .title(deviceName)
                .data(data)
                .build();
    }

    /**
     * 构造 SMS8600V2（SerialDeviceBase 分支）测试 entry。
     * <p>comm_settings 为扁平串口字段（SerialDeviceBase.load 直接读 comm_settings.serial_port 等，
     * 非 modbus 的嵌套 serial_settings）；schema 仅校验 class/name/sn，model/comm_settings 不入 schema。
     */
    private ConfigEntry createSMS8600V2Entry() {
        Map<String, Object> data = new HashMap<>();
        data.put("class", "air.monitor.calibrator");
        data.put("name", "SMS8600V2 校准器");
        data.put("vendor", "saimosen");
        data.put("sn", "SMS8600V2-SN001");
        data.put("model", "SMS8600V2");

        Map<String, Object> comm = new HashMap<>();
        comm.put("serial_port", "/dev/ttyUSB200");
        comm.put("baudrate", "9600");
        comm.put("data_bits", "8");
        comm.put("stop_bits", "1");
        comm.put("parity", "N");
        // timeout 经 SerialDeviceBase.load:68 直接 (int) 强转（非 toInt(String)），须为 Integer 非 String
        comm.put("timeout", 2000);
        data.put("comm_settings", comm);

        return new ConfigEntry.Builder()
                .entryId("test-entry-sms8600v2")
                .coordinate("integration-saimosen")
                .uniqueId("saimosen_sms8600v2_SN001")
                .title("SMS8600V2")
                .data(data)
                .build();
    }

    /**
     * 为 SerialDeviceBase（SMS8600V2）设备创建流程配置 mockCore：
     * IntegrationRegistry 返回 mock SerialIntegration（"integration-serial"），
     * register 返回 mock SerialSource；并接 TaskManager/executor 与 BusRegistry（doNothing publish）。
     */
    private void setupMockCoreForSerialDeviceCreation() {
        try {
            java.lang.reflect.Field coreField = integration.getClass().getSuperclass().getSuperclass().getDeclaredField("core");
            coreField.setAccessible(true);
            coreField.set(integration, mockCore);

            com.ecat.core.Integration.IntegrationRegistry mockRegistry =
                    mock(com.ecat.core.Integration.IntegrationRegistry.class);
            when(mockCore.getIntegrationRegistry()).thenReturn(mockRegistry);

            // modbus 分支共用此 registry（兼容既有设备），serial 分支取 integration-serial
            ModbusIntegration mockModbusIntegration = mock(ModbusIntegration.class);
            ModbusSource mockModbusSource = mock(ModbusSource.class);
            when(mockRegistry.getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);
            when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

            SerialIntegration mockSerialIntegration = mock(SerialIntegration.class);
            SerialSource mockSerialSource = mock(SerialSource.class);
            when(mockRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
            when(mockSerialIntegration.register(any(), anyString())).thenReturn(mockSerialSource);
            when(mockSerialSource.getTimeout()).thenReturn(500);

            com.ecat.core.Task.TaskManager mockTaskManager = mock(com.ecat.core.Task.TaskManager.class);
            when(mockCore.getTaskManager()).thenReturn(mockTaskManager);
            when(mockTaskManager.getExecutorService()).thenReturn(mock(java.util.concurrent.ScheduledExecutorService.class));

            com.ecat.core.Bus.BusRegistry mockBusRegistry = mock(com.ecat.core.Bus.BusRegistry.class);
            doNothing().when(mockBusRegistry).publish(any(com.ecat.core.Bus.event.BusEvent.class));
            when(mockCore.getBusRegistry()).thenReturn(mockBusRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 重置 SerialDeviceBase.serialIntegration 静态字段，确保 load() 从本测试的 mock registry 重新取值
     * （静态字段跨测试共享，否则可能残留其他用例的真实/mock 实例跳过 load 内的赋值）。
     */
    private void resetSerialIntegrationStatic() {
        try {
            java.lang.reflect.Field f = SerialDeviceBase.class.getDeclaredField("serialIntegration");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 配置 mockCore 以支持设备创建流程
     * <p>
     * 需要 mock IntegrationRegistry、ModbusIntegration、ModbusSource、TaskManager 等
     */
    private void setupMockCoreForDeviceCreation() {
        try {
            java.lang.reflect.Field coreField = integration.getClass().getSuperclass().getSuperclass().getDeclaredField("core");
            coreField.setAccessible(true);
            coreField.set(integration, mockCore);

            com.ecat.core.Integration.IntegrationRegistry mockRegistry =
                mock(com.ecat.core.Integration.IntegrationRegistry.class);
            when(mockCore.getIntegrationRegistry()).thenReturn(mockRegistry);

            com.ecat.integration.ModbusIntegration.ModbusIntegration mockModbusIntegration =
                mock(com.ecat.integration.ModbusIntegration.ModbusIntegration.class);
            when(mockRegistry.getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);

            com.ecat.integration.ModbusIntegration.ModbusSource mockModbusSource =
                mock(com.ecat.integration.ModbusIntegration.ModbusSource.class);
            when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

            com.ecat.core.Task.TaskManager mockTaskManager = mock(com.ecat.core.Task.TaskManager.class);
            when(mockCore.getTaskManager()).thenReturn(mockTaskManager);

            java.util.concurrent.ScheduledExecutorService mockExecutor =
                mock(java.util.concurrent.ScheduledExecutorService.class);
            when(mockTaskManager.getExecutorService()).thenReturn(mockExecutor);

        } catch (Exception e) {
            // Ignore reflection errors
        }
    }
}
