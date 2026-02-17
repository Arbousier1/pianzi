package cn.pianzi.liarbar.core.event;

import java.util.Map;

public record CoreEvent(CoreEventType type, String message, Map<String, Object> data) {
    public CoreEvent {
        if (data == null) {
            data = Map.of();
        }
        // Skip defensive copy for already-unmodifiable maps (Map.of / Map.copyOf)
    }

    public static CoreEvent of(CoreEventType type, String message) {
        return new CoreEvent(type, message, Map.of());
    }

    public static CoreEvent of(CoreEventType type, String message, Map<String, Object> data) {
        return new CoreEvent(type, message, Map.copyOf(data));
    }
}

