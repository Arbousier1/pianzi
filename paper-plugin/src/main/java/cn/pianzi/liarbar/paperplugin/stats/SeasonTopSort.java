package cn.pianzi.liarbar.paperplugin.stats;

import java.util.Locale;

public enum SeasonTopSort {
    SCORE,
    WINS;

    public static SeasonTopSort parseOrDefault(String raw, SeasonTopSort fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "score", "points", "rank" -> SCORE;
            case "wins", "win" -> WINS;
            default -> fallback;
        };
    }
}
