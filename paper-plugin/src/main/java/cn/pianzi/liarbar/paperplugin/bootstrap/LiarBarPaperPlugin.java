package cn.pianzi.liarbar.paperplugin.bootstrap;

import cn.pianzi.liarbar.core.config.TableConfig;
import cn.pianzi.liarbar.core.port.EconomyPort;
import cn.pianzi.liarbar.core.port.RandomSource;
import cn.pianzi.liarbar.paper.application.TableApplicationService;
import cn.pianzi.liarbar.paper.command.PaperCommandFacade;
import cn.pianzi.liarbar.paper.integration.vault.VaultEconomyAdapter;
import cn.pianzi.liarbar.paper.integration.vault.VaultGateway;
import cn.pianzi.liarbar.paper.presentation.PacketEventsPublisher;
import cn.pianzi.liarbar.paper.presentation.PacketEventsViewBridge;
import cn.pianzi.liarbar.paperplugin.command.LiarBarCommandExecutor;
import cn.pianzi.liarbar.paperplugin.config.PluginSettings;
import cn.pianzi.liarbar.paperplugin.config.TableConfigLoader;
import cn.pianzi.liarbar.paperplugin.game.DatapackParityRewardService;
import cn.pianzi.liarbar.paperplugin.game.TablePlayerConnectionListener;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.integration.packet.PacketEventsLifecycle;
import cn.pianzi.liarbar.paperplugin.integration.vault.VaultGatewayFactory;
import cn.pianzi.liarbar.paperplugin.presentation.PacketEventsActionBarPublisher;
import cn.pianzi.liarbar.paperplugin.config.DatabaseConfig;
import cn.pianzi.liarbar.paperplugin.stats.H2StatsRepository;
import cn.pianzi.liarbar.paperplugin.stats.LiarBarStatsService;
import cn.pianzi.liarbar.paperplugin.stats.MariaDbStatsRepository;
import cn.pianzi.liarbar.paperplugin.stats.StatsRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
        tableConfig = TableConfigLoader.fromConfig(getConfig());
        packetEventsLifecycle.init();

        VaultGateway vaultGateway = VaultGatewayFactory.fromServer(this)
                .orElseGet(() -> {
                    getLogger().warning("Vault economy service not found; wager modes will reject player joins.");
                    return VaultGatewayFactory.disabledGateway();
                });

        economyPort = new VaultEconomyAdapter(
                vaultGateway,
                settings.fantuanEntryFee(),
                settings.kunkunEntryFee()
        );
        randomSource = RandomSource.threadLocal();

        tableService = new TableApplicationService();
        getLogger().info("No table is auto-created. Use /liarbar create as OP at your current location.");

        StatsRepository statsRepository = createStatsRepository(settings.databaseConfig());
        statsService = new LiarBarStatsService(this, statsRepository, settings.scoreRule());

        commandFacade = new PaperCommandFacade(tableService);
        PacketEventsPublisher publisher = new PacketEventsActionBarPublisher(this, packetEventsLifecycle.isReady());
        viewBridge = new PacketEventsViewBridge(publisher);
        rewardService = new DatapackParityRewardService(this, i18n);

        if (!registerCommands()) {
            getLogger().severe("Failed to register /liarbar command. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(
                new TablePlayerConnectionListener(
                        this,
                        tableService,
                        viewBridge,
                        statsService,
                        rewardService
                ),
                this
        );

        startTickLoop();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        if (tableService != null) {
            tableService.close();
            tableService = null;
        }

        if (statsService != null) {
            statsService.close();
            statsService = null;
        }
        rewardService = null;

        if (packetEventsLifecycle != null) {
            packetEventsLifecycle.terminate();
        }
    }

    private boolean registerCommands() {
        LiarBarCommandExecutor executor = new LiarBarCommandExecutor(
                this,
                commandFacade,
                viewBridge,
                statsService,
                rewardService,
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
        record TickResult(String tableId, List<cn.pianzi.liarbar.paper.presentation.UserFacingEvent> events, Throwable error) {}
        List<java.util.concurrent.CompletableFuture<TickResult>> futures = new ArrayList<>(ids.size());
        for (String tableId : ids) {
            futures.add(
                    tableService.tick(tableId)
                            .thenApply(events -> new TickResult(tableId, events, null))
                            .exceptionally(ex -> new TickResult(tableId, List.of(), ex))
                            .toCompletableFuture()
            );
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenRun(() -> getServer().getScheduler().runTask(this, () -> {
                    for (var future : futures) {
                        TickResult result = future.join();
                        if (result.error() != null) {
                            getLogger().log(java.util.logging.Level.WARNING, "Table tick failed: " + result.tableId(), result.error());
                            continue;
                        }
                        if (!result.events().isEmpty()) {
                            statsService.handleEvents(result.events());
                            rewardService.handleEvents(result.events());
                            viewBridge.publishAll(result.events());
                        }
                    }
                }));
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
        return new LiarBarCommandExecutor.CreateTableResult(tableId, created);
    }

    private boolean deleteTable(String tableId) {
        return tableService.removeTable(tableId);
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
}

