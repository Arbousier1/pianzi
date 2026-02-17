package cn.pianzi.liarbar.core.runtime;

import cn.pianzi.liarbar.core.domain.Card;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PlayerState {
    final UUID id;
    final int seat;
    final List<Card> hand;
    boolean alive;
    int bullets;

    PlayerState(UUID id, int seat, int bullets) {
        this.id = id;
        this.seat = seat;
        this.bullets = bullets;
        this.alive = true;
        this.hand = new ArrayList<>();
    }
}


