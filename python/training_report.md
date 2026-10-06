# DeepGuard 模型训练报告

- 训练时间：2026-10-06 05:55:31
- 训练设备：cpu
- 耗时：4.5 秒

## 数据

- 样本总数：413（正常 364 / 作弊 49）
- 划分：训练 289 / 验证 62 / 测试 62
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：46 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.6226
- 最后一个 epoch 的训练损失：0.6156
- 测试集 AUC：0.7818

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.92      0.87      0.90        55
       Cheat       0.30      0.43      0.35         7

    accuracy                           0.82        62
   macro avg       0.61      0.65      0.63        62
weighted avg       0.85      0.82      0.84        62
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`275af11dca8f`
- `training_curves.png` — 损失曲线
