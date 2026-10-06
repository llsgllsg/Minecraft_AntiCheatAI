"""训练 DeepGuard 的搭路外挂检测模型。

前置: 先运行 python prepare_data.py <数据目录> 生成 X.npy / y.npy。

用法:
    python train_model.py                 # 完整训练（早停）
    python train_model.py --quick         # CI/冒烟测试：最多 2 个 epoch

产出:
    best_model.pth           最佳权重（PyTorch 版，用于离线调试）
    scaffold_detector.onnx   部署用 ONNX 模型（插件在运行时加载）
    training_curves.png      损失曲线

注意:
- 导出时在 softmax 之后输出，因此 Java 端 (AIInferenceEngine) 读到的
  output[0] 就是真实概率，阈值比较 (0.85 / 0.5) 才有意义。
- 导出弃用手工修改 IR version 的做法，依赖 onnxruntime 的后向兼容
  （建议 onnxruntime >= 1.14）。
"""

import argparse
import datetime
import hashlib
import os
import random
import sys
import time

os.environ.setdefault('MPLBACKEND', 'Agg')  # headless 安全
# Windows 终端编码容错（避免 GBK 无法打印某些字符导致崩溃）
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except (AttributeError, ValueError):
        pass

import matplotlib.pyplot as plt
import numpy as np
import onnx
from features import CHANNELS, TIME_STEPS
import torch
import torch.nn as nn
import torch.optim as optim
from sklearn.metrics import classification_report, roc_auc_score
from sklearn.model_selection import train_test_split
from torch.utils.data import DataLoader, TensorDataset


def set_seed(seed=42):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


class ScaffoldDetector(nn.Module):
    # 通道数从 features.py 取，避免改特征时忘了同步模型结构
    def __init__(self, in_channels=CHANNELS, num_classes=2):
        super().__init__()
        self.conv1 = nn.Conv1d(in_channels, 32, 5, padding=2)
        self.bn1 = nn.BatchNorm1d(32)
        self.pool1 = nn.MaxPool1d(2)
        self.conv2 = nn.Conv1d(32, 64, 5, padding=2)
        self.bn2 = nn.BatchNorm1d(64)
        self.pool2 = nn.MaxPool1d(2)
        self.conv3 = nn.Conv1d(64, 128, 3, padding=1)
        self.bn3 = nn.BatchNorm1d(128)
        self.pool3 = nn.AdaptiveAvgPool1d(1)
        self.fc1 = nn.Linear(128, 32)
        self.dropout = nn.Dropout(0.3)
        self.fc2 = nn.Linear(32, num_classes)

    def forward(self, x):
        x = self.pool1(torch.relu(self.bn1(self.conv1(x))))
        x = self.pool2(torch.relu(self.bn2(self.conv2(x))))
        x = self.pool3(torch.relu(self.bn3(self.conv3(x))))
        x = x.squeeze(-1)
        x = torch.relu(self.fc1(x))
        x = self.dropout(x)
        x = self.fc2(x)
        return x


class ExportModel(nn.Module):
    """导出用包装：输出 softmax 概率，保证 Java 端拿到 [0,1] 且和为 1。"""

    def __init__(self, base):
        super().__init__()
        self.base = base

    def forward(self, x):
        return torch.softmax(self.base(x), dim=1)


def main():
    parser = argparse.ArgumentParser(description='训练 DeepGuard 检测模型')
    parser.add_argument('--data', default='.', help='X.npy / y.npy 所在目录')
    parser.add_argument('--epochs', type=int, default=0,
                        help='强制 epoch 数（0 = 使用默认 50 + 早停）')
    parser.add_argument('--quick', action='store_true',
                        help='快速模式：最多 2 个 epoch（用于 CI 冒烟）')
    parser.add_argument('--batch-size', type=int, default=32)
    parser.add_argument('--seed', type=int, default=42)
    args = parser.parse_args()

    set_seed(args.seed)
    device = torch.device('cpu')
    print(f'使用设备: {device}')

    X_path, y_path = os.path.join(args.data, 'X.npy'), os.path.join(args.data, 'y.npy')
    if not (os.path.exists(X_path) and os.path.exists(y_path)):
        print('未找到 X.npy / y.npy，自动调用 prepare_data.py 生成...')
        import subprocess
        raw_dir = os.path.join(args.data, 'data')
        if not os.path.isdir(raw_dir):
            # 向上一级找 data/ 目录
            raw_dir = os.path.join(os.path.dirname(args.data), 'data')
        if os.path.isdir(raw_dir):
            subprocess.check_call([sys.executable, 'prepare_data.py', raw_dir, '--out', args.data])
        else:
            print(f'错误: 未找到 X.npy / y.npy，也未找到 raw data 目录 ({raw_dir})')
            print('请把 jsonl 样本放入 data/normal/ 与 data/cheat/ 目录，或先运行 prepare_data.py')
            sys.exit(1)

    print('加载数据...')
    X = np.load(X_path)
    y = np.load(y_path)
    print(f'总样本: {len(X)}, 作弊样本: {int(np.sum(y == 1))}')

    X_train, X_temp, y_train, y_temp = train_test_split(
        X, y, test_size=0.3, stratify=y, random_state=args.seed)
    X_val, X_test, y_val, y_test = train_test_split(
        X_temp, y_temp, test_size=0.5, stratify=y_temp, random_state=args.seed)

    train_loader = DataLoader(
        TensorDataset(torch.tensor(X_train), torch.tensor(y_train, dtype=torch.long)),
        batch_size=args.batch_size, shuffle=True)
    val_loader = DataLoader(
        TensorDataset(torch.tensor(X_val), torch.tensor(y_val, dtype=torch.long)),
        batch_size=args.batch_size)
    test_loader = DataLoader(
        TensorDataset(torch.tensor(X_test), torch.tensor(y_test, dtype=torch.long)),
        batch_size=args.batch_size)

    model = ScaffoldDetector().to(device)

    # 类别权重：作弊样本远少于正常样本（当前约 1:7）。不加权的话损失会被多数类
    # 主导，模型只要无脑预测「正常」就能拿到很高的准确率，作弊类召回直接塌掉。
    # 用 sklearn 式的 balanced 权重 w_c = N / (类别数 * 该类样本数)。
    counts = np.bincount(y_train, minlength=2).astype(np.float64)
    class_weights = len(y_train) / (2.0 * np.maximum(counts, 1.0))
    weight_tensor = torch.tensor(class_weights, dtype=torch.float32).to(device)
    criterion = nn.CrossEntropyLoss(weight=weight_tensor)
    print(f'训练集类别分布: 正常 {int(counts[0])} / 作弊 {int(counts[1])}')
    print(f'类别权重: 正常 {class_weights[0]:.3f} / 作弊 {class_weights[1]:.3f} '
          f'(作弊样本被放大 {class_weights[1] / class_weights[0]:.1f} 倍)')

    optimizer = optim.Adam(model.parameters(), lr=0.000006)
    scheduler = optim.lr_scheduler.ReduceLROnPlateau(optimizer, patience=5, factor=0.5)

    if args.quick:
        epochs = 2
        patience = 1
    else:
        epochs = args.epochs if args.epochs > 0 else 5000
        patience = 10

    best_val_loss = float('inf')
    early_stop_counter = 0
    epochs_run = 0
    train_losses, val_losses = [], []

    print(f'开始训练 (epochs={epochs}, 早停 patience={patience})...')
    start_time = time.time()
    for epoch in range(epochs):
        model.train()
        train_loss = 0.0
        for inputs, labels in train_loader:
            inputs, labels = inputs.to(device), labels.to(device)
            optimizer.zero_grad()
            loss = criterion(model(inputs), labels)
            loss.backward()
            optimizer.step()
            train_loss += loss.item()

        model.eval()
        val_loss, correct, total = 0.0, 0, 0
        with torch.no_grad():
            for inputs, labels in val_loader:
                inputs, labels = inputs.to(device), labels.to(device)
                outputs = model(inputs)
                val_loss += criterion(outputs, labels).item()
                _, predicted = torch.max(outputs, 1)
                total += labels.size(0)
                correct += (predicted == labels).sum().item()

        train_loss /= len(train_loader)
        val_loss /= len(val_loader)
        val_acc = correct / total
        train_losses.append(train_loss)
        val_losses.append(val_loss)
        scheduler.step(val_loss)

        print(f'Epoch {epoch + 1:3d}: Train Loss {train_loss:.4f}, '
              f'Val Loss {val_loss:.4f}, Val Acc {val_acc:.4f}')

        epochs_run = epoch + 1
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            torch.save(model.state_dict(), 'best_model.pth')
            early_stop_counter = 0
        else:
            early_stop_counter += 1
            if early_stop_counter >= patience:
                print('Early stopping')
                break

    print(f'训练完成，耗时 {time.time() - start_time:.1f} 秒')
    model.load_state_dict(torch.load('best_model.pth', map_location=device))
    model.eval()

    # 测试集评估
    all_preds, all_probs, all_labels = [], [], []
    with torch.no_grad():
        for inputs, labels in test_loader:
            inputs, labels = inputs.to(device), labels.to(device)
            outputs = model(inputs)
            probs = torch.softmax(outputs, dim=1)
            _, preds = torch.max(outputs, 1)
            all_preds.extend(preds.cpu().numpy())
            all_probs.extend(probs.cpu().numpy())
            all_labels.extend(labels.cpu().numpy())

    y_pred = np.array(all_preds)
    y_prob = np.array(all_probs)[:, 1]
    y_true = np.array(all_labels)
    test_report = classification_report(y_true, y_pred, target_names=['Normal', 'Cheat'],
                                        zero_division=0)
    print('\n测试集报告:')
    print(test_report)
    auc = None
    if len(np.unique(y_true)) > 1:
        auc = float(roc_auc_score(y_true, y_prob))
        print('AUC:', auc)

    plt.figure(figsize=(12, 4))
    plt.subplot(1, 2, 1)
    plt.plot(train_losses, label='Train Loss')
    plt.plot(val_losses, label='Val Loss')
    plt.legend()
    plt.title('Loss Curves')
    plt.savefig('training_curves.png', dpi=120)
    print('已保存训练曲线 training_curves.png')
    plt.close('all')

    # 导出 ONNX：softmax 概率输出，输入名与 Java 端一致
    export_model = ExportModel(model).to(device).eval()
    dummy_input = torch.randn(1, CHANNELS, TIME_STEPS).to(device)
    torch.onnx.export(
        export_model, dummy_input, 'scaffold_detector.onnx',
        input_names=['behavior_sequence'], output_names=['output'],
        dynamic_axes={'behavior_sequence': {0: 'batch_size'}},
        opset_version=12, dynamo=False)
    print('ONNX 模型已保存为 scaffold_detector.onnx')

    write_training_report(
        'training_report.md',
        model_path='scaffold_detector.onnx',
        device=str(device),
        n_total=len(X), n_cheat=int(np.sum(y == 1)),
        n_train=len(X_train), n_val=len(X_val), n_test=len(X_test),
        epochs_run=epochs_run, epochs_limit=epochs,
        best_val_loss=best_val_loss, final_train_loss=train_losses[-1],
        test_report=test_report, auc=auc,
        batch_size=args.batch_size, lr=0.000006, seed=args.seed,
        w_normal=float(class_weights[0]), w_cheat=float(class_weights[1]),
        duration=time.time() - start_time)


def _sha256_prefix(path, length=12):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()[:length]


def write_training_report(out_path, **m):
    """写出训练报告（Markdown）。Release 工作流会把它内联进 Release Notes 并作为产物上传。

    报告描述的是本次训练导出的那个模型，附上 ONNX 的 sha256 前缀，
    便于核对线上模型与报告是否对应。
    """
    auc_line = f'{m["auc"]:.4f}' if m['auc'] is not None else '不可用（测试集只有单一类别）'
    stopped = '（提前停止）' if m['epochs_run'] < m['epochs_limit'] else ''
    text = f"""# DeepGuard 模型训练报告

- 训练时间：{datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
- 训练设备：{m['device']}
- 耗时：{m['duration']:.1f} 秒

## 数据

- 样本总数：{m['n_total']}（正常 {m['n_total'] - m['n_cheat']} / 作弊 {m['n_cheat']}）
- 划分：训练 {m['n_train']} / 验证 {m['n_val']} / 测试 {m['n_test']}
- 特征图形状：({CHANNELS}, {TIME_STEPS})，与 `features.py` / `BehaviorImageBuilder.java` 逐位一致
- 类别权重：正常 {m['w_normal']:.3f} / 作弊 {m['w_cheat']:.3f}（balanced，抵消类别不平衡）

## 超参数

- 训练轮数：{m['epochs_run']} / {m['epochs_limit']}{stopped}
- batch size：{m['batch_size']}，学习率：{m['lr']}，随机种子：{m['seed']}
- 优化器：Adam + ReduceLROnPlateau

## 训练结果

- 最佳验证损失：{m['best_val_loss']:.4f}
- 最后一个 epoch 的训练损失：{m['final_train_loss']:.4f}
- 测试集 AUC：{auc_line}

测试集分类报告：

```
{m['test_report'].rstrip()}
```

## 产物

- `scaffold_detector.onnx` — sha256 前 12 位：`{_sha256_prefix(m['model_path'])}`
- `training_curves.png` — 损失曲线
"""
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write(text)
    print(f'已保存训练报告 {out_path}')


if __name__ == '__main__':
    main()
