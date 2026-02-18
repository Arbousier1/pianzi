package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static cn.pianzi.liarbar.paperplugin.util.EventDataAccessor.asInt;
import static cn.pianzi.liarbar.paperplugin.util.EventDataAccessor.asString;
import static cn.pianzi.liarbar.paperplugin.util.EventDataAccessor.asUuid;

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
        return Set.copyOf(seatedPlayersInSeatOrder(tableId));
    }

    /**
     * Detect currently seated players on the given table in seat index order.
     * seat-1, seat-2, seat-3, seat-4.
     */
    public List<UUID> seatedPlayersInSeatOrder(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return List.of();
        }

        List<Location> seatLocations = seatLocationsOf(tableId);
        if (seatLocations.isEmpty()) {
            return List.of();
        }

        if (gsitBridge != null) {
            List<UUID> ordered = new ArrayList<>(seatLocations.size());
            for (Location seatLocation : seatLocations) {
                Set<UUID> seated = gsitBridge.seatedPlayersOnBlocks(List.of(seatLocation.getBlock()));
                if (seated.isEmpty()) {
                    continue;
                }
                UUID selected = seated.stream()
                        .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString()))
                        .findFirst()
                        .orElse(null);
                if (selected != null) {
                    ordered.add(selected);
                }
            }
            return List.copyOf(ordered);
        }

        List<UUID> entities = tableSeatEntities.get(tableId);
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<UUID> players = new ArrayList<>(entities.size());
        for (UUID entityId : entities) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                continue;
            }
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof Player player) {
                    players.add(player.getUniqueId());
                    break;
                }
            }
        }
        return List.copyOf(players);
    }

    /**
     * Try to seat player by clicking a native seat interaction entity.
     */
    public boolean seatByNativeSeatEntity(Player player, UUID seatEntityId) {
        if (player == null || seatEntityId == null || gsitBridge != null) {
            return false;
        }
        String tableId = seatEntityToTable.get(seatEntityId);
        if (tableId == null) {
            return false;
        }

        Entity seatEntity = Bukkit.getEntity(seatEntityId);
        if (!(seatEntity instanceof Interaction interaction)) {
            return false;
        }
        if (!interaction.getPassengers().isEmpty()) {
            return false;
        }

        return mountNativeSeat(player, interaction);
    }

    public String tableOfSeatEntity(UUID seatEntityId) {
        if (seatEntityId == null) {
            return null;
        }
        return seatEntityToTable.get(seatEntityId);
    }

    /**
     * Try to seat player by clicking a seat block (oak stair).
     * Works with GSit and native seat backend.
     */
    public boolean seatBySeatBlock(Player player, Block clickedBlock) {
        if (player == null || clickedBlock == null) {
            return false;
        }

        SeatRef seat = resolveSeatByBlock(clickedBlock);
        if (seat == null) {
            return false;
        }

        Location seatLoc = seat.seatLocation();
        seatLoc.setYaw(SEAT_YAWS[seat.seatIndex()]);
        seatLoc.setPitch(10f);
        player.teleport(seatLoc);

        if (gsitBridge != null) {
            if (gsitBridge.sit(player, seatLoc.getBlock(), seatLoc.getYaw())) {
                return true;
            }
            plugin.getLogger().warning("GSit seat failed for " + player.getName() + ", fallback to native seat.");
        }

        if (!tableSeatEntities.containsKey(seat.tableId())) {
            spawnNativeSeats(seat.tableId());
        }
        List<UUID> entities = tableSeatEntities.get(seat.tableId());
        if (entities == null || seat.seatIndex() < 0 || seat.seatIndex() >= entities.size()) {
            return false;
        }
        Entity seatEntity = Bukkit.getEntity(entities.get(seat.seatIndex()));
        if (!(seatEntity instanceof Interaction interaction)) {
            return false;
        }
        return mountNativeSeat(player, interaction);
    }

    public String tableOfSeatBlock(Block clickedBlock) {
        if (clickedBlock == null) {
            return null;
        }
        SeatRef seat = resolveSeatByBlock(clickedBlock);
        return seat == null ? null : seat.tableId();
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

    private boolean mountNativeSeat(Player player, Interaction interaction) {
        if (!interaction.getPassengers().isEmpty()) {
            return false;
        }
        dismount(player);
        interaction.addPassenger(player);
        return true;
    }

    private SeatRef resolveSeatByBlock(Block clickedBlock) {
        for (String tableId : structureBuilder.tableIds()) {
            List<Location> seatLocations = seatLocationsOf(tableId);
            for (int seatIndex = 0; seatIndex < seatLocations.size(); seatIndex++) {
                Block seatBlock = seatLocations.get(seatIndex).getBlock();
                if (sameBlock(seatBlock, clickedBlock)) {
                    return new SeatRef(tableId, seatIndex, seatLocations.get(seatIndex).clone());
                }
            }
        }
        return null;
    }

    private boolean sameBlock(Block a, Block b) {
        return Objects.equals(a.getWorld(), b.getWorld())
                && a.getX() == b.getX()
                && a.getY() == b.getY()
                && a.getZ() == b.getZ();
    }

    private record SeatRef(String tableId, int seatIndex, Location seatLocation) {
    }

}
