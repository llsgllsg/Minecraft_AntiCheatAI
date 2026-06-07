import json
import sys
import numpy as np
import torch
import torch.nn as nn

# ================= 模型定义（必须与训练时完全一致） =================
class ScaffoldDetector(nn.Module):
    def __init__(self, in_channels=12, num_classes=2):
        super().__init__()
        self.conv1 = nn.Conv1d(in_channels, 32, 5, padding=2)
        self.bn1 = nn.BatchNorm1d(32)
        self.pool1 = nn.MaxPool1d(2)
        self.conv2 = nn.Conv1d(32, 64, 5, padding=2)
        self.bn2 = nn.BatchNorm1d(64)
        self.pool2 = nn.MaxPool1d(2)
        self.conv3 = nn.Conv1d(64, 128, 3, padding=1)
        self.bn3 = nn.BatchNorm1d(128)
        self.pool3 = nn.AdaptiveAvgPool1d(1)
        self.fc1 = nn.Linear(128, 32)
        self.dropout = nn.Dropout(0.3)
        self.fc2 = nn.Linear(32, num_classes)

    def forward(self, x):
        x = self.pool1(torch.relu(self.bn1(self.conv1(x))))
        x = self.pool2(torch.relu(self.bn2(self.conv2(x))))
        x = self.pool3(torch.relu(self.bn3(self.conv3(x))))
        x = x.squeeze(-1)
        x = torch.relu(self.fc1(x))
        x = self.dropout(x)
        x = self.fc2(x)
        return x

# ================= 特征工程（与训练时完全一致） =================
def build_behavior_image(ticks, time_steps=128):
    channels = 12
    img = np.zeros((channels, time_steps), dtype=np.float32)
    if len(ticks) < time_steps:
        offset = time_steps - len(ticks)
    else:
        offset = 0
        ticks = ticks[-time_steps:]

    for i, tick in enumerate(ticks):
        idx = offset + i
        if idx >= time_steps:
            break
        pitch = tick.get('pitch', 0.0)
        yaw = tick.get('yaw', 0.0)
        move_speed = tick.get('moveSpeed', 0.0)
        vert_speed = tick.get('vertSpeed', 0.0)
        placing = tick.get('placing', False)
        sprinting = tick.get('sprinting', False)
        jumping = tick.get('jumping', False)
        on_ground = tick.get('onGround', False)

        if i > 0:
            prev_pitch = ticks[i-1].get('pitch', pitch)
            pitch_change = abs(pitch - prev_pitch)
        else:
            pitch_change = 0.0

        img[0, idx] = (pitch + 90) / 180.0
        img[1, idx] = (yaw + 180) / 360.0
        img[2, idx] = min(move_speed / 10.0, 1.0)
        img[3, idx] = (vert_speed + 1) / 2.0
        img[4, idx] = 1.0 if placing else 0.0
        img[5, idx] = 1.0 if sprinting else 0.0
        img[6, idx] = 1.0 if jumping else 0.0
        img[7, idx] = min(pitch_change / 90.0, 1.0)
        img[8, idx] = 1.0 if pitch_change / 0.05 > 500 else 0.0
        img[9, idx] = 1.0 if (sprinting and placing) else 0.0
        start_idx = max(0, i - 19)
        place_count = sum(1 for t in ticks[start_idx:i+1] if t.get('placing', False))
        img[10, idx] = min(place_count / 10.0, 1.0)
        if i >= 5:
            intervals = []
            last_time = None
            for t in ticks[max(0, i-19):i+1]:
                if t.get('placing', False):
                    ts_val = t.get('ts') or t.get('timestamp')
                    if last_time is not None and ts_val is not None:
                        intervals.append(ts_val - last_time)
                    last_time = ts_val
            if len(intervals) >= 5:
                variance = np.var(intervals)
                img[11, idx] = max(0, 1.0 - variance / 1000.0)
        else:
            img[11, idx] = 0.0
    return img

# ================= 主程序 =================
if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python predict_one.py <jsonl文件路径>")
        sys.exit(1)

    file_path = sys.argv[1]

    # 读取 JSONL 数据
    with open(file_path, 'r') as f:
        lines = f.readlines()
    ticks = [json.loads(line) for line in lines]

    if len(ticks) < 100:
        print(f"警告: 数据只有 {len(ticks)} 行，少于100行，模型可能不准确")

    # 构造输入
    img = build_behavior_image(ticks)  # shape (12, 128)
    input_tensor = torch.tensor(img).unsqueeze(0)  # 增加 batch 维度 -> (1, 12, 128)

    # 加载模型
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = ScaffoldDetector().to(device)
    model.load_state_dict(torch.load("best_model.pth", map_location=device))
    model.eval()

    # 推理
    with torch.no_grad():
        output = model(input_tensor.to(device))
        probs = torch.softmax(output, dim=1).cpu().numpy()[0]

    normal_prob = probs[0]
    cheat_prob = probs[1]
    prediction = "作弊" if cheat_prob >= 0.5 else "正常"

    print(f"文件: {file_path}")
    print(f"正常概率: {normal_prob:.4f} ({normal_prob*100:.1f}%)")
    print(f"作弊概率: {cheat_prob:.4f} ({cheat_prob*100:.1f}%)")
    print(f"判定结果: {prediction}")