"""共享特征工程 —— DeepGuard 的唯一权威实现。

Java 运行时端 (AntiCheat/src/main/java/com/gfish/anticheat/BehaviorImageBuilder.java)
必须与此文件逐位一致，否则训练 / 离线预测 / 在线检测三者会产生系统性偏差。

约定：
- ticks: jsonl 中解析出的 dict 列表，按时间先后排序。
- 输出: float32 numpy 数组，形状 (12, 128)：
    0  pitch 归一化      7  |Δpitch| 归一化
    1  yaw  归一化       8  快速转头(>25°)二值
    2  水平速度          9  冲刺+放置 二值
    3  垂直速度          10 近20tick放置数
    4  placing 二值      11 放置节奏规律性(间隔方差)
    5  sprinting 二值
    6  jumping 二值
"""

import numpy as np

CHANNELS = 12
TIME_STEPS = 128

# Java 端 (BehaviorImageBuilder) 的滑动窗口大小 —— 放置统计 / 间隔统计共用
PLACE_WINDOW = 20
# 间隔方差阈值：方差越小(节奏越规律)该通道越接近 1
INTERVAL_VARIANCE_DIVISOR = 1000.0


def build_behavior_image(ticks, time_steps=TIME_STEPS):
    """把行为 tick 序列编码成 (CHANNELS, time_steps) 特征图。

    与 Java 实现保持一致的要点：
    1. 只取末尾 min(len, time_steps) 个 tick，靠右对齐填充。
    2. 所有滑动窗口 (channel 10/11) 都基于"末尾窗口"内的下标计算。
    """
    img = np.zeros((CHANNELS, time_steps), dtype=np.float32)
    n = len(ticks)
    if n == 0:
        return img

    length = min(n, time_steps)
    offset = time_steps - length

    for i in range(length):
        idx = offset + i
        t = ticks[n - length + i]
        pitch = t.get('pitch', 0.0)
        yaw = t.get('yaw', 0.0)
        move_speed = t.get('moveSpeed', 0.0)
        vert_speed = t.get('vertSpeed', 0.0)
        placing = bool(t.get('placing', False))
        sprinting = bool(t.get('sprinting', False))
        jumping = bool(t.get('jumping', False))

        pitch_change = 0.0
        if i > 0:
            prev = ticks[n - length + i - 1]
            pitch_change = abs(pitch - prev.get('pitch', pitch))

        img[0, idx] = (pitch + 90.0) / 180.0
        img[1, idx] = (yaw + 180.0) / 360.0
        img[2, idx] = min(move_speed / 10.0, 1.0)
        img[3, idx] = (vert_speed + 1.0) / 2.0
        img[4, idx] = 1.0 if placing else 0.0
        img[5, idx] = 1.0 if sprinting else 0.0
        img[6, idx] = 1.0 if jumping else 0.0
        img[7, idx] = min(pitch_change / 90.0, 1.0)
        img[8, idx] = 1.0 if (pitch_change / 0.05) > 500 else 0.0
        img[9, idx] = 1.0 if (sprinting and placing) else 0.0

        # channel 10: 末尾窗口内 (包含当前) 近 PLACE_WINDOW tick 的放置数
        place_count = 0
        for j in range(max(0, i - (PLACE_WINDOW - 1)), i + 1):
            if ticks[n - length + j].get('placing', False):
                place_count += 1
        img[10, idx] = min(place_count / 10.0, 1.0)

        # channel 11: 放置间隔的规律性 —— 稳定节奏(外挂)趋近 1，随机间隔(真人)趋近 0
        img[11, idx] = _placing_regularity(ticks, n - length, i)

    return img


def _placing_regularity(ticks, base, i):
    """计算末尾窗口中下标 i 处的放置节奏规律性。"""
    if i < 5:
        return 0.0

    intervals = []
    last_time = None
    for t in ticks[max(0, base + i - (PLACE_WINDOW - 1)): base + i + 1]:
        if not t.get('placing', False):
            continue
        ts = t.get('ts') or t.get('timestamp')
        if ts is None:
            continue
        if last_time is not None:
            intervals.append(ts - last_time)
        last_time = ts

    # 至少 5 个间隔（6 次放置）才计算方差 —— 与最初训练数据的 prepare_data 保持一致
    if len(intervals) < 5:
        return 0.0
    variance = float(np.var(intervals))
    return max(0.0, 1.0 - variance / INTERVAL_VARIANCE_DIVISOR)


def jsonl_to_ticks(path):
    """读取录制插件输出的 jsonl 文件，按时间排序。"""
    import json
    ticks = []
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                ticks.append(json.loads(line))
    ticks.sort(key=lambda t: t.get('ts') or t.get('timestamp') or 0)
    return ticks
