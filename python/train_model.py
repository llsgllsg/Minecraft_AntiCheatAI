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
    def __init__(self, in_channels=12, num_classes=2):
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
    criterion = nn.CrossEntropyLoss()
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
    print('\n测试集报告:')
    print(classification_report(y_true, y_pred, target_names=['Normal', 'Cheat']))
    if len(np.unique(y_true)) > 1:
        print('AUC:', roc_auc_score(y_true, y_prob))

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
    dummy_input = torch.randn(1, 12, 128).to(device)
    torch.onnx.export(
        export_model, dummy_input, 'scaffold_detector.onnx',
        input_names=['behavior_sequence'], output_names=['output'],
        dynamic_axes={'behavior_sequence': {0: 'batch_size'}},
        opset_version=12, dynamo=False)
    print('ONNX 模型已保存为 scaffold_detector.onnx')


if __name__ == '__main__':
    main()
