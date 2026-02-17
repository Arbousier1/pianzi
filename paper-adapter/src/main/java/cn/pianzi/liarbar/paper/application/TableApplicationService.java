package cn.pianzi.liarbar.paper.application;

import cn.pianzi.liarbar.core.config.TableConfig;
import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.port.EconomyPort;
import cn.pianzi.liarbar.core.port.RandomSource;
import cn.pianzi.liarbar.core.runtime.AsyncTableRuntime;
import cn.pianzi.liarbar.core.runtime.LiarBarRuntimeManager;
import cn.pianzi.liarbar.core.snapshot.GameSnapshot;
import cn.pianzi.liarbar.paper.presentation.CoreEventTranslator;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class TableApplicationService implements AutoCloseable {
    private final LiarBarRuntimeManager runtimeManager;
    private final CoreEventTranslator eventTranslator;

    public TableApplicationService() {
        this(new LiarBarRuntimeManager(), new CoreEventTranslator());
    }

    public TableApplicationService(LiarBarRuntimeManager runtimeManager, CoreEventTranslator eventTranslator) {
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.eventTranslator = Objects.requireNonNull(eventTranslator, "eventTranslator");
    }

    public void ensureTable(String tableId, TableConfig config, EconomyPort economyPort, RandomSource randomSource) {
        runtimeManager.createTable(tableId, config, economyPort, randomSource);
    }

    public boolean tableExists(String tableId) {
        return runtimeManager.getTable(tableId).isPresent();
    }

    public boolean createTableIfAbsent(String tableId, TableConfig config, EconomyPort economyPort, RandomSource randomSource) {
        boolean existed = tableExists(tableId);
        if (!existed) {
            runtimeManager.createTable(tableId, config, economyPort, randomSource);
        }
        return !existed;
    }

    public boolean removeTable(String tableId) {
        return runtimeManager.removeTable(tableId);
    }

    public Set<String> tableIds() {
        return runtimeManager.tableIds();
    }

    public CompletionStage<List<UserFacingEvent>> selectMode(String tableId, UUID actor, TableMode mode) {
        return execute(tableId, runtime -> runtime.selectMode(actor, mode));
    }

    public CompletionStage<List<UserFacingEvent>> join(String tableId, UUID playerId) {
        return execute(tableId, runtime -> runtime.join(playerId));
    }

    public CompletionStage<List<UserFacingEvent>> play(String tableId, UUID playerId, List<Integer> slots) {
        return execute(tableId, runtime -> runtime.play(playerId, slots));
    }

    public CompletionStage<List<UserFacingEvent>> challenge(String tableId, UUID playerId) {
        return execute(tableId, runtime -> runtime.challenge(playerId));
    }

    public CompletionStage<List<UserFacingEvent>> playerDisconnected(String tableId, UUID playerId) {
        return execute(tableId, runtime -> runtime.playerDisconnected(playerId));
    }

    public CompletionStage<List<UserFacingEvent>> forceStop(String tableId) {
        return execute(tableId, AsyncTableRuntime::forceStop);
    }

    public CompletionStage<List<UserFacingEvent>> tick(String tableId) {
        return execute(tableId, AsyncTableRuntime::tickSecond);
    }

    public CompletionStage<GameSnapshot> snapshot(String tableId) {
        return runtimeManager.getTable(tableId)
                .<CompletionStage<GameSnapshot>>map(AsyncTableRuntime::snapshot)
                .orElseGet(() -> CompletableFuture.failedStage(
                        new IllegalStateException("table not found: " + tableId)));
    }

    private CompletionStage<List<UserFacingEvent>> execute(
            String tableId,
            TableExecutor executor
    ) {
        return runtimeManager.getTable(tableId)
                .<CompletionStage<List<UserFacingEvent>>>map(runtime -> executor.execute(runtime)
                        .thenApply(eventTranslator::translate)
                        .thenApply(events -> injectTableId(events, tableId)))
                .orElseGet(() -> CompletableFuture.failedStage(
                        new IllegalStateException("table not found: " + tableId)));
    }

    private List<UserFacingEvent> injectTableId(List<UserFacingEvent> events, String tableId) {
        return events.stream()
                .map(e -> e.withTableId(tableId))
                .toList();
    }

    @Override
    public void close() {
        runtimeManager.close();
    }

    @FunctionalInterface
    private interface TableExecutor {
        CompletionStage<List<cn.pianzi.liarbar.core.event.CoreEvent>> execute(AsyncTableRuntime runtime);
    }
}
