# 赛默森设备集成模块 (SaimosenIntegration)

针对河北赛默森环保公司设备的 ECAT 集成模块，通过 Modbus RTU（部分型号为串口协议）接入环境监测站房设备，实现数据采集、状态监测与校准控制。

## 支持的设备类型与型号

| 设备类型 (class) | 型号 (model) | 实现类 | 协议 | 说明 |
|------------------|--------------|--------|------|------|
| `air.monitor.so2` | SMS8200 | SO2Device | Modbus RTU | SO₂ 自动分析仪 |
| `air.monitor.co` | SMS8500 | CODevice | Modbus RTU | CO 自动分析仪 |
| `air.monitor.no2` | SMS8300 | NO2Device | Modbus RTU | NOx 自动分析仪（NO/NO₂/NOx） |
| `air.monitor.o3` | SMS8400 | O3Device | Modbus RTU | O₃ 自动分析仪 |
| `air.monitor.qc` | SMS8910 | QCDevice | Modbus RTU | 质控仪（完整协议 V1，起始地址 0~232） |
| `air.monitor.qc` | SMS8910V2 | QCV2Device | Modbus RTU | 质控仪 V2（起始地址 0~244，含稳压电源参数） |
| `air.monitor.calibrator` | SMS8600V1 | CalibratorDevice | Modbus RTU | 动态气体校准仪 V1 |
| `air.monitor.calibrator` | SMS8600V2 | SMS8600V2Device | 串口 | 动态气体校准仪 V2（非 Modbus） |
| `air.monitor.pm.qc` | SMS8220 | ParticulateZeroChecker | Modbus RTU | 颗粒物零点检查仪 |
| `power.supply.stabilizer` | IRP0501B | SmartPowerStabilizer | Modbus RTU | 智能电力稳压器 |
| `sample.tube` | SMS6930 | SampleTube | Modbus RTU | 采样管加热器 |

四参数分析仪（SO₂/CO/NOx/O₃）轮询周期为 **5 秒**；质控仪、校准仪为 **5 秒**；采样管加热器为 **5 秒**。

---

## 部署配置

以下为常规配置示例通讯参数：

### 站房设备

| 设备 ID | 名称 | class | 型号 | 串口 | 波特率 | Slave ID | 超时(ms) |
|---------|------|-------|------|------|--------|----------|----------|
| sms-qc | 质控仪 | air.monitor.qc | SMS8910 | COM2 | 9600 | **1** | 5000 |
| sms-calib | 校准仪 | air.monitor.calibrator | SMS 8600 | COM9 | **19200** | **1** | — |

| 设备 ID | 名称 | class | 型号 | 串口 | 波特率 | Slave ID | 超时(ms) |
|---------|------|-------|------|------|--------|----------|----------|
| sms-power | 稳压电源 | power.supply.stabilizer | SMS-POWER | COM7 | 9600 | 1 |
| sms-particulate-zero-checker | 颗粒物零点检查器 | air.monitor.pm.qc | XH2000E | COM3 | 19200 | 5 |
| sms-sample-tube | 采样总管 | sample.tube | SMS-D-H | COM9 | 9600 | 1 |

> 质控仪额外配置：`device_settings.sampling_tube_length: 4.5`（采样管长度，单位米）。

### 监测仪器
| 设备 ID | 名称 | class | 型号 | 串口 | 波特率 | Slave ID |
|---------|------|-------|------|------|--------|----------|
| sms-so2 | SO2监测仪 | air.monitor.so2 | SMS8200 | COM3 | 9600 | **4** | — |
| sms-no2 | NO2监测仪 | air.monitor.no2 | SMS8300 | COM6 | 9600 | **2** | 2000 |
| sms-o3 | O3监测仪 | air.monitor.o3 | SMS8400 | COM4 | 9600 | **3** | — |
| sms-co | CO监测仪 | air.monitor.co | SMS8500 | COM5 | 9600 | **1** | — |



### 通用串口参数

| 参数 | 值 |
|------|-----|
| 数据位 | 8 |
| 停止位 | 1 |
| 校验位 | N（无校验） |
| 功能码 | 03（读保持寄存器）、06（写单个寄存器） |

---

## 四参数分析仪公共校准协议（1000~1006）

SO₂、CO、NOx、O₃ 分析仪共用以下校准寄存器（与 `GasDeviceCommandAttribute` / 设备校准方法一致）：

| 地址(Dec) | 地址(Hex) | 参数 | 写入值 | 读/写 | 说明 |
|-----------|-----------|------|--------|-------|------|
| 1000 | 0x3E8 | 零点校准开始 | 0 | 写 | 进入零点校准 |
| 1001 | 0x3E9 | 零点校准确认 | 0 | 写 | 确认零点 |
| 1002 | 0x3EA | 零点校准取消 | 0 | 写 | 取消零点，恢复采样 |
| 1003 | 0x3EB | 跨度校准 | 400（或设定浓度） | 读/写 | 进入跨度校准并设定浓度 |
| 1004 | 0x3EC | 跨度校准确认 | 400 | 写 | 确认跨度 |
| 1005 | 0x3ED | 跨度校准取消 | 400 | 写 | 取消跨度，恢复采样 |
| 1006 | 0x3EE | 仪器校准状态 | — | 读 | 0=采样，1=零点，2=跨度 |

Float 类型参数占 **2 个连续寄存器**，编码为 **BADC 字序**（`convertLittleEndianByteSwapToFloat`）；U16 类型占 **1 个寄存器**。

---

## SO₂ 分析仪 (SMS8200 / SO2Device)

**Slave ID（示例配置）：4**　**波特率：9600**

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 单位 |
|---------|------|---------------|---------------|------|----------|------|
| measure_volt | 测量电压 | 0 | 0x0000 | Float | 2 | mV |
| sample_press | 样气压力 | 2 | 0x0002 | Float | 2 | Pa |
| chamber_temp | 反应室温度 | 4 | 0x0004 | Float | 2 | °C |
| sample_flow | 样气流量 | 6 | 0x0006 | Float | 2 | mL/min |
| pump_press | 泵压力 | 8 | 0x0008 | Float | 2 | Pa |
| sample_temp | 样气温度 | 10 | 0x000A | Float | 2 | °C |
| slope | 浓度斜率 | 14 | 0x000E | Float | 2 | — |
| intercept | 浓度截距 | 16 | 0x0010 | Float | 2 | — |
| so2 | SO₂浓度 | 30 | 0x001E | Float | 2 | ppb |
| device_address | 仪器地址 | 38 | 0x0026 | U16 | 1 | — |
| device_status | 仪器状态 | 39 | 0x0027 | U16 | 1 | — |
| sample_cal_valve_status | 采样校准阀状态 | 56 | 0x0038 | U16 | 1 | — |
| fault_code | 故障代码 | 63 | 0x003F | U16 | 1 | — |

校准寄存器见上文 [四参数分析仪公共校准协议](#四参数分析仪公共校准协议10001006)。

### 串口调试示例（Slave ID = 4）

```
# 读取 SO₂ 浓度（起始地址 30，Float 占 2 个寄存器）
发送：04 03 00 1E 00 02 A4 58

# 读取校准状态（起始地址 1006）
发送：04 03 03 EE 00 01 E4 2E

# 零点校准开始（写 1000 = 0）
发送：04 06 03 E8 00 00 09 EF
```

---

## CO 分析仪 (SMS8500 / CODevice)

**Slave ID（示例配置）：1**　**波特率：9600**

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 单位 |
|---------|------|---------------|---------------|------|----------|------|
| co | CO浓度 | 0 | 0x0000 | Float | 2 | ppm |
| measure_volt | 测量电压 | 2 | 0x0002 | Float | 2 | mV |
| ref_volt | 参比电压 | 4 | 0x0004 | Float | 2 | mV |
| slope | 浓度斜率 | 10 | 0x000A | Float | 2 | — |
| intercept | 浓度截距 | 12 | 0x000C | Float | 2 | — |
| sample_press | 样气压力 | 14 | 0x000E | Float | 2 | kPa |
| sample_flow | 样气流量 | 18 | 0x0012 | Float | 2 | mL/min |
| voltage_12v | 12V电压 | 60 | 0x003C | U16 | 1 | mV |
| sample_cal_status | 采样/校准状态 | 71 | 0x0047 | U16 | 1 | — |
| fault_code | 故障代码 | 72 | 0x0048 | U16 | 1 | — |

> CO 的 `0x3E8`（1000）同时作为**模式寄存器**（0=采样，1=零点，2=跨度），与 SO₂/NOx/O₃ 的命令触发语义不同。

### 串口调试示例（Slave ID = 1）

```
# 读取 CO 浓度（起始地址 0，Float 占 2 个寄存器）
发送：01 03 00 00 00 02 C4 0B

# 读取校准状态（起始地址 1006）
发送：01 03 03 EE 00 01 E4 7B
```

---

## NOx 分析仪 (SMS8300 / NO2Device)

**Slave ID（示例配置）：2**　**波特率：9600**

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 单位 |
|---------|------|---------------|---------------|------|----------|------|
| no | NO浓度 | 0 | 0x0000 | Float | 2 | ppb |
| no2 | NO₂浓度 | 2 | 0x0002 | Float | 2 | ppb |
| nox | NOx浓度 | 4 | 0x0004 | Float | 2 | ppb |
| no_measure_volt | NO测量电压 | 6 | 0x0006 | Float | 2 | mV |
| nox_measure_volt | NOx测量电压 | 8 | 0x0008 | Float | 2 | mV |
| sample_press | 样气压力 | 10 | 0x000A | Float | 2 | kPa |
| sample_temp | 样气温度 | 12 | 0x000C | Float | 2 | °C |
| sample_flow | 样气流量 | 14 | 0x000E | Float | 2 | mL/min |
| no_slope | NO浓度斜率 | 22 | 0x0016 | Float | 2 | — |
| no_intercept | NO浓度截距 | 24 | 0x0018 | Float | 2 | — |
| nox_slope | NOx浓度斜率 | 26 | 0x001A | Float | 2 | — |
| nox_intercept | NOx浓度截距 | 28 | 0x001C | Float | 2 | — |
| device_address | 仪器地址 | 58 | 0x003A | U16 | 1 | — |
| device_status | 仪器状态 | 59 | 0x003B | U16 | 1 | — |
| sample_cal_valve_status | 采样校准阀状态 | 76 | 0x004C | U16 | 1 | — |
| alarm_info | 报警信息 | 83 | 0x0053 | U16 | 1 | 按位 |
| fault_code | 故障代码 | 84 | 0x0054 | U16 | 1 | — |

### 串口调试示例（Slave ID = 2）

```
# 读取 NO 浓度（起始地址 0，Float 占 2 个寄存器）
发送：02 03 00 00 00 02 C4 38

# 读取校准状态（起始地址 1006，0=采样）
发送：02 03 03 EE 00 01 E4 48

# 跨度校准取消，恢复采样（写 1005 = 400）
发送：02 06 03 ED 01 90 18 74

# 停止校准 / 写状态寄存器为采样（写 1006 = 0）
发送：02 06 03 EE 00 00 E9 88
```

---

## O₃ 分析仪 (SMS8400 / O3Device)

**Slave ID（示例配置）：3**　**波特率：9600**

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 单位 |
|---------|------|---------------|---------------|------|----------|------|
| o3 | O₃浓度 | 0 | 0x0000 | Float | 2 | ppb |
| measure_volt | 测量电压 | 2 | 0x0002 | Float | 2 | mV |
| ref_volt | 参比电压 | 4 | 0x0004 | Float | 2 | mV |
| sample_press | 样气压力 | 6 | 0x0006 | Float | 2 | kPa |
| sample_temp | 样气温度 | 8 | 0x0008 | Float | 2 | °C |
| sample_flow | 样气流量 | 10 | 0x000A | Float | 2 | mL/min |
| slope | 浓度斜率 | 14 | 0x000E | Float | 2 | — |
| intercept | 浓度截距 | 16 | 0x0010 | Float | 2 | — |
| device_address | 仪器地址 | 40 | 0x0028 | U16 | 1 | — |
| device_status | 仪器状态 | 41 | 0x0029 | U16 | 1 | — |
| sample_cal_valve_status | 采样校准阀状态 | 53 | 0x0035 | U16 | 1 | — |
| fault_code | 故障代码 | 57 | 0x0039 | U16 | 1 | — |

### 串口调试示例（Slave ID = 3）

```
# 读取 O₃ 浓度（起始地址 0，Float 占 2 个寄存器）
发送：03 03 00 00 00 02 C5 E9

# 读取校准状态（起始地址 1006）
发送：03 03 03 EE 00 01 E5 99
```

---

## 质控仪 (SMS8910 / QCDevice)

**Slave ID（示例配置）：1**　**波特率：9600**

Float 参数为大端序，占 2 个连续寄存器；U16 占 1 个寄存器。参数起始地址范围 **0~232**（SMS8910V2 扩展至 **244**）。

### 参数寄存器地址（节选）

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 读写 |
|---------|------|---------------|---------------|------|----------|------|
| system_state | 仪器状态 | 0 | 0x0000 | U16 | 1 | R/W |
| bench_temp | 站房温度 | 1 | 0x0001 | Float | 2 | R |
| bench_humidity | 站房湿度 | 3 | 0x0003 | Float | 2 | R |
| sample_tube_temp | 采样管温度 | 5 | 0x0005 | Float | 2 | R |
| sample_tube_humidity | 采样管湿度 | 7 | 0x0007 | Float | 2 | R |
| sample_tube_flow | 采样管流速 | 9 | 0x0009 | Float | 2 | R |
| sample_tube_pressure | 采样管静压 | 11 | 0x000B | Float | 2 | R |
| station_ua | 站房A相电压 | 14 | 0x000E | Float | 2 | R |
| station_ub | 站房B相电压 | 16 | 0x0010 | Float | 2 | R |
| station_uc | 站房C相电压 | 18 | 0x0012 | Float | 2 | R |
| ac1_power | 空调1开机状态 | 46 | 0x002E | U16 | 1 | R/W |
| so2_film_changer_status | SO₂换膜状态 | 116 | 0x0074 | U16 | 1 | R |

> 完整参数表见 `QCDevice.initAttributeMap()`（共 200+ 项）。

### 串口调试示例（Slave ID = 1）

```
# 读取仪器状态（起始地址 0）
发送：01 03 00 00 00 01 84 0A

# 读取站房温度（起始地址 1，Float 占 2 个寄存器）
发送：01 03 00 01 00 02 95 CB

# 读取 SO₂ 换膜状态（起始地址 116）
发送：01 03 00 74 00 01 C4 10
```

---

## 动态气体校准仪 (SMS8600V1 / CalibratorDevice)

**Slave ID（示例配置）：1**　**波特率：19200**

Float 参数为大端序，占 2 个连续寄存器。

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 读写 |
|---------|------|---------------|---------------|------|----------|------|
| other_gas_concentration | NO/CO/SO₂生成浓度 | 0 | 0x0000 | Float | 2 | R/W |
| so2_std_gas_concentration | SO₂标气浓度 | 2 | 0x0002 | Float | 2 | R/W |
| no_std_gas_concentration | NO标气浓度 | 4 | 0x0004 | Float | 2 | R/W |
| co_std_gas_concentration | CO标气浓度 | 6 | 0x0006 | Float | 2 | R/W |
| o3_gas_concentration | O₃生成浓度 | 8 | 0x0008 | Float | 2 | R/W |
| calibrator_gas_select | 气体选择/启停 | 70 | 0x0046 | U16 | 1 | R/W |

气体选择值：Choose=待机，SO2/NO/CO/O3=对应气体，以及 GPT 相关模式。

### 串口调试示例（Slave ID = 1，19200 8N1）

```
# 读取 other_gas_concentration（起始地址 0，Float 占 2 个寄存器）
发送：01 03 00 00 00 02 C4 0B

# 选择 NO 气体并开始生成（写起始地址 0x46 = 1）
发送：01 06 00 46 00 01 A9 DF

# 恢复待机（写起始地址 0x46 = 0）
发送：01 06 00 46 00 00 68 1F
```

> **SMS8600V2** 使用串口协议（`SMS8600V2Device`），不走 Modbus，此处不适用。

---

## 颗粒物零点检查仪 (SMS8220 / ParticulateZeroChecker)

**Slave ID（示例配置）：5**　**波特率：19200**

仅命令寄存器，无周期性数据读取。

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 说明 |
|---------|------|---------------|---------------|------|
| pm10_zero_check_command | PM10零点检查-开始 | 1 | 0x0001 | 写 1 触发开始 |
| pm10_zero_check_command | PM10零点检查-停止 | 2 | 0x0002 | 写 1 触发停止 |
| pm2_5_zero_check_command | PM2.5零点检查-开始 | 3 | 0x0003 | 写 1 触发开始 |
| pm2_5_zero_check_command | PM2.5零点检查-停止 | 4 | 0x0004 | 写 1 触发停止 |

### 串口调试示例（Slave ID = 5）

```
# PM2.5 零点检查开始（写起始地址 0x03 = 1）
发送：05 06 00 03 00 01 B9 8E

# PM2.5 零点检查停止（写起始地址 0x04 = 1）
发送：05 06 00 04 00 01 08 4F
```

---

## 智能电力稳压器 (IRP0501B / SmartPowerStabilizer)

**Slave ID（示例配置）：1**　**波特率：9600**

大端序，每个参数占 **1 个寄存器**，读取后按缩放系数换算（÷10 或 ÷100）。

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 缩放 |
|---------|------|---------------|---------------|------|----------|------|
| current_l1 | 第1路电流 | 0 | 0x0000 | Scaled | 1 | ÷100 |
| current_l2 | 第2路电流 | 1 | 0x0001 | Scaled | 1 | ÷100 |
| current_l3 | 第3路电流 | 2 | 0x0002 | Scaled | 1 | ÷100 |
| current_l4 | 第4路电流 | 3 | 0x0003 | Scaled | 1 | ÷100 |
| voltage_l1 | 第1路电压 | 4 | 0x0004 | Scaled | 1 | ÷10 |
| voltage_l2 | 第2路电压 | 5 | 0x0005 | Scaled | 1 | ÷10 |
| voltage_l3 | 第3路电压 | 6 | 0x0006 | Scaled | 1 | ÷10 |
| voltage_l4 | 第4路电压 | 7 | 0x0007 | Scaled | 1 | ÷10 |
| power_l1 | 第1路功率 | 8 | 0x0008 | Scaled | 1 | ÷100 |
| temperature | 温度 | 12 | 0x000C | Scaled | 1 | ÷10 |
| humidity | 湿度 | 13 | 0x000D | Scaled | 1 | ÷10 |
| relay_l1 | 第1路继电器 | 14 | 0x000E | Scaled | 1 | — |

### 串口调试示例（Slave ID = 1）

```
# 读取第1路电压（起始地址 4）
发送：01 03 00 04 00 01 C5 CB
```

---

## 采样管加热器 (SMS6930 / SampleTube)

**Slave ID（示例配置）：1**　**波特率：9600**

### 参数寄存器地址

| 属性 ID | 名称 | 起始地址(Dec) | 起始地址(Hex) | 类型 | 寄存器数 | 缩放 | 读写 |
|---------|------|---------------|---------------|------|----------|------|------|
| humidity | 样气湿度 | 0 | 0x0000 | U16 | 1 | ×10 | R |
| sample_gas_temperature | 样气温度 | 1 | 0x0001 | U16 | 1 | ×10 | R |
| calibration_status | 校准状态 | 2 | 0x0002 | U16 | 1 | — | R/W |
| device_address | 设备地址 | 4 | 0x0004 | U16 | 1 | — | R/W |
| gas_flow_rate | 样气流速 | 5 | 0x0005 | U16 | 1 | ×10 | R |
| heating_tube_actual_temp | 加热管实际温度 | 6 | 0x0006 | U16 | 1 | ×10 | R/W |
| fan_power | 风机功率 | 7 | 0x0007 | U16 | 1 | ×10 | R |
| heating_belt_power | 加热带功率 | 8 | 0x0008 | U16 | 1 | ×10 | R |
| heating_tube_target_temp | 加热管设置温度 | 10 | 0x000A | U16 | 1 | ×10 | R/W |

### 串口调试示例（Slave ID = 1）

```
# 读取样气湿度（起始地址 0）
发送：01 03 00 00 00 01 84 0A

# 设置加热管目标温度 50.0°C（写起始地址 10 = 500）
发送：01 06 00 0A 01 F4 2C 48
```

---

## 注意事项

1. 串口命名：Windows 为 `COMn`，Linux 为 `/dev/ttyUSBn` 或 `/dev/ttySn`。
2. 同一 RS485 总线上各设备 **Slave ID 不可重复**；示例配置中 CO 与质控仪、校准仪均为 Slave ID 1，需接在不同串口上。
3. 四参数分析仪 Float 字节序为 BADC，与质控仪/校准仪的大端 Float 不同，调试时注意区分。
4. 校准相关操作请优先通过 `dispatch_command`（或 CO 的 `gas_device_command`）属性下发，避免直接写错寄存器。

## 协议声明

1. 核心依赖：本插件基于 **ECAT Core**（Apache License 2.0）开发，Core 项目地址：https://github.com/ecat-project/ecat-core。
2. 插件自身：本插件的源代码采用 [Apache License 2.0] 授权。
3. 合规说明：使用本插件需遵守 ECAT Core 的 Apache 2.0 协议规则，若复用 ECAT Core 代码片段，需保留原版权声明。

### 许可证获取

- ECAT Core 完整许可证：https://github.com/ecat-project/ecat-core/blob/main/LICENSE
- 本插件许可证：./LICENSE
