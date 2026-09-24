package com.ecat.integration.SaimosenIntegration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.StringSelectAttribute;

/**
 * SMS8200 / SMS8300 / SMS8400 仪器状态寄存器（U16 段内第 2 个寄存器）解码。
 * <p>
 * 协议取值：0 采样、1 校准、2 诊断、3 零点测量、4 预热标志（热机）。
 * 与校准寄存器 1006 合成时：诊断一律维护；校准保留 1006 的零点/跨度细分；
 * 零点测量和热机只覆盖测量态。
 */
public final class SmsGasInstrumentStatus {

    public static final String SAMPLE = "0";
    public static final String CALIBRATION = "1";
    public static final String DIAGNOSTIC = "2";
    public static final String ZERO_MEASURE = "3";
    public static final String WARM_UP = "4";
    public static final String UNKNOWN = "unknown";

    /** SO2 / NOx / O3 的 U16 段中，仪器状态紧跟仪器地址。 */
    public static final int U16_INDEX = 1;

    private static final List<String> OPTIONS = Collections.unmodifiableList(Arrays.asList(
            SAMPLE, CALIBRATION, DIAGNOSTIC, ZERO_MEASURE, WARM_UP, UNKNOWN));

    private SmsGasInstrumentStatus() {
    }

    public static List<String> options() {
        return OPTIONS;
    }

    public static String optionKey(int register) {
        switch (register) {
            case 0:
                return SAMPLE;
            case 1:
                return CALIBRATION;
            case 2:
                return DIAGNOSTIC;
            case 3:
                return ZERO_MEASURE;
            case 4:
                return WARM_UP;
            default:
                return UNKNOWN;
        }
    }

    /**
     * 用仪器状态覆盖校准寄存器 1006 已经解析出的设备状态。
     *
     * @param calibrationStatus 1006 的解析结果；缺失时按未知处理
     * @param instrumentRegister 仪器状态寄存器原值
     */
    public static DeviceStatus overlay(DeviceStatus calibrationStatus, int instrumentRegister) {
        DeviceStatus base = calibrationStatus == null ? DeviceStatus.UNKNOWN : calibrationStatus;
        switch (instrumentRegister) {
            case 0:
                return base;
            case 1:
                if (base == DeviceStatus.ZERO_CALIBRATION || base == DeviceStatus.SPAN_CALIBRATION) {
                    return base;
                }
                return DeviceStatus.CALIBRATION;
            case 2:
                return DeviceStatus.MAINTENANCE;
            case 3:
                return isMeasureState(base) ? DeviceStatus.ZERO : base;
            case 4:
                return isMeasureState(base) ? DeviceStatus.WARM_UP : base;
            default:
                return base;
        }
    }

    public static AttributeStatus toAttributeStatus(DeviceStatus deviceStatus) {
        if (deviceStatus == null) {
            return AttributeStatus.EMPTY;
        }
        switch (deviceStatus) {
            case MEASURE:
            case NORMAL:
                return AttributeStatus.NORMAL;
            case ZERO_CALIBRATION:
                return AttributeStatus.ZERO_CALIBRATION;
            case SPAN_CALIBRATION:
                return AttributeStatus.SPAN_CALIBRATION;
            case CALIBRATION:
                return AttributeStatus.CALIBRATION;
            case ZERO:
                return AttributeStatus.ZERO_CHECK;
            case WARM_UP:
                return AttributeStatus.WAITING;
            case MAINTENANCE:
                return AttributeStatus.MAINTENANCE;
            case UNKNOWN:
            default:
                return AttributeStatus.EMPTY;
        }
    }

    public static void writeSelect(AttributeBase<?> attribute, int register, AttributeStatus status) {
        if (attribute instanceof StringSelectAttribute) {
            ((StringSelectAttribute) attribute).updateValue(optionKey(register), status);
        }
    }

    private static boolean isMeasureState(DeviceStatus status) {
        return status == DeviceStatus.MEASURE
                || status == DeviceStatus.NORMAL
                || status == DeviceStatus.UNKNOWN;
    }
}
