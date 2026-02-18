package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.domain.GamePhase;
import static cn.pianzi.liarbar.paperplugin.util.ExceptionUtils.rootMessage;
import cn.pianzi.liarbar.paper.command.CommandOutcome;
import cn.pianzi.liarbar.paper.command.PaperCommandFacade;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.presentation.MiniMessageSupport;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Mode selector opened after player sits at table in MODE_SELECTION phase.
 * Uses Paper Dialog API (1.21.6+) instead of legacy anvil inventory UI.
 */
public final class ModeSelectionDialogGui {

    private static final String INPUT_MODE = "mode";
    private static final String INPUT_WAGER = "wager";

    private final JavaPlugin plugin;
    private final PaperCommandFacade commandFacade;
    private final Consumer<List<UserFacingEvent>> eventSink;
    private final I18n i18n;

    private final Map<UUID, Session> activeSessions = new ConcurrentHashMap<>();

    public ModeSelectionDialogGui(
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

        Session session = new Session(tableId, UUID.randomUUID());
        activeSessions.put(player.getUniqueId(), session);
        player.showDialog(buildDialog(player.getUniqueId(), session));
        player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(i18n.t("ui.mode_gui.open_hint"))));
    }

    public void closeAll() {
        for (UUID playerId : List.copyOf(activeSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.closeDialog();
            }
        }
        activeSessions.clear();
    }

    private Dialog buildDialog(UUID playerId, Session session) {
        ActionButton confirmButton = ActionButton.builder(parse(i18n.t("ui.mode_gui.confirm_name")))
                .tooltip(parse(i18n.t("ui.mode_gui.confirm_hint")))
                .width(180)
                .action(DialogAction.customClick(
                        (response, audience) -> handleConfirm(
                                playerId,
                                session,
                                response.getText(INPUT_MODE),
                                response.getText(INPUT_WAGER),
                                audience
                        ),
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(Duration.ofMinutes(2))
                                .build()
                ))
                .build();

        ActionButton cancelButton = ActionButton.builder(parse(i18n.t("ui.mode_gui.cancel_name")))
                .width(180)
                .build();

        List<DialogBody> body = List.of(
                DialogBody.plainMessage(parse(i18n.t("ui.mode_gui.input_hint")), 320),
                DialogBody.plainMessage(parse(i18n.t("ui.mode_gui.mode.life")), 320),
                DialogBody.plainMessage(parse(i18n.t("ui.mode_gui.mode.fantuan")), 320),
                DialogBody.plainMessage(parse(i18n.t("ui.mode_gui.mode.money")), 320),
                DialogBody.plainMessage(parse(i18n.t("ui.mode_gui.wager_input_hint")), 320),
                DialogBody.plainMessage(parse(i18n.t("ui.mode_gui.table_hint", Map.of(
                        "table", MiniMessageSupport.escape(session.tableId())
                ))), 320)
        );

        List<SingleOptionDialogInput.OptionEntry> modeOptions = List.of(
                SingleOptionDialogInput.OptionEntry.create("life", parse(i18n.t("ui.hologram.mode.life")), true),
                SingleOptionDialogInput.OptionEntry.create("fantuan", parse(i18n.t("ui.hologram.mode.fantuan")), false),
                SingleOptionDialogInput.OptionEntry.create("money", parse(i18n.t("ui.hologram.mode.money")), false)
        );

        List<DialogInput> inputs = List.of(
                DialogInput.singleOption(INPUT_MODE, parse(i18n.t("ui.mode_gui.mode_input_label")), modeOptions).build(),
                DialogInput.text(INPUT_WAGER, parse(i18n.t("ui.mode_gui.wager_input_label")))
                        .initial("1")
                        .maxLength(7)
                        .build()
        );

        DialogBase base = DialogBase.builder(parse(i18n.t("ui.mode_gui.title")))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs)
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.confirmation(confirmButton, cancelButton))
        );
    }

    private void handleConfirm(UUID playerId, Session session, String rawMode, String rawWager, Audience audience) {
        if (!(audience instanceof Player player)) {
            return;
        }
        Session active = activeSessions.get(playerId);
        if (active == null || !active.sessionId().equals(session.sessionId())) {
            return;
        }
        activeSessions.remove(playerId, active);

        TableMode mode = parseMode(rawMode);
        if (mode == null) {
            player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                    i18n.t("ui.mode_gui.invalid_input")
            )));
            return;
        }

        Integer wager = parseWager(mode, rawWager);
        if (wager == null) {
            player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                    i18n.t("command.mode.invalid_wager", Map.of("wager", rawWager == null ? "" : rawWager))
            )));
            return;
        }

        verifyHostAndSelectMode(player, session.tableId(), mode, wager);
    }

    private void verifyHostAndSelectMode(Player player, String tableId, TableMode mode, int wager) {
        commandFacade.snapshot(tableId).whenComplete((snapshot, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        sendFailed(player, localizedReason(throwable));
                        return;
                    }
                    if (snapshot.phase() != GamePhase.MODE_SELECTION) {
                        sendFailed(player, i18n.t("command.join.wait_for_host"));
                        return;
                    }
                    if (snapshot.owner().isEmpty() || !snapshot.owner().get().equals(player.getUniqueId())) {
                        sendFailed(player, i18n.t("command.mode.host_only"));
                        return;
                    }
                    selectMode(player, tableId, mode, wager);
                })
        );
    }

    private void selectMode(Player player, String tableId, TableMode mode, int wager) {
        CompletionStage<CommandOutcome> future = commandFacade.selectMode(tableId, player.getUniqueId(), mode, wager);
        future.whenComplete((outcome, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        String reason = localizedReason(throwable);
                        if ("insufficient_balance".equals(reason) && mode == TableMode.KUNKUN_COIN) {
                            sendFailed(player, i18n.t("command.mode.money_insufficient_switch"));
                            open(player, tableId);
                            return;
                        }
                        sendFailed(player, reason);
                        return;
                    }
                    if (!outcome.success()) {
                        String reason = localizedReasonText(outcome.message());
                        if ("insufficient_balance".equals(reason) && mode == TableMode.KUNKUN_COIN) {
                            sendFailed(player, i18n.t("command.mode.money_insufficient_switch"));
                            open(player, tableId);
                            return;
                        }
                        sendFailed(player, reason);
                        return;
                    }

                    String modeName = switch (mode) {
                        case LIFE_ONLY -> i18n.t("ui.hologram.mode.life");
                        case FANTUAN_COIN -> i18n.t("ui.hologram.mode.fantuan");
                        case KUNKUN_COIN -> i18n.t("ui.hologram.mode.money");
                    };
                    player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                            i18n.t("ui.mode_gui.selected", Map.of("mode", MiniMessageSupport.escape(modeName)))
                    )));
                    eventSink.accept(outcome.events());
                })
        );
    }

    private Integer parseWager(TableMode mode, String rawWager) {
        if (mode != TableMode.KUNKUN_COIN) {
            return 1;
        }
        if (rawWager == null || rawWager.isBlank()) {
            return 1;
        }
        try {
            int wager = Integer.parseInt(rawWager.trim());
            return wager > 0 ? wager : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void sendFailed(Player player, String reason) {
        player.sendMessage(MiniMessageSupport.parse(MiniMessageSupport.prefixed(
                i18n.t("command.failed", Map.of("reason", MiniMessageSupport.escape(reason)))
        )));
    }

    private String localizedReason(Throwable throwable) {
        return localizedReasonText(rootMessage(throwable));
    }

    private String localizedReasonText(String reason) {
        if ("only_host_can_select_mode".equals(reason)) {
            return i18n.t("command.mode.host_only");
        }
        if ("insufficient_balance".equals(reason)) {
            return "insufficient_balance";
        }
        if ("invalid_wager_amount".equals(reason)) {
            return i18n.t("command.mode.invalid_wager_range");
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

    private TableMode parseMode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "life" -> TableMode.LIFE_ONLY;
            case "fantuan" -> TableMode.FANTUAN_COIN;
            case "money", "kunkun" -> TableMode.KUNKUN_COIN;
            default -> null;
        };
    }

    private Component parse(String miniMessage) {
        return MiniMessageSupport.parse(miniMessage);
    }

    private record Session(String tableId, UUID sessionId) {
    }
}
