package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.application.TableApplicationService;
import cn.pianzi.liarbar.paper.presentation.PacketEventsViewBridge;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.stats.LiarBarStatsService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TablePlayerConnectionListener implements Listener {
    private final JavaPlugin plugin;
    private final TableApplicationService tableService;
    private final PacketEventsViewBridge viewBridge;
    private final LiarBarStatsService statsService;
    private final DatapackParityRewardService rewardService;

    public TablePlayerConnectionListener(
            JavaPlugin plugin,
            TableApplicationService tableService,
            PacketEventsViewBridge viewBridge,
            LiarBarStatsService statsService,
            DatapackParityRewardService rewardService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tableService = Objects.requireNonNull(tableService, "tableService");
        this.viewBridge = Objects.requireNonNull(viewBridge, "viewBridge");
        this.statsService = Objects.requireNonNull(statsService, "statsService");
        this.rewardService = Objects.requireNonNull(rewardService, "rewardService");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handleDisconnect(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        handleDisconnect(event.getPlayer().getUniqueId());
    }

    private void handleDisconnect(UUID playerId) {
        for (String tableId : tableService.tableIds()) {
            tableService.playerDisconnected(tableId, playerId).whenComplete((events, throwable) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (throwable != null) {
                            if (!isTableNotFound(throwable)) {
                                plugin.getLogger().warning("Failed to handle player disconnect for table "
                                        + tableId + ": " + rootMessage(throwable));
                            }
                            return;
                        }
                        if (events == null || events.isEmpty()) {
                            return;
                        }
                        applyEvents(events);
                    })
            );
        }
    }

    private void applyEvents(List<UserFacingEvent> events) {
        statsService.handleEvents(events);
        rewardService.handleEvents(events);
        viewBridge.publishAll(events);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private boolean isTableNotFound(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase();
        return message.contains("table not found");
    }
}
