package cn.pianzi.liarbar.paperplugin.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ScoreRule(
        int initialScore,
        int minJoinScore,
        int entryCost,
        int join,
        int surviveShot,
        int win,
        int lose,
        int eliminated,
        List<RankTier> rankTiers
) {
    public ScoreRule {
        List<RankTier> copied = rankTiers == null ? List.of() : new ArrayList<>(rankTiers);
        copied.removeIf(Objects::isNull);
        copied.sort(Comparator.comparingInt(RankTier::minPoints));
        if (copied.isEmpty()) {
            copied = List.of(new RankTier(0, "Unranked"));
        }
        rankTiers = List.copyOf(copied);
    }

    public String resolveRankTitle(int score) {
        String resolved = rankTiers.getFirst().title();
        for (RankTier tier : rankTiers) {
            if (score >= tier.minPoints()) {
                resolved = tier.title();
            } else {
                break;
            }
        }
        return resolved;
    }
}
