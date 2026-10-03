# 原始录制数据

录制插件（`recorder-plugin`）产出的 jsonl 原始样本存放处，长期保留。

## 怎么放

按标签分目录：

```
python/recordings/
  normal/*.jsonl    标签 0（正常玩家行为）
  cheat/*.jsonl     标签 1（作弊行为）
```

文件名沿用录制插件的格式 `<uuid>_<时间戳>_<标签>.jsonl` 即可。若文件直接放在
`python/recordings/` 根下（没有子目录），则从文件名尾缀 `_<标签>` 推断标签；
子目录优先于文件名。

## 放进去之后

往这个目录提交（或网页拖拽上传）jsonl，GitHub Actions 的 **导入录制数据**
工作流会自动运行，把它们编码成 (12, 128) 特征图并增量合并进
`python/X.npy` / `python/y.npy`，然后把更新后的特征集提交回仓库。

也可以在 Actions 页面手动触发该工作流。

合并是**幂等**的：已处理过的 jsonl 会因特征图完全相同而被去重跳过，
重复上传或重复触发都不会把同一条样本算两次。

> 注意：这里只做 jsonl → 特征集的转换。要出新模型还得再跑
> **重新训练模型** 工作流（见仓库根 README）。

## 门槛

少于 100 行的文件会被跳过 —— 与 Java 端 `analyzePlayerAsync` 的分析阈值、
以及 `prepare_data.py` 的 `MIN_TICKS` 保持一致。录制缓冲区（`buffer-size`，
默认 256 tick）刷出来的完整文件通常是 256 行。
