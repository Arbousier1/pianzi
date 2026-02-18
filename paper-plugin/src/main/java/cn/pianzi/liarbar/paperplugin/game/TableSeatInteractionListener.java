package cn.pianzi.liarbar.paperplugin.game;

import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Objects;

/**
 * Seat click handler.
 * - Right click seat stair block: try seat via GSit/native backend.
 * - Right click native interaction seat entity: fallback seat for native backend.
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

    @EventHandler(ignoreCancelled = true)
    public void onSeatBlockClicked(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (seatManager.seatBySeatBlock(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }
}
