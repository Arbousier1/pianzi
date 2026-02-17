package cn.pianzi.liarbar.paperplugin.stats;

import java.util.Objects;

public record RankTier(
        int minPoints,
        String title
) {
    public RankTier {
        title = Objects.requireNonNull(title, "title").trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("rank title cannot be blank");
        }
    }
}
