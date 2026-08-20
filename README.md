# DeepGuard

[![构建状态](https://github.com/llsgllsg/Minecraft_AntiCheatAI/actions/workflows/build.yml/badge.svg)](https://github.com/llsgllsg/Minecraft_AntiCheatAI/actions/workflows/build.yml)
[![Python 管线](https://github.com/llsgllsg/Minecraft_AntiCheatAI/actions/workflows/python.yml/badge.svg)](https://github.com/llsgllsg/Minecraft_AntiCheatAI/actions/workflows/python.yml)

> 这个项目在 AI 能力的基础上，参照著名开源反作弊 **Grim** 的架构进行重构。
> 项目大部分由 AI 生成，请注意辨别。

## 功能

- **传统移动检测**（Grim 式架构）：
  - 飞行 / 悬空检测
  - 异常船速检测
  - 水平速度检测（支持 PlaceholderAPI 属性加成、传送 / 击退宽限）
- **AI 智能检测**：通过分析玩家视角旋转与放置方块的协同模式，识别机械式搭路外挂
- **自动定时扫描**：每 60 秒对所有在线玩家进行静默 AI 分析，发现可疑行为自动通知或处罚
- **举报推理**：管理员使用 `/ac report <玩家>` 手动触发 AI 分析，输出详细概率
- **数据录制**：可独立运行的录制插件，自动采集玩家行为数据用于训练新模型

---

## 命令

| 命令 | 说明 |
| --- | --- |
| `/ac report <玩家>` | 分析该玩家最近 30 秒行为，返回正常/作弊概率 |
| `/ac lookup <封禁码>` | 查看违规记录详情 |
| `/ac reload` | 重载配置 |

权限节点：`deepguard.admin`（管理员）、`deepguard.bypass`（豁免检测）。

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
（12 通道特征图）→ `AIInferenceEngine`（ONNX 推理）→ 阈值判定 / 处罚。

**特征一致性保证**：`BehaviorImageBuilder.java` 与 `python/features.py` 的
特征编码逐位一致（含通道 11 放置节奏规律性），并有跨语言一致性验证。
修改特征时请同时更新两端，并运行 `python/test_features.py` 回归。

---

## 构建

```bash
mvn clean package
```

根 `pom.xml` 聚合 `AntiCheat`（主插件）与 `recorder-plugin`（录制插件）两个模块，
一次构建产出 `AntiCheat/target/DeepGuard.jar` 与 `recorder-plugin/target/BehaviorRecorder.jar`。

GitHub Actions 会在每次 push / PR 自动构建并运行 Python 特征测试。

---

## 训练自定义模型（仅使用真实录制数据）

本仓库**不包含**任何模拟/假数据。请使用录制插件在服务器上采集真实行为数据。

### 1. 采集数据

使用录制插件 `recorder-plugin`：

- 自动录制所有玩家正常行为（标签 0）
- 使用 `/record cheat` / `/record normal` 手动采集作弊 / 正常样本（标签 1 / 0）
- 数据保存在 `plugins/BehaviorRecorder/recordings/`

### 2. 预处理数据

```bash
pip install -r python/requirements.txt
python python/prepare_data.py 数据目录/ --out ./
# 数据目录下应包含 normal/ 与 cheat/ 两个子文件夹
```

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
