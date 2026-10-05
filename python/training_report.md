# DeepGuard 模型训练报告

- 训练时间：2026-10-05 23:49:25
- 训练设备：cpu
- 耗时：0.3 秒

## 数据

- 样本总数：92（正常 71 / 作弊 21）
- 划分：训练 64 / 验证 14 / 测试 14
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：27 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.6651
- 最后一个 epoch 的训练损失：0.6702
- 测试集 AUC：0.2121

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.77      0.91      0.83        11
       Cheat       0.00      0.00      0.00         3

    accuracy                           0.71        14
   macro avg       0.38      0.45      0.42        14
weighted avg       0.60      0.71      0.65        14
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`119933b34f49`
- `training_curves.png` — 损失曲线
