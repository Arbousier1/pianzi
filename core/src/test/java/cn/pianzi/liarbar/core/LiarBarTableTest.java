package cn.pianzi.liarbar.core;

import cn.pianzi.liarbar.core.config.TableConfig;
import cn.pianzi.liarbar.core.domain.GamePhase;
import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.event.CoreEvent;
import cn.pianzi.liarbar.core.event.CoreEventType;
import cn.pianzi.liarbar.core.port.EconomyPort;
import cn.pianzi.liarbar.core.port.RandomSource;
import cn.pianzi.liarbar.core.runtime.LiarBarTable;
import cn.pianzi.liarbar.core.snapshot.GameSnapshot;
import cn.pianzi.liarbar.core.snapshot.PlayerSnapshot;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiarBarTableTest {
    @Test
    void shouldRejectJoinBeforeModeSelection() {
        LiarBarTable table = new LiarBarTable(
                "auto",
                testConfig(),
                EconomyPort.noop(),
                new SeededRandomSource(11L)
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> table.join(UUID.randomUUID()));
        assertEquals("cannot join in phase MODE_SELECTION", ex.getMessage());
    }

    @Test
    void shouldDealCardsAfterJoinTimeout() {
        LiarBarTable table = new LiarBarTable(
                "a",
                testConfig(),
                EconomyPort.noop(),
                new SeededRandomSource(1L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());

        table.tickSecond();
        GameSnapshot snapshot = table.snapshot();

        assertEquals(GamePhase.DEALING, snapshot.phase());
        assertEquals(1, snapshot.round());
        assertTrue(snapshot.mainRank().isPresent());
        long aliveWithFiveCards = snapshot.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> player.handSize() == 5)
                .count();
        assertEquals(2, aliveWithFiveCards);
    }

    @Test
    void shouldReturnToIdleWithoutGameFinishedWhenJoinTimeoutHasNoPlayers() {
        LiarBarTable table = new LiarBarTable(
                "join_timeout_empty",
                testConfig(),
                EconomyPort.noop(),
                new SeededRandomSource(21L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);

        List<CoreEvent> events = table.tickSecond();

        assertEquals(GamePhase.MODE_SELECTION, table.snapshot().phase());
        assertEquals(0, table.snapshot().joinedCount());
        assertTrue(!containsEvent(events, CoreEventType.GAME_FINISHED));
    }

    @Test
    void shouldTimeoutModeSelectionWithoutGameFinished() {
        TableConfig config = new TableConfig(
                1,
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
        LiarBarTable table = new LiarBarTable(
                "mode_timeout",
                config,
                EconomyPort.noop(),
                new SeededRandomSource(22L)
        );

        List<CoreEvent> events = table.tickSecond();

        assertEquals(GamePhase.MODE_SELECTION, table.snapshot().phase());
        assertEquals(0, table.snapshot().joinedCount());
        assertTrue(!containsEvent(events, CoreEventType.GAME_FINISHED));
    }

    @Test
    void shouldAdvanceToStandardTurnAfterPlay() {
        LiarBarTable table = new LiarBarTable(
                "b",
                testConfig(),
                EconomyPort.noop(),
                new SeededRandomSource(2L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.tickSecond(); // JOINING -> DEALING
        table.tickSecond(); // DEALING -> FIRST_TURN

        UUID first = table.snapshot().currentPlayer().orElseThrow();
        table.play(first, List.of(1));
        GameSnapshot snapshot = table.snapshot();

        assertEquals(GamePhase.STANDARD_TURN, snapshot.phase());
        assertEquals(first, snapshot.lastPlayer().orElseThrow());
        assertEquals(1, snapshot.centerCards().size());
        assertTrue(snapshot.currentPlayer().isPresent());
    }

    @Test
    void shouldResolveShotAfterChallenge() {
        LiarBarTable table = new LiarBarTable(
                "c",
                testConfig(),
                EconomyPort.noop(),
                new SeededRandomSource(3L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        table.join(p1);
        table.join(p2);
        table.tickSecond();
        table.tickSecond();

        UUID first = table.snapshot().currentPlayer().orElseThrow();
        table.play(first, List.of(1));
        UUID challenger = table.snapshot().currentPlayer().orElseThrow();

        Map<UUID, Integer> bulletsBefore = toBulletMap(table.snapshot());
        table.challenge(challenger);
        assertEquals(GamePhase.RESOLVE_CHALLENGE, table.snapshot().phase());

        table.tickSecond();
        GameSnapshot after = table.snapshot();
        assertTrue(after.phase() == GamePhase.DEALING || after.phase() == GamePhase.FINISHED);

        Map<UUID, Integer> bulletsAfter = toBulletMap(after);
        boolean bulletChanged = bulletsBefore.entrySet().stream()
                .anyMatch(entry -> !entry.getValue().equals(bulletsAfter.get(entry.getKey())));
        boolean someoneDead = after.players().stream().anyMatch(player -> !player.alive());
        assertTrue(bulletChanged || someoneDead);
    }

    @Test
    void shouldRewardWinnerOnForcedStopInWagerMode() {
        MemoryEconomy economy = new MemoryEconomy();
        LiarBarTable table = new LiarBarTable(
                "d",
                testConfig(),
                economy,
                new SeededRandomSource(4L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.KUNKUN_COIN);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        table.join(p1);
        table.join(p2);

        List<CoreEvent> events = table.forceStop();
        assertNotNull(events);
        assertEquals(1, economy.rewardCalls);
        assertEquals(2, economy.lastRewardAmount);
        assertEquals(TableMode.KUNKUN_COIN, economy.lastRewardMode);
    }

    @Test
    void shouldRewardWinnerInLifeModeWhenOpponentDisconnects() {
        MemoryEconomy economy = new MemoryEconomy();
        LiarBarTable table = new LiarBarTable(
                "life_reward",
                testConfig(),
                economy,
                new SeededRandomSource(12L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        table.join(p1);
        table.join(p2);
        table.tickSecond(); // JOINING -> DEALING
        table.tickSecond(); // DEALING -> FIRST_TURN

        table.playerDisconnected(p2);

        assertEquals(GamePhase.MODE_SELECTION, table.snapshot().phase());
        assertEquals(1, economy.rewardCalls);
        assertEquals(2, economy.lastRewardAmount);
        assertEquals(TableMode.LIFE_ONLY, economy.lastRewardMode);
    }

    @Test
    void shouldForfeitDisconnectedCurrentPlayerAndRedeal() {
        LiarBarTable table = new LiarBarTable(
                "disconnect",
                testConfig(),
                EconomyPort.noop(),
                new SeededRandomSource(13L)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.tickSecond(); // JOINING -> DEALING
        table.tickSecond(); // DEALING -> FIRST_TURN

        UUID current = table.snapshot().currentPlayer().orElseThrow();
        List<CoreEvent> events = table.playerDisconnected(current);

        assertTrue(containsEvent(events, CoreEventType.PLAYER_FORFEITED));
        assertTrue(!containsEvent(events, CoreEventType.PLAYER_ELIMINATED));
        assertEquals(GamePhase.DEALING, table.snapshot().phase());
        assertEquals(2, table.snapshot().players().size());
        assertTrue(table.snapshot().players().stream().noneMatch(p -> p.playerId().equals(current)));
    }

    @Test
    void shouldRedealWhenResolveChallengeShooterDisconnects() {
        TableConfig config = new TableConfig(
                99,
                1,
                1,
                30,
                30,
                1,
                4,
                1,
                1,
                1,
                6
        );
        LiarBarTable table = new LiarBarTable(
                "disconnect_shooter",
                config,
                EconomyPort.noop(),
                new SequenceRandomSource(1, 0, 0, 1, 0)
        );
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.tickSecond(); // JOINING -> DEALING
        table.tickSecond(); // DEALING -> FIRST_TURN

        UUID first = table.snapshot().currentPlayer().orElseThrow();
        table.play(first, List.of(1));
        UUID challenger = table.snapshot().currentPlayer().orElseThrow();
        List<CoreEvent> challengeEvents = table.challenge(challenger);
        CoreEvent resolved = eventOf(challengeEvents, CoreEventType.CHALLENGE_RESOLVED);
        @SuppressWarnings("unchecked")
        List<UUID> shooters = (List<UUID>) resolved.data().get("shooters");
        assertEquals(1, shooters.size());

        UUID shooter = shooters.getFirst();
        List<CoreEvent> disconnectEvents = table.playerDisconnected(shooter);

        assertTrue(containsEvent(disconnectEvents, CoreEventType.PLAYER_FORFEITED));
        assertTrue(!containsEvent(disconnectEvents, CoreEventType.GAME_FINISHED));
        assertEquals(GamePhase.DEALING, table.snapshot().phase());
    }

    @Test
    void shouldForceChallengeImmediatelyWhenOnlyOnePlayerHasCards() {
        TableConfig config = new TableConfig(
                99,
                1,
                1,
                30,
                30,
                1,
                4,
                1,
                1,
                1,
                6
        );
        LiarBarTable table = new LiarBarTable(
                "force",
                config,
                EconomyPort.noop(),
                new SequenceRandomSource(0, 0, 0)
        );

        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.tickSecond();
        table.tickSecond();

        UUID first = table.snapshot().currentPlayer().orElseThrow();
        List<CoreEvent> events = table.play(first, List.of(1));

        assertEquals(GamePhase.RESOLVE_CHALLENGE, table.snapshot().phase());
        assertTrue(containsEvent(events, CoreEventType.FORCE_CHALLENGE));
        assertTrue(containsEvent(events, CoreEventType.CHALLENGE_RESOLVED));
    }

    @Test
    void shouldMatchDatapackChallengeOutcomeDemon() {
        LiarBarTable table = tableForChallengeOutcome(new SequenceRandomSource(0, 0, 0));

        OutcomeResult result = playAndChallenge(table);
        assertEquals("DEMON", result.outcome());
        assertEquals(List.of(result.challenger()), result.shooters());
    }

    @Test
    void shouldMatchDatapackChallengeOutcomeNotMain() {
        LiarBarTable table = tableForChallengeOutcome(new SequenceRandomSource(1, 0, 0));

        OutcomeResult result = playAndChallenge(table);
        assertEquals("NOT_MAIN", result.outcome());
        assertEquals(List.of(result.lastPlayer()), result.shooters());
    }

    @Test
    void shouldMatchDatapackChallengeOutcomeMain() {
        LiarBarTable table = tableForChallengeOutcome(new SequenceRandomSource(0, 1, 0));

        OutcomeResult result = playAndChallenge(table);
        assertEquals("MAIN", result.outcome());
        assertEquals(List.of(result.challenger()), result.shooters());
    }

    private static TableConfig testConfig() {
        return new TableConfig(
                99,
                1,
                1,
                30,
                30,
                1,
                4,
                5,
                1,
                3,
                6
        );
    }

    private static Map<UUID, Integer> toBulletMap(GameSnapshot snapshot) {
        Map<UUID, Integer> map = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            map.put(player.playerId(), player.bullets());
        }
        return map;
    }

    private static LiarBarTable tableForChallengeOutcome(RandomSource randomSource) {
        TableConfig config = new TableConfig(
                99,
                1,
                1,
                30,
                30,
                1,
                4,
                2,
                1,
                1,
                6
        );
        LiarBarTable table = new LiarBarTable("outcome", config, EconomyPort.noop(), randomSource);
        table.selectMode(UUID.randomUUID(), TableMode.LIFE_ONLY);
        table.join(UUID.randomUUID());
        table.join(UUID.randomUUID());
        table.tickSecond();
        table.tickSecond();
        return table;
    }

    private static OutcomeResult playAndChallenge(LiarBarTable table) {
        UUID first = table.snapshot().currentPlayer().orElseThrow();
        table.play(first, List.of(1));

        GameSnapshot afterPlay = table.snapshot();
        assertEquals(GamePhase.STANDARD_TURN, afterPlay.phase());
        UUID challenger = afterPlay.currentPlayer().orElseThrow();
        UUID lastPlayer = afterPlay.lastPlayer().orElseThrow();

        List<CoreEvent> events = table.challenge(challenger);
        CoreEvent resolved = eventOf(events, CoreEventType.CHALLENGE_RESOLVED);
        String outcome = String.valueOf(resolved.data().get("outcome"));
        @SuppressWarnings("unchecked")
        List<UUID> shooters = (List<UUID>) resolved.data().get("shooters");
        return new OutcomeResult(outcome, challenger, lastPlayer, List.copyOf(shooters));
    }

    private static CoreEvent eventOf(List<CoreEvent> events, CoreEventType type) {
        for (CoreEvent event : events) {
            if (event.type() == type) {
                return event;
            }
        }
        throw new NoSuchElementException("missing event: " + type);
    }

    private static boolean containsEvent(List<CoreEvent> events, CoreEventType type) {
        for (CoreEvent event : events) {
            if (event.type() == type) {
                return true;
            }
        }
        return false;
    }

    private static final class SeededRandomSource implements RandomSource {
        private final Random random;

        private SeededRandomSource(long seed) {
            this.random = new Random(seed);
        }

        @Override
        public int nextIntInclusive(int minInclusive, int maxInclusive) {
            return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
        }

        @Override
        public <T> void shuffle(List<T> list) {
            Collections.shuffle(list, random);
        }
    }

    private static final class SequenceRandomSource implements RandomSource {
        private final int[] values;
        private int cursor;

        private SequenceRandomSource(int... values) {
            this.values = values;
        }

        @Override
        public int nextIntInclusive(int minInclusive, int maxInclusive) {
            if (cursor >= values.length) {
                throw new IllegalStateException("random sequence exhausted");
            }
            int next = values[cursor++];
            if (next < minInclusive || next > maxInclusive) {
                throw new IllegalStateException(
                        "random value out of range: " + next + " not in [" + minInclusive + ", " + maxInclusive + "]"
                );
            }
            return next;
        }

        @Override
        public <T> void shuffle(List<T> list) {
            // Keep deterministic card order for datapack parity tests.
        }
    }

    private record OutcomeResult(String outcome, UUID challenger, UUID lastPlayer, List<UUID> shooters) {
    }

    private static final class MemoryEconomy implements EconomyPort {
        private int rewardCalls;
        private int lastRewardAmount;
        private TableMode lastRewardMode;

        @Override
        public boolean charge(UUID playerId, TableMode mode, int amount) {
            return true;
        }

        @Override
        public void reward(UUID playerId, TableMode mode, int amount) {
            rewardCalls++;
            lastRewardAmount = amount;
            lastRewardMode = mode;
        }
    }
}
