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
                    "Mode selected: " + modeName(event.data().get("mode")),
                    data
            );
            case PLAYER_JOINED -> UserFacingEvent.broadcast(
                    EventSeverity.SUCCESS,
                    "Player " + shortPlayer(event.data().get("playerId"))
                            + " joined (seat " + event.data().getOrDefault("seat", "?") + ")",
                    data
            );
            case PLAYER_FORFEITED -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    "Player " + shortPlayer(event.data().get("playerId")) + " disconnected and forfeited",
                    data
            );
            case PHASE_CHANGED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "Phase changed to " + event.data().getOrDefault("phase", "UNKNOWN"),
                    data
            );
            case DEAL_COMPLETED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "Deal complete: main=" + event.data().getOrDefault("mainRank", "?")
                            + ", round=" + event.data().getOrDefault("round", "?"),
                    data
            );
            case TURN_CHANGED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "Turn: " + shortPlayer(event.data().get("playerId")),
                    data
            );
            case FORCE_CHALLENGE -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    "Force challenge triggered",
                    data
            );
            case CARDS_PLAYED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    shortPlayer(event.data().get("playerId"))
                            + " played " + event.data().getOrDefault("count", "?") + " card(s)",
                    data
            );
            case CHALLENGE_RESOLVED -> UserFacingEvent.broadcast(
                    EventSeverity.INFO,
                    "Challenge result: " + event.data().getOrDefault("outcome", "UNKNOWN"),
                    data
            );
            case SHOT_RESOLVED -> UserFacingEvent.broadcast(
                    EventSeverity.WARNING,
                    "Shot resolved: " + shortPlayer(event.data().get("playerId"))
                            + (Boolean.TRUE.equals(event.data().get("lethal")) ? " eliminated" : " survived"),
                    data
            );
            case PLAYER_ELIMINATED -> UserFacingEvent.broadcast(
                    EventSeverity.ERROR,
                    "Player " + shortPlayer(event.data().get("playerId")) + " eliminated",
                    data
            );
            case GAME_FINISHED -> UserFacingEvent.broadcast(
                    EventSeverity.SUCCESS,
                    "Game finished. Winner: " + shortPlayer(event.data().get("winner")),
                    data
            );
        };
    }

    private Map<String, Object> withType(CoreEvent event) {
        Map<String, Object> data = new HashMap<>(event.data());
        data.put("_eventType", event.type().name());
        return data;
    }

    private String shortPlayer(Object value) {
        if (value == null) {
            return "N/A";
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

    private String modeName(Object value) {
        if (value == null) {
            return "UNKNOWN";
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
