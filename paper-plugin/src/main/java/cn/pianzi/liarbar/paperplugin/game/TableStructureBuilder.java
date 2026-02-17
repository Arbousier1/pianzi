package cn.pianzi.liarbar.paperplugin.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places and removes the physical table structure in the world,
 * mirroring the datapack's e_2_table_generate.mcfunction.
 */
public final class TableStructureBuilder {

    private final Map<String, Location> tableLocations = new ConcurrentHashMap<>();

    /**
     * Build the table structure at the given location and remember it for later cleanup.
     */
    public void build(String tableId, Location center) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(center, "center");
        World world = center.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location must have a world");
        }

        tableLocations.put(tableId, center.clone());

        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();

        // Center froglight
        world.getBlockAt(x, y, z).setType(Material.OCHRE_FROGLIGHT);

        // 4 seat stairs (bottom half)
        setStair(world, x - 2, y, z, BlockFace.WEST, Bisected.Half.BOTTOM, Stairs.Shape.STRAIGHT);   // seat 1
        setStair(world, x, y, z + 2, BlockFace.SOUTH, Bisected.Half.BOTTOM, Stairs.Shape.STRAIGHT);  // seat 2
        setStair(world, x + 2, y, z, BlockFace.EAST, Bisected.Half.BOTTOM, Stairs.Shape.STRAIGHT);   // seat 3
        setStair(world, x, y, z - 2, BlockFace.NORTH, Bisected.Half.BOTTOM, Stairs.Shape.STRAIGHT);  // seat 4

        // Table surface stairs (top half) — 8 blocks around center
        setStair(world, x - 1, y, z + 1, BlockFace.NORTH, Bisected.Half.TOP, Stairs.Shape.OUTER_RIGHT);
        setStair(world, x - 1, y, z,     BlockFace.EAST,  Bisected.Half.TOP, Stairs.Shape.STRAIGHT);
        setStair(world, x - 1, y, z - 1, BlockFace.EAST,  Bisected.Half.TOP, Stairs.Shape.OUTER_RIGHT);
        setStair(world, x,     y, z + 1, BlockFace.NORTH, Bisected.Half.TOP, Stairs.Shape.STRAIGHT);
        setStair(world, x,     y, z - 1, BlockFace.SOUTH, Bisected.Half.TOP, Stairs.Shape.STRAIGHT);
        setStair(world, x + 1, y, z + 1, BlockFace.NORTH, Bisected.Half.TOP, Stairs.Shape.OUTER_LEFT);
        setStair(world, x + 1, y, z,     BlockFace.WEST,  Bisected.Half.TOP, Stairs.Shape.STRAIGHT);
        setStair(world, x + 1, y, z - 1, BlockFace.SOUTH, Bisected.Half.TOP, Stairs.Shape.OUTER_RIGHT);
    }

    /**
     * Remove the table structure previously built for the given tableId.
     * Returns true if the table was found and removed.
     */
    public boolean demolish(String tableId) {
        Location center = tableLocations.remove(tableId);
        if (center == null) {
            return false;
        }
        World world = center.getWorld();
        if (world == null) {
            return false;
        }

        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();

        // Clear center
        world.getBlockAt(x, y, z).setType(Material.AIR);

        // Clear seats
        world.getBlockAt(x - 2, y, z).setType(Material.AIR);
        world.getBlockAt(x, y, z + 2).setType(Material.AIR);
        world.getBlockAt(x + 2, y, z).setType(Material.AIR);
        world.getBlockAt(x, y, z - 2).setType(Material.AIR);

        // Clear table surface
        world.getBlockAt(x - 1, y, z + 1).setType(Material.AIR);
        world.getBlockAt(x - 1, y, z).setType(Material.AIR);
        world.getBlockAt(x - 1, y, z - 1).setType(Material.AIR);
        world.getBlockAt(x, y, z + 1).setType(Material.AIR);
        world.getBlockAt(x, y, z - 1).setType(Material.AIR);
        world.getBlockAt(x + 1, y, z + 1).setType(Material.AIR);
        world.getBlockAt(x + 1, y, z).setType(Material.AIR);
        world.getBlockAt(x + 1, y, z - 1).setType(Material.AIR);

        return true;
    }

    /**
     * Remove all tracked table structures (used on plugin disable).
     */
    public void demolishAll() {
        for (String tableId : tableLocations.keySet()) {
            demolish(tableId);
        }
    }

    /**
     * Get the stored center location for a table, or null if not tracked.
     */
    public Location locationOf(String tableId) {
        Location loc = tableLocations.get(tableId);
        return loc != null ? loc.clone() : null;
    }

    private void setStair(World world, int x, int y, int z,
                          BlockFace facing, Bisected.Half half, Stairs.Shape shape) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_STAIRS, false);
        if (block.getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing);
            stairs.setHalf(half);
            stairs.setShape(shape);
            stairs.setWaterlogged(false);
            block.setBlockData(stairs, false);
        }
    }
}
