package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends clickable chat messages to players when they receive cards or when it's their turn.
 * Each card is a clickable text element that runs the /liarbar play command.
 */
public final class ClickableCardPresenter {

    private static final String EVENT_TYPE_KEY = "_eventType";

    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            String eventType = String.valueOf(event.data().get(EVENT_TYPE_KEY));
            switch (eventType) {
                case "HAND_DEALT" -> onHandDealt(event);
                case "TURN_CHANGED" -> onTurnChanged(event);
                case "FORCE_CHALLENGE" -> onForceChallenge(event);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void onHandDealt(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        String tableId = asString(event.data().get("tableId"));
        String mainRank = asString(event.data().get("mainRank"));
        if (playerId == null || tableId == null) return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        Object cardsObj = event.data().get("cards");
        if (!(cardsObj instanceof List<?> rawCards) || rawCards.isEmpty()) return;

        // Header
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("═══════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("  🃏 你的手牌", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("  (主牌: ", NamedTextColor.GRAY))
                .append(Component.text(mainRank != null ? mainRank : "?", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(")", NamedTextColor.GRAY)));

        // Card row — each card is clickable
        Component cardRow = Component.text("  ", NamedTextColor.WHITE);
        for (int i = 0; i < rawCards.size(); i++) {
            Object cardObj = rawCards.get(i);
            String rank = "?";
            boolean demon = false;
            int slot = i + 1;

            if (cardObj instanceof Map<?, ?> cardMap) {
                Object rankObj = cardMap.get("rank");
                if (rankObj != null) rank = String.valueOf(rankObj);
                Object demonObj = cardMap.get("demon");
                if (Boolean.TRUE.equals(demonObj)) demon = true;
                Object idObj = cardMap.get("id");
                // slot index is 1-based for the play command
            }

            NamedTextColor cardColor = demon ? NamedTextColor.DARK_PURPLE : cardColor(rank);
            String displayText = demon ? "☠" + rank : rank;

            Component card = Component.text("[" + displayText + "]", cardColor, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(
                            Component.text("点击出牌: 槽位 " + slot, NamedTextColor.GREEN)
                                    .append(Component.newline())
                                    .append(Component.text("牌面: " + rank + (demon ? " (恶魔)" : ""), NamedTextColor.GRAY))
                    ))
                    .clickEvent(ClickEvent.runCommand("/liarbar play " + tableId + " " + slot));

            cardRow = cardRow.append(card);
            if (i < rawCards.size() - 1) {
                cardRow = cardRow.append(Component.text(" ", NamedTextColor.DARK_GRAY));
            }
        }
        player.sendMessage(cardRow);

        // Multi-select hint
        player.sendMessage(Component.text("  💡 可同时出多张: ", NamedTextColor.GRAY)
                .append(Component.text("/liarbar play " + tableId + " 1,2,3", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand("/liarbar play " + tableId + " "))
                        .hoverEvent(HoverEvent.showText(Component.text("点击填入命令", NamedTextColor.GREEN)))));

        player.sendMessage(Component.text("═══════════════════════════════", NamedTextColor.GOLD));
    }

    private void onTurnChanged(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        String tableId = asString(event.data().get("tableId"));
        if (playerId == null || tableId == null) return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        // Action buttons: [出牌] [质疑]
        Component actions = Component.text("  ▶ 轮到你了! ", NamedTextColor.GREEN, TextDecoration.BOLD)
                .append(Component.text("[出牌]", NamedTextColor.AQUA, TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("点击输入出牌命令", NamedTextColor.GREEN)))
                        .clickEvent(ClickEvent.suggestCommand("/liarbar play " + tableId + " ")))
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(Component.text("[质疑]", NamedTextColor.RED, TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("质疑上家出的牌!", NamedTextColor.RED)))
                        .clickEvent(ClickEvent.runCommand("/liarbar challenge " + tableId)));

        player.sendMessage(actions);
    }

    private void onForceChallenge(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        UUID playerId = asUuid(event.data().get("playerId"));
        if (tableId == null) return;

        // If we know who must challenge, send to them; otherwise it's broadcast
        if (playerId != null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(Component.text("  ⚠ 强制质疑! ", NamedTextColor.RED, TextDecoration.BOLD)
                        .append(Component.text("[点击质疑]", NamedTextColor.GOLD, TextDecoration.BOLD)
                                .hoverEvent(HoverEvent.showText(Component.text("你必须质疑!", NamedTextColor.RED)))
                                .clickEvent(ClickEvent.runCommand("/liarbar challenge " + tableId))));
            }
        }
    }

    private NamedTextColor cardColor(String rank) {
        return switch (rank) {
            case "A" -> NamedTextColor.RED;
            case "K" -> NamedTextColor.GOLD;
            case "Q" -> NamedTextColor.LIGHT_PURPLE;
            case "J" -> NamedTextColor.GREEN;
            default -> NamedTextColor.WHITE;
        };
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
}
