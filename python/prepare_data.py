"""把录制插件产出的 jsonl 样本转换为训练用 X.npy / y.npy。

用法:
    python prepare_data.py <数据目录> [--out 输出目录]

数据目录结构（推荐）:
    data/
      normal/*.jsonl     标签 0
      cheat/*.jsonl      标签 1

兼容性：
- 若文件直接放在数据目录根下，则从文件名尾缀解析标签
  （录制插件文件名格式为 <uuid>_<时间戳>_<标签>.jsonl，如 xxx_123_1.jsonl）。
- 少于 100 行的文件会被跳过（与 Java 端 analyzePlayerAsync 的阈值一致）。
"""

import argparse
import glob
import json
import os
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except (AttributeError, ValueError):
        pass

import numpy as np

from features import CHANNELS, TIME_STEPS, build_behavior_image, jsonl_to_ticks

MIN_TICKS = 100  # 与 AntiCheatPlugin#analyzePlayerAsync 的 100 行阈值对齐


def label_for_file(path, root):
    """优先按目录推断标签，其次按文件名尾缀 `_<label>.jsonl` 推断。"""
    rel = os.path.relpath(path, root)
    parts = rel.split(os.sep)
    if len(parts) >= 2:
        folder = parts[-2].lower()
        if folder == 'normal':
            return 0
        if folder in ('cheat', 'cheating', 'cheats'):
            return 1
    base = os.path.basename(path)
    # xxx_<ts>_<label>.jsonl
    try:
        stem = base.rsplit('.', 1)[0]
        return int(stem.rsplit('_', 1)[1])
    except (ValueError, IndexError):
        return None


def collect_files(root):
    files = []
    for ext in ('*.jsonl', '*.json'):
        files.extend(glob.glob(os.path.join(root, '**', ext), recursive=True))
    return sorted(files)


def main():
    parser = argparse.ArgumentParser(description='jsonl -> X.npy/y.npy')
    parser.add_argument('data_dir', help='数据根目录（含 normal/ 与 cheat/ 子目录）')
    parser.add_argument('--out', default='.', help='X.npy / y.npy 输出目录（默认当前目录）')
    args = parser.parse_args()

    if not os.path.isdir(args.data_dir):
        print(f"错误: 数据目录不存在: {args.data_dir}")
        sys.exit(1)

    files = collect_files(args.data_dir)
    if not files:
        print(f"错误: 数据目录下未找到任何 .jsonl/.json 文件: {args.data_dir}")
        sys.exit(1)

    samples, labels = [], []
    skipped = 0
    for path in files:
        label = label_for_file(path, args.data_dir)
        if label is None:
            print(f"跳过 {path}: 无法推断标签（请放入 normal/ 或 cheat/ 子目录）")
            continue
        ticks = jsonl_to_ticks(path)
        if len(ticks) < MIN_TICKS:
            print(f"跳过 {path}: 仅 {len(ticks)} 行 (<{MIN_TICKS})")
            skipped += 1
            continue
        samples.append(build_behavior_image(ticks))
        labels.append(label)

    if not samples:
        print(f"错误: 没有可用样本（跳过 {skipped} 个）")
        sys.exit(1)

    X = np.stack(samples).astype(np.float32)   # (N, CHANNELS, TIME_STEPS)
    y = np.array(labels, dtype=np.int64)       # (N,)

    os.makedirs(args.out, exist_ok=True)
    np.save(os.path.join(args.out, 'X.npy'), X)
    np.save(os.path.join(args.out, 'y.npy'), y)

    n_cheat = int(y.sum())
    print(f"已保存 {len(X)} 个样本 (正常 {len(X) - n_cheat}, 作弊 {n_cheat}) "
          f"到 {os.path.abspath(args.out)}/  (形状 {X.shape})")
    if n_cheat == 0 or n_cheat == len(X):
        print("警告: 数据只有单一类别，训练前请补充另一类样本。")


if __name__ == '__main__':
    main()
