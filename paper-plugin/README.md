# Paper Plugin Runtime Layer

This module is the runnable Paper plugin layer and is split by class responsibility:

- `bootstrap`
  - `LiarBarPaperPlugin`: plugin lifecycle, wiring, tick loop.
- `command`
  - `LiarBarCommandExecutor`: human-friendly command + tab completion (`season list [page] [size]`, `season top <id> [page] [size] [score|wins]`, season-id suggestions, `reload`).
- `config`
  - `PluginSettings`: plugin-level options (`i18n.locale`, `i18n.timezone` included).
  - `TableConfigLoader`: maps config to core `TableConfig`.
- `integration.vault`
  - `VaultGatewayFactory`, `BukkitVaultGateway`: Vault bridge implementation.
- `integration.packet`
  - `PacketEventsLifecycle`: PacketEvents load/init/terminate lifecycle.
- `presentation`
  - `PacketEventsActionBarPublisher`: event rendering via PacketEvents with Bukkit fallback.
- `stats`
  - `LiarBarStatsService`: matchmaking settlement, ranking, season reset/archive orchestration.
  - `H2StatsRepository`: H2 database persistence implementation (live + history + season meta table).
  - `ScoreRule`, `RankTier`, `PlayerStatsSnapshot`, `SeasonResetResult`, `SeasonHistorySummary`, `SeasonListResult`, `SeasonTopResult`, `SeasonTopSort`: ranking/season model.
- `i18n`
  - `I18n`: ResourceBundle-based localization and localized time formatting.

The gameplay logic remains in `core`; this module only adapts Paper runtime concerns.
