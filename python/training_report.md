# DeepGuard 模型训练报告

- 训练时间：2026-10-03 07:19:11
- 训练设备：cpu
- 耗时：24.4 秒

## 数据

- 样本总数：403（正常 247 / 作弊 156）
- 划分：训练 282 / 验证 60 / 测试 61
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：426 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.2614
- 最后一个 epoch 的训练损失：0.2502
- 测试集 AUC：0.9617

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.84      1.00      0.91        37
       Cheat       1.00      0.71      0.83        24

    accuracy                           0.89        61
   macro avg       0.92      0.85      0.87        61
weighted avg       0.90      0.89      0.88        61
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`3d282af4c972`
- `training_curves.png` — 损失曲线
