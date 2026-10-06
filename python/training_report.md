# DeepGuard 模型训练报告

- 训练时间：2026-10-06 08:52:46
- 训练设备：cpu
- 耗时：68.2 秒

## 数据

- 样本总数：1821（正常 1496 / 作弊 325）
- 划分：训练 1274 / 验证 273 / 测试 274
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.608 / 作弊 2.806（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：212 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.2433
- 最后一个 epoch 的训练损失：0.3276
- 测试集 AUC：0.9206

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.95      0.93      0.94       225
       Cheat       0.72      0.78      0.75        49

    accuracy                           0.91       274
   macro avg       0.83      0.85      0.84       274
weighted avg       0.91      0.91      0.91       274
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`87894933c105`
- `training_curves.png` — 损失曲线
