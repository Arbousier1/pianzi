package cn.pianzi.liarbar.paperplugin.presentation;

import cn.pianzi.liarbar.paper.presentation.EventSeverity;
import cn.pianzi.liarbar.paper.presentation.PacketEventsPublisher;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.game.TableSeatManager;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketEventsActionBarPublisher implements PacketEventsPublisher {
    private static final long DUPLICATE_WINDOW_MILLIS = 1_000L;
    private static final Map<EventSeverity, String> COLOR_TAGS = Map.of(
            EventSeverity.INFO, "gray",
            EventSeverity.SUCCESS, "green",
            EventSeverity.WARNING, "yellow",
            EventSeverity.ERROR, "red"
    );

    private final JavaPlugin plugin;
    private final I18n i18n;
    private final TableSeatManager seatManager;
    private final EventFingerprintEncoder fingerprintEncoder = new EventFingerprintEncoder();
    private boolean packetEventsReady;
    private boolean packetEventsFailureLogged;

    /** tableId -> set of player UUIDs currently at that table. */
    private final Map<String, Set<UUID>> tablePlayers = new ConcurrentHashMap<>();
    /** playerId -> tableId (reverse index). */
    private final Map<UUID, String> playerToTable = new ConcurrentHashMap<>();
    /** playerId -> last sent fingerprint for duplicate suppression. */
    private final Map<UUID, SentFingerprint> lastSent = new ConcurrentHashMap<>();

    public PacketEventsActionBarPublisher(JavaPlugin plugin, I18n i18n, boolean packetEventsReady, TableSeatManager seatManager) {
        this.plugin = plugin;
        this.i18n = i18n;
        this.packetEventsReady = packetEventsReady;
        this.seatManager = seatManager;
    }

    @Override
    public void publishAll(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            trackMembership(event);
        }
        for (UserFacingEvent event : events) {
            publish(event);
        }
    }

    @Override
    public void publish(UserFacingEvent event) {
        UUID target = event.targetPlayer();
        if (target != null) {
            Player player = Bukkit.getPlayer(target);
            if (player != null && player.isOnline()) {
                publishToPlayer(player, event);
            }
            return;
        }

        String tableId = asString(event.data().get("tableId"));
        if (tableId != null) {
            if (seatManager != null) {
                Set<UUID> seated = seatManager.seatedPlayersAtTable(tableId);
                if (!seated.isEmpty()) {
                    for (UUID pid : seated) {
                        Player p = Bukkit.getPlayer(pid);
                        if (p != null && p.isOnline()) {
                            publishToPlayer(p, event);
                        }
                    }
                    return;
                }
            }

            Set<UUID> players = tablePlayers.get(tableId);
            if (players != null && !players.isEmpty()) {
                for (UUID pid : players) {
                    Player p = Bukkit.getPlayer(pid);
                    if (p != null && p.isOnline()) {
                        publishToPlayer(p, event);
                    }
                }
                return;
            }
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            publishToPlayer(onlinePlayer, event);
        }
    }

    public void removeTable(String tableId) {
        Set<UUID> removed = tablePlayers.remove(tableId);
        if (removed != null) {
            for (UUID pid : removed) {
                playerToTable.remove(pid);
                lastSent.remove(pid);
            }
        }
    }

    public void removeAll() {
        tablePlayers.clear();
        playerToTable.clear();
        lastSent.clear();
    }

    private void trackMembership(UserFacingEvent event) {
        String type = event.eventType();
        if (type == null) {
            return;
        }
        switch (type) {
            case "PLAYER_JOINED" -> {
                UUID pid = asUuid(event.data().get("playerId"));
                String tid = asString(event.data().get("tableId"));
                if (pid != null && tid != null) {
                    tablePlayers.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet()).add(pid);
                    playerToTable.put(pid, tid);
                }
            }
            case "PLAYER_ELIMINATED", "PLAYER_FORFEITED" -> {
                UUID pid = asUuid(event.data().get("playerId"));
                if (pid != null) {
                    String tid = playerToTable.remove(pid);
                    lastSent.remove(pid);
                    if (tid != null) {
                        Set<UUID> set = tablePlayers.get(tid);
                        if (set != null) {
                            set.remove(pid);
                        }
                    }
                }
            }
            case "GAME_FINISHED" -> {
                String tid = asString(event.data().get("tableId"));
                if (tid != null) {
                    Set<UUID> removed = tablePlayers.remove(tid);
                    if (removed != null) {
                        for (UUID pid : removed) {
                            playerToTable.remove(pid);
                            lastSent.remove(pid);
                        }
                    }
                }
            }
        }
    }

    private UUID asUuid(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private void publishToPlayer(Player player, UserFacingEvent event) {
        Map<String, Object> localizedData = localizePlayerPlaceholders(event.data());
        String fingerprint = fingerprintEncoder.encode(event, localizedData);
        if (isDuplicate(player.getUniqueId(), fingerprint)) {
            return;
        }

        String resolvedMessage = i18n.t(event.message(), localizedData);
        String tag = COLOR_TAGS.getOrDefault(event.severity(), "gray");
        String escaped = MiniMessageSupport.escape(resolvedMessage);
        StringBuilder sb = new StringBuilder(tag.length() * 2 + escaped.length() + 6);
        sb.append('<').append(tag).append('>').append(escaped).append("</").append(tag).append('>');
        String line = MiniMessageSupport.prefixed(sb.toString());
        Component component = MiniMessageSupport.parse(line);
        player.sendMessage(component);

        if (!packetEventsReady) {
            return;
        }

        try {
            WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(component);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Throwable throwable) {
            packetEventsReady = false;
            if (!packetEventsFailureLogged) {
                packetEventsFailureLogged = true;
                plugin.getLogger().warning("PacketEvents actionbar failed, fallback to Bukkit message: " + rootMessage(throwable));
            }
        }
    }

    private boolean isDuplicate(UUID playerId, String fingerprint) {
        long now = System.currentTimeMillis();
        SentFingerprint previous = lastSent.get(playerId);
        if (previous != null
                && previous.fingerprint().equals(fingerprint)
                && now - previous.sentAtMillis() <= DUPLICATE_WINDOW_MILLIS) {
            return true;
        }
        lastSent.put(playerId, new SentFingerprint(fingerprint, now));
        return false;
    }

    private Map<String, Object> localizePlayerPlaceholders(Map<String, Object> original) {
        if (original == null || original.isEmpty()) {
            return Map.of();
        }
        HashMap<String, Object> data = HashMap.newHashMap(original.size() + 4);
        data.putAll(original);
        localizePlayerField(data, "player", original.get("playerId"));
        localizePlayerField(data, "winner", original.get("winner"));
        localizePlayerField(data, "challenger", original.get("challenger"));
        localizePlayerField(data, "lastPlayer", original.get("lastPlayer"));
        return data;
    }

    private void localizePlayerField(Map<String, Object> data, String displayKey, Object idRaw) {
        String name = resolvePlayerName(idRaw);
        if (name != null && !name.isBlank()) {
            data.put(displayKey, name);
        }
    }

    private String resolvePlayerName(Object raw) {
        UUID id = asUuid(raw);
        if (id == null) {
            if (raw == null) {
                return null;
            }
            String text = String.valueOf(raw);
            return text.isBlank() ? null : text;
        }

        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        if (offline.getName() != null && !offline.getName().isBlank()) {
            return offline.getName();
        }
        String text = id.toString();
        return text.substring(0, 8);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record SentFingerprint(String fingerprint, long sentAtMillis) {
    }
}
