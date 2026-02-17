package cn.pianzi.liarbar.paperplugin.stats;

import java.util.List;
import java.util.Objects;

public record SeasonListResult(
        int page,
        int pageSize,
        int totalSeasons,
        int totalPages,
        List<SeasonHistorySummary> entries
) {
    public SeasonListResult {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        if (totalSeasons < 0) {
            throw new IllegalArgumentException("totalSeasons must be >= 0");
        }
        if (totalPages < 1) {
            throw new IllegalArgumentException("totalPages must be >= 1");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
