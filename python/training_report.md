# DeepGuard 模型训练报告

- 训练时间：2026-10-05 23:40:48
- 训练设备：cpu
- 耗时：0.4 秒

## 数据

- 样本总数：71（正常 71 / 作弊 0）
- 划分：训练 49 / 验证 11 / 测试 11
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：26 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.6550
- 最后一个 epoch 的训练损失：0.6881
- 测试集 AUC：不可用（测试集只有单一类别）

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       1.00      0.91      0.95        11
       Cheat       0.00      0.00      0.00         0

    accuracy                           0.91        11
   macro avg       0.50      0.45      0.48        11
weighted avg       1.00      0.91      0.95        11
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`4c5f96514702`
- `training_curves.png` — 损失曲线
