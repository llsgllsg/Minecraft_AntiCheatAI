import json
import numpy as np
from pathlib import Path
import sys
from tqdm import tqdm

def build_behavior_image_violent(ticks, time_steps=128):
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
                    # 兼容 ts 和 timestamp 两种字段名
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

def convert_data_dir(data_root, output_prefix="X", output_y="y", time_steps=128):
    data_root = Path(data_root)
    X_list, y_list = [], []
    for label, subdir in [(0, 'normal'), (1, 'cheat')]:
        subdir_path = data_root / subdir
        if not subdir_path.exists():
            print(f"警告: 目录 {subdir_path} 不存在，跳过。")
            continue
        jsonl_files = list(subdir_path.glob("*.jsonl"))
        print(f"处理 {subdir} 标签，共 {len(jsonl_files)} 个文件")
        for f in tqdm(jsonl_files, desc=f"Label {label}"):
            with open(f, 'r') as fp:
                lines = fp.readlines()
            if len(lines) == 0:
                continue
            ticks = [json.loads(line) for line in lines]
            if len(ticks) < 100:
                continue
            img = build_behavior_image_violent(ticks, time_steps)
            X_list.append(img)
            y_list.append(label)

    X = np.array(X_list, dtype=np.float32)
    y = np.array(y_list, dtype=np.int32)
    print(f"\n总共收集到 {len(X)} 个样本，正常: {np.sum(y==0)}，作弊: {np.sum(y==1)}")
    np.save(f"{output_prefix}.npy", X)
    np.save(f"{output_y}.npy", y)
    print(f"已保存 {output_prefix}.npy 和 {output_y}.npy")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python prepare_data.py <数据根目录>")
        print("数据根目录应包含 'normal' 和 'cheat' 两个子目录。")
        sys.exit(1)
    convert_data_dir(sys.argv[1])