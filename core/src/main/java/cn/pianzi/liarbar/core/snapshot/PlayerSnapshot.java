package cn.pianzi.liarbar.core.snapshot;

import java.util.UUID;

public record PlayerSnapshot(
        UUID playerId,
        int seat,
        boolean alive,
        int bullets,
        int handSize
) {
}


