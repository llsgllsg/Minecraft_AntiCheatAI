# DeepGuard 模型训练报告

- 训练时间：2026-10-06 06:36:25
- 训练设备：cpu
- 耗时：14.2 秒

## 数据

- 样本总数：506（正常 369 / 作弊 137）
- 划分：训练 354 / 验证 76 / 测试 76
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.686 / 作弊 1.844（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：142 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.4662
- 最后一个 epoch 的训练损失：0.4888
- 测试集 AUC：0.8473

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.84      0.84      0.84        56
       Cheat       0.55      0.55      0.55        20

    accuracy                           0.76        76
   macro avg       0.69      0.69      0.69        76
weighted avg       0.76      0.76      0.76        76
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`5d7a8e0b1627`
- `training_curves.png` — 损失曲线
