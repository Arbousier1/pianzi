package cn.pianzi.liarbar.paperplugin.stats;

import java.util.List;
import java.util.Objects;

public record SeasonTopResult(
        int seasonId,
        long archivedAtEpochSecond,
        SeasonTopSort sort,
        int page,
        int pageSize,
        int totalPlayers,
        int totalPages,
        List<PlayerStatsSnapshot> entries
) {
    public SeasonTopResult {
        sort = Objects.requireNonNull(sort, "sort");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        if (totalPlayers < 0) {
            throw new IllegalArgumentException("totalPlayers must be >= 0");
        }
        if (totalPages < 1) {
            throw new IllegalArgumentException("totalPages must be >= 1");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
