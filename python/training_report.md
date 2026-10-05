# DeepGuard 模型训练报告

- 训练时间：2026-10-05 11:00:07
- 训练设备：cpu
- 耗时：16.8 秒

## 数据

- 样本总数：419（正常 247 / 作弊 172）
- 划分：训练 293 / 验证 63 / 测试 63
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：212 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.3820
- 最后一个 epoch 的训练损失：0.3913
- 测试集 AUC：0.9771

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.86      1.00      0.93        37
       Cheat       1.00      0.77      0.87        26

    accuracy                           0.90        63
   macro avg       0.93      0.88      0.90        63
weighted avg       0.92      0.90      0.90        63
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`ce77886943ca`
- `training_curves.png` — 损失曲线
