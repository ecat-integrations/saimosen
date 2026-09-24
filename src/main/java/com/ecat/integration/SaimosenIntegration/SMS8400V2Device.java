package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
 * SMS8400V2 臭氧分析仪。串口协议对齐先河 XHOZ2000BV2（{@code oo3chr$} / {@code oo3twc$}）。
 */
public class SMS8400V2Device extends SmsV2GasDeviceBase {

    protected long pollPeriodMs = 5_000L;

    public Double molecularWeight = 48.0;

    private static final String REAL_DATA_CMD = "oo3chr$";
    private static final String STATUS_CMD = "oo3twc$";
    private ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;

    public SMS8400V2Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public String getTypeName() {
        return "sms8400v2device";
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
        setAttribute(new AQAttribute("O3", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, molecularWeight));
        setAttribute(new NumericAttribute("PMT_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("CANBI_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("POWER", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("PRESS", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        setAttribute(new NumericAttribute("FLOW", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        setAttribute(new NumericAttribute("TEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("BOXTEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("UVTEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));

        setAttribute(new NumericAttribute("CALIBRATION_CONCENTRATION", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 3, true, true));

        SmsV2GasCommandAttribute commandAttr = new SmsV2GasCommandAttribute("CALIBRATION_CMD", AttributeClass.DISPATCH_COMMAND, serialSource);
        commandAttr.registerCommand("ZERO_START", new SmsV2GasCommandAttribute.CommandConfig(
                "ozeros$", "ozerosok$", "ozerosfa$", SmsV2GasCommandAttribute.CommandType.ZERO_START));
        commandAttr.registerCommand("ZERO_END", new SmsV2GasCommandAttribute.CommandConfig(
                "ozeroe$", "ozeroeok$", "ozeroefa$", SmsV2GasCommandAttribute.CommandType.ZERO_END));
        commandAttr.registerCommand("ZERO_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "ozeroq$", "ozeroqok$", "ozeroqfa$", SmsV2GasCommandAttribute.CommandType.ZERO_CONFIRM));
        commandAttr.registerCommand("SPAN_START", new SmsV2GasCommandAttribute.CommandConfig(
                "ospans {value}$", "ospansok$", "ospansfa$", SmsV2GasCommandAttribute.CommandType.SPAN_START));
        commandAttr.registerCommand("SPAN_END", new SmsV2GasCommandAttribute.CommandConfig(
                "ospane {value}$", "ospaneok$", "ospanefa$", SmsV2GasCommandAttribute.CommandType.SPAN_END));
        commandAttr.registerCommand("SPAN_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "ospanq {value}$", "ospanqok$", "ospanqfa$", SmsV2GasCommandAttribute.CommandType.SPAN_CONFIRM));
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("CALIBRATION_CONCENTRATION"));
        setAttribute(commandAttr);

        addManualStatusAttributes("o3");
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
                if (result.matches("^[*#]?O3=.*\\$")) {
                    updateO3Attribute(result);
                    return true;
                }
            } else if (sentCmd.equals(STATUS_CMD)) {
                parseStatusResponse(result);
                return true;
            }
        }
        log.warn("Unprocessed response: " + result);
        return false;
    }

    private void updateO3Attribute(String valueStr) {
        AQAttribute o3Attr = (AQAttribute) getAttrs().get("O3");
        if (o3Attr == null) {
            log.warn("O3 attribute not found");
            return;
        }
        try {
            AttributeStatus status = determineDataStatus(valueStr, "o3_manual_status", null);
            deviceStatus = mapAttributeStatusToDeviceStatus(status);

            String numericPart = valueStr;
            if (valueStr.startsWith("*") || valueStr.startsWith("#")) {
                numericPart = valueStr.substring(1);
            }
            numericPart = numericPart.replaceFirst("[*#]?O3=", "").replace("$", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([-+]?\\d*\\.?\\d+)").matcher(numericPart);
            double value = 0.0;
            if (matcher.find()) {
                value = Double.parseDouble(matcher.group(1));
            } else {
                log.error("O3 value regex parse error: " + valueStr);
            }
            o3Attr.updateValue(value, status);
            updateReadonlyStatusAttribute("o3_status", status);
            publicAttrsState();
        } catch (Exception e) {
            log.error("O3 value parse error: " + valueStr);
        }
    }

    private void parseStatusResponse(String result) {
        String statusStr = result.replace("$", "");
        String[] parts = statusStr.split(",");
        if (parts.length != 8) {
            return;
        }
        AttributeStatus status = determineDataStatus("", "o3_manual_status", null);
        updateAttribute("PMT_V", parts[0], status);
        updateAttribute("CANBI_V", parts[1], status);
        updateAttribute("POWER", parts[2], status);
        updateAttribute("PRESS", parts[3], status);
        updateAttribute("FLOW", parts[4], status);
        updateAttribute("TEMP", parts[5], status);
        updateAttribute("BOXTEMP", parts[6], status);
        updateAttribute("UVTEMP", parts[7], status);
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
