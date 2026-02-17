package cn.pianzi.liarbar.paperplugin.config;

import cn.pianzi.liarbar.core.config.TableConfig;
import org.bukkit.configuration.file.FileConfiguration;

public final class TableConfigLoader {
    private TableConfigLoader() {
    }

    public static TableConfig fromConfig(FileConfiguration config) {
        TableConfig defaults = TableConfig.defaults();

        int modeSelectionSeconds = positive(config.getInt("table.mode-selection-seconds", defaults.modeSelectionSeconds()), defaults.modeSelectionSeconds());
        int joinSeconds = positive(config.getInt("table.join-seconds", defaults.joinSeconds()), defaults.joinSeconds());
        int dealingSeconds = positive(config.getInt("table.dealing-seconds", defaults.dealingSeconds()), defaults.dealingSeconds());
        int firstTurnSeconds = positive(config.getInt("table.first-turn-seconds", defaults.firstTurnSeconds()), defaults.firstTurnSeconds());
        int standardTurnSeconds = positive(config.getInt("table.standard-turn-seconds", defaults.standardTurnSeconds()), defaults.standardTurnSeconds());
        int resolveChallengeSeconds = positive(config.getInt("table.resolve-challenge-seconds", defaults.resolveChallengeSeconds()), defaults.resolveChallengeSeconds());
        int maxPlayers = positive(config.getInt("table.max-players", defaults.maxPlayers()), defaults.maxPlayers());
        int handSize = positive(config.getInt("table.hand-size", defaults.handSize()), defaults.handSize());
        int minPlayCards = positive(config.getInt("table.min-play-cards", defaults.minPlayCards()), defaults.minPlayCards());
        int maxPlayCards = positive(config.getInt("table.max-play-cards", defaults.maxPlayCards()), defaults.maxPlayCards());
        int startingBullets = positive(config.getInt("table.starting-bullets", defaults.startingBullets()), defaults.startingBullets());

        if (minPlayCards > maxPlayCards) {
            int swap = minPlayCards;
            minPlayCards = maxPlayCards;
            maxPlayCards = swap;
        }

        return new TableConfig(
                modeSelectionSeconds,
                joinSeconds,
                dealingSeconds,
                firstTurnSeconds,
                standardTurnSeconds,
                resolveChallengeSeconds,
                maxPlayers,
                handSize,
                minPlayCards,
                maxPlayCards,
                startingBullets
        );
    }

    private static int positive(int candidate, int fallback) {
        return candidate > 0 ? candidate : fallback;
    }
}
