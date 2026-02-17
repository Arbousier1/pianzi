package cn.pianzi.liarbar.paperplugin.presentation;

import cn.pianzi.liarbar.paper.presentation.EventSeverity;
import cn.pianzi.liarbar.paper.presentation.PacketEventsPublisher;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;

public final class PacketEventsActionBarPublisher implements PacketEventsPublisher {
    private static final Map<EventSeverity, String> COLOR_TAGS = Map.of(
            EventSeverity.INFO, "gray",
            EventSeverity.SUCCESS, "green",
            EventSeverity.WARNING, "yellow",
            EventSeverity.ERROR, "red"
    );

    private final JavaPlugin plugin;
    private final I18n i18n;
    private boolean packetEventsReady;
    private boolean packetEventsFailureLogged;

    public PacketEventsActionBarPublisher(JavaPlugin plugin, I18n i18n, boolean packetEventsReady) {
        this.plugin = plugin;
        this.i18n = i18n;
        this.packetEventsReady = packetEventsReady;
    }

    @Override
    public void publish(UserFacingEvent event) {
        UUID target = event.targetPlayer();
        if (target == null) {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                publishToPlayer(onlinePlayer, event);
            }
            return;
        }

        Player player = Bukkit.getPlayer(target);
        if (player != null && player.isOnline()) {
            publishToPlayer(player, event);
        }
    }

    private void publishToPlayer(Player player, UserFacingEvent event) {
        String resolvedMessage = i18n.t(event.message(), event.data());
        String tag = COLOR_TAGS.getOrDefault(event.severity(), "gray");
        String escaped = MiniMessageSupport.escape(resolvedMessage);
        // Pre-size: <tag> + escaped + </tag> ≈ tag*2 + escaped + 5 overhead
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
                plugin.getLogger().warning("PacketEvents 发送消息失败，已切换为仅 Bukkit 消息方式: " + rootMessage(throwable));
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
