package cn.pianzi.liarbar.paperplugin.game;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Runtime bridge for GSit API.
 * Uses reflection so the plugin can run normally when GSit is not installed.
 */
final class GsitSeatBridge {

    private final JavaPlugin plugin;
    private final Method getSeatByEntity;
    private final Method removeSeat;
    private final Method createSeat;
    private final Method getSeatsByBlock;
    private final Method seatGetEntity;
    private final Method seatGetBlock;
    private final Object stopReasonPlugin;

    private GsitSeatBridge(
            JavaPlugin plugin,
            Method getSeatByEntity,
            Method removeSeat,
            Method createSeat,
            Method getSeatsByBlock,
            Method seatGetEntity,
            Method seatGetBlock,
            Object stopReasonPlugin
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.getSeatByEntity = Objects.requireNonNull(getSeatByEntity, "getSeatByEntity");
        this.removeSeat = Objects.requireNonNull(removeSeat, "removeSeat");
        this.createSeat = Objects.requireNonNull(createSeat, "createSeat");
        this.getSeatsByBlock = Objects.requireNonNull(getSeatsByBlock, "getSeatsByBlock");
        this.seatGetEntity = Objects.requireNonNull(seatGetEntity, "seatGetEntity");
        this.seatGetBlock = Objects.requireNonNull(seatGetBlock, "seatGetBlock");
        this.stopReasonPlugin = Objects.requireNonNull(stopReasonPlugin, "stopReasonPlugin");
    }

    static Optional<GsitSeatBridge> tryCreate(JavaPlugin plugin) {
        Plugin gsit = Bukkit.getPluginManager().getPlugin("GSit");
        if (gsit == null || !gsit.isEnabled()) {
            return Optional.empty();
        }
        try {
            ClassLoader loader = gsit.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("dev.geco.gsit.api.GSitAPI", false, loader);
            Class<?> seatClass = firstAvailableClass(loader,
                    "dev.geco.gsit.model.Seat",
                    "dev.geco.gsit.objects.GSeat"
            );
            Class<?> stopReasonClass = firstAvailableClass(loader,
                    "dev.geco.gsit.model.StopReason",
                    "dev.geco.gsit.objects.GetUpReason"
            );

            Method getSeatByEntity = firstAvailableMethod(
                    apiClass,
                    new String[]{"getSeatByEntity"},
                    new Class<?>[]{LivingEntity.class},
                    new Class<?>[]{Player.class}
            );
            Method removeSeat = apiClass.getMethod("removeSeat", seatClass, stopReasonClass);
            Method createSeat = firstAvailableMethod(
                    apiClass,
                    new String[]{"createSeat"},
                    new Class<?>[]{Block.class, LivingEntity.class, boolean.class, double.class, double.class, double.class, float.class, boolean.class},
                    new Class<?>[]{Block.class, Player.class, boolean.class, double.class, double.class, double.class, float.class, boolean.class}
            );
            Method getSeatsByBlock = firstAvailableMethod(
                    apiClass,
                    new String[]{"getSeatsByBlock", "getSeats"},
                    new Class<?>[]{Block.class}
            );

            Method seatGetEntity = firstAvailableMethod(
                    seatClass,
                    new String[]{"getEntity", "getPlayer"}
            );
            Method seatGetBlock = firstAvailableMethod(
                    seatClass,
                    new String[]{"getBlock"}
            );

            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumType = (Class<? extends Enum>) stopReasonClass.asSubclass(Enum.class);
            Object stopReasonPlugin = enumConstantOrFirst(enumType, "PLUGIN");

            return Optional.of(new GsitSeatBridge(
                    plugin,
                    getSeatByEntity,
                    removeSeat,
                    createSeat,
                    getSeatsByBlock,
                    seatGetEntity,
                    seatGetBlock,
                    stopReasonPlugin
            ));
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Detected GSit but failed to bind API, fallback to native seats.", throwable);
            return Optional.empty();
        }
    }

    private static Class<?> firstAvailableClass(ClassLoader loader, String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ex) {
                last = ex;
            }
        }
        throw Objects.requireNonNull(last, "no class name candidates provided");
    }

    private static Method firstAvailableMethod(
            Class<?> type,
            String[] names,
            Class<?>[]... signatures
    ) throws NoSuchMethodException {
        NoSuchMethodException last = null;
        for (String name : names) {
            for (Class<?>[] signature : signatures) {
                try {
                    return type.getMethod(name, signature);
                } catch (NoSuchMethodException ex) {
                    last = ex;
                }
            }
        }
        if (signatures.length == 0) {
            for (String name : names) {
                try {
                    return type.getMethod(name);
                } catch (NoSuchMethodException ex) {
                    last = ex;
                }
            }
        }
        throw Objects.requireNonNull(last, "no method candidates provided");
    }

    private static Object enumConstantOrFirst(Class<? extends Enum> enumType, String constantName) {
        try {
            return Enum.valueOf(enumType, constantName);
        } catch (IllegalArgumentException ignored) {
            Object[] values = enumType.getEnumConstants();
            if (values == null || values.length == 0) {
                throw new IllegalStateException("No enum constants in " + enumType.getName());
            }
            return values[0];
        }
    }

    boolean sit(Player player, Block seatBlock, float yaw) {
        try {
            unsit(player);
            Object seat = createSeat.invoke(null, seatBlock, player, false, 0d, 0d, 0d, yaw, true);
            return seat != null;
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Failed to create GSit seat for player " + player.getName(), throwable);
            return false;
        }
    }

    void unsit(Player player) {
        try {
            Object seat = getSeatByEntity.invoke(null, player);
            if (seat == null) {
                return;
            }
            removeSeat.invoke(null, seat, stopReasonPlugin);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Failed to remove GSit seat for player " + player.getName(), throwable);
        }
    }

    Set<UUID> seatedPlayersOnBlocks(List<Block> blocks) {
        Set<UUID> players = new HashSet<>();
        for (Block block : blocks) {
            for (Block probe : verticallyAdjacentBlocks(block)) {
                try {
                    Object seats = getSeatsByBlock.invoke(null, probe);
                    if (!(seats instanceof Iterable<?> iterable)) {
                        continue;
                    }
                    for (Object seat : iterable) {
                        Object entity = seatGetEntity.invoke(seat);
                        if (entity instanceof Entity bukkitEntity) {
                            players.add(bukkitEntity.getUniqueId());
                        }
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.FINE, "Failed to read GSit seats on block " + probe, throwable);
                }
            }
        }
        return players;
    }

    boolean isPlayerSittingOnBlocks(Player player, List<Block> blocks) {
        try {
            Object seat = getSeatByEntity.invoke(null, player);
            if (seat == null) {
                return false;
            }
            Object blockRaw = seatGetBlock.invoke(seat);
            if (!(blockRaw instanceof Block seatBlock)) {
                return false;
            }
            for (Block block : blocks) {
                if (matchesSeatBlock(block, seatBlock)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Failed to check GSit seat table for player " + player.getName(), throwable);
            return false;
        }
    }

    private boolean matchesSeatBlock(Block expected, Block actual) {
        return Objects.equals(expected.getWorld(), actual.getWorld())
                && expected.getX() == actual.getX()
                && expected.getZ() == actual.getZ()
                && Math.abs(expected.getY() - actual.getY()) <= 1;
    }

    private List<Block> verticallyAdjacentBlocks(Block base) {
        return List.of(
                base,
                base.getRelative(0, 1, 0),
                base.getRelative(0, -1, 0)
        );
    }
}
