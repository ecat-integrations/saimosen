// Copyright (c) ecat
package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusShortAttribute;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 测试 QCDevice 类的功能
 * 包含设备初始化、属性创建、定时任务调度等功能的单元测试
 *
 * @version V0.0.1
 * @author coffee
 */
public class QCDeviceTest {

    private QCDevice device;
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

        Map<String, Object> config = new HashMap<>();
        Map<String, Object> deviceSettings = new HashMap<>();
        deviceSettings.put("sampling_tube_length", 4.5);
        config.put("device_settings", deviceSettings);
        device = new QCDevice(config);

        setPrivateField(device, "core", mockEcatCore);
        setPrivateField(device, "modbusSource", mockModbusSource);
        setPrivateField(device, "modbusIntegration", mockModbusIntegration);

        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        when(mockTaskManager.getExecutorService()).thenReturn(mockExecutor);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        device.init();
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

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

    @Test
    public void testInit_CreatesCorrectAttributes() {
        // 验证系统状态属性
        assertNotNull(device.getAttrs().get("system_state"));

        // 验证环境参数属性
        assertNotNull(device.getAttrs().get("bench_temp"));
        assertNotNull(device.getAttrs().get("bench_humidity"));
        assertNotNull(device.getAttrs().get("sample_tube_temp"));
        assertNotNull(device.getAttrs().get("sample_tube_humidity"));
        assertNotNull(device.getAttrs().get("sample_tube_flow"));
        assertNotNull(device.getAttrs().get("sample_tube_pressure"));
        assertNotNull(device.getAttrs().get("sample_tube_leak"));
        assertNotNull(device.getAttrs().get("vibration"));

        // 验证电力参数属性
        assertNotNull(device.getAttrs().get("station_ua"));
        assertNotNull(device.getAttrs().get("station_ub"));
        assertNotNull(device.getAttrs().get("station_uc"));
        assertNotNull(device.getAttrs().get("station_ia"));
        assertNotNull(device.getAttrs().get("station_ib"));
        assertNotNull(device.getAttrs().get("station_ic"));
        assertNotNull(device.getAttrs().get("station_pa"));
        assertNotNull(device.getAttrs().get("station_pb"));
        assertNotNull(device.getAttrs().get("station_pc"));
        assertNotNull(device.getAttrs().get("station_qa"));
        assertNotNull(device.getAttrs().get("station_qb"));
        assertNotNull(device.getAttrs().get("station_qc"));
        assertNotNull(device.getAttrs().get("station_pf_a"));
        assertNotNull(device.getAttrs().get("station_pf_b"));
        assertNotNull(device.getAttrs().get("station_pf_c"));
        assertNotNull(device.getAttrs().get("voltage_freq"));

        // 验证空调控制属性
        assertNotNull(device.getAttrs().get("ac1_power"));
        assertNotNull(device.getAttrs().get("ac1_direction"));
        assertNotNull(device.getAttrs().get("ac1_set_temp"));
        assertNotNull(device.getAttrs().get("ac1_mode"));
        assertNotNull(device.getAttrs().get("ac1_speed"));
        assertNotNull(device.getAttrs().get("ac1_cur_temp"));
        assertNotNull(device.getAttrs().get("ac2_power"));
        assertNotNull(device.getAttrs().get("ac2_direction"));
        assertNotNull(device.getAttrs().get("ac2_set_temp"));
        assertNotNull(device.getAttrs().get("ac2_mode"));
        assertNotNull(device.getAttrs().get("ac2_speed"));
        assertNotNull(device.getAttrs().get("ac2_cur_temp"));

        // 验证钢瓶气参数属性
        assertNotNull(device.getAttrs().get("gas_cylinder1_pressure"));
        assertNotNull(device.getAttrs().get("gas_cylinder2_pressure"));
        assertNotNull(device.getAttrs().get("gas_cylinder3_pressure"));
        assertNotNull(device.getAttrs().get("gas_cylinder_alarm_limit"));
        assertNotNull(device.getAttrs().get("zero_gas_pressure"));
        assertNotNull(device.getAttrs().get("zero_gas_alarm_limit"));
        assertNotNull(device.getAttrs().get("co_purifier_temp"));
        assertNotNull(device.getAttrs().get("co_cylinder_leak"));

        // 验证控制继电器属性
        assertNotNull(device.getAttrs().get("fan_control"));
        assertNotNull(device.getAttrs().get("zero_gas_relay"));
        assertNotNull(device.getAttrs().get("calibrator_relay"));
        assertNotNull(device.getAttrs().get("calibration_valve_so2"));
        assertNotNull(device.getAttrs().get("calibration_valve_nox"));
        assertNotNull(device.getAttrs().get("calibration_valve_o3"));
        assertNotNull(device.getAttrs().get("calibration_valve_co"));
        assertNotNull(device.getAttrs().get("light_control"));

        // 验证传感器状态属性
        assertNotNull(device.getAttrs().get("infrared_status"));
        assertNotNull(device.getAttrs().get("smoke_detector1"));
        assertNotNull(device.getAttrs().get("smoke_detector2"));
        assertNotNull(device.getAttrs().get("temp_detector1"));
        assertNotNull(device.getAttrs().get("temp_detector2"));
        assertNotNull(device.getAttrs().get("water_leak_detector"));
        assertNotNull(device.getAttrs().get("gas_cylinder_alarm_status"));
        assertNotNull(device.getAttrs().get("zero_gas_alarm_status"));

        // 验证UPS参数属性
        assertNotNull(device.getAttrs().get("ups_input_voltage"));
        assertNotNull(device.getAttrs().get("ups_output_voltage"));
        assertNotNull(device.getAttrs().get("ups_load_percent"));
        assertNotNull(device.getAttrs().get("ups_input_freq"));
        assertNotNull(device.getAttrs().get("ups_battery_voltage"));
        assertNotNull(device.getAttrs().get("ups_battery_temp"));
        assertNotNull(device.getAttrs().get("ups_status"));

        // 验证PM浓度属性
        assertNotNull(device.getAttrs().get("pm2_5_concentration"));
        assertNotNull(device.getAttrs().get("pm10_concentration"));

        // 验证QC特定浓度属性
        assertNotNull(device.getAttrs().get("o3_concentration_qc"));
        assertNotNull(device.getAttrs().get("co_concentration_qc"));
        assertNotNull(device.getAttrs().get("no2_concentration_qc"));
        assertNotNull(device.getAttrs().get("so2_concentration_qc"));

        // 验证采样管属性
        assertNotNull(device.getAttrs().get("sample_tube_addr"));
        assertNotNull(device.getAttrs().get("sample_tube_sampling_status"));
        assertNotNull(device.getAttrs().get("sampling_tube_residence_time"));

        // 验证加热和功率属性
        assertNotNull(device.getAttrs().get("heating_temp"));
        assertNotNull(device.getAttrs().get("fan_power"));
        assertNotNull(device.getAttrs().get("heating_belt_power"));

        // 验证膜片状态属性
        assertNotNull(device.getAttrs().get("so2_film_changer_status"));
        assertNotNull(device.getAttrs().get("nox_film_changer_status"));
        assertNotNull(device.getAttrs().get("co_film_changer_status"));
        assertNotNull(device.getAttrs().get("o3_film_changer_status"));

        // 验证气体温度属性
        assertNotNull(device.getAttrs().get("so2_gas_temp"));
        assertNotNull(device.getAttrs().get("nox_gas_temp"));
        assertNotNull(device.getAttrs().get("co_gas_temp"));
        assertNotNull(device.getAttrs().get("o3_gas_temp"));

        // 验证流量参数
        assertNotNull(device.getAttrs().get("pm10_std_flow"));
        assertNotNull(device.getAttrs().get("pm10_working_flow"));
        assertNotNull(device.getAttrs().get("pm2_5_std_flow"));
        assertNotNull(device.getAttrs().get("pm2_5_working_flow"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testStart_SchedulesReadTask() throws Exception {
        when((ScheduledFuture<Object>) mockExecutor.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenReturn((ScheduledFuture<Object>) mockScheduledFuture);

        device.start();

        verify(mockExecutor, times(1)).scheduleWithFixedDelay(
                any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));

        ScheduledFuture<?> actualFuture = (ScheduledFuture<?>) getPrivateField(device, "readFuture");
        assertEquals(mockScheduledFuture, actualFuture);
    }

    @Test
    public void testReadRegisters_ReadsAndParsesData() throws Exception {
        // 第一块真实Modbus响应数据
        String hexData1 = "01 03 DC 00 00 41 CC CC CD 42 3F 33 33 41 FF 33 33 42 28 00 00 3F A6 66 66 00 00 00 00 00 00 43 65 19 9A 43 64 80 00 43 66 CC CD 40 98 51 EC 40 93 D7 0A 3E C7 AE 14 44 19 00 00 44 47 80 00 42 8E 00 00 C4 31 C0 00 C1 C8 00 00 C2 04 00 00 3E E8 F5 C3 3F 7F BE 77 3F 65 A1 CB 42 48 0A 3D 00 01 00 00 00 18 00 01 00 01 00 17 00 01 00 00 00 00 00 19 00 00 00 01 00 17 00 00 46 1B 9E 13 46 69 35 D0 44 E1 43 78 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 01 00 01 00 01 00 01 00 01 00 01 00 00 00 00 43 67 19 9A 43 5C 33 33 00 0A 42 48 00 00 00 00 00 00 00 00 00 00 00 00 00 07 00 11 3C A3 D7 0A 00 05 00 00 00 00 3D CC CC CD 81 7C";
        // 第二块真实Modbus响应数据
        String hexData2 = "01 03 F6 " +
        "00 01 00 00 01 64 00 0F 00 00 00 01 01 22 06 A4 00 00 00 00"+
        "00 00 00 00 00 01 00 00 00 00 00 01 12 34 00 00 00 00 00 00"+
        "00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"+
        "00 00 00 1B 00 02 00 00 41 BC 0A D4 41 B7 7E 3A 41 BD 3E 0A"+
        "41 B7 F8 9E 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 04 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 "+
        "41 7F 2B 18 41 83 E9 2A " +
        "41 7F 2B 18 41 83 E9 2A A5 B7";

        byte[] bytes1 = hexStringToByteArray(hexData1.replaceAll(" ", ""));
        byte[] bytes2 = hexStringToByteArray(hexData2.replaceAll(" ", ""));

        short[] registers1 = parseModbusResponse(bytes1);
        short[] registers2 = parseModbusResponse(bytes2);

        ReadHoldingRegistersResponse mockResponse1 = mock(ReadHoldingRegistersResponse.class);
        when(mockResponse1.getShortData()).thenReturn(registers1);
        ReadHoldingRegistersResponse mockResponse2 = mock(ReadHoldingRegistersResponse.class);
        when(mockResponse2.getShortData()).thenReturn(registers2);

        when(mockModbusSource.readHoldingRegisters(eq(0), eq(110)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse1));
        when(mockModbusSource.readHoldingRegisters(eq(110), eq(123)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse2));

        invokePrivateMethod(device, "readRegisters");

        // 断言部分属性已被正确解析
        ModbusFloatAttribute benchTemp = (ModbusFloatAttribute) device.getAttrs().get("bench_temp");
        assertNotNull(benchTemp);
        assertEquals(25.6f, benchTemp.getValue(), 0.1f);

        ModbusShortAttribute systemState = (ModbusShortAttribute) device.getAttrs().get("system_state");
        assertNotNull(systemState);
        assertEquals(0, (int) systemState.getValue());

        ModbusFloatAttribute stationUa = (ModbusFloatAttribute) device.getAttrs().get("station_ua");
        assertNotNull(stationUa);
        assertEquals(229.1f, stationUa.getValue(), 0.1f);

        ModbusShortAttribute so2_film = (ModbusShortAttribute) device.getAttrs().get("so2_film_changer_status");
        assertNotNull(so2_film);
        assertEquals(290, (int) so2_film.getValue());

        ModbusShortAttribute o3_film = (ModbusShortAttribute) device.getAttrs().get("o3_film_changer_status");
        assertNotNull(o3_film);
        assertEquals(290, (int) o3_film.getValue());

        ModbusFloatAttribute so2temp = (ModbusFloatAttribute) device.getAttrs().get("so2_gas_temp");
        assertNotNull(so2temp);
        assertEquals(23.5f, so2temp.getValue(), 0.1f);

        ModbusFloatAttribute o3temp = (ModbusFloatAttribute) device.getAttrs().get("o3_gas_temp");
        assertNotNull(o3temp);
        assertEquals(22.9f, o3temp.getValue(), 0.1f);

        NumericAttribute tubeResidenceTime = (NumericAttribute) device.getAttrs().get("sampling_tube_residence_time");
        assertNotNull(tubeResidenceTime);
        assertEquals(3.461f, tubeResidenceTime.getValue(), 0.1f);

        ModbusScalableFloatSRAttribute heating_temp = (ModbusScalableFloatSRAttribute) device.getAttrs().get("heating_temp");
        assertNotNull(heating_temp);
        assertEquals(35.6f, heating_temp.getValue(), 0.1f);

        ModbusScalableFloatSRAttribute fan_power = (ModbusScalableFloatSRAttribute) device.getAttrs().get("fan_power");
        assertNotNull(fan_power);
        assertEquals(1.5f, fan_power.getValue(), 0.1f);

        ModbusFloatAttribute pm10StdFlow = (ModbusFloatAttribute) device.getAttrs().get("pm10_std_flow");
        assertNotNull(pm10StdFlow);
        assertEquals(16.28f, pm10StdFlow.getValue(), 0.1f);

        ModbusFloatAttribute pm25WorkFlow = (ModbusFloatAttribute) device.getAttrs().get("pm2_5_working_flow");
        assertNotNull(pm25WorkFlow);
        assertEquals(16.43f, pm25WorkFlow.getValue(), 0.1f);
        assertEquals("16.44", pm25WorkFlow.getDisplayValue());

    }

    // 工具方法：解析Modbus响应数据
    public static short[] parseModbusResponse(byte[] response) {
        if (response.length < 7) throw new IllegalArgumentException("Modbus响应长度不足");
        int byteCount = response[2] & 0xFF;
        int registerCount = byteCount / 2;
        short[] registers = new short[registerCount];
        for (int i = 0; i < registerCount; i++) {
            int highByteIndex = 3 + i * 2;
            int lowByteIndex = 3 + i * 2 + 1;
            int highByte = response[highByteIndex] & 0xFF;
            int lowByte = response[lowByteIndex] & 0xFF;
            registers[i] = (short) ((highByte << 8) | lowByte);
        }
        return registers;
    }

    // 工具方法：十六进制字符串转字节数组
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
    public void testQCDeviceI18nDisplayNames() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            device.init();

            // 验证系统状态属性
            TestTools.assertAttributeDisplayName(device, "system_state", "仪器状态");

            // 验证环境参数属性
            TestTools.assertAttributeDisplayName(device, "bench_temp", "站房温度");
            TestTools.assertAttributeDisplayName(device, "bench_humidity", "站房湿度");
            TestTools.assertAttributeDisplayName(device, "sample_tube_temp", "采样管温度");
            TestTools.assertAttributeDisplayName(device, "sample_tube_humidity", "采样管湿度");
            TestTools.assertAttributeDisplayName(device, "sample_tube_flow", "采样管流速");
            TestTools.assertAttributeDisplayName(device, "sample_tube_pressure", "采样管静压");
            TestTools.assertAttributeDisplayName(device, "sample_tube_leak", "采样管漏水状态");
            TestTools.assertAttributeDisplayName(device, "vibration", "震动");

            // 验证电力参数属性
            TestTools.assertAttributeDisplayName(device, "station_ua", "站房A相电压");
            TestTools.assertAttributeDisplayName(device, "station_ub", "站房B相电压");
            TestTools.assertAttributeDisplayName(device, "station_uc", "站房C相电压");
            TestTools.assertAttributeDisplayName(device, "station_ia", "站房A相电流");
            TestTools.assertAttributeDisplayName(device, "station_ib", "站房B相电流");
            TestTools.assertAttributeDisplayName(device, "station_ic", "站房C相电流");
            TestTools.assertAttributeDisplayName(device, "station_pa", "A相有功功率");
            TestTools.assertAttributeDisplayName(device, "station_pb", "B相有功功率");
            TestTools.assertAttributeDisplayName(device, "station_pc", "C相有功功率");
            TestTools.assertAttributeDisplayName(device, "station_qa", "A相无功功率");
            TestTools.assertAttributeDisplayName(device, "station_qb", "B相无功功率");
            TestTools.assertAttributeDisplayName(device, "station_qc", "C相无功功率");
            TestTools.assertAttributeDisplayName(device, "station_pf_a", "A相功率因数");
            TestTools.assertAttributeDisplayName(device, "station_pf_b", "B相功率因数");
            TestTools.assertAttributeDisplayName(device, "station_pf_c", "C相功率因数");
            TestTools.assertAttributeDisplayName(device, "voltage_freq", "电压频率");

            // 验证空调控制属性
            TestTools.assertAttributeDisplayName(device, "ac1_power", "空调1开机状态");
            TestTools.assertAttributeDisplayName(device, "ac1_direction", "空调1风向");
            TestTools.assertAttributeDisplayName(device, "ac1_set_temp", "空调1设定温度");
            TestTools.assertAttributeDisplayName(device, "ac1_mode", "空调1运行模式");
            TestTools.assertAttributeDisplayName(device, "ac1_speed", "空调1风速");
            TestTools.assertAttributeDisplayName(device, "ac1_cur_temp", "空调1当前温度");
            TestTools.assertAttributeDisplayName(device, "ac2_power", "空调2开机状态");
            TestTools.assertAttributeDisplayName(device, "ac2_direction", "空调2风向");
            TestTools.assertAttributeDisplayName(device, "ac2_set_temp", "空调2设定温度");
            TestTools.assertAttributeDisplayName(device, "ac2_mode", "空调2运行模式");
            TestTools.assertAttributeDisplayName(device, "ac2_speed", "空调2风速");
            TestTools.assertAttributeDisplayName(device, "ac2_cur_temp", "空调2当前温度");

            // 验证钢瓶气参数属性
            TestTools.assertAttributeDisplayName(device, "gas_cylinder1_pressure", "SO2钢瓶气压力");
            TestTools.assertAttributeDisplayName(device, "gas_cylinder2_pressure", "NO钢瓶气压力");
            TestTools.assertAttributeDisplayName(device, "gas_cylinder3_pressure", "CO钢瓶气压力");
            TestTools.assertAttributeDisplayName(device, "gas_cylinder_alarm_limit", "钢瓶气报警限值");
            TestTools.assertAttributeDisplayName(device, "zero_gas_pressure", "零气压力");
            TestTools.assertAttributeDisplayName(device, "zero_gas_alarm_limit", "零气报警限值");
            TestTools.assertAttributeDisplayName(device, "co_purifier_temp", "CO涤除器温度");
            TestTools.assertAttributeDisplayName(device, "co_cylinder_leak", "CO钢瓶气泄露状态");

            // 验证控制继电器属性
            TestTools.assertAttributeDisplayName(device, "fan_control", "风机控制");
            TestTools.assertAttributeDisplayName(device, "zero_gas_relay", "零气继电器");
            TestTools.assertAttributeDisplayName(device, "calibrator_relay", "校准仪继电器");
            TestTools.assertAttributeDisplayName(device, "calibration_valve_so2", "SO2校准阀控制");
            TestTools.assertAttributeDisplayName(device, "calibration_valve_nox", "NOx校准阀控制");
            TestTools.assertAttributeDisplayName(device, "calibration_valve_o3", "O3校准阀控制");
            TestTools.assertAttributeDisplayName(device, "calibration_valve_co", "CO校准阀控制");
            TestTools.assertAttributeDisplayName(device, "light_control", "灯");

            // 验证传感器状态属性
            TestTools.assertAttributeDisplayName(device, "infrared_status", "红外状态");
            TestTools.assertAttributeDisplayName(device, "smoke_detector1", "烟感1状态");
            TestTools.assertAttributeDisplayName(device, "smoke_detector2", "烟感2状态");
            TestTools.assertAttributeDisplayName(device, "temp_detector1", "温感1状态");
            TestTools.assertAttributeDisplayName(device, "temp_detector2", "温感2状态");
            TestTools.assertAttributeDisplayName(device, "water_leak_detector", "水浸状态");
            TestTools.assertAttributeDisplayName(device, "gas_cylinder_alarm_status", "钢瓶气压力报警状态");
            TestTools.assertAttributeDisplayName(device, "zero_gas_alarm_status", "零气压力报警状态");

            // 验证UPS参数属性
            TestTools.assertAttributeDisplayName(device, "ups_input_voltage", "UPS输入电压");
            TestTools.assertAttributeDisplayName(device, "ups_output_voltage", "UPS输出电压");
            TestTools.assertAttributeDisplayName(device, "ups_load_percent", "UPS输出负载百分比");
            TestTools.assertAttributeDisplayName(device, "ups_input_freq", "UPS输入频率");
            TestTools.assertAttributeDisplayName(device, "ups_battery_voltage", "UPS电池单元电压");
            TestTools.assertAttributeDisplayName(device, "ups_battery_temp", "UPS电池温度");
            TestTools.assertAttributeDisplayName(device, "ups_status", "UPS状态");

            // 验证PM浓度属性
            TestTools.assertAttributeDisplayName(device, "pm2_5_concentration", "PM2.5浓度");
            TestTools.assertAttributeDisplayName(device, "pm10_concentration", "PM10浓度");

            // 验证QC特定浓度属性
            TestTools.assertAttributeDisplayName(device, "o3_concentration_qc", "O3浓度");
            TestTools.assertAttributeDisplayName(device, "co_concentration_qc", "CO浓度");
            TestTools.assertAttributeDisplayName(device, "no2_concentration_qc", "NO2浓度");
            TestTools.assertAttributeDisplayName(device, "so2_concentration_qc", "SO2浓度");

            // 验证采样管属性
            TestTools.assertAttributeDisplayName(device, "sample_tube_addr", "采样管地址");
            TestTools.assertAttributeDisplayName(device, "sample_tube_sampling_status", "采样管采样状态");
            TestTools.assertAttributeDisplayName(device, "sampling_tube_residence_time", "采样管滞留时间");

            // 验证加热和功率属性
            TestTools.assertAttributeDisplayName(device, "heating_temp", "加热温度");
            TestTools.assertAttributeDisplayName(device, "fan_power", "风机功率");
            TestTools.assertAttributeDisplayName(device, "heating_belt_power", "加热带功率");

            // 验证膜片状态属性
            TestTools.assertAttributeDisplayName(device, "so2_film_changer_status", "SO2换膜器状态");
            TestTools.assertAttributeDisplayName(device, "nox_film_changer_status", "NOx换膜器状态");
            TestTools.assertAttributeDisplayName(device, "co_film_changer_status", "CO换膜器状态");
            TestTools.assertAttributeDisplayName(device, "o3_film_changer_status", "O3换膜器状态");

            // 验证气体温度属性
            TestTools.assertAttributeDisplayName(device, "so2_gas_temp", "SO2支管温度");
            TestTools.assertAttributeDisplayName(device, "nox_gas_temp", "NOX支管温度");
            TestTools.assertAttributeDisplayName(device, "co_gas_temp", "CO支管温度");
            TestTools.assertAttributeDisplayName(device, "o3_gas_temp", "O3支管温度");

            // 验证流量参数
            TestTools.assertAttributeDisplayName(device, "pm10_std_flow", "PM10标况流量");
            TestTools.assertAttributeDisplayName(device, "pm10_working_flow", "PM10工况流量");
            TestTools.assertAttributeDisplayName(device, "pm2_5_std_flow", "PM2.5标况流量");
            TestTools.assertAttributeDisplayName(device, "pm2_5_working_flow", "PM2.5工况流量");

        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }

    @Test
    public void testQCDeviceI18nWithDeviceBinding() throws Exception {
        // 禁用i18n目录资源，确保只使用strings.json
        ResourceLoader.setLoadI18nResources(false);

        try {
            // 执行初始化
            device.init();

            // 验证绑定设备后的displayname仍然正确
            // TestTools.assertAttributeDisplayName(device, "co", "CO浓度");
            TestTools.assertAttributeDisplayName(device, "pm2_5_concentration", "PM2.5浓度");
            // TestTools.assertAttributeDisplayName(device, "device_power", "设备电源"); // QC设备没有此属性
            // TestTools.assertAttributeDisplayName(device, "communication_status", "通信状态"); // QC设备没有此属性
        } finally {
            // 恢复i18n功能
            ResourceLoader.setLoadI18nResources(true);
        }
    }
}
