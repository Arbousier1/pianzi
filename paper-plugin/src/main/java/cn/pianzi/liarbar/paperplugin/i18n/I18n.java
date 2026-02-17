package cn.pianzi.liarbar.paperplugin.i18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

public final class I18n {
    private static final String BUNDLE_BASE = "i18n.messages";

    private final Locale locale;
    private final ZoneId zoneId;
    private final ResourceBundle bundle;
    private final DateTimeFormatter timeFormatter;

    public I18n(String localeTag, ZoneId zoneId) {
        this.locale = resolveLocale(localeTag);
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        this.bundle = loadBundle(this.locale);
        this.timeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(this.locale)
                .withZone(this.zoneId);
    }

    public String t(String key) {
        return t(key, Map.of());
    }

    public String t(String key, Map<String, ?> args) {
        String template = resolve(key);
        if (args == null || args.isEmpty()) {
            return template;
        }
        String resolved = template;
        for (Map.Entry<String, ?> entry : args.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            Object value = entry.getValue();
            resolved = resolved.replace(placeholder, value == null ? "" : String.valueOf(value));
        }
        return resolved;
    }

    public String formatEpochSecond(long epochSecond) {
        return timeFormatter.format(Instant.ofEpochSecond(epochSecond));
    }

    public Locale locale() {
        return locale;
    }

    private String resolve(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException ex) {
            return key;
        }
    }

    private ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE, locale);
        } catch (MissingResourceException ex) {
            return ResourceBundle.getBundle(BUNDLE_BASE, Locale.SIMPLIFIED_CHINESE);
        }
    }

    private static Locale resolveLocale(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));
        if (locale.getLanguage().isBlank()) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return locale;
    }
}
