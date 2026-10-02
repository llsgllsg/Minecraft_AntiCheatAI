# DeepGuard 模型训练报告

- 训练时间：2026-10-02 15:57:41 UTC（GitHub Actions 运行 37030537603）
- 训练设备：cpu
- 耗时：20.6 秒

## 数据

- 样本总数：389（正常 233 / 作弊 156）
- 划分：训练 272 / 验证 58 / 测试 59
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：292 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.3155（第 282 轮）
- 最后一个 epoch 的训练损失：0.3026
- 测试集 AUC：0.9786

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.94      0.91      0.93        35
       Cheat       0.88      0.92      0.90        24

    accuracy                          0.92        59
   macro avg       0.91      0.92      0.91        59
weighted avg       0.92      0.92      0.92        59
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`538912d4adc7`
- `training_curves.png` — 损失曲线
