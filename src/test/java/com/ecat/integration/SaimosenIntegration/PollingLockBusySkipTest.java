package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.EcatCore;
import com.ecat.core.I18n.ResourceLoader;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Task.TaskManager;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Sdk.PollingHandle;
import com.ecat.integration.ModbusIntegration.Sdk.RoundReport;

/**
 * 轮询锁忙降级回归锁（F-23 A 片，ModbusPolling SDK 形态）。
 *
 * <p>源锁忙（tryAcquire 返回 null）时 SDK 轮询必须：本轮立即放弃不发任何 Modbus 读
 * （区别于事务失败重试）、以 LOCK_BUSY_SKIPPED 结局报告（跳拍非设备错误，不走 FAILED）、
 * 轮询不注销（周期网格照常推进）。轮询链由域自持定时器驱动（与生产同源）；
 * 确定性同步 = onRound 六分类 latch，无 sleep。
 */
public class PollingLockBusySkipTest {

    private AutoCloseable mockitoCloseable;
    private SO2Device device;
    private ModbusSource mockModbusSource;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        ResourceLoader.setLoadI18nResources(false);

        Map<String, Object> config = new HashMap<>();
        config.put("name", "SO2锁忙测试设备");
        config.put("class", "air.monitor.so2");
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
        ConfigEntry entry = new ConfigEntry.Builder()
                .entryId("test-entry-lock-busy-skip")
                .coordinate("com.ecat:integration-saimosen")
                .uniqueId("LockBusySkipSO2")
                .data(config)
                .build();
        device = new SO2Device(entry);

        mockModbusSource = mock(ModbusSource.class);
        ModbusIntegration mockModbusIntegration = mock(ModbusIntegration.class);
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        EcatCore mockCore = mock(EcatCore.class);
        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockCore.getTaskManager()).thenReturn(mockTaskManager);

        BusRegistry mockBus = mock(BusRegistry.class);
        doNothing().when(mockBus).publish(any(BusEvent.class));
        when(mockCore.getBusRegistry()).thenReturn(mockBus);

        IntegrationRegistry reg = mock(IntegrationRegistry.class);
        when(mockCore.getIntegrationRegistry()).thenReturn(reg);
        when(reg.getIntegration("integration-modbus")).thenReturn(mockModbusIntegration);

        device.load(mockCore);
        setPrivateField(device, "core", mockCore);
        setPrivateField(device, "modbusSource", mockModbusSource);
        setPrivateField(device, "modbusIntegration", mockModbusIntegration);
        device.init();
        device.markReady();
    }

    @After
    public void tearDown() throws Exception {
        device.stop();
        ResourceLoader.setLoadI18nResources(true);
        mockitoCloseable.close();
    }

    @Test
    public void lockBusyPollRoundSkipsFastWithoutSending() throws Exception {
        when(mockModbusSource.tryAcquire()).thenReturn(null);

        // LOCK_BUSY_SKIPPED 六分类 latch：跳拍即证据（毫秒级结算、非 FAILED 错误链）
        final CountDownLatch twoSkips = new CountDownLatch(2);
        PollingHandle handle = ModbusPolling.on(device, mockModbusSource)
                .round(device::readAndUpdate)
                .every(50, TimeUnit.MILLISECONDS)
                .onRound(r -> {
                    if (r.getOutcome() == RoundReport.Outcome.LOCK_BUSY_SKIPPED) {
                        twoSkips.countDown();
                    }
                })
                .start();

        assertTrue("两个周期内必须观察到两次锁忙跳拍（周期网格照常推进）",
                twoSkips.await(5, TimeUnit.SECONDS));
        verify(mockModbusSource, never()).readHoldingRegisters(anyInt(), anyInt());
        assertTrue("锁忙跳拍不得注销轮询", handle.isRunning());
        handle.cancel();
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类找
            }
        }
        throw new IllegalArgumentException("no field " + name + " on " + type);
    }
}
