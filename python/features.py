"""共享特征工程 —— DeepGuard 的唯一权威实现。

Java 运行时端 (AntiCheat/src/main/java/com/gfish/anticheat/BehaviorImageBuilder.java)
必须与此文件逐位一致，否则训练 / 离线预测 / 在线检测三者会产生系统性偏差。

约定：
- ticks: jsonl 中解析出的 dict 列表，按时间先后排序。
- 输出: float32 numpy 数组，形状 (17, 128)：
    0  pitch 归一化        9  冲刺+放置 二值
    1  yaw  归一化        10  近20tick放置数
    2  水平速度           11  放置节奏规律性(间隔方差)
    3  垂直速度           12  是否在载具 二值
    4  placing 二值       13  是否鞘翅滑翔 二值
    5  sprinting 二值     14  Δx 每tick水平位移(带符号)
    6  jumping 二值       15  Δy 每tick垂直位移(带符号)
    7  |Δpitch| 归一化    16  Δz 每tick水平位移(带符号)
    8  快速转头(>25°)二值

  通道 12/13 依赖 jsonl 里的 inVehicle / gliding 字段 —— 加字段之前录的旧数据
  这两项恒为 False（通道值为 0）。通道 14/15/16 由相邻两 tick 的坐标差算出，
  不依赖新字段，因此**旧数据同样有效**。

  载具的具体类型（vehicleType）只存进 jsonl 供人工分析，不参与特征编码。
"""

import numpy as np

CHANNELS = 17
TIME_STEPS = 128

# Java 端 (BehaviorImageBuilder) 的滑动窗口大小 —— 放置统计 / 间隔统计共用
PLACE_WINDOW = 20
# 间隔方差阈值：方差越小(节奏越规律)该通道越接近 1
INTERVAL_VARIANCE_DIVISOR = 1000.0

# 每 tick 位移的归一化尺度（格/tick）。上下限 ±1，再线性映射到 [0,1]。
# 水平：一般行走约 0.215、疾跑约 0.28，取 1.0 已足够覆盖。
# 垂直：自由落体终端速度约 3.92 格/tick，所以尺度取得更大。
DELTA_H_SCALE = 1.0
DELTA_V_SCALE = 4.0


def _signed_unit(delta, scale):
    """把带符号的位移按 scale 归一化到 [0,1]（0.5 表示没有位移）。"""
    clamped = max(-1.0, min(1.0, delta / scale))
    return (clamped + 1.0) / 2.0


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
        # 加字段之前录的旧数据没有这两项，默认为 False
        in_vehicle = bool(t.get('inVehicle', False))
        gliding = bool(t.get('gliding', False))

        pitch_change = 0.0
        # 与上一 tick 的相对位移：由坐标差算出，不依赖 jsonl 新字段
        delta_x = delta_y = delta_z = 0.0
        if i > 0:
            prev = ticks[n - length + i - 1]
            pitch_change = abs(pitch - prev.get('pitch', pitch))
            delta_x = t.get('posX', 0.0) - prev.get('posX', 0.0)
            delta_y = t.get('posY', 0.0) - prev.get('posY', 0.0)
            delta_z = t.get('posZ', 0.0) - prev.get('posZ', 0.0)

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

        # channel 12/13: 载具 / 鞘翅 —— 区分「合法滞空」与「飞行外挂」的关键信号
        img[12, idx] = 1.0 if in_vehicle else 0.0
        img[13, idx] = 1.0 if gliding else 0.0

        # channel 14/15/16: 与上一 tick 的相对位移（带符号，0.5 = 无位移）
        img[14, idx] = _signed_unit(delta_x, DELTA_H_SCALE)
        img[15, idx] = _signed_unit(delta_y, DELTA_V_SCALE)
        img[16, idx] = _signed_unit(delta_z, DELTA_H_SCALE)

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
