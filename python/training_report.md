# DeepGuard 模型训练报告

- 训练时间：2026-10-06 14:44:52
- 训练设备：cpu
- 耗时：77.0 秒

## 数据

- 样本总数：3925（正常 3558 / 作弊 367）
- 划分：训练 2747 / 验证 589 / 测试 589
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.552 / 作弊 5.344（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：109 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.3025
- 最后一个 epoch 的训练损失：0.3185
- 测试集 AUC：0.8820

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.97      0.85      0.90       534
       Cheat       0.33      0.71      0.45        55

    accuracy                           0.84       589
   macro avg       0.65      0.78      0.67       589
weighted avg       0.91      0.84      0.86       589
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`17ca90ccd64d`
- `training_curves.png` — 损失曲线
