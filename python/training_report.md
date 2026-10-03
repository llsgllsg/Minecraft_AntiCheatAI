# DeepGuard 模型训练报告

- 训练时间：2026-10-03 11:23:33
- 训练设备：cpu
- 耗时：15.6 秒

## 数据

- 样本总数：418（正常 247 / 作弊 171）
- 划分：训练 292 / 验证 63 / 测试 63
- 特征图形状：(12, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致

## 超参数

- 训练轮数：184 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.4253
- 最后一个 epoch 的训练损失：0.4555
- 测试集 AUC：0.9605

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.84      1.00      0.91        37
       Cheat       1.00      0.73      0.84        26

    accuracy                           0.89        63
   macro avg       0.92      0.87      0.88        63
weighted avg       0.91      0.89      0.89        63
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`ce32873472bb`
- `training_curves.png` — 损失曲线
