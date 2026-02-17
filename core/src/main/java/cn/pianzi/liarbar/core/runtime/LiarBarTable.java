package cn.pianzi.liarbar.core.runtime;

import cn.pianzi.liarbar.core.config.TableConfig;
import cn.pianzi.liarbar.core.domain.Card;
import cn.pianzi.liarbar.core.domain.CardRank;
import cn.pianzi.liarbar.core.domain.GamePhase;
import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.event.CoreEvent;
import cn.pianzi.liarbar.core.event.CoreEventType;
import cn.pianzi.liarbar.core.port.EconomyPort;
import cn.pianzi.liarbar.core.port.RandomSource;
import cn.pianzi.liarbar.core.snapshot.GameSnapshot;
import cn.pianzi.liarbar.core.snapshot.PlayerSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LiarBarTable {
    private static final List<CardRank> MAIN_RANKS = List.of(CardRank.A, CardRank.Q, CardRank.K);
    private static final int MIN_WAGER = 1;
    private static final int MAX_WAGER = 1_000_000;

    private final String tableId;
    private final TableConfig config;
    private final EconomyPort economy;
    private final RandomSource random;

    private final Map<UUID, PlayerState> players;
    private final UUID[] seats;
    private final List<Card> centerCards;

    private final LinkedHashSet<UUID> shootCandidates;
    private final LinkedHashSet<UUID> preferredShooters;

    private TableMode mode;
    private GamePhase phase;
    private int phaseSeconds;
    private int joinedCount;
    private int aliveCount;
    private int round;
    private long nextCardId;
    private boolean forceChallenge;
    private int wagerPerPlayer;

    private CardRank mainRank;
    private UUID ownerId;
    private UUID currentPlayerId;
    private UUID lastPlayerId;
    private UUID afterGunCandidateId;

    public LiarBarTable(String tableId) {
        this(tableId, TableConfig.defaults(), EconomyPort.noop(), RandomSource.threadLocal());
    }

    public LiarBarTable(String tableId, TableConfig config, EconomyPort economy, RandomSource random) {
        this.tableId = Objects.requireNonNull(tableId, "tableId");
        this.config = Objects.requireNonNull(config, "config");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.random = Objects.requireNonNull(random, "random");
        this.players = new HashMap<>();
        this.seats = new UUID[config.maxPlayers() + 1];
        this.centerCards = new ArrayList<>();
        this.shootCandidates = new LinkedHashSet<>();
        this.preferredShooters = new LinkedHashSet<>();
        this.mode = TableMode.LIFE_ONLY;
        this.phase = GamePhase.MODE_SELECTION;
        this.phaseSeconds = 0;
        this.joinedCount = 0;
        this.aliveCount = 0;
        this.round = 0;
        this.nextCardId = 1;
        this.forceChallenge = false;
        this.wagerPerPlayer = 1;
        this.mainRank = null;
        this.ownerId = null;
        this.currentPlayerId = null;
        this.lastPlayerId = null;
        this.afterGunCandidateId = null;
    }

    public List<CoreEvent> selectMode(UUID actor, TableMode selectedMode) {
        return selectMode(actor, selectedMode, 1);
    }

    public List<CoreEvent> selectMode(UUID actor, TableMode selectedMode, int wager) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(selectedMode, "selectedMode");
        ensurePhase(GamePhase.MODE_SELECTION, "select mode");
        if (!Objects.equals(ownerId, actor)) {
            throw new IllegalStateException("only_host_can_select_mode");
        }

        List<CoreEvent> events = new ArrayList<>();
        int chargeAmount = resolveWagerAmount(selectedMode, wager);

        if (selectedMode.isWagerMode()) {
            // Players can now sit before mode selection; charge everyone once mode is locked.
            // Roll back already charged players if anyone cannot pay to keep behavior atomic.
            List<UUID> charged = new ArrayList<>();
            for (PlayerState state : players.values()) {
                if (!state.alive) {
                    continue;
                }
                if (!economy.charge(state.id, selectedMode, chargeAmount)) {
                    for (UUID paid : charged) {
                        economy.reward(paid, selectedMode, chargeAmount);
                    }
                    throw new IllegalStateException("insufficient_balance");
                }
                charged.add(state.id);
            }
        }

        this.mode = selectedMode;
        this.wagerPerPlayer = chargeAmount;
        events.add(CoreEvent.of(
                CoreEventType.MODE_SELECTED,
                "mode selected",
                Map.of(
                        "actor", actor,
                        "mode", selectedMode.name(),
                        "wagerPerPlayer", chargeAmount
                )
        ));
        setPhase(GamePhase.JOINING, events, "mode_selected");
        if (alivePlayersCount() >= config.maxPlayers()) {
            events.addAll(startInitialDeal("table_full_after_mode_selected"));
        }
        return Collections.unmodifiableList(events);
    }

    public List<CoreEvent> join(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (phase != GamePhase.MODE_SELECTION && phase != GamePhase.JOINING) {
            throw new IllegalStateException("cannot join in phase " + phase);
        }
        List<CoreEvent> events = new ArrayList<>();
        if (players.containsKey(playerId)) {
            throw new IllegalStateException("player already joined: " + playerId);
        }

        int seat = firstOpenSeat();
        if (seat < 1) {
            throw new IllegalStateException("table is full");
        }

        if (mode.isWagerMode() && !economy.charge(playerId, mode, wagerPerPlayer)) {
            throw new IllegalStateException("insufficient_balance");
        }

        PlayerState player = new PlayerState(playerId, seat, config.startingBullets());
        players.put(playerId, player);
        seats[seat] = playerId;
        joinedCount++;
        aliveCount++;

        events.add(CoreEvent.of(
                CoreEventType.PLAYER_JOINED,
                "player joined",
                Map.of("playerId", playerId, "seat", seat, "joinedCount", joinedCount)
        ));
        if (ownerId == null) {
            ownerId = playerId;
            events.add(hostAssignedEvent(playerId, null, "first_join"));
        }

        if (phase == GamePhase.JOINING && alivePlayersCount() >= config.maxPlayers()) {
            events.addAll(startInitialDeal("table_full"));
        }
        return Collections.unmodifiableList(events);
    }

    public List<CoreEvent> playerDisconnected(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (phase == GamePhase.FINISHED) {
            return List.of();
        }

        PlayerState state = players.get(playerId);
        if (state == null || !state.alive) {
            return List.of();
        }
        if (phase == GamePhase.MODE_SELECTION || phase == GamePhase.JOINING) {
            return removeBeforeGameStart(state);
        }

        List<CoreEvent> events = new ArrayList<>();
        players.remove(state.id);
        seats[state.seat] = null;
        aliveCount = Math.max(0, aliveCount - 1);

        shootCandidates.remove(playerId);
        preferredShooters.remove(playerId);

        if (Objects.equals(currentPlayerId, playerId)) {
            currentPlayerId = null;
        }
        if (Objects.equals(lastPlayerId, playerId)) {
            lastPlayerId = null;
        }
        if (Objects.equals(afterGunCandidateId, playerId)) {
            afterGunCandidateId = null;
        }
        if (Objects.equals(ownerId, playerId)) {
            reassignOwner(events, playerId, "host_disconnected");
        }

        events.add(CoreEvent.of(
                CoreEventType.PLAYER_FORFEITED,
                "player disconnected and forfeited",
                Map.of(
                        "playerId", playerId,
                        "seat", state.seat,
                        "phase", phase.name(),
                        "beforeStart", false,
                        "roundReset", true
                )
        ));

        if (alivePlayersCount() == 0) {
            cancelToIdle("disconnect:no_alive_players", events);
            return Collections.unmodifiableList(events);
        }

        events.addAll(startDealRound("disconnect_round_reset"));

        return Collections.unmodifiableList(events);
    }

    public List<CoreEvent> play(UUID playerId, List<Integer> oneBasedSlots) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(oneBasedSlots, "oneBasedSlots");
        if (phase != GamePhase.FIRST_TURN && phase != GamePhase.STANDARD_TURN) {
            throw new IllegalStateException("cannot play cards in phase " + phase);
        }
        if (!Objects.equals(currentPlayerId, playerId)) {
            throw new IllegalStateException("not current player");
        }
        if (forceChallenge) {
            throw new IllegalStateException("current player must challenge");
        }

        PlayerState player = requiredAlivePlayer(playerId);
        List<Integer> slots = normalizeSlots(oneBasedSlots, player.hand.size());
        if (slots.size() < config.minPlayCards() || slots.size() > config.maxPlayCards()) {
            throw new IllegalStateException("play card count must be in [" + config.minPlayCards() + ", " + config.maxPlayCards() + "]");
        }

        List<Card> selected = new ArrayList<>();
        for (int slot : slots) {
            selected.add(player.hand.get(slot - 1));
        }

        boolean hasDemon = selected.stream().anyMatch(Card::demon);
        if (hasDemon && selected.size() > 1) {
            throw new IllegalStateException("demon card can only be played as single card");
        }

        List<Integer> removeOrder = new ArrayList<>(slots);
        removeOrder.sort(Comparator.reverseOrder());
        for (int slot : removeOrder) {
            player.hand.remove(slot - 1);
        }

        centerCards.clear();
        centerCards.addAll(selected);
        lastPlayerId = playerId;

        List<CoreEvent> events = new ArrayList<>();
        events.add(CoreEvent.of(
                CoreEventType.CARDS_PLAYED,
                "cards played",
                Map.of(
                        "playerId", playerId,
                        "count", selected.size()
                )
        ));
        events.add(CoreEvent.of(
                CoreEventType.CARDS_PLAYED_DETAIL,
                "cards played detail",
                Map.of(
                        "playerId", playerId,
                        "count", selected.size(),
                        "ranks", selected.stream().map(c -> c.rank().name()).toList(),
                        "containsDemon", hasDemon
                )
        ));
        events.addAll(advanceAfterPlay(playerId));
        return Collections.unmodifiableList(events);
    }

    public List<CoreEvent> challenge(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        ensurePhase(GamePhase.STANDARD_TURN, "challenge");
        if (!Objects.equals(currentPlayerId, playerId)) {
            throw new IllegalStateException("not current player");
        }
        if (centerCards.isEmpty()) {
            throw new IllegalStateException("cannot challenge with empty center cards");
        }
        if (lastPlayerId == null) {
            throw new IllegalStateException("cannot challenge without last player");
        }

        List<CoreEvent> events = new ArrayList<>();
        shootCandidates.clear();

        boolean hasDemon = centerCards.stream().anyMatch(Card::demon);
        boolean hasNonMain = centerCards.stream().anyMatch(card -> !card.isMainLike(mainRank));
        String outcome;

        if (hasDemon) {
            outcome = "DEMON";
            for (PlayerState state : players.values()) {
                if (state.alive && !state.id.equals(lastPlayerId)) {
                    shootCandidates.add(state.id);
                }
            }
        } else if (hasNonMain) {
            outcome = "NOT_MAIN";
            if (isAlive(lastPlayerId)) {
                shootCandidates.add(lastPlayerId);
            }
            afterGunCandidateId = playerId;
        } else {
            outcome = "MAIN";
            if (isAlive(playerId)) {
                shootCandidates.add(playerId);
            }
            afterGunCandidateId = findNextPlayerWithCardsAfter(playerId);
        }

        currentPlayerId = null;
        forceChallenge = false;
        setPhase(GamePhase.RESOLVE_CHALLENGE, events, "challenge");
        events.add(CoreEvent.of(
                CoreEventType.CHALLENGE_RESOLVED,
                "challenge resolved",
                Map.of(
                        "challenger", playerId,
                        "lastPlayer", lastPlayerId,
                        "outcome", outcome,
                        "shooters", List.copyOf(shootCandidates)
                )
        ));
        return Collections.unmodifiableList(events);
    }

    public List<CoreEvent> tickSecond() {
        if (phase == GamePhase.FINISHED) {
            return List.of();
        }

        phaseSeconds++;
        List<CoreEvent> events = new ArrayList<>();
        switch (phase) {
            case MODE_SELECTION -> {
                if (phaseSeconds >= config.modeSelectionSeconds()) {
                    cancelToIdle("mode_selection_timeout", events);
                }
            }
            case JOINING -> {
                if (phaseSeconds >= config.joinSeconds()) {
                    events.addAll(startInitialDeal("join_timeout"));
                }
            }
            case DEALING -> {
                if (phaseSeconds >= config.dealingSeconds()) {
                    events.addAll(beginFirstTurn());
                }
            }
            case FIRST_TURN -> {
                if (phaseSeconds >= config.firstTurnSeconds()) {
                    events.addAll(autoPlayCurrent("first_turn_timeout"));
                }
            }
            case STANDARD_TURN -> {
                if (phaseSeconds >= config.standardTurnSeconds()) {
                    events.addAll(autoPlayCurrent("standard_turn_timeout"));
                }
            }
            case RESOLVE_CHALLENGE -> {
                if (phaseSeconds >= config.resolveChallengeSeconds()) {
                    events.addAll(resolveShotsAndContinue());
                }
            }
            case FINISHED -> {
            }
        }
        return Collections.unmodifiableList(events);
    }

    public List<CoreEvent> forceStop() {
        if (phase == GamePhase.FINISHED) {
            return List.of();
        }
        List<CoreEvent> events = new ArrayList<>();
        if (mode.isWagerMode() && alivePlayersCount() > 0) {
            UUID winner = pickRandom(alivePlayersInSeatOrder());
            finish(winner, "forced_stop_wager_mode", events);
        } else {
            finish(null, "forced_stop", events);
        }
        return Collections.unmodifiableList(events);
    }

    public GameSnapshot snapshot() {
        List<PlayerSnapshot> snapshots = new ArrayList<>(joinedCount);
        for (int seat = 1; seat <= config.maxPlayers(); seat++) {
            UUID playerId = seats[seat];
            if (playerId == null) {
                continue;
            }
            PlayerState state = players.get(playerId);
            if (state == null) {
                continue;
            }
            snapshots.add(new PlayerSnapshot(
                    state.id,
                    state.seat,
                    state.alive,
                    state.bullets,
                    state.hand.size()
            ));
        }

        return new GameSnapshot(
                tableId,
                phase,
                phaseSeconds,
                mode,
                joinedCount,
                round,
                Optional.ofNullable(mainRank),
                List.copyOf(centerCards),
                snapshots,
                Optional.ofNullable(ownerId),
                Optional.ofNullable(currentPlayerId),
                Optional.ofNullable(lastPlayerId),
                forceChallenge
        );
    }

    private List<CoreEvent> startInitialDeal(String reason) {
        List<CoreEvent> events = new ArrayList<>();
        if (alivePlayersCount() == 0) {
            cancelToIdle(reason + ":no_players", events);
            return events;
        }
        for (PlayerState state : players.values()) {
            if (state.alive) {
                state.bullets = config.startingBullets();
            }
        }
        events.addAll(startDealRound("initial:" + reason));
        return events;
    }

    private List<CoreEvent> startDealRound(String reason) {
        List<CoreEvent> events = new ArrayList<>();
        UUID winner = soleAlivePlayer();
        if (winner != null) {
            finish(winner, "winner_before_deal:" + reason, events);
            return events;
        }

        for (PlayerState state : players.values()) {
            if (state.alive) {
                state.hand.clear();
            }
        }

        round++;
        mainRank = MAIN_RANKS.get(random.nextIntInclusive(0, MAIN_RANKS.size() - 1));
        List<Card> deck = createRoundDeck(mainRank);
        random.shuffle(deck);

        centerCards.clear();
        currentPlayerId = null;
        forceChallenge = false;

        int cursor = 0;
        for (PlayerState state : alivePlayerStatesInSeatOrder()) {
            for (int i = 0; i < config.handSize(); i++) {
                if (cursor >= deck.size()) {
                    break;
                }
                state.hand.add(deck.get(cursor++));
            }
        }

        setPhase(GamePhase.DEALING, events, "deal_round:" + reason);
        events.add(CoreEvent.of(
                CoreEventType.DEAL_COMPLETED,
                "deal completed",
                Map.of(
                        "round", round,
                        "mainRank", mainRank.name(),
                        "alivePlayers", alivePlayersCount()
                )
        ));

        // Emit HAND_DEALT per player so the presentation layer can show cards
        for (PlayerState state : alivePlayerStatesInSeatOrder()) {
            List<Map<String, Object>> cardList = state.hand.stream()
                    .map(card -> Map.<String, Object>of(
                            "id", card.id(),
                            "rank", card.rank().name(),
                            "demon", card.demon()
                    ))
                    .toList();
            events.add(CoreEvent.of(
                    CoreEventType.HAND_DEALT,
                    "hand dealt",
                    Map.of(
                            "playerId", state.id,
                            "seat", state.seat,
                            "cards", cardList,
                            "mainRank", mainRank.name(),
                            "round", round
                    )
            ));
        }

        return events;
    }

    private List<CoreEvent> beginFirstTurn() {
        List<CoreEvent> events = new ArrayList<>();
        UUID winner = soleAlivePlayer();
        if (winner != null) {
            finish(winner, "winner_before_first_turn", events);
            return events;
        }

        UUID first = selectFirstPlayerAfterDeal();
        if (first == null) {
            finish(null, "no_player_for_first_turn", events);
            return events;
        }

        currentPlayerId = first;
        forceChallenge = false;
        setPhase(GamePhase.FIRST_TURN, events, "first_turn");
        events.add(CoreEvent.of(
                CoreEventType.TURN_CHANGED,
                "first turn selected",
                Map.of("playerId", first, "phase", GamePhase.FIRST_TURN.name())
        ));
        return events;
    }

    private List<CoreEvent> autoPlayCurrent(String reason) {
        UUID actor = currentPlayerId;
        if (actor == null || !isAlive(actor)) {
            actor = findAnyPlayerWithCards();
        }
        if (actor == null) {
            return startDealRound("auto_play_no_actor:" + reason);
        }

        if (phase == GamePhase.STANDARD_TURN && forceChallenge) {
            return challenge(actor);
        }

        PlayerState state = players.get(actor);
        if (state == null || state.hand.isEmpty()) {
            return startDealRound("auto_play_empty_hand:" + reason);
        }
        return play(actor, List.of(1));
    }

    private List<CoreEvent> advanceAfterPlay(UUID fromPlayer) {
        List<CoreEvent> events = new ArrayList<>();
        UUID next = findNextPlayerWithCardsAfter(fromPlayer);
        if (next == null) {
            events.addAll(startDealRound("no_next_player_after_play"));
            return events;
        }

        currentPlayerId = next;
        setPhase(GamePhase.STANDARD_TURN, events, "advance_after_play");
        forceChallenge = countPlayersWithCards() == 1;
        events.add(CoreEvent.of(
                CoreEventType.TURN_CHANGED,
                "turn moved",
                Map.of("playerId", next, "forceChallenge", forceChallenge)
        ));
        if (forceChallenge) {
            events.add(CoreEvent.of(
                    CoreEventType.FORCE_CHALLENGE,
                    "only one player has cards, challenge forced",
                    Map.of("playerId", next)
            ));
            events.addAll(challenge(next));
        }
        return events;
    }

    private List<CoreEvent> resolveShotsAndContinue() {
        List<CoreEvent> events = new ArrayList<>();
        List<UUID> shooters = shootCandidates.stream()
                .filter(this::isAlive)
                .sorted(Comparator.comparingInt(this::seatOf))
                .toList();

        preferredShooters.clear();
        for (UUID shooterId : shooters) {
            PlayerState shooter = players.get(shooterId);
            if (shooter == null || !shooter.alive) {
                continue;
            }

            int bulletsBefore = Math.max(1, shooter.bullets);
            int roll = bulletsBefore == 1 ? 1 : random.nextIntInclusive(1, bulletsBefore);
            shooter.bullets = Math.max(0, shooter.bullets - 1);
            boolean lethal = roll == 1;

            events.add(CoreEvent.of(
                    CoreEventType.SHOT_RESOLVED,
                    "shot resolved",
                    Map.of(
                            "playerId", shooterId,
                            "roll", roll,
                            "bulletsBefore", bulletsBefore,
                            "bulletsAfter", shooter.bullets,
                            "lethal", lethal
                    )
            ));

            if (lethal) {
                eliminate(shooter, events);
            } else {
                preferredShooters.add(shooterId);
            }

            UUID winner = soleAlivePlayer();
            if (winner != null) {
                shootCandidates.clear();
                finish(winner, "winner_after_shoot", events);
                return events;
            }
        }
        shootCandidates.clear();

        UUID winner = soleAlivePlayer();
        if (winner != null) {
            finish(winner, "winner_after_shoot", events);
            return events;
        }

        events.addAll(startDealRound("after_shoot"));
        return events;
    }

    private void eliminate(PlayerState player, List<CoreEvent> events) {
        player.alive = false;
        aliveCount--;
        player.hand.clear();
        player.bullets = 0;

        if (Objects.equals(currentPlayerId, player.id)) {
            currentPlayerId = null;
        }
        if (Objects.equals(afterGunCandidateId, player.id)) {
            afterGunCandidateId = null;
        }

        events.add(CoreEvent.of(
                CoreEventType.PLAYER_ELIMINATED,
                "player eliminated",
                Map.of("playerId", player.id, "seat", player.seat)
        ));
    }

    private void finish(UUID winner, String reason, List<CoreEvent> events) {
        if (phase == GamePhase.FINISHED) {
            return;
        }
        if (winner != null) {
            economy.reward(winner, mode, joinedCount * wagerPerPlayer);
        }
        currentPlayerId = null;
        forceChallenge = false;
        shootCandidates.clear();
        preferredShooters.clear();
        setPhase(GamePhase.FINISHED, events, reason);
        Map<String, Object> payload = new HashMap<>();
        if (winner != null) {
            payload.put("winner", winner);
        }
        payload.put("mode", mode.name());
        payload.put("joinedCount", joinedCount);
        payload.put("reason", reason);
        events.add(CoreEvent.of(
                CoreEventType.GAME_FINISHED,
                "game finished",
                payload
        ));
        resetForIdle();
    }

    private void cancelToIdle(String reason, List<CoreEvent> events) {
        if (phase != GamePhase.MODE_SELECTION) {
            setPhase(GamePhase.MODE_SELECTION, events, reason);
        } else {
            phaseSeconds = 0;
        }
        resetForIdle();
    }

    private List<CoreEvent> removeBeforeGameStart(PlayerState state) {
        List<CoreEvent> events = new ArrayList<>();
        players.remove(state.id);
        seats[state.seat] = null;
        joinedCount = Math.max(0, joinedCount - 1);
        aliveCount = Math.max(0, aliveCount - 1);

        shootCandidates.remove(state.id);
        preferredShooters.remove(state.id);
        if (Objects.equals(currentPlayerId, state.id)) {
            currentPlayerId = null;
        }
        if (Objects.equals(lastPlayerId, state.id)) {
            lastPlayerId = null;
        }
        if (Objects.equals(afterGunCandidateId, state.id)) {
            afterGunCandidateId = null;
        }
        if (Objects.equals(ownerId, state.id)) {
            reassignOwner(events, state.id, "host_left_before_start");
        }

        events.add(CoreEvent.of(
                CoreEventType.PLAYER_FORFEITED,
                "player left before game start",
                Map.of(
                        "playerId", state.id,
                        "seat", state.seat,
                        "phase", phase.name(),
                        "beforeStart", true
                )
        ));

        return Collections.unmodifiableList(events);
    }

    private void resetForIdle() {
        players.clear();
        Arrays.fill(seats, null);
        centerCards.clear();
        shootCandidates.clear();
        preferredShooters.clear();

        mode = TableMode.LIFE_ONLY;
        phase = GamePhase.MODE_SELECTION;
        phaseSeconds = 0;
        joinedCount = 0;
        aliveCount = 0;
        round = 0;
        nextCardId = 1;
        forceChallenge = false;
        wagerPerPlayer = 1;

        mainRank = null;
        ownerId = null;
        currentPlayerId = null;
        lastPlayerId = null;
        afterGunCandidateId = null;
    }

    private void reassignOwner(List<CoreEvent> events, UUID previousOwner, String reason) {
        List<UUID> candidates = alivePlayersInSeatOrder();
        if (candidates.isEmpty()) {
            ownerId = null;
            return;
        }
        UUID nextOwner = pickRandom(candidates);
        if (nextOwner == null) {
            ownerId = null;
            return;
        }
        ownerId = nextOwner;
        events.add(hostAssignedEvent(nextOwner, previousOwner, reason));
    }

    private CoreEvent hostAssignedEvent(UUID newOwner, UUID previousOwner, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", newOwner);
        payload.put("reason", reason);
        if (previousOwner != null) {
            payload.put("previousOwner", previousOwner);
        }
        return CoreEvent.of(
                CoreEventType.HOST_ASSIGNED,
                "host assigned",
                payload
        );
    }

    private List<Card> createRoundDeck(CardRank selectedMain) {
        List<Card> cards = new ArrayList<>(20);
        addCards(cards, CardRank.A, 7);
        addCards(cards, CardRank.Q, 6);
        addCards(cards, CardRank.K, 5);
        addCards(cards, CardRank.J, 2);

        // Collect main card indexes without allocating a separate list
        int mainCount = 0;
        int firstMainIndex = -1;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).rank() == selectedMain) {
                if (firstMainIndex == -1) firstMainIndex = i;
                mainCount++;
            }
        }
        int pick = random.nextIntInclusive(0, mainCount - 1);
        int selectedIndex = firstMainIndex;
        for (int i = firstMainIndex, seen = 0; i < cards.size(); i++) {
            if (cards.get(i).rank() == selectedMain) {
                if (seen == pick) { selectedIndex = i; break; }
                seen++;
            }
        }
        cards.set(selectedIndex, cards.get(selectedIndex).asDemon());
        return cards;
    }

    private void addCards(List<Card> cards, CardRank rank, int count) {
        for (int i = 0; i < count; i++) {
            cards.add(new Card(nextCardId++, rank, false));
        }
    }

    private UUID selectFirstPlayerAfterDeal() {
        if (lastPlayerId != null && preferredShooters.contains(lastPlayerId) && isAlive(lastPlayerId)) {
            preferredShooters.clear();
            afterGunCandidateId = null;
            return lastPlayerId;
        }

        List<UUID> alivePreferredShooters = preferredShooters.stream().filter(this::isAlive).toList();
        if (!alivePreferredShooters.isEmpty()) {
            UUID selected = pickRandom(alivePreferredShooters);
            preferredShooters.clear();
            afterGunCandidateId = null;
            return selected;
        }

        if (afterGunCandidateId != null && isAlive(afterGunCandidateId)) {
            UUID selected = afterGunCandidateId;
            preferredShooters.clear();
            afterGunCandidateId = null;
            return selected;
        }

        preferredShooters.clear();
        afterGunCandidateId = null;
        List<UUID> alive = alivePlayersInSeatOrder();
        if (alive.isEmpty()) {
            return null;
        }
        return pickRandom(alive);
    }

    private UUID findNextPlayerWithCardsAfter(UUID playerId) {
        if (playerId == null) {
            return findAnyPlayerWithCards();
        }

        PlayerState base = players.get(playerId);
        if (base == null) {
            return findAnyPlayerWithCards();
        }

        int max = config.maxPlayers();
        for (int offset = 1; offset <= max; offset++) {
            int seat = ((base.seat - 1 + offset) % max) + 1;
            UUID candidateId = seats[seat];
            if (candidateId == null) {
                continue;
            }
            PlayerState candidate = players.get(candidateId);
            if (candidate != null && candidate.alive && !candidate.hand.isEmpty()) {
                return candidateId;
            }
        }
        return null;
    }

    private UUID findAnyPlayerWithCards() {
        for (PlayerState state : alivePlayerStatesInSeatOrder()) {
            if (!state.hand.isEmpty()) {
                return state.id;
            }
        }
        return null;
    }

    private List<PlayerState> alivePlayerStatesInSeatOrder() {
        List<PlayerState> states = new ArrayList<>(aliveCount);
        for (int seat = 1; seat <= config.maxPlayers(); seat++) {
            UUID playerId = seats[seat];
            if (playerId == null) {
                continue;
            }
            PlayerState state = players.get(playerId);
            if (state != null && state.alive) {
                states.add(state);
            }
        }
        return states;
    }

    private List<UUID> alivePlayersInSeatOrder() {
        List<UUID> ids = new ArrayList<>(aliveCount);
        for (int seat = 1; seat <= config.maxPlayers(); seat++) {
            UUID playerId = seats[seat];
            if (playerId == null) {
                continue;
            }
            PlayerState state = players.get(playerId);
            if (state != null && state.alive) {
                ids.add(playerId);
            }
        }
        return ids;
    }

    private UUID soleAlivePlayer() {
        if (aliveCount != 1) {
            return null;
        }
        for (int seat = 1; seat <= config.maxPlayers(); seat++) {
            UUID playerId = seats[seat];
            if (playerId == null) {
                continue;
            }
            PlayerState state = players.get(playerId);
            if (state != null && state.alive) {
                return playerId;
            }
        }
        return null;
    }

    private int alivePlayersCount() {
        return aliveCount;
    }

    private int countPlayersWithCards() {
        int count = 0;
        for (PlayerState state : players.values()) {
            if (state.alive && !state.hand.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int firstOpenSeat() {
        for (int i = 1; i <= config.maxPlayers(); i++) {
            if (seats[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private PlayerState requiredAlivePlayer(UUID playerId) {
        PlayerState state = players.get(playerId);
        if (state == null || !state.alive) {
            throw new IllegalStateException("player is not alive in this table: " + playerId);
        }
        return state;
    }

    private int seatOf(UUID playerId) {
        PlayerState state = players.get(playerId);
        if (state == null) {
            return Integer.MAX_VALUE;
        }
        return state.seat;
    }

    private boolean isAlive(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state != null && state.alive;
    }

    private void ensurePhase(GamePhase expected, String action) {
        if (phase != expected) {
            throw new IllegalStateException("cannot " + action + " in phase " + phase);
        }
    }

    private void setPhase(GamePhase next, List<CoreEvent> events, String reason) {
        phase = next;
        phaseSeconds = 0;
        events.add(CoreEvent.of(
                CoreEventType.PHASE_CHANGED,
                "phase changed",
                Map.of("phase", next.name(), "reason", reason)
        ));
    }

    private UUID pickRandom(List<UUID> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        int idx = random.nextIntInclusive(0, candidates.size() - 1);
        return candidates.get(idx);
    }

    private int resolveWagerAmount(TableMode selectedMode, int wager) {
        if (selectedMode != TableMode.KUNKUN_COIN) {
            return 1;
        }
        if (wager < MIN_WAGER || wager > MAX_WAGER) {
            throw new IllegalStateException("invalid_wager_amount");
        }
        return wager;
    }

    private List<Integer> normalizeSlots(List<Integer> slots, int handSize) {
        if (slots.isEmpty()) {
            throw new IllegalStateException("must select at least one card");
        }
        Set<Integer> unique = new LinkedHashSet<>(slots);
        List<Integer> normalized = new ArrayList<>(unique);
        normalized.sort(Integer::compareTo);
        for (int slot : normalized) {
            if (slot < 1 || slot > handSize) {
                throw new IllegalStateException("invalid card slot: " + slot);
            }
        }
        return normalized;
    }
}
