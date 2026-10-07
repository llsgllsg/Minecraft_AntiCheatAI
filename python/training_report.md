# DeepGuard 模型训练报告

- 训练时间：2026-10-07 06:19:02
- 训练设备：cpu
- 耗时：48.2 秒

## 数据

- 样本总数：938（正常 505 / 作弊 433）
- 划分：训练 656 / 验证 141 / 测试 141
- 特征图形状：(17, 128)，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 0.929 / 作弊 1.083（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：253 / 5000（提前停止）
- batch size：32，学习率：6e-06，随机种子：42
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：0.3185
- 最后一个 epoch 的训练损失：0.3281
- 测试集 AUC：0.9745

测试集分类报告：

```
              precision    recall  f1-score   support

      Normal       0.90      0.96      0.93        76
       Cheat       0.95      0.88      0.91        65

    accuracy                           0.92       141
   macro avg       0.93      0.92      0.92       141
weighted avg       0.92      0.92      0.92       141
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`9144bd1e9b9c`
- `training_curves.png` — 损失曲线
