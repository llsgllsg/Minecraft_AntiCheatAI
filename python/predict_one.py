"""用 ONNX 模型对单个 jsonl 行为文件做离线推理。

与插件内 Java 端 (AIInferenceEngine) 使用相同的 onnxruntime 与相同的特征工程，
因此这里的概率可以与线上检测结果互相印证。

用法:
    python predict_one.py <jsonl文件> [--model scaffold_detector.onnx]
"""

import argparse
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except (AttributeError, ValueError):
        pass

import numpy as np
import onnxruntime as ort

from features import TIME_STEPS, build_behavior_image, jsonl_to_ticks

MIN_TICKS = 100  # 与 Java 端阈值一致


def main():
    parser = argparse.ArgumentParser(description='对单个 jsonl 行为文件推理')
    parser.add_argument('jsonl', help='录制插件产出的 .jsonl 行为文件')
    parser.add_argument('--model', default='scaffold_detector.onnx',
                        help='ONNX 模型路径（默认 scaffold_detector.onnx）')
    args = parser.parse_args()

    ticks = jsonl_to_ticks(args.jsonl)
    if not ticks:
        print('错误: 文件中没有数据')
        sys.exit(1)
    if len(ticks) < MIN_TICKS:
        print(f'警告: 数据只有 {len(ticks)} 行 (<{MIN_TICKS})，结果可能不准确')

    img = build_behavior_image(ticks)[np.newaxis, ...].astype(np.float32)  # (1,12,128)

    try:
        sess = ort.InferenceSession(args.model, providers=['CPUExecutionProvider'])
    except Exception as e:
        print(f'错误: 无法加载模型 {args.model}: {e}')
        print('请先运行 python train_model.py 生成模型。')
        sys.exit(1)

    input_name = sess.get_inputs()[0].name
    probs = sess.run(None, {input_name: img})[0][0]  # 模型已在 softmax 后输出

    normal_prob, cheat_prob = float(probs[0]), float(probs[1])
    prediction = '作弊' if cheat_prob >= 0.5 else '正常'

    print(f'文件: {args.jsonl}')
    print(f'正常概率: {normal_prob:.4f} ({normal_prob * 100:.1f}%)')
    print(f'作弊概率: {cheat_prob:.4f} ({cheat_prob * 100:.1f}%)')
    print(f'判定结果: {prediction}')


if __name__ == '__main__':
    main()
