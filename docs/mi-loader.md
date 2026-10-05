# 为什么 DeepGuard 暂不支持 Mi-loader（Mili Platform）

> 结论：**先不做，等它支持服务端。**
> 本文记录 2026-10-05 的调研依据，免得以后重复踩。

## TL;DR

Mi-loader 是一个定位在 **Minecraft 客户端** 的 Mod 加载器，目前没有任何服务端集成路径。
DeepGuard 是纯服务端反作弊 —— 拿不到服务端的每 tick 玩家行为，就什么都检测不了。
所以这不是"移植工作量"的问题，而是**平台上不存在可用的接入点**。

## 调研依据

仓库：[`MiliMCL/Mi-loader`](https://github.com/MiliMCL/Mi-loader)

| 项 | 事实 |
| --- | --- |
| 仓库创建时间 | 2026-10-04（调研时仅 1 天） |
| Star 数 | 2 |
| 定位（README 原文） | "Mili 的目标不是替代 Fabric，而是为 Minecraft **客户端** Mod 工程提供一个全新的、无历史包袱的基础设施选择" |
| 与 MC 交互的模块 | `mili-minecraft-integration` —— "反射桥接层，负责与 Minecraft 26.2 **客户端**交互" |
| 构建输入 | "Minecraft 26.2 **客户端** JAR 是 Mili Platform 的正式构建输入" |
| 模块构成 | `mili-abi` / `mili-runtime` / `mili-loader` / `mili-minecraft-integration` —— **没有服务端模块** |
| 与 Fabric 的关系 | "不克隆也不兼容 Fabric" |
| 字节码手段 | "不使用 Mixin，也不依赖 LaunchWrapper" |

关键几点展开：

1. **没有任何服务端入口。** 四个 Gradle 模块里唯一的游戏集成模块
   `mili-minecraft-integration` 明确只面向客户端；README 未提及 dedicated server。
   调研时仓库里也没有服务端方向的 issue 或分支。

2. **唯一的游戏交互手段是客户端 tick 轮询。** 桥接层通过反射访问
   `net.minecraft.client.Minecraft.getInstance()`，以 10 Hz 轮询客户端状态
   （`ClientTickPoller`）。服务端没有 `Minecraft` 这个单例，
   这套机制在 dedicated server 上无从谈起。

3. **ABI 刻意与 Minecraft 零耦合。** 这是它的设计优点（MC 升版本不用重编译 mod），
   但也意味着平台**不打算**提供"直接读写游戏内部状态"的能力 ——
   而反作弊恰恰重度依赖这个（每 tick 的位置/转向/载具/滑翔状态）。
   剩下能走的只有反射桥接，而服务端侧桥接根本不存在。

4. **不使用 Mixin。** 即便将来有了服务端支持，DeepGuard 现有的注入点
   （移动包、放置方块、传送、击退）也需要另一套机制重写。

5. **生态规模。** 创建一天、2 star。现在为它做一个拿不到收益的移植，
   性价比明显不划算。

## 什么情况下值得重新评估

- Mi-loader 出现**服务端/dedicated server 集成模块**，或 README 明确声明支持服务端；
- 出现真实的、跑在服务端的 Mi-loader 反作弊 / 服务端工具需求。

届时重新调研的入口：`https://github.com/MiliMCL/Mi-loader` 的 `mili-minecraft-integration`
模块与 README 的"模块构成"一节。

## 目前实际支持的平台

| 平台 | 状态 |
| --- | --- |
| Paper（Maven 模块 `AntiCheat/` + `recorder-plugin/`） | ✅ 完整功能 |
| Fabric（`fabric/`，Minecraft 26.2） | ✅ 可构建，v4.1.0 起随 Release 出包（当前为骨架） |
| NeoForge（`neoforge/`，Minecraft 26.2） | ✅ 可构建，下一次 Release 起出包（当前为骨架） |
| Mi-loader | ❌ 平台无服务端支持，见上 |

> 「骨架」= 工具链、构建、发布链路已打通，检测逻辑仍在从 Paper 版移植中。
