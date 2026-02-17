# Paper 1.21.11 改写建议

## 目标
- 玩法核心保持与数据包一致。
- 不使用 mcfunction 逻辑链，不依赖命令方块。
- 资源包继续沿用，仅替换逻辑执行层。

## 架构分层
- `core`（已实现）：纯 Java 状态机与规则，不依赖 Bukkit/Paper。
- `adapter-paper`（后续）：监听玩家行为、调用 `core`，并把 `CoreEvent` 映射为 UI/音效/实体展示。
- `integration`（后续）：Vault、PacketEvents、持久化等。

## 指令系统（建议）
- 使用 Brigadier 子命令，替代 trigger：
  - `/liarbar mode <life|fantuan|kunkun>`
  - `/liarbar join <table>`
  - `/liarbar play <slot...>`
  - `/liarbar challenge`
  - `/liarbar leave`
- 提供 Tab 补全 + 参数校验，避免玩家输错触发隐藏状态。

## PacketEvents 接入点
- 用于低层包优化与交互反馈（ActionBar、BossBar、轻量交互包）。
- 从 `CoreEvent` 到客户端反馈的映射建议：
  - `TURN_CHANGED` -> 回合提示 + 高亮 UI
  - `CARDS_PLAYED` -> 牌面动画
  - `SHOT_RESOLVED` -> 开枪音效/粒子
  - `GAME_FINISHED` -> 胜负结算界面

## Vault 接入点
- `EconomyPort` 直接对接 Vault：
  - `charge`：进桌扣费（fantuan/kunkun 模式）
  - `reward`：胜者返奖（按进桌人数）

## 并发建议
- 每桌一个 `AsyncTableRuntime` 串行执行（单桌无锁）。
- 多桌并行运行（多 executor）。
- 主线程只做 Paper API 调用，重计算留在 core runtime 线程。

## 内存与稳定性
- 只向外暴露 `GameSnapshot`（不可变快照），避免外部持有内部可变对象。
- 玩家断线、跨服重连时，仅通过 `playerId` 重绑定会话，不重写核心状态。
- 统一超时驱动：Paper 调度器每秒调用 `tickSecond()`。
