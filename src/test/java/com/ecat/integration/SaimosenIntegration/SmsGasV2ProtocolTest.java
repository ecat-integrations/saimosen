package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.EcatCore;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.State.AQAttribute;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.StateManager;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SMS8000 V2 四气态串口帧解析。协议样本与先河 XH*2000BV2 相同。
 */
public class SmsGasV2ProtocolTest {

    private AutoCloseable mocks;

    @Mock
    private SerialSource serialSource;
    @Mock
    private SerialIntegration serialIntegration;
    @Mock
    private EcatCore core;
    @Mock
    private ByteResponseHandlingContext<byte[]> context;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Before
    public void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        ResourceLoader.setLoadI18nResources(false);
        Field integration = SerialDeviceBase.class.getDeclaredField("serialIntegration");
        integration.setAccessible(true);
        integration.set(null, serialIntegration);
        when(serialIntegration.register(any(), any(RemovalHost.class))).thenReturn(serialSource);
        when(serialSource.getTimeout()).thenReturn(500);
        when(serialSource.getPortName()).thenReturn("COM1");
        BusRegistry bus = mock(BusRegistry.class);
        doNothing().when(bus).publish(any(BusEvent.class));
        when(core.getBusRegistry()).thenReturn(bus);
        when(core.getStateManager()).thenReturn(mock(StateManager.class));
        when(context.getReceiveBuffer()).thenReturn(buffer);
    }

    @After
    public void tearDown() throws Exception {
        ResourceLoader.setLoadI18nResources(true);
        Field integration = SerialDeviceBase.class.getDeclaredField("serialIntegration");
        integration.setAccessible(true);
        integration.set(null, null);
        mocks.close();
    }

    @Test
    public void spanConcentrationIsThreeDigits() {
        assertEquals("005", SmsV2GasCommandAttribute.formatSpanConcentration(5));
        assertEquals("050", SmsV2GasCommandAttribute.formatSpanConcentration(50));
        assertEquals("400", SmsV2GasCommandAttribute.formatSpanConcentration(400));
        assertEquals("000", SmsV2GasCommandAttribute.formatSpanConcentration(-1));
    }

    /**
     * 命令应答契约：fa 是设备基于自身当前状态的否决应答（幂等语义，目标态已满足），
     * 收到协议响应（含 fa）即视为命令送达并被设备裁决成功；传输失败（无响应/超时）
     * 仍走 handleException 返回 false，失败面不被掩盖。
     */
    @Test
    public void commandResponseFaIsIdempotentSuccessAndTransportFailureIsFalse() throws Exception {
        SmsV2GasCommandAttribute commandAttr = new SmsV2GasCommandAttribute(
                "CALIBRATION_CMD", AttributeClass.DISPATCH_COMMAND, serialSource);
        commandAttr.registerCommand("ZERO_START", new SmsV2GasCommandAttribute.CommandConfig(
                "szeros$", "szerosok$", "szerosfa$", SmsV2GasCommandAttribute.CommandType.ZERO_START));
        Method method = SmsV2GasCommandAttribute.class.getDeclaredMethod(
                "processResponse", ByteResponseHandlingContext.class);
        method.setAccessible(true);

        // fa 否决应答 → 仍成功
        buffer.reset();
        buffer.write("\r\n#szerosfa$".getBytes());
        when(context.getNewValue()).thenReturn("ZERO_START".getBytes());
        assertTrue(Boolean.TRUE.equals(method.invoke(commandAttr, context)));

        // ok 正常应答 → 成功
        buffer.reset();
        buffer.write("szerosok$".getBytes());
        assertTrue(Boolean.TRUE.equals(method.invoke(commandAttr, context)));

        // 传输层失败（无响应/超时经 handleException）→ false
        assertFalse(commandAttr.handleException(new RuntimeException("timeout")));
    }

    @Test
    public void so2ParsesConcentrationAndNineStatusFields() throws Exception {
        SMS8200V2Device device = ready(new SMS8200V2Device(entry("sms8200v2")));
        feed(device, "\r\n*SO2=25.5ppb$", "so2chr$");
        AQAttribute so2 = (AQAttribute) device.getAttrs().get("SO2");
        assertEquals(25.5, ((Number) so2.getState().getValue()).doubleValue(), 0.01);
        assertEquals(AttributeStatus.CALIBRATION, so2.getState().getStatus());

        feed(device, "\r\n1000,2000,2500,80,500,45,30,800,600$", "so2twc$");
        assertEquals(500, number(device, "FLOW"), 0.01);
        assertEquals(30, number(device, "BOXTEMP"), 0.01);
        assertEquals(600, number(device, "XIANDENG_HV"), 0.01);
    }

    @Test
    public void coParsesConcentrationAndStatus() throws Exception {
        SMS8500V2Device device = ready(new SMS8500V2Device(entry("sms8500v2")));
        feed(device, "\r\n#CO=1.25ppm$", "coochr$");
        AQAttribute co = (AQAttribute) device.getAttrs().get("CO");
        assertEquals(1.25, ((Number) co.getState().getValue()).doubleValue(), 0.01);
        assertEquals(AttributeStatus.MAINTENANCE, co.getState().getStatus());

        feed(device, "\r\n1000,2000,80,500,45,30,0,0,0,0$", "cootwc$");
        assertEquals(30, number(device, "XG_TEMP"), 0.01);
        assertEquals(80, number(device, "PRESS"), 0.01);
    }

    @Test
    public void o3ParsesConcentrationAndEightStatusFields() throws Exception {
        SMS8400V2Device device = ready(new SMS8400V2Device(entry("sms8400v2")));
        feed(device, "\r\nO3=15.5ppb$", "oo3chr$");
        AQAttribute o3 = (AQAttribute) device.getAttrs().get("O3");
        assertEquals(15.5, ((Number) o3.getState().getValue()).doubleValue(), 0.01);
        assertEquals(AttributeStatus.NORMAL, o3.getState().getStatus());

        feed(device, "\r\n1,2,3,4,5,6,7,8$", "oo3twc$");
        assertEquals(7, number(device, "BOXTEMP"), 0.01);
        assertEquals(8, number(device, "UVTEMP"), 0.01);
        assertEquals(null, device.getAttrs().get("SLOPE").getState());
    }

    @Test
    public void o3ParsesTenFieldStatusFromDevice() throws Exception {
        SMS8400V2Device device = ready(new SMS8400V2Device(entry("sms8400v2")));
        feed(device, "2101.7,2102.2,2102.2,94.0,858.8,31.6,26.0,50.5,1.0,-0.7$", "oo3twc$");
        assertEquals(2101.7, number(device, "PMT_V"), 0.01);
        assertEquals(2102.2, number(device, "CANBI_V"), 0.01);
        assertEquals(2102.2, number(device, "POWER"), 0.01);
        assertEquals(94.0, number(device, "PRESS"), 0.01);
        assertEquals(858.8, number(device, "FLOW"), 0.01);
        assertEquals(31.6, number(device, "TEMP"), 0.01);
        assertEquals(26.0, number(device, "BOXTEMP"), 0.01);
        assertEquals(50.5, number(device, "UVTEMP"), 0.01);
        assertEquals(1.0, number(device, "SLOPE"), 0.01);
        assertEquals(-0.7, number(device, "INTERCEPT"), 0.01);
    }

    @Test
    public void noxParsesThreeGasesAndTwelveStatusFields() throws Exception {
        SMS8300V2Device device = ready(new SMS8300V2Device(entry("sms8300v2")));
        feed(device, "\r\nNO=10.5\nNO2=20.5\nNOX=31.0PPB$$", "noxchr$");
        assertEquals(10.5, number(device, "NO"), 0.01);
        assertEquals(20.5, number(device, "NO2"), 0.01);
        assertEquals(31.0, number(device, "NOX"), 0.01);

        feed(device, "\r\n1,2,3,4,5,6,7,8,9,10,11,12$", "noxtwc$");
        assertEquals(11, number(device, "SAMPLE_FLOW"), 0.01);
        assertEquals(12, number(device, "PMT_HVDIS"), 0.01);
    }

    private <T extends SmsV2GasDeviceBase> T ready(T device) throws Exception {
        setField(device, "core", core);
        device.init();
        setField(device, "core", core);
        device.markReady();
        return device;
    }

    private void feed(Object device, String frame, String command) throws Exception {
        buffer.reset();
        buffer.write(frame.getBytes());
        when(context.getNewValue()).thenReturn(command.getBytes());
        Method method = device.getClass().getDeclaredMethod("processResponse", ByteResponseHandlingContext.class);
        method.setAccessible(true);
        Object result = method.invoke(device, context);
        assertTrue(Boolean.TRUE.equals(result));
    }

    private static double number(SmsV2GasDeviceBase device, String attrId) {
        return ((Number) device.getAttrs().get(attrId).getState().getValue()).doubleValue();
    }

    private static ConfigEntry entry(String id) {
        return new ConfigEntry.Builder().entryId(id).uniqueId(id).title(id).build();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
