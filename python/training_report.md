# DeepGuard 模型训练报告

- 训练时间：2026-10-06 07:21:24
- 训练设备：cpu
- 耗时：6.6 秒

## 数据

- 样本总数：647（正常 510 / 作弊 137）
- 划分：训练 452 / 验证 97 / 测试 98
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.635 / 作弊 2.354（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：99 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.5746
- 最后一个 epoch 的训练损失：0.5638
- 测试集 AUC：0.8800

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.94      0.78      0.85        77
       Cheat       0.50      0.81      0.62        21

    accuracy                           0.79        98
   macro avg       0.72      0.79      0.73        98
weighted avg       0.84      0.79      0.80        98
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`5136243e8513`
- `training_curves.png` — 损失曲线
