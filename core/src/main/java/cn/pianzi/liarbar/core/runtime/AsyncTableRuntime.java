package cn.pianzi.liarbar.core.runtime;

import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.event.CoreEvent;
import cn.pianzi.liarbar.core.snapshot.GameSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class AsyncTableRuntime implements AutoCloseable {
    private final LiarBarTable table;
    private final ExecutorService mailbox;

    public AsyncTableRuntime(LiarBarTable table) {
        this.table = Objects.requireNonNull(table, "table");
        ThreadFactory threadFactory = Thread.ofVirtual()
                .name("liar-bar-core-" + table.snapshot().tableId() + "-", 0)
                .factory();
        this.mailbox = Executors.newSingleThreadExecutor(threadFactory);
    }

    public CompletionStage<List<CoreEvent>> selectMode(UUID actor, TableMode mode) {
        return CompletableFuture.supplyAsync(() -> table.selectMode(actor, mode), mailbox);
    }

    public CompletionStage<List<CoreEvent>> selectMode(UUID actor, TableMode mode, int wager) {
        return CompletableFuture.supplyAsync(() -> table.selectMode(actor, mode, wager), mailbox);
    }

    public CompletionStage<List<CoreEvent>> join(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> table.join(playerId), mailbox);
    }

    public CompletionStage<List<CoreEvent>> play(UUID playerId, List<Integer> oneBasedSlots) {
        return CompletableFuture.supplyAsync(() -> table.play(playerId, oneBasedSlots), mailbox);
    }

    public CompletionStage<List<CoreEvent>> challenge(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> table.challenge(playerId), mailbox);
    }

    public CompletionStage<List<CoreEvent>> playerDisconnected(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> table.playerDisconnected(playerId), mailbox);
    }

    public CompletionStage<List<CoreEvent>> tickSecond() {
        return CompletableFuture.supplyAsync(table::tickSecond, mailbox);
    }

    public CompletionStage<List<CoreEvent>> forceStop() {
        return CompletableFuture.supplyAsync(table::forceStop, mailbox);
    }

    public CompletionStage<GameSnapshot> snapshot() {
        return CompletableFuture.supplyAsync(table::snapshot, mailbox);
    }

    @Override
    public void close() {
        mailbox.shutdown();
        try {
            if (!mailbox.awaitTermination(3, TimeUnit.SECONDS)) {
                mailbox.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            mailbox.shutdownNow();
        }
    }
}


