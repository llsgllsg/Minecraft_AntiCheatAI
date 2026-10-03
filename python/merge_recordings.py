"""把录制插件新产出的 jsonl 样本增量合并进已有的 X.npy / y.npy。

与 prepare_data.py 的区别：prepare_data 从原始 jsonl 全量重建特征集，
本脚本用于「已有特征集 + 新录制」的增量追加，旧样本按位保留不变。

用法:
    python merge_recordings.py <新录制目录> [--data .] [--archive] [--dry-run]

标签推断与 prepare_data.py 一致：
    - 目录名为 normal/  → 0，cheat*/  → 1
    - 否则取文件名尾缀 <uuid>_<ts>_<label>.jsonl 的 label
    - 少于 MIN_TICKS 行的文件跳过（与 Java 端 analyzePlayerAsync 阈值一致）

--archive 会把实际合并进去的 jsonl 复制到 <data>/data/{normal,cheat}/ 归档，
便于日后从原始数据全量重建（该目录已被 .gitignore 忽略）。
"""

import argparse
import glob
import os
import shutil
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except (AttributeError, ValueError):
        pass

import numpy as np

from features import build_behavior_image, jsonl_to_ticks
from prepare_data import MIN_TICKS, label_for_file


def main():
    parser = argparse.ArgumentParser(description='增量合并新录制到 X.npy/y.npy')
    parser.add_argument('recordings_dir', help='新录制目录（recorder-plugin 的 recordings/）')
    parser.add_argument('--data', default='.', help='已有 X.npy / y.npy 所在目录（默认当前目录）')
    parser.add_argument('--archive', action='store_true',
                        help='把合并的 jsonl 归档到 <data>/data/{normal,cheat}/')
    parser.add_argument('--dry-run', action='store_true', help='只统计不写盘')
    args = parser.parse_args()

    if not os.path.isdir(args.recordings_dir):
        print(f'错误: 录制目录不存在: {args.recordings_dir}')
        sys.exit(1)

    x_path = os.path.join(args.data, 'X.npy')
    y_path = os.path.join(args.data, 'y.npy')
    if not (os.path.exists(x_path) and os.path.exists(y_path)):
        print(f'错误: 未找到 {x_path} / {y_path}，请先用 prepare_data.py 全量生成')
        sys.exit(1)

    X_old = np.load(x_path)
    y_old = np.load(y_path)
    print(f'已有数据: {len(X_old)} 个样本 '
          f'(正常 {int((y_old == 0).sum())} / 作弊 {int((y_old == 1).sum())})')

    # 已有样本的特征指纹，用于去重（整张特征图按位比较）
    seen = {X_old[i].tobytes() for i in range(len(X_old))}

    files = sorted(glob.glob(os.path.join(args.recordings_dir, '**', '*.jsonl'), recursive=True)
                   + glob.glob(os.path.join(args.recordings_dir, '**', '*.json'), recursive=True))

    new_X, new_y, merged_files = [], [], []
    dup = short = unlabeled = 0
    for path in files:
        label = label_for_file(path, args.recordings_dir)
        if label is None:
            print(f'跳过 {os.path.basename(path)}: 无法推断标签')
            unlabeled += 1
            continue
        ticks = jsonl_to_ticks(path)
        if len(ticks) < MIN_TICKS:
            print(f'跳过 {os.path.basename(path)}: 仅 {len(ticks)} 行 (<{MIN_TICKS})')
            short += 1
            continue
        img = build_behavior_image(ticks)
        if img.tobytes() in seen:
            print(f'跳过 {os.path.basename(path)}: 与已有样本特征重复')
            dup += 1
            continue
        seen.add(img.tobytes())
        new_X.append(img)
        new_y.append(label)
        merged_files.append((path, label))

    if not new_X:
        print(f'没有可合并的新样本（重复 {dup}，过短 {short}，无标签 {unlabeled}）')
        return

    X_new = np.stack(new_X).astype(np.float32)
    y_new = np.array(new_y, dtype=np.int64)
    X = np.concatenate([X_old, X_new], axis=0)
    y = np.concatenate([y_old, y_new], axis=0)

    n_add_normal = int((y_new == 0).sum())
    n_add_cheat = int((y_new == 1).sum())
    print(f'新增 {len(X_new)} 个样本 (正常 {n_add_normal} / 作弊 {n_add_cheat})，'
          f'跳过: 重复 {dup} / 过短 {short} / 无标签 {unlabeled}')
    print(f'合并后: {len(X)} 个样本 '
          f'(正常 {int((y == 0).sum())} / 作弊 {int((y == 1).sum())})，形状 {X.shape}')

    if args.dry_run:
        print('--dry-run：未写入。')
        return

    np.save(x_path, X)
    np.save(y_path, y)
    print(f'已写入 {x_path} / {y_path}')

    if args.archive:
        for path, label in merged_files:
            sub = 'cheat' if label == 1 else 'normal'
            dest_dir = os.path.join(args.data, 'data', sub)
            os.makedirs(dest_dir, exist_ok=True)
            dest = os.path.join(dest_dir, os.path.basename(path))
            if os.path.exists(dest):
                print(f'归档已存在，跳过: {dest}')
                continue
            shutil.copy2(path, dest)
        print(f'已归档 {len(merged_files)} 个 jsonl 到 {os.path.join(args.data, "data")}/')


if __name__ == '__main__':
    main()
