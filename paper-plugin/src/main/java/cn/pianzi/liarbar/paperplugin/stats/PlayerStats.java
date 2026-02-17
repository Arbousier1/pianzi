package cn.pianzi.liarbar.paperplugin.stats;

import java.time.Instant;
import java.util.UUID;

final class PlayerStats {
    private final UUID playerId;
    private int score;
    private int gamesPlayed;
    private int wins;
    private int losses;
    private int eliminatedCount;
    private int survivedShots;
    private int currentWinStreak;
    private int bestWinStreak;
    private long updatedAtEpochSecond;

    private PlayerStats(UUID playerId, int initialScore) {
        this.playerId = playerId;
        this.score = initialScore;
        touch();
    }

    static PlayerStats create(UUID playerId, int initialScore) {
        return new PlayerStats(playerId, initialScore);
    }

    static PlayerStats fromSnapshot(PlayerStatsSnapshot snapshot) {
        PlayerStats stats = new PlayerStats(snapshot.playerId(), 0);
        stats.score = snapshot.score();
        stats.gamesPlayed = snapshot.gamesPlayed();
        stats.wins = snapshot.wins();
        stats.losses = snapshot.losses();
        stats.eliminatedCount = snapshot.eliminatedCount();
        stats.survivedShots = snapshot.survivedShots();
        stats.currentWinStreak = snapshot.currentWinStreak();
        stats.bestWinStreak = snapshot.bestWinStreak();
        stats.updatedAtEpochSecond = snapshot.updatedAtEpochSecond();
        return stats;
    }

    void onJoin(int points) {
        score += points;
        touch();
    }

    void onSurviveShot(int points) {
        survivedShots++;
        score += points;
        touch();
    }

    void onEliminated(int points) {
        eliminatedCount++;
        score += points;
        touch();
    }

    void applyScoreDelta(int points) {
        score += points;
        touch();
    }

    int score() {
        return score;
    }

    void onWin(int points) {
        wins++;
        score += points;
        currentWinStreak++;
        if (currentWinStreak > bestWinStreak) {
            bestWinStreak = currentWinStreak;
        }
        touch();
    }

    void onLose(int points) {
        losses++;
        score += points;
        currentWinStreak = 0;
        touch();
    }

    PlayerStatsSnapshot snapshot() {
        return new PlayerStatsSnapshot(
                playerId,
                score,
                gamesPlayed,
                wins,
                losses,
                eliminatedCount,
                survivedShots,
                currentWinStreak,
                bestWinStreak,
                updatedAtEpochSecond
        );
    }

    private void touch() {
        updatedAtEpochSecond = Instant.now().getEpochSecond();
    }
}
