package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.application.TableApplicationService;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class TablePlayerConnectionListener implements Listener {
    private final JavaPlugin plugin;
    private final TableApplicationService tableService;
    private final TableSeatManager seatManager;
    private final Consumer<List<UserFacingEvent>> eventSink;

    public TablePlayerConnectionListener(
            JavaPlugin plugin,
            TableApplicationService tableService,
            TableSeatManager seatManager,
            Consumer<List<UserFacingEvent>> eventSink
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tableService = Objects.requireNonNull(tableService, "tableService");
        this.seatManager = Objects.requireNonNull(seatManager, "seatManager");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
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
        String seatedTableId = seatManager.tableOf(playerId);
        List<String> targets = seatedTableId != null ? List.of(seatedTableId) : List.copyOf(tableService.tableIds());
        for (String tableId : targets) {
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
        eventSink.accept(events);
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
