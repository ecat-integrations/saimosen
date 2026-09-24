package com.ecat.integration.SaimosenIntegration;

import org.junit.Test;

import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttributeStatus;

import static org.junit.Assert.assertEquals;

public class SmsGasInstrumentStatusTest {

    @Test
    public void optionKey_mapsProtocolValuesAndUnknown() {
        assertEquals(SmsGasInstrumentStatus.SAMPLE, SmsGasInstrumentStatus.optionKey(0));
        assertEquals(SmsGasInstrumentStatus.CALIBRATION, SmsGasInstrumentStatus.optionKey(1));
        assertEquals(SmsGasInstrumentStatus.DIAGNOSTIC, SmsGasInstrumentStatus.optionKey(2));
        assertEquals(SmsGasInstrumentStatus.ZERO_MEASURE, SmsGasInstrumentStatus.optionKey(3));
        assertEquals(SmsGasInstrumentStatus.WARM_UP, SmsGasInstrumentStatus.optionKey(4));
        assertEquals(SmsGasInstrumentStatus.UNKNOWN, SmsGasInstrumentStatus.optionKey(5));
        assertEquals(SmsGasInstrumentStatus.UNKNOWN, SmsGasInstrumentStatus.optionKey(-1));
    }

    @Test
    public void overlay_sampleKeepsCalibrationRegister() {
        assertEquals(DeviceStatus.MEASURE, SmsGasInstrumentStatus.overlay(DeviceStatus.MEASURE, 0));
        assertEquals(DeviceStatus.SPAN_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.SPAN_CALIBRATION, 0));
        assertEquals(DeviceStatus.ZERO_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.ZERO_CALIBRATION, 0));
    }

    @Test
    public void overlay_calibrationKeepsFinerZeroAndSpan() {
        assertEquals(DeviceStatus.CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.MEASURE, 1));
        assertEquals(DeviceStatus.CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.UNKNOWN, 1));
        assertEquals(DeviceStatus.ZERO_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.ZERO_CALIBRATION, 1));
        assertEquals(DeviceStatus.SPAN_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.SPAN_CALIBRATION, 1));
    }

    @Test
    public void overlay_diagnosticIsMaintenanceEvenDuringSpanCalibration() {
        assertEquals(DeviceStatus.MAINTENANCE, SmsGasInstrumentStatus.overlay(DeviceStatus.MEASURE, 2));
        assertEquals(DeviceStatus.MAINTENANCE, SmsGasInstrumentStatus.overlay(DeviceStatus.SPAN_CALIBRATION, 2));
        assertEquals(DeviceStatus.MAINTENANCE, SmsGasInstrumentStatus.overlay(DeviceStatus.ZERO_CALIBRATION, 2));
    }

    @Test
    public void overlay_zeroMeasureAndWarmUpOverrideMeasureOnly() {
        assertEquals(DeviceStatus.ZERO, SmsGasInstrumentStatus.overlay(DeviceStatus.MEASURE, 3));
        assertEquals(DeviceStatus.SPAN_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.SPAN_CALIBRATION, 3));
        assertEquals(DeviceStatus.WARM_UP, SmsGasInstrumentStatus.overlay(DeviceStatus.MEASURE, 4));
        assertEquals(DeviceStatus.WARM_UP, SmsGasInstrumentStatus.overlay(DeviceStatus.UNKNOWN, 4));
        assertEquals(DeviceStatus.SPAN_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.SPAN_CALIBRATION, 4));
        assertEquals(DeviceStatus.ZERO_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.ZERO_CALIBRATION, 4));
    }

    @Test
    public void overlay_unknownRegisterKeepsCalibrationStatus() {
        assertEquals(DeviceStatus.MEASURE, SmsGasInstrumentStatus.overlay(DeviceStatus.MEASURE, 101));
        assertEquals(DeviceStatus.SPAN_CALIBRATION, SmsGasInstrumentStatus.overlay(DeviceStatus.SPAN_CALIBRATION, 9));
        assertEquals(DeviceStatus.UNKNOWN, SmsGasInstrumentStatus.overlay(null, 0));
        assertEquals(DeviceStatus.WARM_UP, SmsGasInstrumentStatus.overlay(null, 4));
    }

    @Test
    public void toAttributeStatus_mapsWarmUpZeroAndMaintenance() {
        assertEquals(AttributeStatus.NORMAL, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.MEASURE));
        assertEquals(AttributeStatus.CALIBRATION, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.CALIBRATION));
        assertEquals(AttributeStatus.ZERO_CALIBRATION, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.ZERO_CALIBRATION));
        assertEquals(AttributeStatus.SPAN_CALIBRATION, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.SPAN_CALIBRATION));
        assertEquals(AttributeStatus.MAINTENANCE, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.MAINTENANCE));
        assertEquals(AttributeStatus.ZERO_CHECK, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.ZERO));
        assertEquals(AttributeStatus.WAITING, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.WARM_UP));
        assertEquals(AttributeStatus.EMPTY, SmsGasInstrumentStatus.toAttributeStatus(DeviceStatus.UNKNOWN));
        assertEquals(AttributeStatus.EMPTY, SmsGasInstrumentStatus.toAttributeStatus(null));
    }
}
