
# DeepGuard.jar
---
# 这个项目80%由AI生成，请注意辨别

## 功能

- **传统反作弊**
- **AI 智能检测**：通过分析玩家视角旋转与放置方块的协同模式，识别机械式搭路外挂
- **自动定时扫描**：每 5 分钟对所有在线玩家进行静默 AI 分析，发现可疑行为自动通知或处罚
- **举报推理**：管理员使用 `/ac report <玩家>` 手动触发 AI 分析，输出详细概率
- **数据录制**：可独立运行的录制插件，自动采集玩家行为数据用于训练新模型

---

命令	说明
/ac report <玩家>	分析该玩家最近 30 秒行为，返回正常/作弊概率
/ac reload	重载配置

---

训练自定义模型
如果您希望用自己的数据训练更精准的模型：

1. 采集数据
使用录制插件 recorder-plugin：
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

本插件仅供学习与研究使用。请遵守服务器所在地区法规及 Minecraft EULA。
