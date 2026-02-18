package cn.pianzi.liarbar.paperplugin.bootstrap;

import cn.pianzi.liarbar.core.config.TableConfig;
import cn.pianzi.liarbar.core.domain.GamePhase;
import cn.pianzi.liarbar.core.port.EconomyPort;
import cn.pianzi.liarbar.core.port.RandomSource;
import cn.pianzi.liarbar.core.snapshot.PlayerSnapshot;
import cn.pianzi.liarbar.paper.application.TableApplicationService;
import cn.pianzi.liarbar.paper.command.PaperCommandFacade;
import cn.pianzi.liarbar.paper.integration.vault.VaultEconomyAdapter;
import cn.pianzi.liarbar.paper.integration.vault.VaultGateway;
import cn.pianzi.liarbar.paper.presentation.PacketEventsViewBridge;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.command.LiarBarCommandExecutor;
import cn.pianzi.liarbar.paperplugin.config.PluginSettings;
import cn.pianzi.liarbar.paperplugin.config.TableConfigLoader;
import cn.pianzi.liarbar.paperplugin.game.ClickableCardPresenter;
import cn.pianzi.liarbar.paperplugin.game.DatapackParityRewardService;
import cn.pianzi.liarbar.paperplugin.game.GameEffectsManager;
import cn.pianzi.liarbar.paperplugin.game.GameBossBarManager;
import cn.pianzi.liarbar.paperplugin.game.ModeSelectionDialogGui;
import cn.pianzi.liarbar.paperplugin.game.TablePersistenceStore;
import cn.pianzi.liarbar.paperplugin.game.TableLobbyHologramManager;
import cn.pianzi.liarbar.paperplugin.game.TablePlayerConnectionListener;
import cn.pianzi.liarbar.paperplugin.game.TableSeatInteractionListener;
import cn.pianzi.liarbar.paperplugin.game.TableSeatManager;
import cn.pianzi.liarbar.paperplugin.game.TableStructureBuilder;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.integration.packet.PacketEventsLifecycle;
import cn.pianzi.liarbar.paperplugin.integration.vault.VaultGatewayFactory;
import cn.pianzi.liarbar.paperplugin.presentation.PacketEventsActionBarPublisher;
import cn.pianzi.liarbar.paperplugin.presentation.MiniMessageSupport;
import cn.pianzi.liarbar.paperplugin.config.DatabaseConfig;
import cn.pianzi.liarbar.paperplugin.game.SavedTable;
import cn.pianzi.liarbar.paperplugin.stats.H2StatsRepository;
import cn.pianzi.liarbar.paperplugin.stats.LiarBarStatsService;
import cn.pianzi.liarbar.paperplugin.stats.MariaDbStatsRepository;
import cn.pianzi.liarbar.paperplugin.stats.StatsRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static cn.pianzi.liarbar.paperplugin.util.ExceptionUtils.rootMessage;

public final class LiarBarPaperPlugin extends JavaPlugin {
    private PacketEventsLifecycle packetEventsLifecycle;
    private TableApplicationService tableService;
    private PaperCommandFacade commandFacade;
    private PacketEventsViewBridge viewBridge;
    private LiarBarStatsService statsService;
    private DatapackParityRewardService rewardService;
    private PluginSettings settings;
    private I18n i18n;
    private TableConfig tableConfig;
    private EconomyPort economyPort;
    private RandomSource randomSource;
    private TableStructureBuilder structureBuilder;
    private TableSeatManager seatManager;
    private GameBossBarManager bossBarManager;
    private ClickableCardPresenter cardPresenter;
    private GameEffectsManager effectsManager;
    private TableLobbyHologramManager lobbyHologramManager;
    private PacketEventsActionBarPublisher actionBarPublisher;
    private ModeSelectionDialogGui modeSelectionGui;
    private TablePersistenceStore tablePersistenceStore;
    private StatsRepository statsRepository;
    private BukkitTask tickTask;

    @Override
    public void onLoad() {
        packetEventsLifecycle = new PacketEventsLifecycle(this);
        packetEventsLifecycle.load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        settings = PluginSettings.fromConfig(getConfig());
        i18n = new I18n(settings.localeTag(), settings.zoneId());
        MiniMessageSupport.setPrefix(i18n.t("ui.prefix"));
        tableConfig = TableConfigLoader.fromConfig(getConfig());
        tablePersistenceStore = new TablePersistenceStore(getDataFolder().toPath());
        packetEventsLifecycle.init();

        VaultGateway vaultGateway = VaultGatewayFactory.fromServer(this)
                .orElseGet(() -> {
                    getLogger().warning("Vault economy service not found; wager modes will reject player joins.");
                    return VaultGatewayFactory.disabledGateway();
                });

        economyPort = new VaultEconomyAdapter(
                vaultGateway,
                settings.fantuanEntryFee(),
                settings.moneyUnitPrice()
        );
        randomSource = RandomSource.threadLocal();

        tableService = new TableApplicationService();
        structureBuilder = new TableStructureBuilder();
        seatManager = new TableSeatManager(this, structureBuilder);
        bossBarManager = new GameBossBarManager(i18n, seatManager);
        cardPresenter = new ClickableCardPresenter(i18n);
        effectsManager = new GameEffectsManager(structureBuilder, i18n);
        lobbyHologramManager = new TableLobbyHologramManager(structureBuilder, i18n);
        getLogger().info("No table is auto-created. Use /liarbar create as OP at your current location.");

        statsRepository = createStatsRepository(settings.databaseConfig());
        statsService = new LiarBarStatsService(this, statsRepository, settings.scoreRule());

        commandFacade = new PaperCommandFacade(tableService);
        actionBarPublisher = new PacketEventsActionBarPublisher(this, i18n, packetEventsLifecycle.isReady(), seatManager);
        viewBridge = new PacketEventsViewBridge(actionBarPublisher);
        rewardService = new DatapackParityRewardService(this, i18n);
        modeSelectionGui = new ModeSelectionDialogGui(this, commandFacade, this::applyEvents, i18n);

        if (!registerCommands()) {
            getLogger().severe("Failed to register /liarbar command. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(
                new TablePlayerConnectionListener(
                        this,
                        tableService,
                        seatManager,
                        this::applyEvents
                ),
                this
        );
        getServer().getPluginManager().registerEvents(
                new TableSeatInteractionListener(seatManager, this::handlePlayerSeated),
                this
        );
        restoreSavedTables();
        startTickLoop();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        // Persist table locations before tearing down structures
        if (structureBuilder != null) {
            persistTables();
        }

        if (effectsManager != null) {
            effectsManager.removeAll();
            effectsManager = null;
        }

        if (modeSelectionGui != null) {
            modeSelectionGui.closeAll();
            modeSelectionGui = null;
        }

        if (lobbyHologramManager != null) {
            lobbyHologramManager.removeAll();
            lobbyHologramManager = null;
        }

        if (actionBarPublisher != null) {
            actionBarPublisher.removeAll();
            actionBarPublisher = null;
        }

        if (bossBarManager != null) {
            bossBarManager.removeAll();
            bossBarManager = null;
        }

        if (seatManager != null) {
            seatManager.removeAll();
            seatManager = null;
        }

        if (structureBuilder != null) {
            structureBuilder.demolishAll();
            structureBuilder = null;
        }

        if (tableService != null) {
            tableService.close();
            tableService = null;
        }

        if (statsService != null) {
            statsService.close();
            statsService = null;
        }
        statsRepository = null;
        tablePersistenceStore = null;
        rewardService = null;

        if (packetEventsLifecycle != null) {
            packetEventsLifecycle.terminate();
        }
    }

    private boolean registerCommands() {
        LiarBarCommandExecutor executor = new LiarBarCommandExecutor(
                this,
                commandFacade,
                this::applyEvents,
                modeSelectionGui,
                seatManager,
                statsService,
                i18n,
                this::tableIds,
                this::createConfiguredTableAtPlayer,
                this::deleteTable
        );

        BasicCommand command = new BasicCommand() {
            @Override
            public void execute(CommandSourceStack commandSourceStack, String[] args) {
                executor.onCommand(commandSourceStack.getSender(), null, "liarbar", args);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
                return executor.onTabComplete(commandSourceStack.getSender(), null, "liarbar", args);
            }

            @Override
            public boolean canUse(CommandSender sender) {
                return true;
            }
        };

        try {
            registerCommand("liarbar", command);
            return true;
        } catch (RuntimeException ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to register /liarbar command", ex);
            return false;
        }
    }

    private void startTickLoop() {
        tickTask = getServer().getScheduler().runTaskTimer(
                this,
                this::tickOnce,
                settings.tickIntervalTicks(),
                settings.tickIntervalTicks()
        );
    }

    private void tickOnce() {
        Collection<String> ids = tableService.tableIds();
        if (ids.isEmpty()) {
            return;
        }
        record TickResult(String tableId, List<UserFacingEvent> events, Throwable error) {}
        List<CompletableFuture<TickResult>> futures = new ArrayList<>(ids.size());
        for (String tableId : ids) {
            List<UUID> seatedInSeatOrder = seatManager != null
                    ? seatManager.seatedPlayersInSeatOrder(tableId)
                    : List.of();
            futures.add(
                    syncSeatMembershipAndTick(tableId, seatedInSeatOrder)
                            .thenApply(events -> new TickResult(tableId, events, null))
                            .exceptionally(ex -> new TickResult(tableId, List.of(), ex))
                            .toCompletableFuture()
            );
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenRun(() -> getServer().getScheduler().runTask(this, () -> {
                    for (var future : futures) {
                        TickResult result = future.join();
                        if (result.error() != null) {
                            getLogger().log(java.util.logging.Level.WARNING, "Table tick failed: " + result.tableId(), result.error());
                            continue;
                        }
                        applyEvents(result.events());
                    }
                }));
    }

    private void handlePlayerSeated(Player player, String tableId) {
        if (player == null || tableId == null || tableId.isBlank() || tableService == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        tableService.snapshot(tableId)
                .thenCompose(snapshot -> {
                    boolean joined = snapshot.players().stream()
                            .anyMatch(p -> p.playerId().equals(playerId));
                    if (joined) {
                        return CompletableFuture.completedFuture(List.<UserFacingEvent>of());
                    }
                    return tableService.join(tableId, playerId);
                })
                .whenComplete((events, throwable) ->
                        getServer().getScheduler().runTask(this, () -> {
                            if (throwable != null) {
                                maybeLogSeatSyncError(tableId, "join", playerId, throwable);
                                return;
                            }
                            applyEvents(events);
                            reopenModeDialogIfNeeded(player, tableId);
                        }));
    }

    private void reopenModeDialogIfNeeded(Player player, String tableId) {
        if (modeSelectionGui == null || player == null || !player.isOnline() || tableService == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        tableService.snapshot(tableId).whenComplete((snapshot, throwable) ->
                getServer().getScheduler().runTask(this, () -> {
                    if (throwable != null || snapshot == null || snapshot.phase() != GamePhase.MODE_SELECTION) {
                        return;
                    }
                    boolean joined = snapshot.players().stream()
                            .anyMatch(p -> p.playerId().equals(playerId));
                    if (joined && tableId.equals(seatManager.tableOf(playerId))) {
                        modeSelectionGui.open(player, tableId);
                    }
                })
        );
    }

    private CompletionStage<List<UserFacingEvent>> syncSeatMembershipAndTick(String tableId, List<UUID> seatedInSeatOrder) {
        Set<UUID> seatedNow = new HashSet<>(seatedInSeatOrder);
        return tableService.snapshot(tableId).thenCompose(snapshot -> {
            Set<UUID> joinedNow = new HashSet<>();
            for (PlayerSnapshot player : snapshot.players()) {
                joinedNow.add(player.playerId());
            }

            List<UUID> toLeave = new ArrayList<>();
            for (PlayerSnapshot player : snapshot.players()) {
                if (!seatedNow.contains(player.playerId())) {
                    toLeave.add(player.playerId());
                }
            }

            List<UUID> toJoin = new ArrayList<>();
            for (UUID seatedPlayer : seatedInSeatOrder) {
                if (!joinedNow.contains(seatedPlayer)) {
                    toJoin.add(seatedPlayer);
                }
            }

            CompletionStage<List<UserFacingEvent>> sequence = CompletableFuture.completedFuture(List.of());
            for (UUID playerId : toLeave) {
                sequence = sequence.thenCompose(accumulated ->
                        tableService.playerDisconnected(tableId, playerId).handle((events, throwable) -> {
                            if (throwable != null) {
                                maybeLogSeatSyncError(tableId, "leave", playerId, throwable);
                                return accumulated;
                            }
                            return appendEvents(accumulated, events);
                        })
                );
            }
            for (UUID playerId : toJoin) {
                sequence = sequence.thenCompose(accumulated ->
                        tableService.join(tableId, playerId).handle((events, throwable) -> {
                            if (throwable != null) {
                                maybeLogSeatSyncError(tableId, "join", playerId, throwable);
                                return accumulated;
                            }
                            return appendEvents(accumulated, events);
                        })
                );
            }

            return sequence.thenCompose(accumulated ->
                    tableService.tick(tableId).thenApply(tickEvents -> appendEvents(accumulated, tickEvents))
            );
        });
    }

    private List<UserFacingEvent> appendEvents(List<UserFacingEvent> left, List<UserFacingEvent> right) {
        if ((left == null || left.isEmpty()) && (right == null || right.isEmpty())) {
            return List.of();
        }
        List<UserFacingEvent> merged = new ArrayList<>((left == null ? 0 : left.size()) + (right == null ? 0 : right.size()));
        if (left != null && !left.isEmpty()) {
            merged.addAll(left);
        }
        if (right != null && !right.isEmpty()) {
            merged.addAll(right);
        }
        return List.copyOf(merged);
    }

    private void maybeLogSeatSyncError(String tableId, String action, UUID playerId, Throwable throwable) {
        String reason = rootMessage(throwable);
        String lowered = reason.toLowerCase(Locale.ROOT);
        boolean noisyExpected = lowered.contains("player already joined")
                || lowered.contains("cannot join in phase")
                || lowered.contains("table is full")
                || lowered.contains("table not found")
                || lowered.contains("insufficient_balance");
        if (noisyExpected) {
            return;
        }
        getLogger().warning("Seat sync " + action + " failed. table="
                + tableId + ", player=" + playerId + ", reason=" + reason);
    }

    private LiarBarCommandExecutor.CreateTableResult createConfiguredTableAtPlayer(Player player, String requestedId) {
        String tableId = (requestedId == null || requestedId.isBlank())
                ? toTableId(player)
                : requestedId;
        boolean created = tableService.createTableIfAbsent(
                tableId,
                tableConfig,
                economyPort,
                randomSource
        );
        if (created) {
            structureBuilder.build(tableId, player.getLocation());
            seatManager.spawnSeats(tableId);
            lobbyHologramManager.createTable(tableId);
            persistTables();
        }
        return new LiarBarCommandExecutor.CreateTableResult(tableId, created);
    }

    private boolean deleteTable(String tableId) {
        boolean removed = tableService.removeTable(tableId);
        if (removed) {
            bossBarManager.removeTable(tableId);
            effectsManager.removeTable(tableId);
            lobbyHologramManager.removeTable(tableId);
            if (actionBarPublisher != null) {
                actionBarPublisher.removeTable(tableId);
            }
            seatManager.removeSeats(tableId);
            structureBuilder.demolish(tableId);
            persistTables();
        }
        return removed;
    }

    private List<String> tableIds() {
        List<String> ids = new ArrayList<>(tableService.tableIds());
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    private String toTableId(Player player) {
        String world = player.getWorld().getName().replaceAll("[^A-Za-z0-9_\\-]", "_");
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        return "table_" + world + "_" + x + "_" + y + "_" + z;
    }

    private void restoreSavedTables() {
        try {
            List<SavedTable> saved = loadPersistedTables();
            if (saved.isEmpty()) {
                return;
            }
            int restored = 0;
            for (SavedTable st : saved) {
                World world = getServer().getWorld(st.worldName());
                if (world == null) {
                    getLogger().warning("Skipping table '" + st.tableId()
                            + "': world '" + st.worldName() + "' not loaded.");
                    continue;
                }
                Location loc = new Location(world, st.x(), st.y(), st.z());
                boolean created = tableService.createTableIfAbsent(
                        st.tableId(), tableConfig, economyPort, randomSource);
                if (created) {
                    structureBuilder.build(st.tableId(), loc);
                    seatManager.spawnSeats(st.tableId());
                    lobbyHologramManager.createTable(st.tableId());
                    restored++;
                }
            }
            getLogger().info("Restored " + restored + " table(s) from persistence.");
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to restore saved tables", ex);
        }
    }

    private List<SavedTable> loadPersistedTables() {
        List<SavedTable> dbSaved = List.of();
        if (statsRepository != null) {
            try {
                dbSaved = statsRepository.loadTables();
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.WARNING, "Failed to load table locations from database.", ex);
            }
        }
        if (dbSaved != null && !dbSaved.isEmpty()) {
            return dbSaved;
        }
        if (tablePersistenceStore == null) {
            return List.of();
        }
        try {
            List<SavedTable> fileSaved = tablePersistenceStore.load();
            if (!fileSaved.isEmpty()) {
                getLogger().info("Loaded " + fileSaved.size() + " table(s) from tables.json fallback.");
            }
            return fileSaved;
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to load table locations from tables.json.", ex);
            return List.of();
        }
    }

    private void persistTables() {
        List<SavedTable> tables = structureBuilder != null ? structureBuilder.toSavedTables() : List.of();
        if (statsRepository != null) {
            try {
                statsRepository.saveTables(tables);
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.WARNING, "Failed to save table locations to database.", ex);
            }
        }
        if (tablePersistenceStore != null) {
            try {
                tablePersistenceStore.save(tables);
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.WARNING, "Failed to save table locations to tables.json.", ex);
            }
        }
    }

    private StatsRepository createStatsRepository(DatabaseConfig dbConfig) {
        return switch (dbConfig.type()) {
            case MARIADB -> {
                getLogger().info("Using MariaDB stats backend: "
                        + dbConfig.host() + ":" + dbConfig.port() + "/" + dbConfig.database());
                yield new MariaDbStatsRepository(
                        dbConfig.host(), dbConfig.port(), dbConfig.database(),
                        dbConfig.username(), dbConfig.password(), dbConfig.maxPoolSize()
                );
            }
            case H2 -> {
                getLogger().info("Using embedded H2 stats backend.");
                yield new H2StatsRepository(getDataFolder().toPath());
            }
        };
    }

    private void applyEvents(List<UserFacingEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        if (statsService != null) {
            statsService.handleEvents(events);
        }
        if (rewardService != null) {
            rewardService.handleEvents(events);
        }
        if (seatManager != null) {
            seatManager.handleEvents(events);
        }
        if (bossBarManager != null) {
            bossBarManager.handleEvents(events);
        }
        if (cardPresenter != null) {
            cardPresenter.handleEvents(events);
        }
        if (effectsManager != null) {
            effectsManager.handleEvents(events);
        }
        if (lobbyHologramManager != null) {
            lobbyHologramManager.handleEvents(events);
        }
        if (viewBridge != null) {
            viewBridge.publishAll(events);
        }
    }
}
