package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.EcatCore;
import com.ecat.integration.SaimosenIntegration.ConfigSchemas.SaimosenDeviceConfigSchema;
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
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
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
        ConfigEntry entry = createTestEntry("air.monitor.qc", "质控仪", "RTU");
        // Add device_settings required by QCDevice.parseConfig()
        Map<String, Object> data = entry.getData();
        Map<String, Object> deviceSettings = new HashMap<>();
        deviceSettings.put("sampling_tube_length", 3.0);
        data.put("device_settings", deviceSettings);

        setupMockCoreForDeviceCreation();

        com.ecat.core.Device.DeviceBase device = integration.createDeviceFromEntry(entry);
        assertNotNull("Device should be created from valid entry", device);
        assertTrue("Device should be QCDevice", device instanceof QCDevice);
        assertTrue(device.getUniqueId().startsWith("saimosen_air.monitor.qc"));
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
