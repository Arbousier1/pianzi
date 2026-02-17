package cn.pianzi.liarbar.paper.presentation;

import java.util.List;

public interface PacketEventsPublisher {
    void publish(UserFacingEvent event);

    /**
     * Publish a batch of events. Default implementation delegates to {@link #publish} one by one.
     * Implementations may override to perform pre-processing (e.g. membership tracking).
     */
    default void publishAll(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            publish(event);
        }
    }

    static PacketEventsPublisher noop() {
        return event -> {
        };
    }
}
