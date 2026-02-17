package cn.pianzi.liarbar.paperplugin.presentation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class MiniMessageSupport {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MiniMessageSupport() {
    }

    public static Component parse(String miniMessageText) {
        return MINI_MESSAGE.deserialize(miniMessageText);
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return MINI_MESSAGE.escapeTags(raw);
    }

    public static String prefixed(String message) {
        return "<gray>[<gold>骗子酒馆</gold>]</gray> " + message;
    }
}

