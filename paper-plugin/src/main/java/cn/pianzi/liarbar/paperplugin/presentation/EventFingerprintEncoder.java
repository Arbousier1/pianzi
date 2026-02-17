package cn.pianzi.liarbar.paperplugin.presentation;

import cn.pianzi.liarbar.paper.presentation.EventSeverity;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes user-facing events into stable JSON fingerprints.
 */
final class EventFingerprintEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    String encode(UserFacingEvent event, Map<String, Object> localizedData) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.eventType());
        payload.put("messageKey", event.message());
        payload.put("severity", severityName(event.severity()));
        payload.put("targetPlayer", event.targetPlayer() != null ? event.targetPlayer().toString() : null);
        payload.put("data", localizedData != null ? localizedData : Map.of());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return payload.toString();
        }
    }

    private String severityName(EventSeverity severity) {
        return severity != null ? severity.name() : "INFO";
    }
}
