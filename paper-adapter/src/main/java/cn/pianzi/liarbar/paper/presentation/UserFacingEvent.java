package cn.pianzi.liarbar.paper.presentation;

import java.util.Map;
import java.util.UUID;

public record UserFacingEvent(
        EventSeverity severity,
        String message,
        UUID targetPlayer,
        Map<String, Object> data
) {
    public UserFacingEvent {
        data = Map.copyOf(data);
    }

    public static UserFacingEvent broadcast(EventSeverity severity, String message, Map<String, Object> data) {
        return new UserFacingEvent(severity, message, null, data);
    }

    public static UserFacingEvent personal(EventSeverity severity, String message, UUID targetPlayer, Map<String, Object> data) {
        return new UserFacingEvent(severity, message, targetPlayer, data);
    }
}
