package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.presentation.MiniMessageSupport;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DatapackParityRewardService {

    private final JavaPlugin plugin;
    private final I18n i18n;

    public DatapackParityRewardService(JavaPlugin plugin, I18n i18n) {
        this.plugin = plugin;
        this.i18n = i18n;
    }

    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            if (!"GAME_FINISHED".equals(event.eventType())) {
                continue;
            }
            grantLifeModeReward(event);
        }
    }

    private void grantLifeModeReward(UserFacingEvent event) {
        if (!"LIFE_ONLY".equals(String.valueOf(event.data().get("mode")))) {
            return;
        }
        UUID winner = asUuid(event.data().get("winner"));
        if (winner == null) {
            return;
        }
        Player player = Bukkit.getPlayer(winner);
        if (player == null || !player.isOnline()) {
            return;
        }

        int amount = clampPositive(asInt(event.data().get("joinedCount"), 1), 64);
        ItemStack reward = new ItemStack(Material.BREAD, amount);
        ItemMeta meta = reward.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.displayName(MiniMessageSupport.parse(i18n.t("reward.life_mode.item_name")));
        meta.lore(List.of(MiniMessageSupport.parse(i18n.t("reward.life_mode.item_lore"))));
        reward.setItemMeta(meta);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(reward);
        for (ItemStack item : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }

    private UUID asUuid(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().fine("Invalid winner UUID in reward event: " + text);
            }
        }
        return null;
    }

    private int asInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int clampPositive(int value, int max) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, max);
    }
}
