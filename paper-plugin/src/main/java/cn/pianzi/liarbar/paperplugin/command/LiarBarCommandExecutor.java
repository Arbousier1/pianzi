package cn.pianzi.liarbar.paperplugin.command;

import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.domain.GamePhase;
import static cn.pianzi.liarbar.paperplugin.util.ExceptionUtils.rootMessage;
import cn.pianzi.liarbar.core.snapshot.GameSnapshot;
import cn.pianzi.liarbar.core.snapshot.PlayerSnapshot;
import cn.pianzi.liarbar.paper.command.CommandOutcome;
import cn.pianzi.liarbar.paper.command.PaperCommandFacade;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.config.PluginSettings;
import cn.pianzi.liarbar.paperplugin.game.ModeSelectionDialogGui;
import cn.pianzi.liarbar.paperplugin.game.TableSeatManager;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.presentation.MiniMessageSupport;
import cn.pianzi.liarbar.paperplugin.stats.LiarBarStatsService;
import cn.pianzi.liarbar.paperplugin.stats.PlayerStatsSnapshot;
import cn.pianzi.liarbar.paperplugin.stats.RankTier;
import cn.pianzi.liarbar.paperplugin.stats.SeasonHistorySummary;
import cn.pianzi.liarbar.paperplugin.stats.SeasonListResult;
import cn.pianzi.liarbar.paperplugin.stats.SeasonResetResult;
import cn.pianzi.liarbar.paperplugin.stats.SeasonTopSort;
import cn.pianzi.liarbar.paperplugin.stats.ScoreRule;
import cn.pianzi.liarbar.paperplugin.stats.SeasonTopResult;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LiarBarCommandExecutor implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("mode", "join", "play", "challenge", "leave", "stop", "status", "create", "delete", "tables", "stats", "top", "season", "reload", "help");
    private static final List<String> MODES = List.of("life", "fantuan", "money");

    private final JavaPlugin plugin;
    private final PaperCommandFacade commandFacade;
    private final Consumer<List<UserFacingEvent>> eventSink;
    private final ModeSelectionDialogGui modeSelectionGui;
    private final TableSeatManager seatManager;
    private final LiarBarStatsService statsService;
    private final I18n i18n;
    private final Supplier<List<String>> tableIdsSupplier;
    private final BiFunction<Player, String, CreateTableResult> createTableAction;
    private final Function<String, Boolean> deleteTableAction;

    public LiarBarCommandExecutor(
            JavaPlugin plugin,
            PaperCommandFacade commandFacade,
            Consumer<List<UserFacingEvent>> eventSink,
            ModeSelectionDialogGui modeSelectionGui,
            TableSeatManager seatManager,
            LiarBarStatsService statsService,
            I18n i18n,
            Supplier<List<String>> tableIdsSupplier,
            BiFunction<Player, String, CreateTableResult> createTableAction,
            Function<String, Boolean> deleteTableAction
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.commandFacade = Objects.requireNonNull(commandFacade, "commandFacade");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.modeSelectionGui = Objects.requireNonNull(modeSelectionGui, "modeSelectionGui");
        this.seatManager = Objects.requireNonNull(seatManager, "seatManager");
        this.statsService = Objects.requireNonNull(statsService, "statsService");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.tableIdsSupplier = Objects.requireNonNull(tableIdsSupplier, "tableIdsSupplier");
        this.createTableAction = Objects.requireNonNull(createTableAction, "createTableAction");
        this.deleteTableAction = Objects.requireNonNull(deleteTableAction, "deleteTableAction");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || equalsIgnoreCase(args[0], "help")) {
            sendHelp(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "mode" -> handleMode(sender, args);
            case "join" -> handleJoin(sender, args);
            case "play" -> handlePlay(sender, args);
            case "challenge" -> handleChallenge(sender, args);
            case "leave" -> handleLeave(sender, args);
            case "stop" -> handleStop(sender, args);
            case "status" -> handleStatus(sender, args);
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "tables" -> handleTables(sender);
            case "stats" -> handleStats(sender, args);
            case "top" -> handleTop(sender, args);
            case "season" -> handleSeason(sender, args);
            case "reload" -> handleReload(sender);
            default -> {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.unknown_subcommand", Map.of("label", label))));
                yield true;
            }
        };
    }

    private boolean handleMode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liarbar.admin")) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_admin")));
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 3) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.mode")));
            return true;
        }

        String tableId = args[1];
        TableMode mode;
        try {
            mode = parseMode(args[2]);
        } catch (IllegalArgumentException ex) {
            send(sender, MiniMessageSupport.prefixed("<red>" + MiniMessageSupport.escape(ex.getMessage()) + "</red>"));
            return true;
        }

        int wager = 1;
        if (mode == TableMode.KUNKUN_COIN && args.length >= 4) {
            try {
                wager = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.mode.invalid_wager", Map.of("wager", args[3]))));
                return true;
            }
        }

        dispatchOutcome(sender, commandFacade.selectMode(tableId, player.getUniqueId(), mode, wager));
        return true;
    }

    private boolean handleJoin(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 2) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.join")));
            return true;
        }

        if (!statsService.canJoinRanked(player.getUniqueId())) {
            PlayerStatsSnapshot stats = statsService.statsOf(player.getUniqueId());
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.join.not_enough_score", Map.of(
                    "min", statsService.minJoinScore(),
                    "current", stats.score()
            ))));
            return true;
        }

        String tableId = args[1];
        String seatedTable = seatManager.tableOf(player.getUniqueId());
        if (seatedTable == null) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.join.must_be_seated", Map.of(
                    "table", MiniMessageSupport.escape(tableId)
            ))));
            return true;
        }
        if (!tableId.equals(seatedTable)) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.join.seated_other_table", Map.of(
                    "table", MiniMessageSupport.escape(seatedTable)
            ))));
            return true;
        }
        commandFacade.snapshot(tableId).whenComplete((snapshot, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        send(sender, MiniMessageSupport.prefixed(i18n.t("command.failed", Map.of(
                                "reason", MiniMessageSupport.escape(localizedReason(throwable))
                        ))));
                        return;
                    }

                    if (snapshot.phase() == GamePhase.MODE_SELECTION
                            && snapshot.players().stream().anyMatch(p -> p.playerId().equals(player.getUniqueId()))) {
                        if (snapshot.owner().filter(owner -> owner.equals(player.getUniqueId())).isPresent()) {
                            send(sender, MiniMessageSupport.prefixed(i18n.t("command.join.reopen_mode_gui")));
                            modeSelectionGui.open(player, tableId);
                        } else {
                            send(sender, MiniMessageSupport.prefixed(i18n.t("command.join.wait_for_host")));
                        }
                        return;
                    }

                    dispatchOutcome(sender, commandFacade.join(tableId, player.getUniqueId()), outcome -> {
                        if (!outcome.success()) {
                            return;
                        }
                        commandFacade.snapshot(tableId).whenComplete((afterJoin, afterJoinErr) ->
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (afterJoinErr != null) {
                                        send(sender, MiniMessageSupport.prefixed(i18n.t("command.failed", Map.of(
                                                "reason", MiniMessageSupport.escape(localizedReason(afterJoinErr))
                                        ))));
                                        return;
                                    }
                                    if (afterJoin.phase() == GamePhase.MODE_SELECTION
                                            && afterJoin.owner().filter(owner -> owner.equals(player.getUniqueId())).isPresent()) {
                                        modeSelectionGui.open(player, tableId);
                                    } else if (afterJoin.phase() == GamePhase.MODE_SELECTION) {
                                        send(sender, MiniMessageSupport.prefixed(i18n.t("command.join.wait_for_host")));
                                    }
                                })
                        );
                    });
                })
        );
        return true;
    }

    private boolean handlePlay(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 3) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.play")));
            return true;
        }

        String tableId = args[1];
        List<Integer> slots;
        try {
            slots = parseSlots(args, 2);
        } catch (IllegalArgumentException ex) {
            send(sender, MiniMessageSupport.prefixed("<red>" + MiniMessageSupport.escape(ex.getMessage()) + "</red>"));
            return true;
        }

        dispatchOutcome(sender, commandFacade.play(tableId, player.getUniqueId(), slots));
        return true;
    }

    private boolean handleChallenge(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 2) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.challenge")));
            return true;
        }

        String tableId = args[1];
        dispatchOutcome(sender, commandFacade.challenge(tableId, player.getUniqueId()));
        return true;
    }

    private boolean handleLeave(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 2) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.leave")));
            return true;
        }

        String tableId = args[1];
        dispatchOutcome(sender, commandFacade.leave(tableId, player.getUniqueId()));
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liarbar.admin")) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_admin")));
            return true;
        }

        if (args.length < 2) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.stop")));
            return true;
        }

        String tableId = args[1];
        dispatchOutcome(sender, commandFacade.forceStop(tableId));
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.status")));
            return true;
        }

        String tableId = args[1];
        commandFacade.snapshot(tableId).whenComplete((snapshot, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        send(sender, MiniMessageSupport.prefixed(i18n.t("command.status_failed", Map.of(
                                "reason", MiniMessageSupport.escape(localizedReason(throwable))
                        ))));
                        return;
                    }
                    sendSnapshot(sender, snapshot);
                })
        );
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liarbar.admin")) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_admin")));
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.player_only")));
            return true;
        }

        String requestedId = args.length >= 2 ? args[1] : null;
        CreateTableResult result = createTableAction.apply(player, requestedId);
        if (result.created()) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.create.ok", Map.of("table", result.tableId()))));
        } else {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.create.exists", Map.of("table", result.tableId()))));
        }
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liarbar.admin")) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_admin")));
            return true;
        }
        if (args.length < 2) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.delete")));
            return true;
        }

        String tableId = args[1];
        if (deleteTableAction.apply(tableId)) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.delete.ok", Map.of("table", tableId))));
        } else {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.delete.not_found", Map.of("table", tableId))));
        }
        return true;
    }

    private boolean handleTables(CommandSender sender) {
        List<String> ids = tableIdsSupplier.get();
        send(sender, i18n.t("command.tables.header"));
        if (ids.isEmpty()) {
            send(sender, i18n.t("command.tables.empty"));
            return true;
        }
        for (String id : ids) {
            send(sender, i18n.t("command.tables.row", Map.of("table", MiniMessageSupport.escape(id))));
        }
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        UUID target;
        if (args.length >= 2) {
            target = resolvePlayerId(args[1]);
            if (target == null) {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.player_not_found", Map.of(
                        "player", MiniMessageSupport.escape(args[1])
                ))));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player.getUniqueId();
        } else {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.stats_console")));
            return true;
        }

        PlayerStatsSnapshot snapshot = statsService.statsOf(target);
        sendStats(sender, snapshot);
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ex) {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.top.invalid_limit")));
                return true;
            }
        }

        List<PlayerStatsSnapshot> top = statsService.top(limit);
        send(sender, i18n.t("command.top.header"));
        if (top.isEmpty()) {
            send(sender, i18n.t("command.top.empty"));
            return true;
        }

        int index = 1;
        for (PlayerStatsSnapshot snapshot : top) {
            String line = i18n.t("command.top.row", Map.of(
                    "rank", index,
                    "player", MiniMessageSupport.escape(displayName(snapshot.playerId())),
                    "score", snapshot.score(),
                    "tier", MiniMessageSupport.escape(statsService.rankTitleOf(snapshot.score())),
                    "wins", snapshot.wins(),
                    "games", snapshot.gamesPlayed()
            ));
            send(sender, line);
            index++;
        }
        return true;
    }

    private boolean handleSeason(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liarbar.admin")) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_admin")));
            return true;
        }

        if (args.length < 2 || equalsIgnoreCase(args[1], "info")) {
            sendSeasonInfo(sender);
            return true;
        }

        if (equalsIgnoreCase(args[1], "reset")) {
            if (args.length < 3 || !equalsIgnoreCase(args[2], "confirm")) {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.reset_confirm_hint")));
                return true;
            }
            statsService.resetSeason().whenComplete((result, throwable) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (throwable != null) {
                            send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.reset_failed", Map.of(
                                    "reason", MiniMessageSupport.escape(localizedReason(throwable))
                            ))));
                            return;
                        }
                        sendSeasonResetSuccess(sender, result);
                    })
            );
            return true;
        }

        if (equalsIgnoreCase(args[1], "list")) {
            int page = 1;
            int pageSize = 10;
            if (args.length >= 3) {
                try {
                    page = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException ex) {
                    send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.list.invalid_page")));
                    return true;
                }
            }
            if (args.length >= 4) {
                try {
                    pageSize = Math.max(1, Math.min(50, Integer.parseInt(args[3])));
                } catch (NumberFormatException ex) {
                    send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.list.invalid_size")));
                    return true;
                }
            }
            statsService.listSeasons(page, pageSize).whenComplete((pageResult, throwable) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (throwable != null) {
                            send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.list_failed", Map.of(
                                    "reason", MiniMessageSupport.escape(localizedReason(throwable))
                            ))));
                            return;
                        }
                        sendSeasonList(sender, pageResult);
                    })
            );
            return true;
        }

        if (equalsIgnoreCase(args[1], "top")) {
            if (args.length < 3) {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.season_top")));
                return true;
            }
            int seasonId;
            int page = 1;
            int pageSize = 10;
            SeasonTopSort sort = SeasonTopSort.SCORE;
            try {
                seasonId = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.top.invalid_season")));
                return true;
            }

            if (args.length == 4) {
                Integer maybeNumber = tryParseInt(args[3]);
                if (maybeNumber != null) {
                    if (maybeNumber <= 50) {
                        pageSize = Math.max(1, maybeNumber);
                    } else {
                        page = Math.max(1, maybeNumber);
                    }
                } else {
                    sort = parseSortOrFail(sender, args[3]);
                    if (sort == null) {
                        return true;
                    }
                }
            }
            if (args.length >= 5) {
                Integer maybePage = tryParseInt(args[3]);
                if (maybePage == null) {
                    send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.top.invalid_page")));
                    return true;
                }
                page = Math.max(1, maybePage);

                Integer maybeSize = tryParseInt(args[4]);
                if (maybeSize == null) {
                    SeasonTopSort parsed = parseSortOrFail(sender, args[4]);
                    if (parsed == null) {
                        return true;
                    }
                    sort = parsed;
                } else {
                    pageSize = Math.max(1, Math.min(50, maybeSize));
                }
            }
            if (args.length >= 6) {
                SeasonTopSort parsed = parseSortOrFail(sender, args[5]);
                if (parsed == null) {
                    return true;
                }
                sort = parsed;
            }
            statsService.topForSeason(seasonId, page, pageSize, sort).whenComplete((topResult, throwable) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (throwable != null) {
                            send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.top_failed", Map.of(
                                    "reason", MiniMessageSupport.escape(localizedReason(throwable))
                            ))));
                            return;
                        }
                        sendSeasonTop(sender, topResult);
                    })
            );
            return true;
        }

        send(sender, MiniMessageSupport.prefixed(i18n.t("command.usage.season")));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("liarbar.admin")) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_admin")));
            return true;
        }
        try {
            plugin.reloadConfig();
            PluginSettings updated = PluginSettings.fromConfig(plugin.getConfig());
            statsService.updateScoreRule(updated.scoreRule());
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.reload.ok")));
            send(sender, i18n.t("command.reload.note"));
        } catch (Exception ex) {
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.reload.failed", Map.of(
                    "reason", MiniMessageSupport.escape(localizedReason(ex))
            ))));
        }
        return true;
    }

    private void dispatchOutcome(CommandSender sender, CompletionStage<CommandOutcome> future) {
        dispatchOutcome(sender, future, ignored -> {
        });
    }

    private void dispatchOutcome(
            CommandSender sender,
            CompletionStage<CommandOutcome> future,
            Consumer<CommandOutcome> afterSuccess
    ) {
        future.whenComplete((outcome, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        send(sender, MiniMessageSupport.prefixed(i18n.t("command.failed", Map.of(
                                "reason", MiniMessageSupport.escape(localizedReason(throwable))
                        ))));
                        return;
                    }

                    String color = outcome.success() ? "green" : "red";
                    String message = outcome.success() ? i18n.t(outcome.message()) : outcome.message();
                    send(sender, MiniMessageSupport.prefixed("<" + color + ">" + MiniMessageSupport.escape(message) + "</" + color + ">"));
                    if (outcome.success()) {
                        eventSink.accept(outcome.events());
                        afterSuccess.accept(outcome);
                    }
                })
        );
    }

    private void sendSnapshot(CommandSender sender, GameSnapshot snapshot) {
        send(sender, i18n.t("command.snapshot.header"));
        send(sender, i18n.t("command.snapshot.overview", Map.of(
                "table", MiniMessageSupport.escape(snapshot.tableId()),
                "phase", snapshot.phase(),
                "round", snapshot.round(),
                "mode", snapshot.mode(),
                "joined", snapshot.joinedCount()
        )));

        String current = snapshot.currentPlayer().map(this::displayName).orElse(i18n.t("command.snapshot.none"));
        String last = snapshot.lastPlayer().map(this::displayName).orElse(i18n.t("command.snapshot.none"));
        send(sender, i18n.t("command.snapshot.turn", Map.of(
                "current", MiniMessageSupport.escape(current),
                "last", MiniMessageSupport.escape(last),
                "force", snapshot.forceChallenge()
        )));

        List<PlayerSnapshot> sorted = snapshot.players().stream()
                .sorted(Comparator.comparingInt(PlayerSnapshot::seat))
                .toList();
        for (PlayerSnapshot player : sorted) {
            send(sender, i18n.t("command.snapshot.player_row", Map.of(
                    "seat", player.seat(),
                    "player", MiniMessageSupport.escape(displayName(player.playerId())),
                    "alive", player.alive(),
                    "bullets", player.bullets(),
                    "hand", player.handSize()
            )));
        }
    }

    private void sendStats(CommandSender sender, PlayerStatsSnapshot snapshot) {
        send(sender, i18n.t("command.stats.header"));
        send(sender, i18n.t("command.stats.player", Map.of("player", MiniMessageSupport.escape(displayName(snapshot.playerId())))));
        send(sender, i18n.t("command.stats.tier", Map.of("tier", MiniMessageSupport.escape(statsService.rankTitleOf(snapshot.score())))));
        send(sender, i18n.t("command.stats.score", Map.of("score", snapshot.score())));
        send(sender, i18n.t("command.stats.wl", Map.of(
                "games", snapshot.gamesPlayed(),
                "wins", snapshot.wins(),
                "losses", snapshot.losses()
        )));
        send(sender, i18n.t("command.stats.misc", Map.of(
                "eliminated", snapshot.eliminatedCount(),
                "survived", snapshot.survivedShots()
        )));
        send(sender, i18n.t("command.stats.streak", Map.of(
                "current", snapshot.currentWinStreak(),
                "best", snapshot.bestWinStreak()
        )));
    }

    private void sendHelp(CommandSender sender, String label) {
        send(sender, i18n.t("command.help.header"));
        Map<String, String> vars = Map.of("label", MiniMessageSupport.escape(label));
        send(sender, i18n.t("command.help.mode", vars));
        send(sender, i18n.t("command.help.join", vars));
        send(sender, i18n.t("command.help.play", vars));
        send(sender, i18n.t("command.help.challenge", vars));
        send(sender, i18n.t("command.help.leave", vars));
        send(sender, i18n.t("command.help.status", vars));
        send(sender, i18n.t("command.help.create", vars));
        send(sender, i18n.t("command.help.delete", vars));
        send(sender, i18n.t("command.help.tables", vars));
        send(sender, i18n.t("command.help.stats", vars));
        send(sender, i18n.t("command.help.top", vars));
        send(sender, i18n.t("command.help.stop", vars));
        send(sender, i18n.t("command.help.season", vars));
        send(sender, i18n.t("command.help.reload", vars));
    }

    private void sendSeasonInfo(CommandSender sender) {
        ScoreRule rule = statsService.scoreRule();
        send(sender, i18n.t("command.season.info.header"));
        send(sender, i18n.t("command.season.info.base", Map.of(
                "initial", rule.initialScore(),
                "min", rule.minJoinScore(),
                "entry", rule.entryCost()
        )));
        send(sender, i18n.t("command.season.info.score", Map.of(
                "win", signed(rule.win()),
                "lose", signed(rule.lose()),
                "join", signed(rule.join()),
                "survive", signed(rule.surviveShot()),
                "eliminated", signed(rule.eliminated())
        )));
        int index = 1;
        for (RankTier tier : rule.rankTiers()) {
            send(sender, i18n.t("command.season.info.tier_row", Map.of(
                    "index", index,
                    "title", MiniMessageSupport.escape(tier.title()),
                    "min", tier.minPoints()
            )));
            index++;
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            if (sender.hasPermission("liarbar.use")) {
                return player;
            }
            send(sender, MiniMessageSupport.prefixed(i18n.t("command.no_permission_use")));
            return null;
        }
        send(sender, MiniMessageSupport.prefixed(i18n.t("command.player_only")));
        return null;
    }

    private TableMode parseMode(String rawMode) {
        return switch (rawMode.toLowerCase(Locale.ROOT)) {
            case "life" -> TableMode.LIFE_ONLY;
            case "fantuan" -> TableMode.FANTUAN_COIN;
            case "money", "kunkun" -> TableMode.KUNKUN_COIN;
            default -> throw new IllegalArgumentException(i18n.t("command.mode.invalid", Map.of("mode", rawMode)));
        };
    }

    private List<Integer> parseSlots(String[] args, int startInclusive) {
        List<Integer> slots = new ArrayList<>();
        for (int i = startInclusive; i < args.length; i++) {
            String[] tokens = args[i].split(",");
            for (String token : tokens) {
                String value = token.trim();
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    slots.add(Integer.parseInt(value));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(i18n.t("command.play.invalid_slot", Map.of("slot", value)));
                }
            }
        }

        if (slots.isEmpty()) {
            throw new IllegalArgumentException(i18n.t("command.play.no_slot"));
        }
        return List.copyOf(slots);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return SUBCOMMANDS;
        }
        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && equalsIgnoreCase(args[0], "mode")) {
            return filterByPrefix(tableIdsSupplier.get(), args[1]);
        }
        if (args.length == 2 && (
                equalsIgnoreCase(args[0], "join")
                        || equalsIgnoreCase(args[0], "play")
                        || equalsIgnoreCase(args[0], "challenge")
                        || equalsIgnoreCase(args[0], "leave")
                        || equalsIgnoreCase(args[0], "status")
                        || equalsIgnoreCase(args[0], "stop")
                        || equalsIgnoreCase(args[0], "delete")
        )) {
            return filterByPrefix(tableIdsSupplier.get(), args[1]);
        }
        if (args.length == 3 && equalsIgnoreCase(args[0], "mode")) {
            return filterByPrefix(MODES, args[2]);
        }
        if (args.length == 4 && equalsIgnoreCase(args[0], "mode") && equalsIgnoreCase(args[2], "money")) {
            return filterByPrefix(List.of("10", "50", "100", "500", "1000"), args[3]);
        }
        if (args.length >= 3 && equalsIgnoreCase(args[0], "play")) {
            return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[args.length - 1]);
        }
        if (args.length == 2 && equalsIgnoreCase(args[0], "stats")) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return filterByPrefix(names, args[1]);
        }
        if (args.length == 2 && equalsIgnoreCase(args[0], "top")) {
            return filterByPrefix(List.of("5", "10", "20", "50"), args[1]);
        }
        if (args.length == 2 && equalsIgnoreCase(args[0], "season")) {
            return filterByPrefix(List.of("info", "list", "top", "reset"), args[1]);
        }
        if (args.length == 3 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "reset")) {
            return filterByPrefix(List.of("confirm"), args[2]);
        }
        if (args.length == 3 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "list")) {
            return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[2]);
        }
        if (args.length == 4 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "list")) {
            return filterByPrefix(List.of("5", "10", "20", "50"), args[3]);
        }
        if (args.length == 3 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "top")) {
            List<String> ids = statsService.recentSeasonIds(20).stream()
                    .map(String::valueOf)
                    .toList();
            if (ids.isEmpty()) {
                ids = List.of("1");
            }
            return filterByPrefix(ids, args[2]);
        }
        if (args.length == 4 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "top")) {
            return filterByPrefix(List.of("5", "10", "20", "50", "wins", "score"), args[3]);
        }
        if (args.length == 5 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "top")) {
            return filterByPrefix(List.of("5", "10", "20", "50", "wins", "score"), args[4]);
        }
        if (args.length == 6 && equalsIgnoreCase(args[0], "season") && equalsIgnoreCase(args[1], "top")) {
            return filterByPrefix(List.of("wins", "score"), args[5]);
        }
        return List.of();
    }

    private List<String> filterByPrefix(List<String> values, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return values;
        }
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }

    private UUID resolvePlayerId(String raw) {
        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String displayName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        if (offline.getName() != null && !offline.getName().isBlank()) {
            return offline.getName();
        }
        String text = playerId.toString();
        return text.substring(0, 8);
    }

    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MiniMessageSupport.parse(miniMessage));
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private String localizedReason(Throwable throwable) {
        String reason = rootMessage(throwable);
        if ("insufficient_balance".equals(reason)) {
            return i18n.t("command.join.insufficient_balance");
        }
        if ("only_host_can_select_mode".equals(reason)) {
            return i18n.t("command.mode.host_only");
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

    private void sendSeasonResetSuccess(CommandSender sender, SeasonResetResult result) {
        send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.reset_ok")));
        send(sender, i18n.t("command.season.reset.meta", Map.of(
                "season", result.seasonId(),
                "archived", result.archivedRows(),
                "deleted", result.deletedRows()
        )));
    }

    private void sendSeasonList(CommandSender sender, SeasonListResult pageResult) {
        send(sender, i18n.t("command.season.list_header"));
        send(sender, i18n.t("command.season.list.meta", Map.of(
                "page", pageResult.page(),
                "totalPages", pageResult.totalPages(),
                "size", pageResult.pageSize(),
                "totalSeasons", pageResult.totalSeasons()
        )));
        if (pageResult.entries().isEmpty()) {
            send(sender, i18n.t("command.season.list.empty"));
            return;
        }
        for (SeasonHistorySummary season : pageResult.entries()) {
            send(sender, i18n.t("command.season.list.row", Map.of(
                    "season", season.seasonId(),
                    "archivedAt", MiniMessageSupport.escape(formatEpoch(season.archivedAtEpochSecond())),
                    "players", season.playerCount()
            )));
        }
    }

    private void sendSeasonTop(CommandSender sender, SeasonTopResult topResult) {
        send(sender, i18n.t("command.season.top_header", Map.of("season", topResult.seasonId())));
        send(sender, i18n.t("command.season.top.meta", Map.of(
                "archivedAt", MiniMessageSupport.escape(formatEpoch(topResult.archivedAtEpochSecond())),
                "sort", sortLabel(topResult.sort()),
                "page", topResult.page(),
                "totalPages", topResult.totalPages(),
                "totalPlayers", topResult.totalPlayers()
        )));
        int rank = 1;
        for (PlayerStatsSnapshot snapshot : topResult.entries()) {
            String line = i18n.t("command.season.top.row", Map.of(
                    "rank", rank,
                    "player", MiniMessageSupport.escape(displayName(snapshot.playerId())),
                    "score", snapshot.score(),
                    "tier", MiniMessageSupport.escape(statsService.rankTitleOf(snapshot.score())),
                    "wins", snapshot.wins(),
                    "games", snapshot.gamesPlayed()
            ));
            send(sender, line);
            rank++;
        }
    }

    private String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    private String formatEpoch(long epochSecond) {
        return i18n.formatEpochSecond(epochSecond);
    }

    private Integer tryParseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String sortLabel(SeasonTopSort sort) {
        if (sort == null) {
            return i18n.t("command.season.sort.score");
        }
        return switch (sort) {
            case SCORE -> i18n.t("command.season.sort.score");
            case WINS -> i18n.t("command.season.sort.wins");
        };
    }

    private SeasonTopSort parseSortOrFail(CommandSender sender, String raw) {
        SeasonTopSort sort = SeasonTopSort.parseOrDefault(raw, null);
        if (sort != null) {
            return sort;
        }
        send(sender, MiniMessageSupport.prefixed(i18n.t("command.season.top.invalid_sort")));
        return null;
    }

    public record CreateTableResult(String tableId, boolean created) {
    }
}

