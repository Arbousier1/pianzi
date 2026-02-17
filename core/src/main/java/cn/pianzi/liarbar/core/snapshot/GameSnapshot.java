package cn.pianzi.liarbar.core.snapshot;

import cn.pianzi.liarbar.core.domain.Card;
import cn.pianzi.liarbar.core.domain.CardRank;
import cn.pianzi.liarbar.core.domain.GamePhase;
import cn.pianzi.liarbar.core.domain.TableMode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record GameSnapshot(
        String tableId,
        GamePhase phase,
        int phaseSeconds,
        TableMode mode,
        int joinedCount,
        int round,
        Optional<CardRank> mainRank,
        List<Card> centerCards,
        List<PlayerSnapshot> players,
        Optional<UUID> currentPlayer,
        Optional<UUID> lastPlayer,
        boolean forceChallenge
) {
}


