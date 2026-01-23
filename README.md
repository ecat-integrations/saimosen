# 赛默森设备集成模块 (SaimosenIntegration)

针对河北赛默森环保公司设备的集成模块，支持多种环境监测设备的通信和控制。

## 支持的设备类型

1. **校准仪设备** (CalibratorDevice)
   - 适用于SMS校准仪-RTU
   - 支持气体选择和标气浓度控制

2. **质控仪设备** (QCDevice)
   - 适用于SMS 8910质控仪
   - 支持分两次读取Modbus参数及中文显示名称

3. **智能电力稳压器** (SmartPowerStabilizer)
   - 通过Modbus协议读取和控制电力稳压器设备
   - 支持电压、电流、功率等参数监测

4. **颗粒物零点检查设备** (ParticulateZeroChecker)
   - 颗粒物零点检查8220设备，Modbus通讯
   - 支持PM10和PM2.5零点校准控制

5. **O3臭氧分析仪** (O3Device)
   - 适用于SMS O3臭氧分析仪
   - 支持O3浓度、测量电压、参比电压、样气压力、温度、流量等参数监测
   - 支持浓度斜率和截距的读写控制
   - 支持数据状态监测

6. **NO2氮氧化物分析仪** (NO2Device)
   - 适用于SMS NO2氮氧化物分析仪
   - 支持NO、NO2、NOx浓度，测量电压，样气压力、温度、流量等参数监测
   - 支持NO和NOx浓度斜率和截距的读写控制
   - 支持数据状态监测

7. **CO一氧化碳分析仪** (CODevice)
    - 适用于SMS CO一氧化碳分析仪
    - 支持CO浓度、测量电压、参比电压、样气压力、温度、流量等参数监测
    - 支持浓度斜率和截距的读写控制
    - 支持数据状态监测

8. **SO2二氧化硫分析仪** (SO2Device)
    - 适用于SMS SO2二氧化硫分析仪
    - 支持SO2浓度、测量电压、参比电压、样气压力、温度、流量等参数监测
    - 支持浓度斜率和截距的读写控制
    - 支持数据状态监测

9. **采样管加热器** (SampleTube)
   - 适用于SMS-D-H型采样管加热器
   - 支持温度控制、功率监控、湿度和流速监测
   - 提供11个关键参数监测和控制功能
   - 支持Modbus RTU协议通信（功能码：03读取，06写入）

## 配置说明

设备配置通过YAML文件进行定义，示例如下：

```yaml
devices:
  - id: sms-no2
    name: NO2监测仪
    class: air.monitor.no2
    sn: SMS8300-001
    vendor: 赛默森环保
    model: SMS 8300
    comm_settings:
      port: COM1
      baudRate: 9600
      numDataBit: 8
      numStopBit: 1
      parity: N
      slaveId: 1
      timeout: 2000

  - id: sms-o3
    name: O3监测仪
    class: air.monitor.o3
    sn: SMS8300-004
    vendor: 赛默森环保
    model: SMS 8200
    comm_settings:
      port: COM4
      baudRate: 9600
      numDataBit: 8
      numStopBit: 1
      parity: N
      slaveId: 1
      timeout: 2000

  - id: sample-tube-001
    name: 采样管加热器
    class: sample.tube
    sn: ST-001
    vendor: 赛默森环保
    model: SMS-D-H
    comm_settings:
      port: COM5
      baudRate: 9600
      numDataBit: 8
      numStopBit: 1
      parity: N
      slaveId: 1
      timeout: 2000

```

## 通信协议

所有设备均通过Modbus RTU协议进行通信，具有以下特点：

- 波特率: 9600
- 数据位: 8
- 停止位: 1
- 校验位: 无校验 (N)
- 从站ID: 根据设备配置设定

## 设备功能

### 校准仪 (CalibratorDevice)
- 支持气体选择 (如 SO2, NO, NO2, CO, O3, CO2, N2, 零气)
- 可设置各种气体的标准浓度值
- 支持设备状态监控

#### 质控校准说明

##### 气态设备连接方式
- 设备端串口通讯线为母头交叉线，默认 9600 8 1 N

##### 质控检查和校准涉及的参数和含义
- attribute_id: OtherGasConcentration
  desc: 设置NO、CO、SO2气体的生成浓度，单位PPM，数值如 0.4

- attribute_id: O3GasConcentration
  desc: 设置O3气体的生成浓度，单位PPM，数值如 0.4

- attribute_id: CalibratorGasSelect
  desc: 设置校准气体并开始或结束，当用于单点、多点时值为O3、NO、CO、SO2，结束时为 Choose

##### 仪器初始化状态序列
- attribute_id: CalibratorGasSelect
  value: "Choose"
  desc: 让校准仪恢复待机


##### 仪器进入零点或跨度检查状态序列集

- 如果是NO、CO、SO2气体
    - attribute_id: OtherGasConcentration
    value: "0.4"
    desc: 设置气体的生成浓度，单位PPM
    - attribute_id: CalibratorGasSelect
    value: "NO"
    desc: 设置气体的生成浓度，单位PPM

- 如果是O3气体
    - attribute_id: O3GasConcentration
    value: "0.4"
    desc: 设置气体的生成浓度，单位PPM
    - attribute_id: CalibratorGasSelect
    value: "O3"
    desc: 设置气体的生成浓度，单位PPM


##### 仪器工作中切换浓度序列集
同[仪器进入零点或跨度检查状态序列集]的对应分支序列

##### 仪器恢复测量状态序列集
- attribute_id: CalibratorGasSelect
  value: "Choose"
  desc: 让校准仪恢复待机




### 质控仪 (QCDevice)
- 读取大量设备参数 (超过200个寄存器)
- 分两次读取以避免Modbus通信超时
- 支持温度、湿度、流量、压力等多种参数监测

### 智能电力稳压器 (SmartPowerStabilizer)
- 实时监测电压、电流、功率等电力参数
- 支持设备状态监控

### 颗粒物零点检查设备 (ParticulateZeroChecker)
- 支持PM10和PM2.5零点校准控制
- 可发送开始、停止等控制命令

### O3臭氧分析仪 (O3Device)
- 支持O3浓度监测（ppb）
- 支持测量电压、参比电压监测（mV）
- 支持样气压力、温度、流量监测
- 支持浓度斜率和截距的读写控制
- 每5秒自动读取一次数据

### NO2氮氧化物分析仪 (NO2Device)
- 支持NO、NO2、NOx浓度监测（ppb）
- 支持NO和NOx测量电压监测（mV）
- 支持样气压力、温度、流量监测
- 支持NO和NOx浓度斜率和截距的读写控制
- 每5秒自动读取一次数据

### 采样管加热器 (SampleTube)

#### 功能特性
- 支持样气湿度、温度、流速实时监测
- 支持加热管实际温度和设置温度的读取与控制
- 支持风机功率、加热带功率监测（W）
- 支持设备地址配置（0-255）
- 支持校准状态设置
- 每5秒自动读取一次数据

#### Modbus协议详细说明

**通信参数：**
- 波特率：9600
- 数据位：8
- 停止位：1
- 校验位：无
- 协议：Modbus RTU
- 功能码：03（读保持寄存器）、06（写单个寄存器）

**寄存器地址映射表：**

| 地址(Hex) | 地址(Dec) | 内容 | 数据类型 | 读写权限 | 缩放因子 | 功能码 |
|-----------|-----------|------|----------|----------|----------|--------|
| 0x0000 | 0 | 样气湿度 | Int16 | 只读 | ×10 | 03 |
| 0x0001 | 1 | 样气温度 | Int16 | 只读 | ×10 | 03 |
| 0x0002 | 2 | 校准状态 | Int16 | 读写 | - | 03/06 |
| 0x0003 | 3 | 保留（无意义） | Int16 | 只读 | - | 03 |
| 0x0004 | 4 | 设备地址 | Int16 | 读写 | - | 03/06 |
| 0x0005 | 5 | 样气流速 | Int16 | 只读 | ×10 | 03 |
| 0x0006 | 6 | 加热管实际温度 | Int16 | 读写 | ×10 | 03/06 |
| 0x0007 | 7 | 风机功率 | Int16 | 只读 | ×10 | 03 |
| 0x0008 | 8 | 加热带功率 | Int16 | 只读 | ×10 | 03 |
| 0x0009 | 9 | 未使用 | Int16 | 只读 | - | 03 |
| 0x000A | 10 | 加热管设置温度 | Int16 | 读写 | ×10 | 03/06 |

**数据说明：**
- 温度值的负值以补码形式发送
- 所有温度、湿度、功率、流速值均为实际值的10倍
- 例如：温度25.5°C在寄存器中为255
- 设备地址范围：0-255

#### 串口调试指令示例

**1. 读取温湿度（地址0-1，共2个寄存器）**
```
发送：00 03 00 00 00 02 DA C5
返回：00 03 04 01 5E 01 9E 0A E5
解析：
  - 湿度：0x015E = 350 → 35.0%
  - 温度：0x019E = 414 → 41.4°C
```

**2. 读取设备地址（地址4）**
```
发送：00 03 00 04 00 01 C4 1A
返回：00 03 02 00 01 85 84
解析：设备地址 = 1
```

**3. 修改设备地址为5**
```
发送：00 06 00 04 00 05 89 DB
返回：00 06 00 04 00 05 89 DB（回显）
说明：修改地址后需要使用新地址重新连接
```

**4. 读取加热管温度（地址6和10）**
```
发送：00 03 00 06 00 02 64 1B
返回：00 03 04 01 C2 01 C2 XX XX
解析：
  - 实际温度：0x01C2 = 450 → 45.0°C
  - 设置温度：0x01C2 = 450 → 45.0°C
```

**5. 设置加热管目标温度为50.0°C**
```
发送：00 06 00 0A 01 F4 2C 48
说明：50.0°C × 10 = 500 = 0x01F4
返回：00 06 00 0A 01 F4 2C 48（回显）
```

**6. 读取功率参数（地址7-8）**
```
发送：00 03 00 07 00 02 35 DB
返回：00 03 04 00 32 00 64 XX XX
解析：
  - 风机功率：0x0032 = 50 → 5.0W
  - 加热带功率：0x0064 = 100 → 10.0W
```

**7. 将加热器置为校准状态**
```
发送：00 06 00 02 00 00 29 DB
返回：00 06 00 02 00 00 29 DB（回显）
```

**8. 读取所有寄存器（0-10，共11个）**
```
发送：00 03 00 00 00 0B 05 D4
返回：00 03 16 [22字节数据] CRC_L CRC_H
说明：一次读取所有11个寄存器的值
```

#### 属性列表

| 属性ID                     | 中文名称 | 寄存器地址 | 单位 | 读写 |
|--------------------------|----------|------------|------|------|
| humidity                 | 样气湿度 | 0 | % | R |
| sample_gas_temperature   | 样气温度 | 1 | °C | R |
| calibration_status       | 校准状态 | 2 | - | R/W |
| reserved_3               | 保留字段 | 3 | - | R |
| device_address           | 设备地址 | 4 | - | R/W |
| gas_flow_rate            | 样气流速 | 5 | L/min | R |
| heating_tube_actual_temp | 加热管实际温度 | 6 | °C | R/W |
| fan_power                | 风机功率 | 7 | W | R |
| heating_belt_power       | 加热带功率 | 8 | W | R |
| reserved_9               | 保留字段 | 9 | - | R |
| heating_tube_target_temp | 加热管设置温度 | 10 | °C | R/W |

#### PID调节参考
初始设置（在没有风机的情况下能够控制湿度在35%±2）：

| 参数 | 值 | 单位 | 说明 |
|------|-----|------|------|
| 湿度设定 | 35 | % | 最终想要达到的样气湿度 |
| 温度设定 | 45 | °C | 采样管加热温度的上下限值（下限为温度设定值-15，上限为温度设定值+5） |
| 比例 | 80 | - | PID调节器的比例输出 |
| 积分 | 160 | 100S | PID调节器的积分时间，单位为（100S），如160表示16000S |
| 微分 | 10 | 10S | PID调节器的微分时间，单位为（10S），如10表示100S |
| Kt | 3 | S | PID调节器的采样周期 |
| To | 1 | S | PID的PWM输出周期 |

## 设备生命周期管理

### 1. 配置加载
设备配置在系统启动时自动加载:
1. `SaimosenIntegration.onInit()` 方法读取 `ecat-config.yml` 配置文件
2. 遍历配置中的设备列表，为每个设备调用 `createDevice()` 方法
3. 验证设备配置是否符合 `ConfigDefinition` 规范
4. 根据设备类别创建对应的设备实例

### 2. 设备启动
设备启动流程:
1. 系统调用 `SaimosenIntegration.onStart()` 方法
2. 遍历所有已创建的设备实例
3. 调用每个设备的 `start()` 方法开始设备通信和数据采集

### 3. 设备暂停
当系统需要暂停设备操作时:
1. 系统调用 `SaimosenIntegration.onPause()` 方法
2. 遍历所有设备实例
3. 调用每个设备的 `stop()` 方法停止数据采集任务

### 4. 资源释放
当系统关闭或模块卸载时:
1. 系统调用 `SaimosenIntegration.onRelease()` 方法
2. 遍历所有设备实例
3. 调用每个设备的 `release()` 方法释放占用的资源
4. 清空设备列表

## 添加新设备支持

要添加对新设备的支持，请按照以下步骤操作:

### 1. 创建设备类
创建新的设备类，继承 `SmsDeviceBase` 类:

```java
public class NewDevice extends SmsDeviceBase {
    public NewDevice(Map<String, Object> config) {
        super(config);
    }
    
    @Override
    public void init() {
        super.init();
        // 初始化设备特定属性
        createAttributes();
    }
    
    @Override
    public void start() {
        // 启动设备任务
    }
    
    @Override
    public void stop() {
        // 停止设备任务
    }
    
    @Override
    public void release() {
        // 释放设备资源
        super.release();
    }
}
```

### 2. 更新设备配置定义
在 `SaimosenIntegration.getDeviceConfigDefinition()` 方法中，如果需要添加新的设备类别，更新设备类别的枚举值:

```java
Set<String> classValidValues = new HashSet<>(Arrays.asList(
    DeviceClasses.AIR_MONITOR_CALIBRATOR.getClassName(),
    DeviceClasses.AIR_MONITOR_QC.getClassName(),
    DeviceClasses.POWER_SUPPLY_STABILIZER.getClassName(),
    "new.device.class.name"  // 添加新的设备类别
));
```

### 3. 更新设备创建逻辑
在 `SaimosenIntegration.createDevice()` 方法中添加新设备的创建逻辑:

```java
@Override
public boolean createDevice(Map<String, Object> config) {
    switch(dc){
        // ... existing cases ...
        case NEW_DEVICE_TYPE:
            device = new NewDevice(config);
            break;
        default:
            return false;
    }
}
```

### 4. 添加设备配置
在 `ecat-config.yml` 文件中添加新设备的配置:

```
devices:
  - id: new-device-01
    name: 新设备
    class: new.device.class.name
    # 其他配置项
    comm_settings:
      # 通信设置
```

### 5. 实现设备属性（可选）
如果设备需要特殊的属性控制，可以创建自定义属性类，继承相应的属性基类，如 `ModbusFloatAttribute` 或 `CommandAttribute`。

## 开发说明

### 主要类结构

- `SaimosenIntegration`: 集成模块主类，负责设备初始化和管理
- `SmsDeviceBase`: 所有赛默森设备的基类
- `CalibratorDevice`: 校准仪设备实现
- `QCDevice`: 质控仪设备实现
- `SmartPowerStabilizer`: 智能电力稳压器实现
- `ParticulateZeroChecker`: 颗粒物零点检查设备实现
- `O3Device`: O3臭氧分析仪设备实现
- `NO2Device`: NO2氮氧化物分析仪设备实现
- `SampleTube`: 采样管加热器设备实现

### 属性类

- `Modbus10XShortAttribute`: 处理值为实际值10倍的short类型数据
- `CalibratorGasSelectAttribute`: 校准器气体选择属性
- `ParticulateZeroCheckerCommandAttribute`: 颗粒物零点检查控制命令属性
- `ModbusScalableFloatSRAttribute`: 可缩放的浮点型Modbus寄存器属性
- `ModbusFloatAttribute`: 标准浮点型Modbus寄存器属性
- `NumericAttribute`: 数值型属性
- `AQAttribute`: 空气质量属性

## 部署说明

1. 确保串口设备正确连接
2. 根据实际环境修改配置文件中的串口参数
3. 在Linux系统中，可能需要将用户添加到dialout组以获得串口访问权限:
   ```bash
   sudo usermod -a -G dialout $USER
   ```
4. 启动系统，设备将自动连接和初始化

## 注意事项

1. 不同操作系统下串口设备命名不同:
   - Windows: COM1, COM2, ...
   - Linux: /dev/ttyUSB0, /dev/ttyS0, ...

2. 确保串口设备权限正确设置

3. 部分设备可能需要特定的接线方式，请参考设备手册

4. 在生产环境中，建议关闭调试模式以提高性能

## 协议声明
1. 核心依赖：本插件基于 **ECAT Core**（Apache License 2.0）开发，Core 项目地址：https://github.com/ecat-project/ecat-core。
2. 插件自身：本插件的源代码采用 [Apache License 2.0] 授权。
3. 合规说明：使用本插件需遵守 ECAT Core 的 Apache 2.0 协议规则，若复用 ECAT Core 代码片段，需保留原版权声明。

### 许可证获取
- ECAT Core 完整许可证：https://github.com/ecat-project/ecat-core/blob/main/LICENSE
- 本插件许可证：./LICENSE

