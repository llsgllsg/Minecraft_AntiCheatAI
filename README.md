# AntiCheatAI

基于 **CNN 行为序列分析** 的 Minecraft 反作弊插件，专为 **Paper 1.21.1** 设计。  
结合 **传统规则检测** 与 **AI 深度学习推理**，精准识别暴力外挂。

---

## 功能

- **传统反作弊**
- **AI 智能检测**：通过分析玩家视角旋转与放置方块的协同模式，识别机械式搭路外挂
- **自动定时扫描**：每 5 分钟对所有在线玩家进行静默 AI 分析，发现可疑行为自动通知或处罚
- **举报推理**：管理员使用 `/ac report <玩家>` 手动触发 AI 分析，输出详细概率
- **数据录制**：可独立运行的录制插件，自动采集玩家行为数据用于训练新模型

---

## 快速开始

### 1. 部署反作弊插件

```bash
# 编译
cd AntiCheatAI
mvn -pl anticheat-plugin clean package

# 部署
cp anticheat-plugin/target/AntiCheat.jar <服务器目录>/plugins/
mkdir <服务器目录>/plugins/AntiCheat
cp models/scaffold_detector.onnx <服务器目录>/plugins/AntiCheat/
# 重启服务器
'''

---

命令	说明
/ac report <玩家>	分析该玩家最近 30 秒行为，返回正常/作弊概率
/ac reload	重载配置

---

编辑 plugins/AntiCheat/config.yml：

punish-command：处罚命令，支持 %player% 占位符

ai.auto-punish-threshold：作弊概率 ≥ 此值时自动处罚（默认 0.85）

ai.alert-threshold：作弊概率 ≥ 此值时仅通知管理员（默认 0.5）

movement.*：传统规则开关

---

训练自定义模型
如果您希望用自己的数据训练更精准的模型：

1. 采集数据
使用录制插件 recorder-plugin：

bash
mvn -pl recorder-plugin clean package
cp recorder-plugin/target/BehaviorRecorder.jar <服务器目录>/plugins/
自动录制所有玩家正常行为（标签 0）

使用 /record cheat / /record normal 手动采集作弊/正常样本（标签 1 / 0）

数据保存在 plugins/BehaviorRecorder/recordings/

2. 预处理数据
bash
pip install -r python/requirements.txt
python python/prepare_data.py data/   # data/ 下包含 normal/ 和 cheat/ 文件夹
3. 训练模型
bash
python python/train_model.py
训练完成后会生成 scaffold_detector.onnx，替换到服务器 plugins/AntiCheat/ 下并重载。

---

技术细节
AI 模型：轻量 1D-CNN，输入 12 通道 × 128 时间步（6.4秒），参数约 10 万

推理引擎：ONNX Runtime，CPU 推理 < 20ms，几乎不消耗服务器资源

特征工程：俯仰角、偏航角、速度、跳跃、疾跑、放置密度、机械式节奏等 12 维特征

训练数据：模拟暴力 Scaffold + 真实外挂样本，结合人工/自动清洗

本插件仅供学习与研究使用。请遵守服务器所在地区法规及 Minecraft EULA。
