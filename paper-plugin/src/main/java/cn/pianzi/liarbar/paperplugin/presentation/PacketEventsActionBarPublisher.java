package cn.pianzi.liarbar.paperplugin.presentation;

import cn.pianzi.liarbar.paper.presentation.EventSeverity;
import cn.pianzi.liarbar.paper.presentation.PacketEventsPublisher;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class PacketEventsActionBarPublisher implements PacketEventsPublisher {
    private final JavaPlugin plugin;
    private boolean packetEventsReady;
    private boolean packetEventsFailureLogged;

    public PacketEventsActionBarPublisher(JavaPlugin plugin, boolean packetEventsReady) {
        this.plugin = plugin;
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
        String body = "<" + colorTag(event.severity()) + ">" + MiniMessageSupport.escape(event.message()) + "</" + colorTag(event.severity()) + ">";
        String line = MiniMessageSupport.prefixed(body);
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

    private String colorTag(EventSeverity severity) {
        return switch (severity) {
            case INFO -> "gray";
            case SUCCESS -> "green";
            case WARNING -> "yellow";
            case ERROR -> "red";
        };
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
