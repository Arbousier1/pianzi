package cn.pianzi.liarbar.core.event;

import java.util.Map;

public record CoreEvent(CoreEventType type, String message, Map<String, Object> data) {
    public CoreEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static CoreEvent of(CoreEventType type, String message) {
        return new CoreEvent(type, message, Map.of());
    }

    public static CoreEvent of(CoreEventType type, String message, Map<String, Object> data) {
        return new CoreEvent(type, message, data);
    }
}

