# DeepGuard


> 这个项目在 AI 能力的基础上，参照著名开源反作弊 **Grim** 的架构进行重构。
> 项目大部分由 AI 生成，请注意辨别。

## 支持平台

| 平台 | 模块 | 说明 |
| --- | --- | --- |
| Paper | `AntiCheat/` `recorder-plugin/` | 主插件 + 录制插件，Maven 构建 |
| Fabric（Minecraft 26.2） | `fabric/` | 服务端模组，Gradle/Loom 构建 |
| NeoForge（Minecraft 26.2） | `neoforge/` | 服务端模组，Gradle/ModDevGradle 构建 |

> **Fabric / NeoForge 版目前是骨架**：工具链、构建与发布链路已经打通并随 Release 出包，
> 但检测逻辑仍在从 Paper 版移植中，装上后暂时只打印一行加载日志。
> 功能完整的是 Paper 版。

## 功能

- **传统移动检测**
  - 飞行 / 悬空检测
  - 异常船速检测
  - 水平速度检测（支持 PlaceholderAPI 属性加成、传送 / 击退宽限）
- **AI 智能检测**：通过分析玩家视角旋转与放置方块的协同模式，识别机械式搭路外挂
- **自动定时扫描**：每 60 秒对所有在线玩家进行静默 AI 分析，发现可疑行为自动通知或处罚
- **举报推理**：管理员使用 `/ac report <玩家>` 手动触发 AI 分析，输出详细概率
- **数据录制**：可独立运行的录制插件，自动采集玩家行为数据用于训练新模型
- **自动更新**：启动时检查 GitHub 新版本并通知管理员；自动下载最新 AI 模型，无需手动替换

---

## 命令

| 命令 | 说明 |
| --- | --- |
| `/ac report <玩家>` | 分析该玩家最近 30 秒行为，返回正常/作弊概率 |
| `/ac lookup <封禁码>` | 查看违规记录详情 |
| `/ac update` | 手动检查更新并同步最新 AI 模型 |
| `/ac reload` | 重载配置 |

权限节点：`deepguard.admin`（管理员）、`deepguard.bypass`（豁免检测）。

---

## 版本检测与模型自动更新

插件启动时（以及 `/ac update`）会：

1. 查询 GitHub 最新 Release，发现新版本时在控制台与游戏内通知管理员；
2. 自动下载 latest release 中的 `scaffold_detector.onnx` 到数据目录并重载——管理员无需手动下载模型。

模型加载优先级：数据目录已有模型 → 从 jar 内置模型解压 → 自动下载。配置文件 `config.yml` 的 `updates` 段可开关或自定义 URL：

```yaml
updates:
  enabled: true
  version-check: true
  auto-download-model: true
  version-url: "https://api.github.com/repos/llsgllsg/Minecraft_AntiCheatAI/releases/latest"
  model-url: "https://github.com/llsgllsg/Minecraft_AntiCheatAI/releases/latest/download/scaffold_detector.onnx"
```

数据与模型是分开的两步工作流：**导入录制数据** 负责 jsonl → `python/X.npy` / `python/y.npy`，
**重新训练模型** 负责特征集 → ONNX 模型。

重新训练模型：在 GitHub Actions 的 **Actions → 重新训练模型** 手动触发，会用仓库内
已提交的特征数据（`python/X.npy` / `python/y.npy`）重训并把新模型提交回仓库、
上传产物。打 `v*` tag 或手动触发 **发布 Release** 工作流即可让所有服务器自动同步到
最新模型。

---

## 架构（参照 Grim 重构）

检测逻辑不再堆在主类里，而是按 Grim 的分层拆解：

| 组件 | 对应 Grim | 职责 |
| --- | --- | --- |
| `AntiCheatPlugin` | GrimPlugin | 生命周期、调度、命令、事件分发 |
| `TrackedPlayer` | GrimPlayer | 每玩家数据对象：录制器、移动处理器、豁免、各检查状态 |
| `check.MovementProcessor` | Processors | 把原始移动事件换算成运动数据（速度等），维护位置基线 |
| `check.CheckData` | CheckData | 一次移动事件的计算快照，供各检查只读共享 |
| `check.FlyCheck` / `BoatSpeedCheck` / `SpeedCheck` | AbstractCheck | 每项独立检测，只做判定 |
| `PunishmentManager` | PunishmentManager | 累进处罚、封禁码、违规记录落盘 |
| `ExemptionType` | ExemptionType | 传送 / 击退宽限期豁免 |

AI 检测路径完整保留：`BehaviorRecorder`（每 tick 录制）→ `BehaviorImageBuilder`
（17 通道特征图）→ `AIInferenceEngine`（ONNX 推理）→ 阈值判定 / 处罚。

每 tick 记录的字段：`pitch` `yaw` `posX/Y/Z` `placing` `sprinting` `jumping`
`onGround` `moveSpeed` `vertSpeed`，以及 `inVehicle`（是否在载具）、`gliding`
（是否鞘翅滑翔）、`vehicleType`（载具具体类型，仅供人工分析）。

17 个特征通道：

```
 0 pitch 归一化        9 冲刺+放置 二值
 1 yaw  归一化        10 近20tick放置数
 2 水平速度           11 放置节奏规律性(间隔方差)
 3 垂直速度           12 是否在载具 二值
 4 placing 二值       13 是否鞘翅滑翔 二值
 5 sprinting 二值     14 Δx 每tick水平位移(带符号)
 6 jumping 二值       15 Δy 每tick垂直位移(带符号)
 7 |Δpitch| 归一化    16 Δz 每tick水平位移(带符号)
 8 快速转头(>25°)二值
```

通道 14/15/16 由相邻两 tick 的坐标差算出，因此对加字段之前录的旧数据同样有效；
通道 12/13 依赖录制时的 `inVehicle` / `gliding` 字段，旧数据恒为 0。

**特征一致性**：`BehaviorImageBuilder.java` 与 `python/features.py` 的编码一致到
float32 精度（实测最大偏差 1.19e-07，约 1 ULP —— Java 用 float32 算、Python 用
float64 算完再存 float32）。对模型输出的影响远低于阈值，实测 95 份录制里
0.5 / 0.85 两个阈值上零次判定翻转。修改特征时请同时更新两端，
并运行 `python/test_features.py` 回归。

**模型通道数向下兼容**：新增通道一律**追加在末尾**，所以前 12 个通道与旧版编码
逐位一致（回归测试 `test_new_channels_do_not_affect_old_ones` 守着这个不变量）。
`AIInferenceEngine` 加载时会读取模型输入的通道数：

- 模型通道数**少于**特征通道数（例如 12 通道的旧模型）→ 只取前 N 个通道，
  **旧模型照常可用**，升级插件不必立刻换模型；
- 模型通道数**多于**特征通道数 → 特征不够用，拒绝加载、停用 AI 并打印明确日志
  （不会静默失效，也不会每次推理刷栈）。

---

## 构建

仓库里并存两套构建系统，各管各的模块，互不干扰：

**Paper 插件（Maven，JDK 21）**

```bash
mvn clean package
```

根 `pom.xml` 聚合 `AntiCheat`（主插件）与 `recorder-plugin`（录制插件）两个模块，
一次构建产出 `AntiCheat/target/DeepGuard.jar` 与 `recorder-plugin/target/BehaviorRecorder.jar`。

**Fabric / NeoForge 模组（Gradle，JDK 25）**

```bash
cd fabric   && ./gradlew build     # -> fabric/build/libs/DeepGuard-Fabric-<版本>.jar
cd neoforge && ./gradlew build     # -> neoforge/build/libs/DeepGuard-NeoForge-<版本>.jar
```

两边都是**独立的 Gradle 根**（各有自己的 `settings.gradle` 与 wrapper），
不参与根 Maven 构建。Minecraft 26.2 的字节码目标是 Java 25，所以这两个模块需要 JDK 25，
与 Maven 侧的 21 并存。

两个模块共用 `core/src/main/java`（平台无关的行为录制与特征编码），
它们都把这个目录加进自己的源码根 —— Paper / Fabric / NeoForge / `python/features.py`
四方的特征编码因此不可能悄悄漂移。

**版本号只有一个真源**：根 `pom.xml`。`fabric/build.gradle` 与 `neoforge/build.gradle`
都在配置阶段直接读它，`gradle.properties` 里只放工具链版本（Minecraft / Loader / 插件），
`release.yml` 不需要为它们单独同步版本号（CI 里有断言守着这条）。

GitHub Actions 会在每次 push / PR 自动构建三端并运行 Python 特征测试。

---

## 训练自定义模型（仅使用真实录制数据）

本仓库**不包含**任何模拟/假数据。请使用录制插件在服务器上采集真实行为数据。

### 1. 采集数据

使用录制插件 `recorder-plugin`：

- 自动录制所有玩家正常行为（标签 0）
- 使用 `/record cheat` / `/record normal` 手动采集作弊 / 正常样本（标签 1 / 0）
- 数据保存在 `plugins/BehaviorRecorder/recordings/`

### 2. 预处理数据（jsonl → 特征集）

把 jsonl 按标签放进 `python/recordings/normal/` 与 `python/recordings/cheat/`，
提交（网页拖拽上传也行）即会自动触发 **导入录制数据** 工作流，把它们编码成
(12, 128) 特征图并增量合并进 `python/X.npy` / `python/y.npy`，再把特征集提交回仓库。
也可以在 Actions 页面手动触发。

合并是**幂等**的：已处理过的 jsonl 会被去重跳过，重复上传不会把样本算两次。
少于 100 行的文件按管线约定跳过。详见 `python/recordings/README.md`。

本地环境也可以用同一个脚本：

```bash
pip install -r python/requirements.txt
python python/merge_recordings.py python/recordings --data python
```

> `prepare_data.py` 是**全量重建**（从原始 jsonl 重新生成整个特征集）。
> 仓库内更早的样本没有保留原始 jsonl，用它会丢掉那部分数据，
> 日常加数据请走上面的增量合并。

### 3. 训练模型

```bash
python python/train_model.py
```

训练完成后生成 `scaffold_detector.onnx`，替换到服务器 `plugins/AntiCheat/` 下并重载。
离线推理验证：

```bash
python python/predict_one.py 某个行为文件.jsonl --model scaffold_detector.onnx
```

---

本插件仅供学习与研究使用。请遵守服务器所在地区法规及 Minecraft EULA。
