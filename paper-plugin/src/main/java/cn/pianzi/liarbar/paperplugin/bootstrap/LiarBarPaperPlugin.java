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
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class LiarBarPaperPlugin extends JavaPlugin {
    private PacketEventsLifecycle packetEventsLifecycle;
    private TableApplicationService tableService;
    private PaperCommandFacade commandFacade;
    private PacketEventsViewBridge viewBridge;
    private LiarBarStatsService statsService;
    private DatapackParityRewardService rewardService;
    private PluginSettings settings;
    private I18n i18n;
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
        TableConfig tableConfig = TableConfigLoader.fromConfig(getConfig());
        packetEventsLifecycle.init();

        VaultGateway vaultGateway = VaultGatewayFactory.fromServer(this)
                .orElseGet(() -> {
                    getLogger().warning("未找到 Vault 经济服务，下注模式将拒绝玩家加入。");
                    return VaultGatewayFactory.disabledGateway();
                });

        EconomyPort economyPort = new VaultEconomyAdapter(
                vaultGateway,
                settings.fantuanEntryFee(),
                settings.kunkunEntryFee()
        );

        tableService = new TableApplicationService();
        tableService.ensureTable(settings.tableId(), tableConfig, economyPort, RandomSource.threadLocal());

        StatsRepository statsRepository = createStatsRepository(settings.databaseConfig());
        statsService = new LiarBarStatsService(this, statsRepository, settings.scoreRule());

        commandFacade = new PaperCommandFacade(tableService);
        PacketEventsPublisher publisher = new PacketEventsActionBarPublisher(this, packetEventsLifecycle.isReady());
        viewBridge = new PacketEventsViewBridge(publisher);
        rewardService = new DatapackParityRewardService(this, i18n);

        if (!registerCommands()) {
            getLogger().severe("注册 /liarbar 命令失败，插件将被禁用。请检查 plugin.yml 是否正确。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(
                new TablePlayerConnectionListener(
                        this,
                        tableService,
                        viewBridge,
                        statsService,
                        rewardService,
                        settings.tableId()
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
        PluginCommand liarbar = getCommand("liarbar");
        if (liarbar == null) {
            return false;
        }

        LiarBarCommandExecutor executor = new LiarBarCommandExecutor(
                this,
                commandFacade,
                viewBridge,
                statsService,
                rewardService,
                i18n,
                settings.tableId()
        );
        liarbar.setExecutor(executor);
        liarbar.setTabCompleter(executor);
        return true;
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
        tableService.tick(settings.tableId()).whenComplete((events, throwable) ->
                getServer().getScheduler().runTask(this, () -> {
                    if (throwable != null) {
                        getLogger().log(java.util.logging.Level.WARNING, "牌桌 tick 执行失败", throwable);
                        return;
                    }
                    statsService.handleEvents(events);
                    rewardService.handleEvents(events);
                    viewBridge.publishAll(events);
                })
        );
    }

    private StatsRepository createStatsRepository(DatabaseConfig dbConfig) {
        return switch (dbConfig.type()) {
            case MARIADB -> {
                getLogger().info("使用 MariaDB 统计后端: " + dbConfig.host() + ":" + dbConfig.port() + "/" + dbConfig.database());
                yield new MariaDbStatsRepository(
                        dbConfig.host(), dbConfig.port(), dbConfig.database(),
                        dbConfig.username(), dbConfig.password(), dbConfig.maxPoolSize()
                );
            }
            case H2 -> {
                getLogger().info("使用 H2 内嵌统计后端。");
                yield new H2StatsRepository(getDataFolder().toPath());
            }
        };
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
