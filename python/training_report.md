# DeepGuard 模型训练报告

- 训练时间：2026-10-08 05:44:25
- 训练设备：cpu
- 耗时：46.3 秒

## 数据

- 样本总数：2206（正常 1773 / 作弊 433）
- 划分：训练 1544 / 验证 331 / 测试 331
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.622 / 作弊 2.548（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：236 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.3287
- 最后一个 epoch 的训练损失：0.3010
- 测试集 AUC：0.9338

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.95      0.94      0.95       266
       Cheat       0.76      0.80      0.78        65

    accuracy                           0.91       331
   macro avg       0.86      0.87      0.86       331
weighted avg       0.91      0.91      0.91       331
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`7b05aec872e6`
- `training_curves.png` — 损失曲线
