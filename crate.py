import json
import os
import random
import time

def generate_cheat_file(output_dir, file_id, length=128):
    """
    生成一个模拟暴力 Scaffold 的 jsonl 文件。
    pitch 在 75-85 度（低头放）和 0-10 度（抬头）之间快速切换，
    伴随 placing 在低头时为 true。
    """
    ticks = []
    base_time = int(time.time() * 1000)
    state = "look_down"
    pitch_down = 80.0
    pitch_up = 5.0
    yaw = 0.0
    sprinting = True
    for i in range(length):
        ts = base_time + i * 50
        if state == "look_down":
            pitch = pitch_down + random.uniform(-3, 3)
            placing = False
            state = "place"
        elif state == "place":
            pitch = pitch_down + random.uniform(-3, 3)
            placing = True
            state = "look_up"
        elif state == "look_up":
            pitch = pitch_up + random.uniform(-3, 3)
            placing = False
            state = "wait"
        else:
            pitch = pitch_up + random.uniform(-3, 3)
            placing = False
            state = "look_down"

        tick = {
            "ts": ts,
            "pitch": round(pitch, 2),
            "yaw": round(yaw, 2),
            "posX": 0.0,
            "posY": 64.0,
            "posZ": 0.0,
            "placing": placing,
            "sprinting": sprinting,
            "jumping": False,
            "onGround": True,
            "moveSpeed": 4.3 if sprinting else 0.0,
            "vertSpeed": 0.0
        }
        ticks.append(tick)

    os.makedirs(output_dir, exist_ok=True)
    filepath = os.path.join(output_dir, f"fake_cheat_{file_id}_1.jsonl")
    with open(filepath, 'w') as f:
        for tick in ticks:
            f.write(json.dumps(tick) + '\n')

if __name__ == "__main__":
    output = "fake_cheat_data"
    print(f"生成 100 个假作弊文件到 {output}/")
    for i in range(100):
        generate_cheat_file(output, i)
    print("完成。")