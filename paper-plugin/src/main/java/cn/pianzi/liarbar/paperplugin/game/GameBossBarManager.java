package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player boss bar showing bullets remaining, hand size, main rank, and current turn.
 * Updated reactively from UserFacingEvents.
 */
public final class GameBossBarManager {

    private static final int MAX_BULLETS = 6;

    private final I18n i18n;

    /** playerId → active boss bar */
    private final Map<UUID, BossBar> activeBars = new ConcurrentHashMap<>();

    /** playerId → tableId (tracks which table a player is in) */
    private final Map<UUID, String> playerTables = new ConcurrentHashMap<>();

    /** tableId → set of player UUIDs at that table (reverse index for fast lookup) */
    private final Map<String, java.util.Set<UUID>> tablePlayerSets = new ConcurrentHashMap<>();

    /** tableId → current main rank */
    private final Map<String, String> tableMainRank = new ConcurrentHashMap<>();

    /** tableId → current turn player UUID */
    private final Map<String, UUID> tableTurn = new ConcurrentHashMap<>();

    /** playerId → bullets remaining */
    private final Map<UUID, Integer> playerBullets = new ConcurrentHashMap<>();

    /** playerId → hand size */
    private final Map<UUID, Integer> playerHandSize = new ConcurrentHashMap<>();

    public GameBossBarManager(I18n i18n) {
        this.i18n = i18n;
    }

    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            String eventType = event.eventType();
            if (eventType == null) continue;
            switch (eventType) {
                case "PLAYER_JOINED" -> onPlayerJoined(event);
                case "DEAL_COMPLETED" -> onDealCompleted(event);
                case "HAND_DEALT" -> onHandDealt(event);
                case "TURN_CHANGED" -> onTurnChanged(event);
                case "CARDS_PLAYED" -> onCardsPlayed(event);
                case "SHOT_RESOLVED" -> onShotResolved(event);
                case "PLAYER_ELIMINATED" -> onPlayerEliminated(event);
                case "GAME_FINISHED" -> onGameFinished(event);
                case "PLAYER_FORFEITED" -> onPlayerForfeited(event);
            }
        }
    }

    /**
     * Remove all boss bars for players at a specific table (used on table delete).
     */
    public void removeTable(String tableId) {
        java.util.Set<UUID> players = tablePlayerSets.remove(tableId);
        if (players != null) {
            for (UUID pid : List.copyOf(players)) {
                removeBarForPlayer(pid);
            }
        }
        tableMainRank.remove(tableId);
        tableTurn.remove(tableId);
    }

    public void removeAll() {
        for (Map.Entry<UUID, BossBar> entry : activeBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        activeBars.clear();
        playerTables.clear();
        tablePlayerSets.clear();
        tableMainRank.clear();
        tableTurn.clear();
        playerBullets.clear();
        playerHandSize.clear();
    }

    private void onPlayerJoined(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        String tableId = asString(event.data().get("tableId"));
        if (playerId == null || tableId == null) return;

        playerTables.put(playerId, tableId);
        tablePlayerSets.computeIfAbsent(tableId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        playerBullets.put(playerId, MAX_BULLETS); // default bullets
        playerHandSize.put(playerId, 0);

        BossBar bar = BossBar.bossBar(
                Component.text(i18n.t("ui.bossbar.waiting"), NamedTextColor.YELLOW),
                1.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_6
        );
        activeBars.put(playerId, bar);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.showBossBar(bar);
        }
    }

    private void onDealCompleted(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        String mainRank = asString(event.data().get("mainRank"));
        if (tableId == null) return;
        if (mainRank != null) {
            tableMainRank.put(tableId, mainRank);
        }
    }

    private void onHandDealt(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;

        Object cardsObj = event.data().get("cards");
        if (cardsObj instanceof List<?> cards) {
            playerHandSize.put(playerId, cards.size());
        }
        refreshBar(playerId);
    }

    private void onTurnChanged(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        UUID turnPlayer = asUuid(event.data().get("playerId"));
        if (tableId == null) return;

        tableTurn.put(tableId, turnPlayer);

        // Refresh all players at this table using reverse index (O(tableSize) instead of O(allPlayers))
        java.util.Set<UUID> players = tablePlayerSets.get(tableId);
        if (players != null) {
            for (UUID pid : players) {
                refreshBar(pid);
            }
        }
    }

    private void onCardsPlayed(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        int count = asInt(event.data().get("count"), 0);
        if (playerId == null) return;

        playerHandSize.merge(playerId, -count, Integer::sum);
        if (playerHandSize.getOrDefault(playerId, 0) < 0) {
            playerHandSize.put(playerId, 0);
        }
        refreshBar(playerId);
    }

    private void onShotResolved(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;

        int remaining = asInt(event.data().get("bulletsAfter"), -1);
        if (remaining >= 0) {
            playerBullets.put(playerId, remaining);
        } else {
            // Decrement by 1 if no explicit count
            playerBullets.merge(playerId, -1, Integer::sum);
        }
        refreshBar(playerId);
    }

    private void onPlayerEliminated(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;
        removeBarForPlayer(playerId);
    }

    private void onPlayerForfeited(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;
        removeBarForPlayer(playerId);
    }

    private void onGameFinished(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        if (tableId == null) return;

        // Remove bars for all players at this table using reverse index
        java.util.Set<UUID> players = tablePlayerSets.remove(tableId);
        if (players != null) {
            for (UUID pid : players) {
                removeBarForPlayer(pid);
            }
        }
        tableMainRank.remove(tableId);
        tableTurn.remove(tableId);
    }

    private void refreshBar(UUID playerId) {
        BossBar bar = activeBars.get(playerId);
        if (bar == null) return;

        String tableId = playerTables.get(playerId);
        String mainRank = tableId != null ? tableMainRank.getOrDefault(tableId, "?") : "?";
        int bullets = playerBullets.getOrDefault(playerId, MAX_BULLETS);
        int hand = playerHandSize.getOrDefault(playerId, 0);
        UUID turnPlayer = tableId != null ? tableTurn.get(tableId) : null;
        boolean isMyTurn = playerId.equals(turnPlayer);

        // Build boss bar title: localized labels, language-agnostic icons.
        Component title = Component.empty()
                .append(Component.text("🎯 " + i18n.t("ui.bossbar.main_rank_label"), NamedTextColor.GOLD))
                .append(Component.text(mainRank, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("🃏 " + i18n.t("ui.bossbar.hand_label"), NamedTextColor.AQUA))
                .append(Component.text(hand, NamedTextColor.WHITE))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("🔫 " + i18n.t("ui.bossbar.bullet_label"), NamedTextColor.RED))
                .append(Component.text(bullets + "/" + MAX_BULLETS, NamedTextColor.WHITE));

        if (isMyTurn) {
            title = title
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("▶ " + i18n.t("ui.bossbar.my_turn"), NamedTextColor.GREEN, TextDecoration.BOLD));
        }

        bar.name(title);

        // Progress = bullets / max bullets
        float progress = Math.max(0f, Math.min(1f, bullets / (float) MAX_BULLETS));
        bar.progress(progress);

        // Color based on bullets
        if (bullets <= 1) {
            bar.color(BossBar.Color.RED);
        } else if (bullets <= 3) {
            bar.color(BossBar.Color.YELLOW);
        } else {
            bar.color(BossBar.Color.GREEN);
        }
    }

    private void removeBarForPlayer(UUID playerId) {
        BossBar bar = activeBars.remove(playerId);
        if (bar != null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.hideBossBar(bar);
            }
        }
        String tableId = playerTables.remove(playerId);
        if (tableId != null) {
            java.util.Set<UUID> set = tablePlayerSets.get(tableId);
            if (set != null) {
                set.remove(playerId);
            }
        }
        playerBullets.remove(playerId);
        playerHandSize.remove(playerId);
    }

    private UUID asUuid(Object raw) {
        if (raw instanceof UUID uuid) return uuid;
        if (raw instanceof String text) {
            try { return UUID.fromString(text); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private int asInt(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
