package cn.pianzi.liarbar.core.event;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

public record CoreEvent(CoreEventType type, String message, Map<String, Object> data) {
    public CoreEvent {
        data = safeCopy(data);
    }

    private static Map<String, Object> safeCopy(Map<String, Object> input) {
        try {
            input.put("__safeCopy_probe__", null);
            input.remove("__safeCopy_probe__");
            // mutable – need defensive copy
            return Collections.unmodifiableMap(new HashMap<>(input));
        } catch (UnsupportedOperationException e) {
            // already unmodifiable
            return input;
        }
    }

    public static CoreEvent of(CoreEventType type, String message) {
        return new CoreEvent(type, message, Map.of());
    }

    public static CoreEvent of(CoreEventType type, String message, Map<String, Object> data) {
        return new CoreEvent(type, message, data);
    }
}

