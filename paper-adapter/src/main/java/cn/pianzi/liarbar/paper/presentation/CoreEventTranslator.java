package cn.pianzi.liarbar.paper.presentation;

import cn.pianzi.liarbar.core.event.CoreEvent;
import cn.pianzi.liarbar.core.event.CoreEventType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoreEventTranslator {
    public List<UserFacingEvent> translate(List<CoreEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<UserFacingEvent> result = new ArrayList<>(events.size());
        for (CoreEvent event : events) {
            result.add(translateOne(event));
        }
        return result;
    }

    private UserFacingEvent translateOne(CoreEvent event) {
        CoreEventType type = event.type();
        String typeName = type.name();
        Map<String, Object> data = event.data();
        return switch (type) {
            case HOST_ASSIGNED -> {
                UUID host = asUuid(data.get("playerId"));
                yield UserFacingEvent.personal(
                        EventSeverity.INFO,
                        "event.host_assigned",
                        host,
                        typeName,
                        withEntries(data, Map.of(
                                "player", shortPlayer(data.get("playerId"))
                        ))
                );
            }
            case MODE_SELECTED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.mode_selected",
                    typeName,
                    withEntries(data, Map.of("mode", modeName(data.get("mode"))))
            );
            case PLAYER_JOINED -> UserFacingEvent.broadcast(
                    EventSeverity.SUCCESS,
                    "event.player_joined",
                    typeName,
                    withEntries(data, Map.of(
                            "player", shortPlayer(data.get("playerId")),
                            "seat", data.getOrDefault("seat", "?")
                    ))
            );
            case PLAYER_FORFEITED -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    forfeitMessageKey(event),
                    typeName,
                    withEntries(data, Map.of("player", shortPlayer(data.get("playerId"))))
            );
            case PHASE_CHANGED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.phase_changed",
                    typeName,
                    withEntries(data, Map.of("phase", data.getOrDefault("phase", "?")))
            );
            case DEAL_COMPLETED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.deal_completed",
                    typeName,
                    withEntries(data, Map.of(
                            "mainRank", data.getOrDefault("mainRank", "?"),
                            "round", data.getOrDefault("round", "?")
                    ))
            );
            case TURN_CHANGED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.turn_changed",
                    typeName,
                    withEntries(data, Map.of("player", shortPlayer(data.get("playerId"))))
            );
            case FORCE_CHALLENGE -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    "event.force_challenge",
                    typeName,
                    data
            );
            case CARDS_PLAYED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.cards_played",
                    typeName,
                    withEntries(data, Map.of(
                            "player", shortPlayer(data.get("playerId")),
                            "count", data.getOrDefault("count", "?")
                    ))
            );
            case CARDS_PLAYED_DETAIL -> {
                UUID cardOwner = asUuid(data.get("playerId"));
                yield UserFacingEvent.personal(
                        EventSeverity.INFO,
                        "event.cards_played_detail",
                        cardOwner,
                        typeName,
                        withEntries(data, Map.of("count", data.getOrDefault("count", "?")))
                );
            }
            case CHALLENGE_RESOLVED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "event.challenge_resolved",
                    typeName,
                    withEntries(data, Map.of("outcome", data.getOrDefault("outcome", "?")))
            );
            case SHOT_RESOLVED -> {
                boolean lethal = Boolean.TRUE.equals(data.get("lethal"));
                yield UserFacingEvent.broadcast(
                        EventSeverity.WARNING,
                        lethal ? "event.shot_resolved_eliminated" : "event.shot_resolved_survived",
                        typeName,
                        withEntries(data, Map.of("player", shortPlayer(data.get("playerId"))))
                );
            }
            case PLAYER_ELIMINATED -> UserFacingEvent.broadcast(
                    EventSeverity.ERROR,
                    "event.player_eliminated",
                    typeName,
                    withEntries(data, Map.of("player", shortPlayer(data.get("playerId"))))
            );
            case GAME_FINISHED -> UserFacingEvent.broadcast(
                    EventSeverity.SUCCESS,
                    "event.game_finished",
                    typeName,
                    withEntries(data, Map.of("winner", shortPlayer(data.get("winner"))))
            );
            case HAND_DEALT -> {
                UUID target = asUuid(data.get("playerId"));
                yield UserFacingEvent.personal(
                        EventSeverity.INFO,
                        "event.hand_dealt",
                        target,
                        typeName,
                        withEntries(data, Map.of("round", data.getOrDefault("round", "?")))
                );
            }
        };
    }

    private Map<String, Object> withEntries(Map<String, Object> base, Map<String, Object> extra) {
        // Create a fresh mutable map merging base + extra
        HashMap<String, Object> merged = HashMap.newHashMap(base.size() + extra.size());
        merged.putAll(base);
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

    private String forfeitMessageKey(CoreEvent event) {
        Object beforeStart = event.data().get("beforeStart");
        if (Boolean.TRUE.equals(beforeStart)) {
            return "event.player_left_before_start";
        }
        return "event.player_disconnected_round_reset";
    }
}
