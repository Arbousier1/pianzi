package cn.pianzi.liarbar.paperplugin.util;

import java.util.UUID;

/**
 * Centralized type-safe accessors for event data map values.
 * Replaces duplicated private asUuid / asString / asInt / asBoolean helpers
 * scattered across handler classes.
 */
public final class EventDataAccessor {

    private EventDataAccessor() {
    }

    public static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    public static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return null;
    }
}
