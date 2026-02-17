package cn.pianzi.liarbar.paper.presentation;

import cn.pianzi.liarbar.core.event.CoreEvent;
import cn.pianzi.liarbar.core.event.CoreEventType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoreEventTranslator {
    public List<UserFacingEvent> translate(List<CoreEvent> events) {
        return events.stream()
                .map(this::translateOne)
                .toList();
    }

    private UserFacingEvent translateOne(CoreEvent event) {
        CoreEventType type = event.type();
        Map<String, Object> data = withType(event);
        return switch (type) {
            case MODE_SELECTED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.mode_selected",
                    withEntries(data, Map.of("mode", modeName(event.data().get("mode"))))
            );
            case PLAYER_JOINED -> UserFacingEvent.broadcast(
                    EventSeverity.SUCCESS,
                    "event.player_joined",
                    withEntries(data, Map.of(
                            "player", shortPlayer(event.data().get("playerId")),
                            "seat", event.data().getOrDefault("seat", "?")
                    ))
            );
            case PLAYER_FORFEITED -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    "event.player_forfeited",
                    withEntries(data, Map.of("player", shortPlayer(event.data().get("playerId"))))
            );
            case PHASE_CHANGED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.phase_changed",
                    withEntries(data, Map.of("phase", event.data().getOrDefault("phase", "?")))
            );
            case DEAL_COMPLETED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.deal_completed",
                    withEntries(data, Map.of(
                            "mainRank", event.data().getOrDefault("mainRank", "?"),
                            "round", event.data().getOrDefault("round", "?")
                    ))
            );
            case TURN_CHANGED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.turn_changed",
                    withEntries(data, Map.of("player", shortPlayer(event.data().get("playerId"))))
            );
            case FORCE_CHALLENGE -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    "event.force_challenge",
                    data
            );
            case CARDS_PLAYED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.cards_played",
                    withEntries(data, Map.of(
                            "player", shortPlayer(event.data().get("playerId")),
                            "count", event.data().getOrDefault("count", "?")
                    ))
            );
            case CARDS_PLAYED_DETAIL -> {
                UUID cardOwner = asUuid(event.data().get("playerId"));
                yield UserFacingEvent.personal(
                        EventSeverity.INFO,
                        "event.cards_played_detail",
                        cardOwner,
                        withEntries(data, Map.of("count", event.data().getOrDefault("count", "?")))
                );
            }
            case CHALLENGE_RESOLVED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.challenge_resolved",
                    withEntries(data, Map.of("outcome", event.data().getOrDefault("outcome", "?")))
            );
            case SHOT_RESOLVED -> {
                boolean lethal = Boolean.TRUE.equals(event.data().get("lethal"));
                yield UserFacingEvent.broadcast(
                        EventSeverity.WARNING,
                        lethal ? "event.shot_resolved_eliminated" : "event.shot_resolved_survived",
                        withEntries(data, Map.of("player", shortPlayer(event.data().get("playerId"))))
                );
            }
            case PLAYER_ELIMINATED -> UserFacingEvent.broadcast(
                    EventSeverity.ERROR,
                    "event.player_eliminated",
                    withEntries(data, Map.of("player", shortPlayer(event.data().get("playerId"))))
            );
            case GAME_FINISHED -> UserFacingEvent.broadcast(
                    EventSeverity.SUCCESS,
                    "event.game_finished",
                    withEntries(data, Map.of("winner", shortPlayer(event.data().get("winner"))))
            );
            case HAND_DEALT -> {
                UUID target = asUuid(event.data().get("playerId"));
                yield UserFacingEvent.personal(
                        EventSeverity.INFO,
                        "event.hand_dealt",
                        target,
                        withEntries(data, Map.of("round", event.data().getOrDefault("round", "?")))
                );
            }
        };
    }

    private Map<String, Object> withType(CoreEvent event) {
        Map<String, Object> data = new HashMap<>(event.data());
        data.put("_eventType", event.type().name());
        return data;
    }

    private Map<String, Object> withEntries(Map<String, Object> data, Map<String, Object> extra) {
        HashMap<String, Object> merged = new HashMap<>(data);
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private String shortPlayer(Object value) {
        if (value == null) {
            return "?";
        }
        if (value instanceof UUID uuid) {
            String text = uuid.toString();
            return text.substring(0, 8);
        }
        String text = String.valueOf(value);
        if (text.length() > 8) {
            return text.substring(0, 8);
        }
        return text;
    }

    private UUID asUuid(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private String modeName(Object value) {
        if (value == null) {
            return "?";
        }
        String mode = String.valueOf(value);
        return switch (mode) {
            case "LIFE_ONLY" -> "life";
            case "FANTUAN_COIN" -> "fantuan";
            case "KUNKUN_COIN" -> "kunkun";
            default -> mode;
        };
    }
}
