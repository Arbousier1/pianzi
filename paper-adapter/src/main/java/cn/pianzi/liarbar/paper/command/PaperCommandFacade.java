package cn.pianzi.liarbar.paper.command;

import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.snapshot.GameSnapshot;
import cn.pianzi.liarbar.paper.application.TableApplicationService;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PaperCommandFacade {
    private final TableApplicationService service;

    public PaperCommandFacade(TableApplicationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public CompletionStage<CommandOutcome> selectMode(String tableId, UUID playerId, TableMode mode) {
        return run("command.result.mode_updated", () -> service.selectMode(tableId, playerId, mode));
    }

    public CompletionStage<CommandOutcome> join(String tableId, UUID playerId) {
        return run("command.result.joined", () -> service.join(tableId, playerId));
    }

    public CompletionStage<CommandOutcome> play(String tableId, UUID playerId, List<Integer> slots) {
        return run("command.result.played", () -> service.play(tableId, playerId, slots));
    }

    public CompletionStage<CommandOutcome> challenge(String tableId, UUID playerId) {
        return run("command.result.challenged", () -> service.challenge(tableId, playerId));
    }

    public CompletionStage<CommandOutcome> leave(String tableId, UUID playerId) {
        return run("command.result.left", () -> service.playerDisconnected(tableId, playerId));
    }

    public CompletionStage<CommandOutcome> forceStop(String tableId) {
        return run("command.result.stopped", () -> service.forceStop(tableId));
    }

    public CompletionStage<GameSnapshot> snapshot(String tableId) {
        try {
            return service.snapshot(tableId);
        } catch (Exception ex) {
            return CompletableFuture.failedStage(ex);
        }
    }

    private CompletionStage<CommandOutcome> run(String successMessage, EventSupplier supplier) {
        try {
            return supplier.get().handle((events, ex) -> {
                if (ex == null) {
                    return CommandOutcome.success(successMessage, events);
                }
                return CommandOutcome.failure(rootMessage(ex));
            });
        } catch (Exception ex) {
            return CompletableFuture.completedFuture(CommandOutcome.failure(rootMessage(ex)));
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface EventSupplier {
        CompletionStage<List<UserFacingEvent>> get();
    }
}
