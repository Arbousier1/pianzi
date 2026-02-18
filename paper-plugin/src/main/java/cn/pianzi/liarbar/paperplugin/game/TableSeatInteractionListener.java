package cn.pianzi.liarbar.paperplugin.game;

import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Seat click handler.
 * - Right click seat stair block: try seat via GSit/native backend.
 * - Right click native interaction seat entity: fallback seat for native backend.
 */
public final class TableSeatInteractionListener implements Listener {
    private final TableSeatManager seatManager;
    private final BiConsumer<Player, String> seatedCallback;

    public TableSeatInteractionListener(
            TableSeatManager seatManager,
            BiConsumer<Player, String> seatedCallback
    ) {
        this.seatManager = Objects.requireNonNull(seatManager, "seatManager");
        this.seatedCallback = Objects.requireNonNull(seatedCallback, "seatedCallback");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSeatClicked(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (seatManager.seatByNativeSeatEntity(event.getPlayer(), event.getRightClicked().getUniqueId())) {
            String tableId = seatManager.tableOfSeatEntity(event.getRightClicked().getUniqueId());
            if (tableId != null) {
                seatedCallback.accept(event.getPlayer(), tableId);
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSeatBlockClicked(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (seatManager.seatBySeatBlock(event.getPlayer(), event.getClickedBlock())) {
            String tableId = seatManager.tableOfSeatBlock(event.getClickedBlock());
            if (tableId != null) {
                seatedCallback.accept(event.getPlayer(), tableId);
            }
            event.setCancelled(true);
        }
    }
}
