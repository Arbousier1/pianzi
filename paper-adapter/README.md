# Paper Adapter (Preparation Layer)

该模块是把 `core` 接到 Paper 插件的预备层，按类职责分包：

- `application`
  - `TableApplicationService`: 对局业务入口，串联 runtime 与事件翻译。
- `command`
  - `PaperCommandFacade`: 指令侧门面，适合后续直接接 Brigadier。
  - `CommandOutcome`: 指令执行结果。
- `presentation`
  - `CoreEventTranslator`: `CoreEvent -> UserFacingEvent` 映射。
  - `PacketEventsPublisher` / `PacketEventsViewBridge`: PacketEvents 展示桥接口。
- `integration.vault`
  - `VaultGateway`: Vault 抽象网关。
  - `VaultEconomyAdapter`: `EconomyPort` 的 Vault 适配实现。

说明：
- 当前模块不直接依赖 Paper API，先保证业务层清晰可测。
- 接入 Paper 1.21.11 时，只需新增 `plugin` 启动类与实际 listener/command 注册代码。
