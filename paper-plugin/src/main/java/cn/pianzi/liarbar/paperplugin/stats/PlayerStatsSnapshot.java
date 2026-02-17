package cn.pianzi.liarbar.paperplugin.stats;

import java.util.UUID;

public record PlayerStatsSnapshot(
        UUID playerId,
        int score,
        int gamesPlayed,
        int wins,
        int losses,
        int eliminatedCount,
        int survivedShots,
        int currentWinStreak,
        int bestWinStreak,
        long updatedAtEpochSecond
) {
}

