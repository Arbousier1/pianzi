package cn.pianzi.liarbar.paperplugin.game;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import java.util.Objects;

/**
 * Native seat click handler. When GSit is absent and a player clicks one of this
 * plugin's seat interaction entities, mount the player onto that seat.
 */
public final class TableSeatInteractionListener implements Listener {
    private final TableSeatManager seatManager;

    public TableSeatInteractionListener(TableSeatManager seatManager) {
        this.seatManager = Objects.requireNonNull(seatManager, "seatManager");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSeatClicked(PlayerInteractAtEntityEvent event) {
        if (seatManager.seatByNativeSeatEntity(event.getPlayer(), event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
