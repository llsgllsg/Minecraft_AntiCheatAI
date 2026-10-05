"""特征工程单元测试（纯内存，不生成数据文件）。

运行: python test_features.py
作用:
- 验证 build_behavior_image 的输出形状、取值范围。
- 验证与 Java 端 BehaviorImageBuilder 的契约（channel 10/11 等关键信号）。
- 验证 jsonl 解析的往返一致性。

这不是数据生成器 —— 所有样本都是测试内部构造的内存 dict，不落盘。
"""

import os
import sys
import tempfile

import numpy as np

sys.stdout.reconfigure(errors='replace') if hasattr(sys.stdout, 'reconfigure') else None
sys.stderr.reconfigure(errors='replace') if hasattr(sys.stderr, 'reconfigure') else None

from features import CHANNELS, TIME_STEPS, build_behavior_image, jsonl_to_ticks


def make_tick(ts, pitch=0.0, yaw=0.0, placing=False, sprinting=False,
              jumping=False, move_speed=0.0, vert_speed=0.0,
              in_vehicle=False, gliding=False,
              pos_x=0.0, pos_y=64.0, pos_z=0.0):
    return {
        'ts': ts, 'pitch': pitch, 'yaw': yaw,
        'posX': pos_x, 'posY': pos_y, 'posZ': pos_z,
        'placing': placing, 'sprinting': sprinting, 'jumping': jumping,
        'onGround': not jumping, 'moveSpeed': move_speed, 'vertSpeed': vert_speed,
        'inVehicle': in_vehicle, 'gliding': gliding,
        'vehicleType': 'minecraft:boat' if in_vehicle else '',
    }


def test_empty():
    img = build_behavior_image([])
    assert img.shape == (CHANNELS, TIME_STEPS), img.shape
    assert img.dtype == np.float32
    assert np.all(img == 0)
    print(f'PASS empty -> ({CHANNELS},{TIME_STEPS}) all-zero')


def test_normalization():
    # 128 个固定 tick：pitch=-90, yaw=-180, move=0, vert=0
    ticks = [make_tick(i, pitch=-90.0, yaw=-180.0, move_speed=0.0, vert_speed=0.0)
             for i in range(128)]
    img = build_behavior_image(ticks)
    assert img.shape == (CHANNELS, TIME_STEPS)
    # pitch 通道: (-90+90)/180 = 0；yaw: (-180+180)/360 = 0
    assert img[0, -1] == 0.0
    assert img[1, -1] == 0.0
    assert img[2, -1] == 0.0
    assert img[3, -1] == 0.5  # (0+1)/2
    print('PASS normalization')


def test_binary_flags():
    ticks = [make_tick(i, placing=True, sprinting=True, jumping=True,
                       move_speed=10.0, vert_speed=1.0) for i in range(128)]
    img = build_behavior_image(ticks)
    assert img[4, -1] == 1.0   # placing
    assert img[5, -1] == 1.0   # sprinting
    assert img[6, -1] == 1.0   # jumping
    assert img[9, -1] == 1.0   # sprinting AND placing
    assert img[2, -1] == 1.0   # move_speed 10/10 clamped
    assert img[3, -1] == 1.0   # vert (1+1)/2
    print('PASS binary flags')


def test_snap_turn():
    # 快速转头: 前一 tick pitch=-90，后一 tick pitch=+30 -> |Δ|=120° > 25°
    ticks = []
    for i in range(128):
        if i == 127:
            ticks.append(make_tick(i, pitch=30.0))
        else:
            ticks.append(make_tick(i, pitch=-90.0))
    img = build_behavior_image(ticks)
    assert img[8, -1] == 1.0, 'rapid pitch change should set channel 8'
    print('PASS snap-turn channel 8')


def test_place_regularity_cheat_like():
    # 外挂式节奏: 每 2 tick 放置一次（20 tick 窗口内含 10 次放置=9 个间隔），
    # 间隔恒定 100ms -> 方差=0 -> 接近 1
    ticks = []
    for i in range(128):
        placing = (i % 2 == 1)
        ticks.append(make_tick(i * 50, placing=placing))
    img = build_behavior_image(ticks)
    val = img[11, -1]
    assert val >= 0.99, f'regular placing should be near 1.0, got {val}'
    print(f'PASS regularity (regular=1.0) got {val:.3f}')


def test_place_regularity_human_like():
    # 真人式: 放置间隔随机 -> 方差大 -> 趋近 0
    rng = np.random.default_rng(42)
    ticks = []
    for i in range(128):
        placing = rng.random() < 0.08
        ticks.append(make_tick(i * 50, placing=placing))
    img = build_behavior_image(ticks)
    val = img[11, -1]
    assert val < 0.6, f'irregular placing should be lower, got {val}'
    print(f'PASS irregularity (irregular<0.6) got {val:.3f}')


def test_jsonl_roundtrip():
    ticks = [make_tick(i * 50, pitch=float(i)) for i in range(64)]
    fd, path = tempfile.mkstemp(suffix='.jsonl')
    os.close(fd)
    try:
        import json
        with open(path, 'w', encoding='utf-8') as f:
            for t in ticks:
                f.write(json.dumps(t) + '\n')
        parsed = jsonl_to_ticks(path)
        assert len(parsed) == 64
        assert parsed[0]['ts'] == 0 and parsed[-1]['ts'] == 63 * 50
        # 乱序输入应被排序
        with open(path, 'w', encoding='utf-8') as f:
            for t in reversed(ticks):
                f.write(json.dumps(t) + '\n')
        parsed = jsonl_to_ticks(path)
        assert parsed[0]['ts'] == 0
        print('PASS jsonl roundtrip + sort')
    finally:
        os.remove(path)


def test_vehicle_and_gliding():
    """通道 12/13：载具 / 鞘翅二值。"""
    ticks = [make_tick(i, in_vehicle=True, gliding=False) for i in range(128)]
    img = build_behavior_image(ticks)
    assert img[12, -1] == 1.0, 'inVehicle should set channel 12'
    assert img[13, -1] == 0.0

    ticks = [make_tick(i, in_vehicle=False, gliding=True) for i in range(128)]
    img = build_behavior_image(ticks)
    assert img[12, -1] == 0.0
    assert img[13, -1] == 1.0, 'gliding should set channel 13'
    print('PASS vehicle/gliding channels 12/13')


def test_delta_channels():
    """通道 14/15/16：相邻 tick 的相对位移，0.5 表示没动。"""
    # 每 tick 水平走 0.5 格（x 方向），垂直不动
    ticks = [make_tick(i, pos_x=i * 0.5, pos_y=64.0, pos_z=0.0) for i in range(128)]
    img = build_behavior_image(ticks)
    # 0.5 格/tick，水平尺度 1.0 -> clamp(0.5) -> (0.5+1)/2 = 0.75
    assert abs(img[14, -1] - 0.75) < 1e-6, f'Δx expected 0.75, got {img[14, -1]}'
    assert abs(img[15, -1] - 0.5) < 1e-6, f'Δy expected 0.5, got {img[15, -1]}'
    assert abs(img[16, -1] - 0.5) < 1e-6, f'Δz expected 0.5, got {img[16, -1]}'

    # 反向移动应落在 0.5 以下
    ticks = [make_tick(i, pos_x=-i * 0.5) for i in range(128)]
    img = build_behavior_image(ticks)
    assert abs(img[14, -1] - 0.25) < 1e-6, f'negative Δx expected 0.25, got {img[14, -1]}'
    print('PASS delta channels 14/15/16')


def test_delta_clamped():
    """超出一格的位移应被钳到 [0,1] 边界（垂直尺度 4.0）。"""
    # 垂直每 tick 掉 10 格 —— 远超尺度 4.0，应钳到 0.0
    ticks = [make_tick(i, pos_y=64.0 - i * 10.0) for i in range(128)]
    img = build_behavior_image(ticks)
    assert img[15, -1] == 0.0, f'clamped Δy expected 0.0, got {img[15, -1]}'
    print('PASS delta clamping')


def test_old_data_without_new_fields():
    """加字段之前录的旧 jsonl 没有 inVehicle/gliding，应默认为 0 且不影响坐标位移。"""
    old = [{'ts': i, 'pitch': 0.0, 'yaw': 0.0, 'posX': i * 0.5, 'posY': 64.0, 'posZ': 0.0,
            'placing': False, 'sprinting': False, 'jumping': False, 'onGround': True,
            'moveSpeed': 0.0, 'vertSpeed': 0.0} for i in range(128)]
    img = build_behavior_image(old)
    assert img.shape[0] == CHANNELS
    assert img[12, -1] == 0.0 and img[13, -1] == 0.0, 'missing fields must default to 0'
    assert abs(img[14, -1] - 0.75) < 1e-6, 'Δx must still work from existing posX'
    print('PASS legacy data without new fields')


def test_few_ticks_aligned_right():
    # 只有 64 个 tick 时，应靠右对齐（前 64 列为 0）
    ticks = [make_tick(i, pitch=45.0) for i in range(64)]
    img = build_behavior_image(ticks)
    assert img[0, 0] == 0.0
    assert img[0, -1] == (45.0 + 90) / 180.0
    print('PASS few-ticks right-alignment')


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_') and callable(v)]
    for t in tests:
        t()
    print(f'\nAll {len(tests)} tests passed.')
