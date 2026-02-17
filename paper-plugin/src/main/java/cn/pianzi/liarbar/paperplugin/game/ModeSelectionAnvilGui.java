package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.paper.command.CommandOutcome;
import cn.pianzi.liarbar.paper.command.PaperCommandFacade;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.presentation.MiniMessageSupport;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Mode selector opened after player sits at table in MODE_SELECTION phase.
 * Uses an anvil UI: player inputs mode text and confirms from result slot.
 */
public final class ModeSelectionAnvilGui implements Listener {

    private final JavaPlugin plugin;
    private final PaperCommandFacade commandFacade;
    private final Consumer<List<UserFacingEvent>> eventSink;
    private final I18n i18n;

    /** Player currently using this GUI -> holder session id */
    private final Map<UUID, UUID> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public ModeSelectionAnvilGui(
            JavaPlugin plugin,
            PaperCommandFacade commandFacade,
            Consumer<List<UserFacingEvent>> eventSink,
            I18n i18n
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.commandFacade = Objects.requireNonNull(commandFacade, "commandFacade");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    public void open(Player player, String tableId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tableId, "tableId");

        ModeSelectionHolder holder = new ModeSelectionHolder(tableId);
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL, i18n.t("ui.mode_gui.title"));
        inventory.setItem(0, hintItem(tableId));
        player.openInventory(inventory);
        activeSessions.put(player.getUniqueId(), holder.sessionId());
        player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(i18n.t("ui.mode_gui.open_hint"))));
    }

    public void closeAll() {
        for (UUID playerId : List.copyOf(activeSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        }
        activeSessions.clear();
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof ModeSelectionHolder holder)) {
            return;
        }

        TableMode mode = parseMode(readRenameText(event.getInventory()));
        if (mode == null) {
            event.setResult(null);
            return;
        }

        event.setResult(confirmItem(mode));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ModeSelectionHolder holder)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        // Keep GUI stable and only allow result-slot confirm.
        if (event.getRawSlot() != 2) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        TableMode mode = parseMode(readRenameText((AnvilInventory) event.getView().getTopInventory()));
        if (mode == null) {
            player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                    i18n.t("ui.mode_gui.invalid_input")
            )));
            return;
        }

        activeSessions.remove(player.getUniqueId(), holder.sessionId());
        player.closeInventory();
        selectMode(player, holder.tableId(), mode);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ModeSelectionHolder holder)) {
            return;
        }

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (activeSessions.remove(player.getUniqueId(), holder.sessionId())) {
            player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                    i18n.t("ui.mode_gui.close_hint", Map.of("table", MiniMessageSupport.escape(holder.tableId())))
            )));
        }
    }

    private void selectMode(Player player, String tableId, TableMode mode) {
        CompletionStage<CommandOutcome> future = commandFacade.selectMode(tableId, player.getUniqueId(), mode);
        future.whenComplete((outcome, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        sendFailed(player, localizedReason(throwable));
                        return;
                    }
                    if (!outcome.success()) {
                        sendFailed(player, outcome.message());
                        return;
                    }

                    String modeName = switch (mode) {
                        case LIFE_ONLY -> i18n.t("ui.hologram.mode.life");
                        case FANTUAN_COIN -> i18n.t("ui.hologram.mode.fantuan");
                        case KUNKUN_COIN -> i18n.t("ui.hologram.mode.kunkun");
                    };
                    player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                            i18n.t("ui.mode_gui.selected", Map.of("mode", MiniMessageSupport.escape(modeName)))
                    )));
                    eventSink.accept(outcome.events());
                })
        );
    }

    private void sendFailed(Player player, String reason) {
        player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                i18n.t("command.failed", Map.of("reason", MiniMessageSupport.escape(reason)))
        )));
    }

    private String localizedReason(Throwable throwable) {
        String reason = rootMessage(throwable);
        if ("only_host_can_select_mode".equals(reason)) {
            return i18n.t("command.mode.host_only");
        }
        if ("insufficient_balance".equals(reason)) {
            return i18n.t("command.join.insufficient_balance");
        }
        String prefix = "table not found: ";
        if (reason.startsWith(prefix)) {
            String tableId = reason.substring(prefix.length()).trim();
            if (!tableId.isEmpty()) {
                return i18n.t("command.table.not_created", Map.of("table", tableId));
            }
        }
        return reason;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private ItemStack hintItem(String tableId) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessageSupport.parse(i18n.t("ui.mode_gui.input_name")));
        meta.lore(List.of(
                MiniMessageSupport.parse(i18n.t("ui.mode_gui.input_hint")),
                MiniMessageSupport.parse(i18n.t("ui.mode_gui.mode.life")),
                MiniMessageSupport.parse(i18n.t("ui.mode_gui.mode.fantuan")),
                MiniMessageSupport.parse(i18n.t("ui.mode_gui.mode.kunkun")),
                MiniMessageSupport.parse(i18n.t("ui.mode_gui.table_hint", Map.of("table", MiniMessageSupport.escape(tableId))))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack confirmItem(TableMode mode) {
        Material material = switch (mode) {
            case LIFE_ONLY -> Material.BREAD;
            case FANTUAN_COIN -> Material.PRIZE_POTTERY_SHERD;
            case KUNKUN_COIN -> Material.HEART_OF_THE_SEA;
        };
        String modeLine = switch (mode) {
            case LIFE_ONLY -> i18n.t("ui.mode_gui.mode.life");
            case FANTUAN_COIN -> i18n.t("ui.mode_gui.mode.fantuan");
            case KUNKUN_COIN -> i18n.t("ui.mode_gui.mode.kunkun");
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessageSupport.parse(i18n.t("ui.mode_gui.confirm_name")));
        meta.lore(List.of(
                MiniMessageSupport.parse(modeLine),
                MiniMessageSupport.parse(i18n.t("ui.mode_gui.confirm_hint"))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private TableMode parseMode(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("模式", "")
                .replace("赌", "")
                .replace(" ", "");
        return switch (key) {
            case "1", "l", "life", "命", "life_only" -> TableMode.LIFE_ONLY;
            case "2", "f", "fantuan", "饭团", "饭团币", "fantuancoin", "fantuan_coin" -> TableMode.FANTUAN_COIN;
            case "3", "k", "kunkun", "坤", "坤坤", "坤坤币", "kunkuncoin", "kunkun_coin" -> TableMode.KUNKUN_COIN;
            default -> null;
        };
    }

    private String readRenameText(AnvilInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getRenameText");
            Object value = method.invoke(inventory);
            return value != null ? String.valueOf(value) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private record ModeSelectionHolder(String tableId, UUID sessionId) implements InventoryHolder {
        private ModeSelectionHolder(String tableId) {
            this(tableId, UUID.randomUUID());
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
