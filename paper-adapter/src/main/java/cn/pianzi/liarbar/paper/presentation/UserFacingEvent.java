package cn.pianzi.liarbar.paper.presentation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record UserFacingEvent(
        EventSeverity severity,
        String message,
        UUID targetPlayer,
        Map<String, Object> data
) {
    public UserFacingEvent {
        data = safeCopy(data);
    }

    private static Map<String, Object> safeCopy(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        // Map.copyOf() rejects null values, so filter them out
        HashMap<String, Object> clean = new HashMap<>(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                clean.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(clean);
    }

    public static UserFacingEvent broadcast(EventSeverity severity, String message, Map<String, Object> data) {
        return new UserFacingEvent(severity, message, null, data);
    }

    public static UserFacingEvent personal(EventSeverity severity, String message, UUID targetPlayer, Map<String, Object> data) {
        return new UserFacingEvent(severity, message, targetPlayer, data);
    }
}
