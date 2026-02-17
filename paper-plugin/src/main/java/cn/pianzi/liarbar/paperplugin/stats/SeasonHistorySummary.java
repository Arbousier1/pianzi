package cn.pianzi.liarbar.paperplugin.stats;

public record SeasonHistorySummary(
        int seasonId,
        long archivedAtEpochSecond,
        int playerCount
) {
}
