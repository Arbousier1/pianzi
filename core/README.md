# Liar Bar Core

`core` 是数据包玩法的纯 Java 抽象层，不依赖 Minecraft 函数或命令系统。

## 设计目标
- 还原数据包核心规则：模式选择、加入、发牌、回合出牌、质疑、开枪、淘汰、胜负。
- 与展示层解耦：资源包继续负责显示，`core` 只产出语义事件。
- 线程安全：支持单桌串行、多桌并发（`AsyncTableRuntime` / `LiarBarRuntimeManager`）。

## 关键规则映射
- 牌池：`A x7`、`Q x6`、`K x5`、`J x2`。
- 主牌：每轮随机 `A/Q/K`。
- 恶魔牌：从“主牌集合”中随机 1 张（对应数据包的 `card_demon`，不可与其他牌同出）。
- 出牌：每次 `1-3` 张。
- 质疑结果：
  - 含恶魔牌：除上家外的存活玩家进入开枪候选。
  - 含非主牌：上家进入开枪候选，质疑者成为下轮优先人选。
  - 全主牌：质疑者进入开枪候选，质疑者下家成为下轮优先人选。
- 转轮：子弹数递减，`1/当前子弹数` 几率中弹；子弹仅在玩家淘汰时清零。

## 与 Paper 对接建议
- 指令系统：将 `/trigger` 改为 Brigadier 子命令（如 `/liarbar play 1 3`、`/liarbar challenge`）。
- Vault：通过 `EconomyPort` 适配货币扣费/奖励。
- PacketEvents：通过事件（`CoreEvent`）推送 UI 变化（ActionBar/BossBar/自定义包）。
- 资源包：继续沿用现有数据包材质，仅把“显示条件”改由 `core` 事件驱动。
