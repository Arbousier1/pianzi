package cn.pianzi.liarbar.paperplugin.stats;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LiarBarStatsService implements AutoCloseable {
    private static final String EVENT_TYPE_KEY = "_eventType";
    private static final long SAVE_DEBOUNCE_MILLIS = 500L;

    private final JavaPlugin plugin;
    private final StatsRepository repository;
    private volatile ScoreRule scoreRule;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService ioExecutor;

    private final Object lock = new Object();
    private final Object persistenceLock = new Object();
    private final Map<UUID, PlayerStats> statsByPlayer = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> eliminatedPlayers = new HashSet<>();

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile List<Integer> recentSeasonIds = List.of();

    public LiarBarStatsService(
            JavaPlugin plugin,
            StatsRepository repository,
            ScoreRule scoreRule
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scoreRule = Objects.requireNonNull(scoreRule, "scoreRule");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("liarbar-stats-scheduler").factory()
        );
        this.ioExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("liarbar-stats-io-", 0).factory()
        );
        loadFromStorage();
    }

    public void handleEvents(List<UserFacingEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        boolean changed = false;
        synchronized (lock) {
            for (UserFacingEvent event : events) {
                if (applyEvent(event)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            requestSave();
        }
    }

    public PlayerStatsSnapshot statsOf(UUID playerId) {
        synchronized (lock) {
            return statsByPlayer.getOrDefault(playerId, PlayerStats.create(playerId, scoreRule.initialScore())).snapshot();
        }
    }

    public List<PlayerStatsSnapshot> top(int limit) {
        int safeLimit = Math.max(1, limit);
        synchronized (lock) {
            return statsByPlayer.values().stream()
                    .map(PlayerStats::snapshot)
                    .sorted(Comparator
                            .comparingInt(PlayerStatsSnapshot::score).reversed()
                            .thenComparingInt(PlayerStatsSnapshot::wins).reversed()
                            .thenComparing(PlayerStatsSnapshot::playerId))
                    .limit(safeLimit)
                    .toList();
        }
    }

    public boolean canJoinRanked(UUID playerId) {
        ScoreRule rule = scoreRule;
        synchronized (lock) {
            PlayerStats stats = statsByPlayer.get(playerId);
            int score = stats == null ? rule.initialScore() : stats.score();
            return score >= rule.minJoinScore();
        }
    }

    public int minJoinScore() {
        return scoreRule.minJoinScore();
    }

    public String rankTitleOf(int score) {
        return scoreRule.resolveRankTitle(score);
    }

    public ScoreRule scoreRule() {
        return scoreRule;
    }

    public void updateScoreRule(ScoreRule newRule) {
        ScoreRule checked = Objects.requireNonNull(newRule, "newRule");
        synchronized (lock) {
            this.scoreRule = checked;
        }
    }

    public CompletionStage<SeasonResetResult> resetSeason() {
        if (closed.get()) {
            return CompletableFuture.failedStage(new IllegalStateException("stats service already closed"));
        }
        return CompletableFuture.supplyAsync(this::resetSeasonBlocking, ioExecutor);
    }

    public CompletionStage<SeasonListResult> listSeasons(int page, int pageSize) {
        if (closed.get()) {
            return CompletableFuture.failedStage(new IllegalStateException("stats service already closed"));
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(50, pageSize));
        return CompletableFuture.supplyAsync(() -> listSeasonsBlocking(safePage, safePageSize), ioExecutor);
    }

    public CompletionStage<SeasonTopResult> topForSeason(int seasonId, int page, int pageSize, SeasonTopSort sort) {
        if (closed.get()) {
            return CompletableFuture.failedStage(new IllegalStateException("stats service already closed"));
        }
        if (seasonId <= 0) {
            return CompletableFuture.failedStage(new IllegalArgumentException("seasonId must be > 0"));
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(50, pageSize));
        SeasonTopSort safeSort = sort == null ? SeasonTopSort.SCORE : sort;
        return CompletableFuture.supplyAsync(() -> topForSeasonBlocking(seasonId, safePage, safePageSize, safeSort), ioExecutor);
    }

    public List<Integer> recentSeasonIds(int limit) {
        int safeLimit = Math.max(1, limit);
        List<Integer> snapshot = recentSeasonIds;
        if (snapshot.size() <= safeLimit) {
            return snapshot;
        }
        return snapshot.subList(0, safeLimit);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            saveNow();
        } catch (Exception ex) {
            plugin.getLogger().warning("关闭时保存统计数据失败: " + rootMessage(ex));
        }

        scheduler.shutdown();
        ioExecutor.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
            ioExecutor.shutdownNow();
        }

        repository.close();
    }

    private boolean applyEvent(UserFacingEvent event) {
        String type = asString(event.data().get(EVENT_TYPE_KEY));
        if (type == null) {
            return false;
        }

        return switch (type) {
            case "PLAYER_JOINED" -> onPlayerJoined(event);
            case "SHOT_RESOLVED" -> onShotResolved(event);
            case "PLAYER_ELIMINATED" -> onPlayerEliminated(event);
            case "GAME_FINISHED" -> onGameFinished(event);
            default -> false;
        };
    }

    private boolean onPlayerJoined(UserFacingEvent event) {
        ScoreRule rule = scoreRule;
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return false;
        }
        if (!participants.add(playerId)) {
            return false;
        }
        mutableStatsOf(playerId, rule).onJoin(rule.join());
        return true;
    }

    private boolean onShotResolved(UserFacingEvent event) {
        ScoreRule rule = scoreRule;
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return false;
        }
        Boolean lethal = asBoolean(event.data().get("lethal"));
        if (Boolean.TRUE.equals(lethal)) {
            return false;
        }
        mutableStatsOf(playerId, rule).onSurviveShot(rule.surviveShot());
        return true;
    }

    private boolean onPlayerEliminated(UserFacingEvent event) {
        ScoreRule rule = scoreRule;
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return false;
        }
        if (!eliminatedPlayers.add(playerId)) {
            return false;
        }
        mutableStatsOf(playerId, rule).onEliminated(rule.eliminated());
        return true;
    }

    private boolean onGameFinished(UserFacingEvent event) {
        ScoreRule rule = scoreRule;
        if (participants.isEmpty()) {
            return false;
        }
        UUID winner = asUuid(event.data().get("winner"));
        if (winner == null) {
            participants.clear();
            eliminatedPlayers.clear();
            return false;
        }

        List<UUID> participantList = new ArrayList<>(participants);
        for (UUID participant : participantList) {
            PlayerStats stats = mutableStatsOf(participant, rule);
            if (rule.entryCost() != 0) {
                stats.applyScoreDelta(-rule.entryCost());
            }
            if (participant.equals(winner)) {
                stats.onWin(rule.win());
            } else {
                stats.onLose(rule.lose());
            }
        }

        participants.clear();
        eliminatedPlayers.clear();
        return true;
    }

    private PlayerStats mutableStatsOf(UUID playerId, ScoreRule rule) {
        return statsByPlayer.computeIfAbsent(playerId, ignored -> PlayerStats.create(playerId, rule.initialScore()));
    }

    private void requestSave() {
        dirty.set(true);
        if (closed.get()) {
            return;
        }
        if (saveScheduled.compareAndSet(false, true)) {
            scheduleSave();
        }
    }

    private void scheduleSave() {
        try {
            scheduler.schedule(() -> ioExecutor.execute(this::saveLoop), SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            saveScheduled.set(false);
            if (!closed.get()) {
                plugin.getLogger().warning("统计数据保存调度被拒绝（可能正在关闭）: " + rootMessage(ex));
            }
        }
    }

    private void saveLoop() {
        try {
            while (dirty.compareAndSet(true, false)) {
                saveNow();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("保存统计数据失败: " + rootMessage(ex));
        } finally {
            saveScheduled.set(false);
            if (dirty.get() && !closed.get()) {
                requestSave();
            }
        }
    }

    private void saveNow() throws SQLException {
        Map<UUID, PlayerStatsSnapshot> snapshots;
        synchronized (lock) {
            snapshots = new HashMap<>(statsByPlayer.size());
            for (Map.Entry<UUID, PlayerStats> entry : statsByPlayer.entrySet()) {
                snapshots.put(entry.getKey(), entry.getValue().snapshot());
            }
        }
        synchronized (persistenceLock) {
            repository.upsertAll(snapshots);
        }
    }

    private void loadFromStorage() {
        try {
            repository.initSchema();
            Map<UUID, PlayerStatsSnapshot> snapshots = repository.loadAll();
            synchronized (lock) {
                for (Map.Entry<UUID, PlayerStatsSnapshot> entry : snapshots.entrySet()) {
                    statsByPlayer.put(entry.getKey(), PlayerStats.fromSnapshot(entry.getValue()));
                }
            }
            refreshSeasonCache();
        } catch (Exception ex) {
            plugin.getLogger().warning("从数据库加载统计数据失败: " + rootMessage(ex));
        }
    }

    private SeasonResetResult resetSeasonBlocking() {
        try {
            synchronized (lock) {
                synchronized (persistenceLock) {
                    Map<UUID, PlayerStatsSnapshot> snapshots = new HashMap<>(statsByPlayer.size());
                    for (Map.Entry<UUID, PlayerStats> entry : statsByPlayer.entrySet()) {
                        snapshots.put(entry.getKey(), entry.getValue().snapshot());
                    }
                    SeasonResetResult result = repository.archiveAndClear(snapshots, Instant.now().getEpochSecond());
                    statsByPlayer.clear();
                    participants.clear();
                    eliminatedPlayers.clear();
                    dirty.set(false);
                    saveScheduled.set(false);
                    pushSeasonCache(result.seasonId());
                    return result;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("赛季重置失败，数据已回滚", ex);
        }
    }

    private SeasonListResult listSeasonsBlocking(int page, int pageSize) {
        try {
            synchronized (persistenceLock) {
                int totalSeasons = repository.countSeasons();
                int totalPages = Math.max(1, (totalSeasons + pageSize - 1) / pageSize);
                int safePage = Math.min(page, totalPages);
                List<SeasonHistorySummary> entries = repository.listSeasons(safePage, pageSize);
                return new SeasonListResult(
                        safePage,
                        pageSize,
                        totalSeasons,
                        totalPages,
                        entries
                );
            }
        } catch (Exception ex) {
            throw new IllegalStateException("查询赛季列表失败", ex);
        }
    }

    private SeasonTopResult topForSeasonBlocking(int seasonId, int page, int pageSize, SeasonTopSort sort) {
        try {
            synchronized (persistenceLock) {
                Optional<SeasonTopResult> result = repository.topForSeason(seasonId, page, pageSize, sort);
                return result.orElseThrow(() -> new IllegalArgumentException("找不到赛季 #" + seasonId));
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("查询赛季排行榜失败", ex);
        }
    }

    private void refreshSeasonCache() {
        try {
            synchronized (persistenceLock) {
                recentSeasonIds = List.copyOf(repository.listSeasonIds(50));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("刷新赛季缓存失败: " + rootMessage(ex));
        }
    }

    private void pushSeasonCache(int seasonId) {
        List<Integer> previous = recentSeasonIds;
        List<Integer> updated = new ArrayList<>(previous.size() + 1);
        updated.add(seasonId);
        for (Integer id : previous) {
            if (!id.equals(seasonId)) {
                updated.add(id);
            }
            if (updated.size() >= 50) {
                break;
            }
        }
        recentSeasonIds = List.copyOf(updated);
    }

    private UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
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
