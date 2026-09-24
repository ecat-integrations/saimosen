package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AQAttribute;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.integration.SerialIntegration.SerialPolling;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;

/**
 * SMS8300V2 氮氧化物分析仪。串口协议对齐先河 XHN2000BV2（{@code noxchr$} / {@code noxtwc$}）。
 */
public class SMS8300V2Device extends SmsV2GasDeviceBase {

    protected long pollPeriodMs = 5_000L;

    public Double noMolecularWeight = 30.0;
    public Double no2MolecularWeight = 46.0;
    public Double noxMolecularWeight = 46.0;

    private static final String REAL_DATA_CMD = "noxchr$";
    private static final String STATUS_CMD = "noxtwc$";
    private ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;

    public SMS8300V2Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public String getTypeName() {
        return "sms8300v2device";
    }

    @Override
    public void init() {
        super.init();
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
        createAttributes();
    }

    @Override
    public void start() {
        SerialPolling.on(this, serialSource)
                .round(source -> getRealData()
                        .thenCompose(v -> getStatusData()))
                .every(pollPeriodMs, TimeUnit.MILLISECONDS)
                .start();
    }

    private void createAttributes() {
        setAttribute(new AQAttribute("NO", AttributeClass.NO, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, noMolecularWeight));
        setAttribute(new AQAttribute("NO2", AttributeClass.NO2, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, no2MolecularWeight));
        setAttribute(new AQAttribute("NOX", AttributeClass.NOX, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, noxMolecularWeight));

        setAttribute(new NumericAttribute("PMT_FILETER_NOV", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("PMT_FILETER_NOXV", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("POWER", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("PRESS", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        setAttribute(new NumericAttribute("FLOW", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        setAttribute(new NumericAttribute("TEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("BOXTEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("MULU_TEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("CANBI_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("CELL_PRESS", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        setAttribute(new NumericAttribute("SAMPLE_FLOW", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        setAttribute(new NumericAttribute("PMT_HVDIS", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));

        setAttribute(new NumericAttribute("CALIBRATION_CONCENTRATION", AttributeClass.NO2, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 3, true, true));

        SmsV2GasCommandAttribute commandAttr = new SmsV2GasCommandAttribute("CALIBRATION_CMD", AttributeClass.DISPATCH_COMMAND, serialSource);
        commandAttr.registerCommand("ZERO_START", new SmsV2GasCommandAttribute.CommandConfig(
                "nzeros$", "nzerosok$", "nzerosfa$", SmsV2GasCommandAttribute.CommandType.ZERO_START));
        commandAttr.registerCommand("ZERO_END", new SmsV2GasCommandAttribute.CommandConfig(
                "nzeroe$", "nzeroeok$", "nzeroefa$", SmsV2GasCommandAttribute.CommandType.ZERO_END));
        commandAttr.registerCommand("ZERO_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "nzeroq$", "nzeroqok$", "nzeroqfa$", SmsV2GasCommandAttribute.CommandType.ZERO_CONFIRM));
        commandAttr.registerCommand("SPAN_START", new SmsV2GasCommandAttribute.CommandConfig(
                "nspans {value}$", "nspansok$", "nspansfa$", SmsV2GasCommandAttribute.CommandType.SPAN_START));
        commandAttr.registerCommand("SPAN_END", new SmsV2GasCommandAttribute.CommandConfig(
                "nspane {value}$", "nspaneok$", "nspanefa$", SmsV2GasCommandAttribute.CommandType.SPAN_END));
        commandAttr.registerCommand("SPAN_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "nspanq {value}$", "nspanqok$", "nspanqfa$", SmsV2GasCommandAttribute.CommandType.SPAN_CONFIRM));
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("CALIBRATION_CONCENTRATION"));
        setAttribute(commandAttr);

        addManualStatusAttributes("nox");
    }

    private CompletableFuture<Boolean> getRealData() {
        return sendCommand(REAL_DATA_CMD.getBytes());
    }

    private CompletableFuture<Boolean> getStatusData() {
        return sendCommand(STATUS_CMD.getBytes());
    }

    private CompletableFuture<Boolean> sendCommand(byte[] cmd) {
        return serialSource.asyncSendData(cmd)
               .thenCompose(v -> responseHandlerStrategy.handleResponse(new ByteResponseHandlingContext<>(cmd)));
    }

    private Boolean processResponse(ByteResponseHandlingContext<byte[]> context) {
        String result = context.getReceiveBuffer().toString();
        if (result.endsWith("$")) {
            result = result.replace("$$", "$");
            result = result.replace("\r\n", "");
            String sentCmd = new String(context.getNewValue());
            if (sentCmd.equals(REAL_DATA_CMD)) {
                updateNOxAttributes(result);
                return true;
            } else if (sentCmd.equals(STATUS_CMD)) {
                parseStatusResponse(result);
                return true;
            }
        }
        log.warn("Unprocessed response: " + result);
        return false;
    }

    private void updateNOxAttributes(String valueStr) {
        String[] lines = valueStr.split("\\n");
        AttributeStatus status = AttributeStatus.NORMAL;
        if (lines.length > 0) {
            status = determineDataStatus(lines[0].trim(), "nox_manual_status", null);
        }
        deviceStatus = mapAttributeStatusToDeviceStatus(status);
        updateReadonlyStatusAttribute("nox_status", status);

        for (String line : lines) {
            String rawLine = line.trim();
            if (rawLine.startsWith("*") || rawLine.startsWith("#")) {
                rawLine = rawLine.substring(1);
            }
            Pattern p = Pattern.compile("(NO|NO2|NOX)=([-+]?\\d*\\.?\\d+)([^\\d.]+)?");
            Matcher m = p.matcher(rawLine.replace("$", ""));
            if (m.find()) {
                String param = m.group(1);
                double value = Double.parseDouble(m.group(2));
                AQAttribute attr = (AQAttribute) getAttrs().get(param);
                if (attr != null) {
                    attr.updateValue(value, status);
                }
            }
        }
        publicAttrsState();
    }

    private void parseStatusResponse(String result) {
        String statusStr = result.replace("$", "");
        String[] parts = statusStr.split(",");
        if (parts.length != 12) {
            return;
        }
        AttributeStatus status = determineDataStatus("", "nox_manual_status", null);
        updateAttribute("PMT_FILETER_NOV", parts[0], status);
        updateAttribute("PMT_FILETER_NOXV", parts[1], status);
        updateAttribute("POWER", parts[2], status);
        updateAttribute("PRESS", parts[3], status);
        updateAttribute("FLOW", parts[4], status);
        updateAttribute("TEMP", parts[5], status);
        updateAttribute("BOXTEMP", parts[6], status);
        updateAttribute("MULU_TEMP", parts[7], status);
        updateAttribute("CANBI_V", parts[8], status);
        updateAttribute("CELL_PRESS", parts[9], status);
        updateAttribute("SAMPLE_FLOW", parts[10], status);
        updateAttribute("PMT_HVDIS", parts[11], status);
        publicAttrsState();
    }

    private void updateAttribute(String attrId, String valueStr, AttributeStatus status) {
        AttributeBase<?> attr = getAttrs().get(attrId);
        if (attr == null) {
            log.warn("Attribute not found: " + attrId);
            return;
        }
        if (attr instanceof NumericAttribute) {
            try {
                double value = Double.parseDouble(valueStr);
                ((NumericAttribute) attr).updateValue(value, status);
            } catch (NumberFormatException e) {
                log.error("Value is not numeric for NumericAttribute: " + attrId + " = " + valueStr);
            }
        }
    }
}
