package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.integration.SerialIntegration.SerialSource;
import org.junit.Test;

public class SaimosenSerialSourceReleaseTest {
    @Test
    public void releaseRetainsTerminalSourceViewAndDoesNotClosePort() {
        SerialSource serialSource = mock(SerialSource.class);
        SerialDeviceBase device = new SerialDeviceBase(new ConfigEntry.Builder()
                .entryId("saimosen-release-test")
                .uniqueId("saimosen-release-test")
                .data(new java.util.HashMap<>())
                .build()) {
            @Override public void start() { }
        };
        device.serialSource = serialSource;

        device.release();

        assertSame(serialSource, device.serialSource);
        verify(serialSource, never()).closePort();
    }
}
