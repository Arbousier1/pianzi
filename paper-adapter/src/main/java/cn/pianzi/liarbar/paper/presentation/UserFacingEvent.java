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
        // Skip copy for maps already produced by Map.of / Map.copyOf (ImmutableCollections)
        String className = input.getClass().getName();
        if (className.startsWith("java.util.ImmutableCollections")) {
            return input;
        }
        // Map.copyOf() rejects null values, so filter them out
        HashMap<String, Object> clean = HashMap.newHashMap(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                clean.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(clean);
    }

    /**
     * Return a new event with the tableId injected into data,
     * avoiding a full defensive copy by building the map directly.
     */
    public UserFacingEvent withTableId(String tableId) {
        HashMap<String, Object> enriched = HashMap.newHashMap(data.size() + 1);
        enriched.putAll(data);
        enriched.put("tableId", tableId);
        // Bypass the compact constructor's safeCopy — we know the map is clean
        return new UserFacingEvent(severity, message, targetPlayer, Map.copyOf(enriched));
    }

    public static UserFacingEvent broadcast(EventSeverity severity, String message, Map<String, Object> data) {
        return new UserFacingEvent(severity, message, null, data);
    }

    public static UserFacingEvent personal(EventSeverity severity, String message, UUID targetPlayer, Map<String, Object> data) {
        return new UserFacingEvent(severity, message, targetPlayer, data);
    }
}
