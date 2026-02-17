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
        // Single-pass scan: find '{' ... '}' and resolve from args map directly.
        // Avoids creating N intermediate String objects from chained replace().
        StringBuilder sb = new StringBuilder(template.length() + 16);
        int len = template.length();
        int i = 0;
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close > i) {
                    String name = template.substring(i + 1, close);
                    Object value = args.get(name);
                    if (value != null) {
                        sb.append(value);
                    } else if (args.containsKey(name)) {
                        // explicit null → empty
                    } else {
                        // not a known placeholder, keep literal
                        sb.append(template, i, close + 1);
                    }
                    i = close + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
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
