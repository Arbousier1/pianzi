package cn.pianzi.liarbar.paper.command;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;

import java.util.List;

public record CommandOutcome(
        boolean success,
        String message,
        List<UserFacingEvent> events
) {
    public static CommandOutcome success(String message, List<UserFacingEvent> events) {
        return new CommandOutcome(true, message, List.copyOf(events));
    }

    public static CommandOutcome failure(String message) {
        return new CommandOutcome(false, message, List.of());
    }
}
