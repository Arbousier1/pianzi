package cn.pianzi.liarbar.paperplugin.integration.packet;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

public final class PacketEventsLifecycle {
    private final JavaPlugin plugin;
    private boolean ownsApi;
    private boolean ready;

    public PacketEventsLifecycle(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try {
            if (PacketEvents.getAPI() == null) {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
                ownsApi = true;
            }
            if (ownsApi && !PacketEvents.getAPI().isLoaded()) {
                PacketEvents.getAPI().getSettings()
                        .checkForUpdates(false)
                        .bStats(false)
                        .debug(false);
                PacketEvents.getAPI().load();
            }
            ready = true;
        } catch (Throwable throwable) {
            ready = false;
            plugin.getLogger().warning("PacketEvents 加载失败，将回退到 Bukkit 消息方式: " + rootMessage(throwable));
        }
    }

    public void init() {
        if (!ready || !ownsApi) {
            return;
        }
        try {
            if (!PacketEvents.getAPI().isInitialized()) {
                PacketEvents.getAPI().init();
            }
        } catch (Throwable throwable) {
            ready = false;
            plugin.getLogger().warning("PacketEvents 初始化失败，将回退到 Bukkit 消息方式: " + rootMessage(throwable));
        }
    }

    public void terminate() {
        if (!ready || !ownsApi) {
            return;
        }
        try {
            if (!PacketEvents.getAPI().isTerminated()) {
                PacketEvents.getAPI().terminate();
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("PacketEvents 关闭时出错: " + rootMessage(throwable));
        }
    }

    public boolean isReady() {
        return ready;
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
