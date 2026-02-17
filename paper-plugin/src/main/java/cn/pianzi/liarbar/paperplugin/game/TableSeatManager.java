package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seat manager with table-seat binding.
 * Uses GSit when available; otherwise falls back to native Interaction seats.
 */
public final class TableSeatManager {

    /** Seat offsets from table center: seat 0=west, 1=south, 2=east, 3=north. */
    private static final int[][] SEAT_OFFSETS = {
            {-2, 0, 0},
            {0, 0, 2},
            {2, 0, 0},
            {0, 0, -2},
    };

    /** Yaw facing toward center for each seat. */
    private static final float[] SEAT_YAWS = {
            90f,
            0f,
            -90f,
            180f,
    };

    private final JavaPlugin plugin;
    private final TableStructureBuilder structureBuilder;
    private final GsitSeatBridge gsitBridge;

    /** tableId -> native seat entities (one entity per seat). */
    private final Map<String, List<UUID>> tableSeatEntities = new ConcurrentHashMap<>();

    /** native seat entity uuid -> tableId. */
    private final Map<UUID, String> seatEntityToTable = new ConcurrentHashMap<>();

    public TableSeatManager(JavaPlugin plugin, TableStructureBuilder structureBuilder) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.structureBuilder = Objects.requireNonNull(structureBuilder, "structureBuilder");
        this.gsitBridge = GsitSeatBridge.tryCreate(plugin).orElse(null);
        if (gsitBridge != null) {
            plugin.getLogger().info("GSit detected, using GSit seat backend.");
        } else {
            plugin.getLogger().info("GSit not found, using native seat backend.");
        }
    }

    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            String eventType = event.eventType();
            if (eventType == null) {
                continue;
            }
            switch (eventType) {
                case "PLAYER_JOINED" -> handlePlayerJoined(event);
                case "GAME_FINISHED" -> handleGameFinished(event);
                case "PLAYER_ELIMINATED" -> handlePlayerEliminated(event);
                case "PLAYER_FORFEITED" -> handlePlayerForfeited(event);
            }
        }
    }

    /**
     * Spawn native seat entities for a table. GSit mode does not need pre-spawn.
     */
    public void spawnSeats(String tableId) {
        if (gsitBridge != null) {
            return;
        }
        spawnNativeSeats(tableId);
    }

    private void spawnNativeSeats(String tableId) {
        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) {
            return;
        }

        List<UUID> entities = new ArrayList<>(SEAT_OFFSETS.length);
        for (int[] offset : SEAT_OFFSETS) {
            Location seatLoc = center.clone().add(offset[0], offset[1], offset[2]);
            seatLoc.setYaw(0f);
            seatLoc.setPitch(0f);

            Interaction interaction = center.getWorld().spawn(seatLoc, Interaction.class, entity -> {
                entity.setInteractionWidth(0.6f);
                entity.setInteractionHeight(0.6f);
                entity.setPersistent(false);
                entity.setSilent(true);
            });
            entities.add(interaction.getUniqueId());
            seatEntityToTable.put(interaction.getUniqueId(), tableId);
        }
        tableSeatEntities.put(tableId, entities);
    }

    public void removeSeats(String tableId) {
        for (UUID playerId : seatedPlayersAtTable(tableId)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                dismount(player);
            }
        }

        List<UUID> entities = tableSeatEntities.remove(tableId);
        if (entities == null) {
            return;
        }
        for (UUID entityId : entities) {
            seatEntityToTable.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && !entity.isDead()) {
                for (Entity passenger : entity.getPassengers()) {
                    entity.removePassenger(passenger);
                }
                entity.remove();
            }
        }
    }

    public void removeAll() {
        for (String tableId : structureBuilder.tableIds()) {
            for (UUID playerId : seatedPlayersAtTable(tableId)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    dismount(player);
                }
            }
        }

        for (String tableId : List.copyOf(tableSeatEntities.keySet())) {
            removeSeats(tableId);
        }

        seatEntityToTable.clear();
    }

    /**
     * Detect currently seated players on the given table from actual seat occupancy.
     */
    public Set<UUID> seatedPlayersAtTable(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return Set.of();
        }

        List<Location> seatLocations = seatLocationsOf(tableId);
        if (seatLocations.isEmpty()) {
            return Set.of();
        }

        if (gsitBridge != null) {
            return Set.copyOf(gsitBridge.seatedPlayersOnBlocks(seatLocations.stream().map(Location::getBlock).toList()));
        }

        List<UUID> entities = tableSeatEntities.get(tableId);
        if (entities == null || entities.isEmpty()) {
            return Set.of();
        }

        Set<UUID> players = new HashSet<>();
        for (UUID entityId : entities) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                continue;
            }
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof Player player) {
                    players.add(player.getUniqueId());
                }
            }
        }
        return Set.copyOf(players);
    }

    /**
     * Resolve the table id by current seat occupancy.
     */
    public String tableOf(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return null;
        }
        for (String tableId : structureBuilder.tableIds()) {
            if (isPlayerSeatedAtTable(player, tableId)) {
                return tableId;
            }
        }
        return null;
    }

    private boolean isPlayerSeatedAtTable(Player player, String tableId) {
        List<Location> seatLocations = seatLocationsOf(tableId);
        if (seatLocations.isEmpty()) {
            return false;
        }

        if (gsitBridge != null) {
            return gsitBridge.isPlayerSittingOnBlocks(player, seatLocations.stream().map(Location::getBlock).toList());
        }

        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return false;
        }

        String boundTable = seatEntityToTable.get(vehicle.getUniqueId());
        return tableId.equals(boundTable);
    }

    private List<Location> seatLocationsOf(String tableId) {
        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) {
            return List.of();
        }

        List<Location> result = new ArrayList<>(SEAT_OFFSETS.length);
        for (int[] offset : SEAT_OFFSETS) {
            result.add(center.clone().add(offset[0], offset[1], offset[2]));
        }
        return result;
    }

    private void handlePlayerJoined(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        UUID playerId = asUuid(event.data().get("playerId"));
        int seat = asInt(event.data().get("seat"), -1);

        if (tableId == null || playerId == null || seat <= 0) {
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

        int seatIndex = (seat - 1) % SEAT_OFFSETS.length;
        int[] offset = SEAT_OFFSETS[seatIndex];

        Location seatLoc = center.clone().add(offset[0], offset[1], offset[2]);
        seatLoc.setYaw(SEAT_YAWS[seatIndex]);
        seatLoc.setPitch(10f);

        player.teleport(seatLoc);

        if (gsitBridge != null) {
            boolean seated = gsitBridge.sit(player, seatLoc.getBlock(), seatLoc.getYaw());
            if (seated) {
                return;
            }
            plugin.getLogger().warning("GSit seat failed for " + player.getName() + ", fallback to native seat.");
            if (!tableSeatEntities.containsKey(tableId)) {
                spawnNativeSeats(tableId);
            }
        }

        if (gsitBridge == null && !tableSeatEntities.containsKey(tableId)) {
            spawnSeats(tableId);
        }

        List<UUID> entities = tableSeatEntities.get(tableId);
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
        dismount(player);
    }

    private void handleGameFinished(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        if (tableId == null) {
            return;
        }

        for (UUID playerId : seatedPlayersAtTable(tableId)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                dismount(player);
            }
        }

        if (gsitBridge != null) {
            return;
        }

        // Keep native seat entities for next game, only clear passengers.
        List<UUID> entities = tableSeatEntities.get(tableId);
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
        dismount(player);
    }

    private void dismount(Player player) {
        if (gsitBridge != null) {
            gsitBridge.unsit(player);
            return;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Interaction) {
            vehicle.removePassenger(player);
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

    private int asInt(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
