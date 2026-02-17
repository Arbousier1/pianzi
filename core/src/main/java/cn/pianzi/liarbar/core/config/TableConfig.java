package cn.pianzi.liarbar.core.config;

public record TableConfig(
        int modeSelectionSeconds,
        int joinSeconds,
        int dealingSeconds,
        int firstTurnSeconds,
        int standardTurnSeconds,
        int resolveChallengeSeconds,
        int maxPlayers,
        int handSize,
        int minPlayCards,
        int maxPlayCards,
        int startingBullets
) {
    public static TableConfig defaults() {
        return new TableConfig(
                20,
                20,
                5,
                30,
                30,
                5,
                4,
                5,
                1,
                3,
                6
        );
    }
}


