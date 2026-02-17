package cn.pianzi.liarbar.paper.presentation;

import java.util.List;
import java.util.Objects;

public final class PacketEventsViewBridge {
    private final PacketEventsPublisher publisher;

    public PacketEventsViewBridge(PacketEventsPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public void publishAll(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            publisher.publish(event);
        }
    }
}
