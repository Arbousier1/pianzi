package cn.pianzi.liarbar.paperplugin.config;

import cn.pianzi.liarbar.paperplugin.stats.RankTier;
import cn.pianzi.liarbar.paperplugin.stats.ScoreRule;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record PluginSettings(
        String tableId,
        int tickIntervalTicks,
        double fantuanEntryFee,
        double kunkunEntryFee,
        String localeTag,
        ZoneId zoneId,
        ScoreRule scoreRule,
        DatabaseConfig databaseConfig
) {
    public static PluginSettings fromConfig(FileConfiguration config) {
        String tableId = nonBlank(config.getString("table.id"), "default");
        int tickIntervalTicks = Math.max(1, config.getInt("table.tick-interval-ticks", 20));
        double fantuanEntryFee = Math.max(0.0D, config.getDouble("economy.fantuan-entry-fee", 1.0D));
        double kunkunEntryFee = Math.max(0.0D, config.getDouble("economy.kunkun-entry-fee", 1.0D));
        String localeTag = nonBlank(config.getString("i18n.locale"), "zh-CN");
        ZoneId zoneId = parseZoneId(config.getString("i18n.timezone"));
        int initialScore = config.getInt("score.initial", 200);
        int minJoinScore = Math.max(0, config.getInt("score.min-join-score", 50));
        int entryCost = Math.max(0, config.getInt("score.entry-cost", 50));
        int joinScore = config.getInt("score.join", 0);
        int surviveShotScore = config.getInt("score.survive-shot", 0);
        int winScore = config.getInt("score.win", 100);
        int loseScore = config.getInt("score.lose", 0);
        int eliminatedScore = config.getInt("score.eliminated", 0);
        ScoreRule scoreRule = new ScoreRule(
                initialScore,
                minJoinScore,
                entryCost,
                joinScore,
                surviveShotScore,
                winScore,
                loseScore,
                eliminatedScore,
                parseRankTiers(config)
        );
        DatabaseConfig databaseConfig = DatabaseConfig.fromConfig(config);
        return new PluginSettings(
                tableId,
                tickIntervalTicks,
                fantuanEntryFee,
                kunkunEntryFee,
                localeTag,
                zoneId,
                scoreRule,
                databaseConfig
        );
    }

    private static ZoneId parseZoneId(String raw) {
        if (raw == null || raw.isBlank() || "system".equalsIgnoreCase(raw)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static List<RankTier> parseRankTiers(FileConfiguration config) {
        List<Map<?, ?>> rawTiers = config.getMapList("ranking.tiers");
        if (rawTiers.isEmpty()) {
            return defaultRankTiers();
        }

        List<RankTier> tiers = new ArrayList<>();
        for (Map<?, ?> rawTier : rawTiers) {
            int min = asInt(rawTier.get("min"), 0);
            String title = asString(rawTier.get("title"));
            if (title == null || title.isBlank()) {
                continue;
            }
            tiers.add(new RankTier(min, title));
        }
        return tiers.isEmpty() ? defaultRankTiers() : tiers;
    }

    private static List<RankTier> defaultRankTiers() {
        return List.of(
                new RankTier(0, "见习骗子"),
                new RankTier(200, "小有名气"),
                new RankTier(500, "老千常客"),
                new RankTier(1000, "骗局大师"),
                new RankTier(1800, "传说骗子")
        );
    }

    private static int asInt(Object value, int fallback) {
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

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
