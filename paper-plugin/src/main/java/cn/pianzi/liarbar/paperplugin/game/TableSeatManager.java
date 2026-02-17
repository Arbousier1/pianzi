package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages invisible Interaction entities at each seat position.
 * When a player joins, they are teleported to their seat and "mounted" on the interaction entity
 * to simulate sitting. On game finish or table delete, entities are cleaned up.
 */
public final class TableSeatManager {

    private static final String EVENT_TYPE_KEY = "_eventType";

    /** Seat offsets from table center: seat 0=west, 1=south, 2=east, 3=north */
    private static final int[][] SEAT_OFFSETS = {
            {-2, 0, 0},  // seat 0
            {0, 0, 2},   // seat 1
            {2, 0, 0},   // seat 2
            {0, 0, -2},  // seat 3
    };

    /** Yaw facing toward center for each seat */
    private static final float[] SEAT_YAWS = {
            90f,   // seat 0 faces east (toward center)
            0f,    // seat 1 faces north
            -90f,  // seat 2 faces west
            180f,  // seat 3 faces south
    };

    private final JavaPlugin plugin;
    private final TableStructureBuilder structureBuilder;

    /** tableId → list of spawned interaction entity UUIDs */
    private final Map<String, List<UUID>> seatEntities = new ConcurrentHashMap<>();

    public TableSeatManager(JavaPlugin plugin, TableStructureBuilder structureBuilder) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.structureBuilder = Objects.requireNonNull(structureBuilder, "structureBuilder");
    }

    /**
     * Process events to seat players and clean up on game finish.
     */
    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            String eventType = String.valueOf(event.data().get(EVENT_TYPE_KEY));
            switch (eventType) {
                case "PLAYER_JOINED" -> handlePlayerJoined(event);
                case "GAME_FINISHED" -> handleGameFinished(event);
                case "PLAYER_ELIMINATED" -> handlePlayerEliminated(event);
                case "PLAYER_FORFEITED" -> handlePlayerForfeited(event);
            }
        }
    }

    /**
     * Spawn seat interaction entities for a table. Called when the table structure is built.
     */
    public void spawnSeats(String tableId) {
        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) {
            return;
        }

        List<UUID> entities = new ArrayList<>(4);
        for (int[] offset : SEAT_OFFSETS) {
            Location seatLoc = center.clone().add(offset[0], offset[1], offset[2]);
            seatLoc.setYaw(0);
            seatLoc.setPitch(0);

            Interaction interaction = center.getWorld().spawn(seatLoc, Interaction.class, entity -> {
                entity.setInteractionWidth(0.6f);
                entity.setInteractionHeight(0.6f);
                entity.setPersistent(false);
                entity.setSilent(true);
            });
            entities.add(interaction.getUniqueId());
        }
        seatEntities.put(tableId, entities);
    }

    /**
     * Remove all seat entities for a table.
     */
    public void removeSeats(String tableId) {
        List<UUID> entities = seatEntities.remove(tableId);
        if (entities == null) {
            return;
        }
        for (UUID entityId : entities) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && !entity.isDead()) {
                // Eject any passengers first
                for (Entity passenger : entity.getPassengers()) {
                    entity.removePassenger(passenger);
                }
                entity.remove();
            }
        }
    }

    /**
     * Remove all tracked seat entities (plugin disable).
     */
    public void removeAll() {
        for (String tableId : List.copyOf(seatEntities.keySet())) {
            removeSeats(tableId);
        }
    }

    private void handlePlayerJoined(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        UUID playerId = asUuid(event.data().get("playerId"));
        int seat = asInt(event.data().get("seat"), -1);

        if (tableId == null || playerId == null || seat < 0) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) {
            return;
        }

        // Ensure seats are spawned
        if (!seatEntities.containsKey(tableId)) {
            spawnSeats(tableId);
        }

        // Teleport player to seat position, facing center (core seats are 1-based)
        int seatIndex = (seat - 1) % SEAT_OFFSETS.length;
        int[] offset = SEAT_OFFSETS[seatIndex];
        Location seatLoc = center.clone().add(offset[0], offset[1], offset[2]);
        seatLoc.setYaw(SEAT_YAWS[seatIndex]);
        seatLoc.setPitch(10f); // slight downward look at table

        player.teleport(seatLoc);

        // Mount player on interaction entity
        List<UUID> entities = seatEntities.get(tableId);
        if (entities != null && seatIndex < entities.size()) {
            Entity seatEntity = Bukkit.getEntity(entities.get(seatIndex));
            if (seatEntity != null) {
                seatEntity.addPassenger(player);
            }
        }
    }

    private void handlePlayerEliminated(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        // Dismount eliminated player
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Interaction) {
            vehicle.removePassenger(player);
        }
    }

    private void handleGameFinished(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        if (tableId == null) {
            return;
        }
        // Dismount all passengers but keep entities for next game
        List<UUID> entities = seatEntities.get(tableId);
        if (entities == null) {
            return;
        }
        for (UUID entityId : entities) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                for (Entity passenger : entity.getPassengers()) {
                    entity.removePassenger(passenger);
                }
            }
        }
    }

    private void handlePlayerForfeited(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Interaction) {
            vehicle.removePassenger(player);
        }
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

    private int asInt(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
