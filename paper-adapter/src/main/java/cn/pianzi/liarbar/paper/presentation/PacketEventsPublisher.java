package cn.pianzi.liarbar.paper.presentation;

public interface PacketEventsPublisher {
    void publish(UserFacingEvent event);

    static PacketEventsPublisher noop() {
        return event -> {
        };
    }
}
