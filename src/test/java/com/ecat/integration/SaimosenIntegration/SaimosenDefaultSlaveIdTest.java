package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceClasses;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.EcatCore;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.ModbusIntegration.ModbusInfo;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code comm_settings.slave_id} 可选时，四气态默认从站号：CO=1 / NO₂=2 / O₃=3 / SO₂=4。
 */
public class SaimosenDefaultSlaveIdTest {

    @Mock private EcatCore mockCore;
    @Mock private IntegrationRegistry mockRegistry;
    @Mock private ModbusIntegration mockModbus;
    @Mock private ModbusSource mockSource;

    @Before
    public void setUp() {
        ResourceLoader.setLoadI18nResources(false);
        MockitoAnnotations.openMocks(this);
        when(mockCore.getIntegrationRegistry()).thenReturn(mockRegistry);
        when(mockRegistry.getIntegration("integration-modbus")).thenReturn(mockModbus);
        when(mockModbus.register(any(), any(RemovalHost.class))).thenReturn(mockSource);
    }

    @After
    public void tearDown() {
        ResourceLoader.setLoadI18nResources(true);
        SmsDeviceBase.modbusIntegration = null;
    }

    @Test
    public void defaultModbusSlaveId_fourGases() {
        assertEquals(1, SaimosenIntegration.defaultModbusSlaveId(
            DeviceClasses.AIR_MONITOR_CO.getClassName()));
        assertEquals(2, SaimosenIntegration.defaultModbusSlaveId(
            DeviceClasses.AIR_MONITOR_NO2.getClassName()));
        assertEquals(3, SaimosenIntegration.defaultModbusSlaveId(
            DeviceClasses.AIR_MONITOR_O3.getClassName()));
        assertEquals(4, SaimosenIntegration.defaultModbusSlaveId(
            DeviceClasses.AIR_MONITOR_SO2.getClassName()));
        assertEquals(1, SaimosenIntegration.defaultModbusSlaveId(
            DeviceClasses.AIR_MONITOR_PM.getClassName()));
        assertEquals(1, SaimosenIntegration.defaultModbusSlaveId(null));
    }

    @Test
    public void so2_omittedSlaveId_defaultsTo4() throws Exception {
        assertEquals(4, loadSlaveId(new SO2Device(entryWithoutSlave("air.monitor.so2", "SO2"))));
    }

    @Test
    public void co_omittedSlaveId_defaultsTo1() throws Exception {
        assertEquals(1, loadSlaveId(new CODevice(entryWithoutSlave("air.monitor.co", "CO"))));
    }

    @Test
    public void o3_omittedSlaveId_defaultsTo3() throws Exception {
        assertEquals(3, loadSlaveId(new O3Device(entryWithoutSlave("air.monitor.o3", "O3"))));
    }

    @Test
    public void no2_omittedSlaveId_defaultsTo2() throws Exception {
        assertEquals(2, loadSlaveId(new NO2Device(entryWithoutSlave("air.monitor.no2", "NO2"))));
    }

    @Test
    public void explicitSlaveId_isPreserved() throws Exception {
        Map<String, Object> data = commWithoutSlave();
        data.put("class", "air.monitor.so2");
        data.put("name", "SO2");
        data.put("modbus_protocol", "RTU");
        @SuppressWarnings("unchecked")
        Map<String, Object> comm = (Map<String, Object>) data.get("comm_settings");
        comm.put("slave_id", 7);
        SO2Device device = new SO2Device(entry("so2-explicit", data));
        assertEquals(7, loadSlaveId(device));
    }

    private int loadSlaveId(SmsDeviceBase device) throws Exception {
        device.load(mockCore);
        Field f = SmsDeviceBase.class.getDeclaredField("modbusInfo");
        f.setAccessible(true);
        ModbusInfo info = (ModbusInfo) f.get(device);
        return info.getSlaveId();
    }

    private static ConfigEntry entryWithoutSlave(String deviceClass, String name) {
        Map<String, Object> data = commWithoutSlave();
        data.put("class", deviceClass);
        data.put("name", name);
        data.put("modbus_protocol", "RTU");
        return entry(deviceClass + "-noslave", data);
    }

    private static Map<String, Object> commWithoutSlave() {
        Map<String, Object> serial = new HashMap<>();
        serial.put("serial_port", "COM1");
        serial.put("baudrate", "9600");
        serial.put("data_bits", "8");
        serial.put("stop_bits", "1");
        serial.put("parity", "None");
        serial.put("timeout", 2000);

        Map<String, Object> comm = new HashMap<>();
        comm.put("serial_settings", serial);

        Map<String, Object> data = new HashMap<>();
        data.put("comm_settings", comm);
        return data;
    }

    private static ConfigEntry entry(String id, Map<String, Object> data) {
        return new ConfigEntry.Builder()
            .entryId("test-entry-" + id)
            .coordinate("com.ecat:integration-saimosen")
            .uniqueId("saimosen_" + id)
            .title(String.valueOf(data.get("name")))
            .data(data)
            .build();
    }
}
