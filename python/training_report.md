# DeepGuard 模型训练报告

- 训练时间：2026-10-06 08:49:07
- 训练设备：cpu
- 耗时：47.1 秒

## 数据

- 样本总数：1361（正常 1068 / 作弊 293）
- 划分：训练 952 / 验证 204 / 测试 205
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.637 / 作弊 2.322（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：229 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.3760
- 最后一个 epoch 的训练损失：0.3010
- 测试集 AUC：0.9157

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.94      0.89      0.91       161
       Cheat       0.66      0.80      0.72        44

    accuracy                           0.87       205
   macro avg       0.80      0.84      0.82       205
weighted avg       0.88      0.87      0.87       205
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`69f791ce17ec`
- `training_curves.png` — 损失曲线
