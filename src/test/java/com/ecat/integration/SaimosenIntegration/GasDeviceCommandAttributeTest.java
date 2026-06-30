package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GasDeviceCommandAttribute}.
 */
public class GasDeviceCommandAttributeTest {

    /** Exposes {@link GasDeviceCommandAttribute#sendCommandImpl(String)} for tests. */
    private static final class TestableAttribute extends GasDeviceCommandAttribute {
        TestableAttribute(GasCommandConfigFactory factory) {
            super("dispatch_command", AttributeClass.DISPATCH_COMMAND, factory);
        }

        CompletableFuture<Boolean> sendCommandForTest(String type) {
            return sendCommandImpl(type);
        }
    }

    private ModbusSource modbusSource;

    @Before
    public void setUp() {
        modbusSource = mock(ModbusSource.class);
    }

    /**
     * 给独立创建的 NumericAttribute 绑定一个 mock 设备（getId() 非 null），
     * 使 updateValue 能构建不可变 lastState（B5/B9 重构后 getState() 依赖 deviceId）。
     */
    private static void bindToMockDevice(NumericAttribute attr) {
        DeviceBase mockDevice = mock(DeviceBase.class);
        when(mockDevice.getId()).thenReturn("test-device-" + attr.getAttributeID());
        attr.setDevice(mockDevice);
    }

    @Test
    public void coFactory_hasExpectedKeysAndGasType() {
        GasDeviceCommandAttribute.COCommandConfigFactory factory = new GasDeviceCommandAttribute.COCommandConfigFactory();
        assertEquals("CO", factory.getGasType());
        Map<String, GasDeviceCommandAttribute.CommandConfig> map = factory.createCommandConfigs();
        assertEquals(6, map.size());
        assertSpanStartDefaults(map, 40, GasDeviceCommandAttribute.CommandType.SPAN_CALIBRATION_START);
    }

    @Test
    public void no2Factory_spanStartUses400() {
        GasDeviceCommandAttribute.NO2CommandConfigFactory factory = new GasDeviceCommandAttribute.NO2CommandConfigFactory();
        assertEquals("NO2", factory.getGasType());
        Map<String, GasDeviceCommandAttribute.CommandConfig> map = factory.createCommandConfigs();
        assertSpanStartDefaults(map, 400, GasDeviceCommandAttribute.CommandType.SPAN_CALIBRATION_START);
    }

    @Test
    public void so2AndO3Factories_matchNo2SpanDefaults() {
        Map<String, GasDeviceCommandAttribute.CommandConfig> so2 =
                new GasDeviceCommandAttribute.SO2CommandConfigFactory().createCommandConfigs();
        Map<String, GasDeviceCommandAttribute.CommandConfig> o3 =
                new GasDeviceCommandAttribute.O3CommandConfigFactory().createCommandConfigs();
        assertEquals("SO2", new GasDeviceCommandAttribute.SO2CommandConfigFactory().getGasType());
        assertEquals("O3", new GasDeviceCommandAttribute.O3CommandConfigFactory().getGasType());
        assertEquals(400, so2.get("span_calibration_start").writeValue);
        assertEquals(400, o3.get("span_calibration_start").writeValue);
    }

    private static void assertSpanStartDefaults(
            Map<String, GasDeviceCommandAttribute.CommandConfig> map,
            int expectedWriteValue,
            GasDeviceCommandAttribute.CommandType expectedType) {
        GasDeviceCommandAttribute.CommandConfig spanStart = map.get("span_calibration_start");
        assertNotNull(spanStart);
        assertEquals(0x3EB, spanStart.modbusAddress);
        assertEquals(expectedWriteValue, spanStart.writeValue);
        assertEquals(expectedType, spanStart.type);
        assertTrue(spanStart.needsConcentration);
        GasDeviceCommandAttribute.CommandConfig zeroStart = map.get("zero_calibration_start");
        assertNotNull(zeroStart);
        assertEquals(0x3E8, zeroStart.modbusAddress);
        assertFalse(zeroStart.needsConcentration);
    }

    @Test
    public void attribute_defaultConstructorUsesCoFactory() {
        GasDeviceCommandAttribute attr = new GasDeviceCommandAttribute("dispatch_command", AttributeClass.DISPATCH_COMMAND);
        assertEquals("CO", attr.getGasType());
        assertTrue(attr.getFactory() instanceof GasDeviceCommandAttribute.COCommandConfigFactory);
    }

    @Test
    public void attribute_getGasTypeAndFactory_reflectConstructor() {
        GasDeviceCommandAttribute attr = new GasDeviceCommandAttribute(
                "dispatch_command", AttributeClass.DISPATCH_COMMAND, new GasDeviceCommandAttribute.O3CommandConfigFactory());
        assertEquals("O3", attr.getGasType());
        assertTrue(attr.getFactory() instanceof GasDeviceCommandAttribute.O3CommandConfigFactory);
    }

    @Test
    public void addDependencyAttribute_ignoresNull() {
        GasDeviceCommandAttribute attr = new GasDeviceCommandAttribute("dispatch_command", AttributeClass.DISPATCH_COMMAND);
        attr.addDependencyAttribute(null);
        // no exception; dependency list unchanged in a way we can observe via successful concentration path default 0
    }

    @Test
    public void sendCommandImpl_returnsFalseWhenModbusSourceMissing() throws Exception {
        TestableAttribute attr = new TestableAttribute(new GasDeviceCommandAttribute.COCommandConfigFactory());
        assertFalse(attr.sendCommandForTest("zero_calibration_start").get());
    }

    @Test
    public void sendCommandImpl_returnsFalseForUnknownCommand() throws Exception {
        TestableAttribute attr = new TestableAttribute(new GasDeviceCommandAttribute.COCommandConfigFactory());
        attr.setModbusSource(modbusSource);
        assertFalse(attr.sendCommandForTest("unknown_command").get());
    }

    @Test
    public void sendCommandImpl_returnsFalseWhenWriteResponseIndicatesException() throws Exception {
        WriteRegisterResponse badResponse = mock(WriteRegisterResponse.class);
        when(badResponse.isException()).thenReturn(true);
        when(badResponse.getExceptionMessage()).thenReturn("timeout");
        when(modbusSource.writeRegister(eq(0x3E8), eq(0))).thenReturn(CompletableFuture.completedFuture(badResponse));

        TestableAttribute attr = new TestableAttribute(new GasDeviceCommandAttribute.COCommandConfigFactory());
        attr.setModbusSource(modbusSource);

        try (MockedStatic<ModbusTransactionStrategy> trx = mockStatic(ModbusTransactionStrategy.class)) {
            trx.when(() -> ModbusTransactionStrategy.executeWithLambda(any(ModbusSource.class), any()))
                    .thenAnswer(invocation -> {
                        ModbusSource src = invocation.getArgument(0);
                        Function<ModbusSource, CompletableFuture<Boolean>> fn = invocation.getArgument(1);
                        return fn.apply(src);
                    });
            assertFalse(attr.sendCommandForTest("zero_calibration_start").get());
        }
    }

    @Test
    public void sendCommandImpl_writesRegisterAndReturnsTrue() throws Exception {
        WriteRegisterResponse okResponse = mock(WriteRegisterResponse.class);
        when(okResponse.isException()).thenReturn(false);
        when(modbusSource.writeRegister(eq(0x3E8), eq(0))).thenReturn(CompletableFuture.completedFuture(okResponse));

        TestableAttribute attr = new TestableAttribute(new GasDeviceCommandAttribute.COCommandConfigFactory());
        attr.setModbusSource(modbusSource);

        try (MockedStatic<ModbusTransactionStrategy> trx = mockStatic(ModbusTransactionStrategy.class)) {
            trx.when(() -> ModbusTransactionStrategy.executeWithLambda(any(ModbusSource.class), any()))
                    .thenAnswer(invocation -> {
                        ModbusSource src = invocation.getArgument(0);
                        Function<ModbusSource, CompletableFuture<Boolean>> fn = invocation.getArgument(1);
                        return fn.apply(src);
                    });
            assertTrue(attr.sendCommandForTest("zero_calibration_start").get());
        }

        verify(modbusSource).writeRegister(0x3E8, 0);
    }

    @Test
    public void sendCommandImpl_spanStart_usesCalibrationConcentrationFromDependency() throws Exception {
        WriteRegisterResponse okResponse = mock(WriteRegisterResponse.class);
        when(okResponse.isException()).thenReturn(false);
        when(modbusSource.writeRegister(eq(0x3EB), eq(123))).thenReturn(CompletableFuture.completedFuture(okResponse));

        NumericAttribute concentration = new NumericAttribute(
                "calibration_concentration", AttributeClass.CO, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false);
        bindToMockDevice(concentration);
        concentration.updateValue(123.0);

        TestableAttribute attr = new TestableAttribute(new GasDeviceCommandAttribute.COCommandConfigFactory());
        attr.setModbusSource(modbusSource);
        attr.addDependencyAttribute(concentration);

        try (MockedStatic<ModbusTransactionStrategy> trx = mockStatic(ModbusTransactionStrategy.class)) {
            trx.when(() -> ModbusTransactionStrategy.executeWithLambda(any(ModbusSource.class), any()))
                    .thenAnswer(invocation -> {
                        ModbusSource src = invocation.getArgument(0);
                        Function<ModbusSource, CompletableFuture<Boolean>> fn = invocation.getArgument(1);
                        return fn.apply(src);
                    });
            assertTrue(attr.sendCommandForTest("span_calibration_start").get());
        }

        verify(modbusSource).writeRegister(0x3EB, 123);
    }

    @Test
    public void sendCommandImpl_notifiesDeviceOnSpanConcentrationWrite() throws Exception {
        WriteRegisterResponse okResponse = mock(WriteRegisterResponse.class);
        when(okResponse.isException()).thenReturn(false);
        when(modbusSource.writeRegister(eq(0x3EB), eq(55))).thenReturn(CompletableFuture.completedFuture(okResponse));

        NumericAttribute concentration = new NumericAttribute(
                "calibration_concentration", AttributeClass.CO, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false);
        bindToMockDevice(concentration);
        concentration.updateValue(55.0);

        CalibNotifyDevice device = new CalibNotifyDevice();
        TestableAttribute attr = new TestableAttribute(new GasDeviceCommandAttribute.COCommandConfigFactory());
        attr.setModbusSource(modbusSource);
        attr.addDependencyAttribute(concentration);
        attr.setDeviceInstance(device);

        try (MockedStatic<ModbusTransactionStrategy> trx = mockStatic(ModbusTransactionStrategy.class)) {
            trx.when(() -> ModbusTransactionStrategy.executeWithLambda(any(ModbusSource.class), any()))
                    .thenAnswer(invocation -> {
                        ModbusSource src = invocation.getArgument(0);
                        Function<ModbusSource, CompletableFuture<Boolean>> fn = invocation.getArgument(1);
                        return fn.apply(src);
                    });
            assertTrue(attr.sendCommandForTest("span_calibration_start").get());
        }

        assertEquals(55.0, device.lastMarked, 0.001);
    }

    public static final class CalibNotifyDevice {
        double lastMarked = Double.NaN;

        @SuppressWarnings("unused")
        public void markCalibrationWrite(double value) {
            this.lastMarked = value;
        }
    }
}
