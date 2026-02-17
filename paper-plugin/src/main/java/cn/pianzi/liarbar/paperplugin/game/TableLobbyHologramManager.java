package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.core.domain.GamePhase;
import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import cn.pianzi.liarbar.paperplugin.presentation.MiniMessageSupport;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lobby hologram shown above the table center before the game starts.
 * Mirrors datapack-style "how to play / waiting for players / selected mode" hints.
 */
public final class TableLobbyHologramManager {

    private static final int MIN_PLAYERS_TO_START = 2;
    private static final double HOLOGRAM_Y_OFFSET = 1.55D;

    private final TableStructureBuilder structureBuilder;
    private final I18n i18n;

    /** tableId -> hologram entity UUID */
    private final Map<String, UUID> tableDisplays = new ConcurrentHashMap<>();

    /** tableId -> tracked lobby state */
    private final Map<String, LobbyState> tableStates = new ConcurrentHashMap<>();

    public TableLobbyHologramManager(TableStructureBuilder structureBuilder, I18n i18n) {
        this.structureBuilder = Objects.requireNonNull(structureBuilder, "structureBuilder");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    public void createTable(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        tableStates.computeIfAbsent(tableId, ignored -> LobbyState.idle());
        render(tableId);
    }

    public void handleEvents(List<UserFacingEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        Set<String> dirtyTables = new HashSet<>();
        for (UserFacingEvent event : events) {
            String tableId = asString(event.data().get("tableId"));
            if (tableId == null || tableId.isBlank()) {
                continue;
            }

            LobbyState state = tableStates.computeIfAbsent(tableId, ignored -> LobbyState.idle());
            String eventType = event.eventType();
            if (eventType == null) {
                continue;
            }

            switch (eventType) {
                case "MODE_SELECTED" -> {
                    state.selectedMode = asMode(event.data().get("mode"));
                    state.wagerPerPlayer = asInt(event.data().get("wagerPerPlayer"), 1);
                    state.phase = GamePhase.JOINING;
                    dirtyTables.add(tableId);
                }
                case "PLAYER_JOINED" -> {
                    // Joining can now happen during MODE_SELECTION (sit first, then pick mode).
                    // Keep MODE_SELECTION unchanged until MODE_SELECTED arrives.
                    if (state.phase != GamePhase.MODE_SELECTION) {
                        state.phase = GamePhase.JOINING;
                    }
                    state.joinedCount = asInt(event.data().get("joinedCount"), state.joinedCount + 1);
                    dirtyTables.add(tableId);
                }
                case "PLAYER_FORFEITED" -> {
                    if (Boolean.TRUE.equals(event.data().get("beforeStart")) || isLobbyPhase(state.phase)) {
                        state.joinedCount = Math.max(0, state.joinedCount - 1);
                        dirtyTables.add(tableId);
                    }
                }
                case "PHASE_CHANGED" -> {
                    GamePhase phase = asPhase(event.data().get("phase"));
                    if (phase != null) {
                        state.phase = phase;
                        if (phase == GamePhase.MODE_SELECTION) {
                            state.joinedCount = 0;
                            state.selectedMode = null;
                            state.wagerPerPlayer = 1;
                        }
                        dirtyTables.add(tableId);
                    }
                }
                case "GAME_FINISHED" -> {
                    state.resetToIdle();
                    dirtyTables.add(tableId);
                }
                default -> {
                }
            }
        }

        for (String tableId : dirtyTables) {
            render(tableId);
        }
    }

    public void removeTable(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        tableStates.remove(tableId);
        removeDisplay(tableId);
    }

    public void removeAll() {
        for (String tableId : List.copyOf(tableDisplays.keySet())) {
            removeDisplay(tableId);
        }
        tableStates.clear();
    }

    private void render(String tableId) {
        LobbyState state = tableStates.computeIfAbsent(tableId, ignored -> LobbyState.idle());

        if (!isLobbyPhase(state.phase)) {
            removeDisplay(tableId);
            return;
        }

        TextDisplay display = ensureDisplay(tableId);
        if (display == null) {
            return;
        }

        display.text(buildText(tableId, state));
    }

    private TextDisplay ensureDisplay(String tableId) {
        Location location = hologramLocation(tableId);
        if (location == null || location.getWorld() == null) {
            removeDisplay(tableId);
            return null;
        }

        UUID existingId = tableDisplays.get(tableId);
        if (existingId != null) {
            Entity existing = Bukkit.getEntity(existingId);
            if (existing instanceof TextDisplay textDisplay && !textDisplay.isDead()) {
                textDisplay.teleport(location);
                return textDisplay;
            }
            tableDisplays.remove(tableId);
        }

        TextDisplay created = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setSeeThrough(true);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(240);
        });
        tableDisplays.put(tableId, created.getUniqueId());
        return created;
    }

    private void removeDisplay(String tableId) {
        UUID entityId = tableDisplays.remove(tableId);
        if (entityId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
    }

    private Component buildText(String tableId, LobbyState state) {
        String escapedTable = MiniMessageSupport.escape(tableId);

        if (state.joinedCount <= 0) {
            return joinLines(
                    parse(i18n.t("ui.hologram.empty.title")),
                    parse(i18n.t("ui.hologram.empty.how_to_play")),
                    parse(i18n.t("ui.hologram.empty.join_hint", Map.of("table", escapedTable)))
            );
        }

        String modeText = modeText(state.selectedMode);
        if (state.selectedMode == null) {
            return joinLines(
                    parse(i18n.t("ui.hologram.joining.title", Map.of("joined", state.joinedCount))),
                    parse(i18n.t("ui.hologram.joining.select_mode_first")),
                    parse(i18n.t("ui.hologram.joining.mode", Map.of("mode", MiniMessageSupport.escape(modeText)))),
                    parse(i18n.t("ui.hologram.joining.mode_gui_hint", Map.of("table", escapedTable)))
            );
        }

        int needMore = Math.max(0, MIN_PLAYERS_TO_START - state.joinedCount);
        Component countLine = needMore > 0
                ? parse(i18n.t("ui.hologram.joining.need_more", Map.of("count", needMore)))
                : parse(i18n.t("ui.hologram.joining.ready"));

        String modeKey = state.selectedMode == TableMode.KUNKUN_COIN
                ? i18n.t("ui.hologram.joining.mode_with_wager", Map.of(
                "mode", MiniMessageSupport.escape(modeText),
                "wager", state.wagerPerPlayer
        ))
                : i18n.t("ui.hologram.joining.mode", Map.of("mode", MiniMessageSupport.escape(modeText)));

        return joinLines(
                parse(i18n.t("ui.hologram.joining.title", Map.of("joined", state.joinedCount))),
                countLine,
                parse(modeKey),
                parse(i18n.t("ui.hologram.joining.join_hint", Map.of("table", escapedTable)))
        );
    }

    private Component joinLines(Component... lines) {
        Component result = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines[i]);
        }
        return result;
    }

    private Component parse(String miniMessage) {
        return MiniMessageSupport.parse(miniMessage);
    }

    private String modeText(TableMode mode) {
        if (mode == null) {
            return i18n.t("ui.hologram.mode.unselected");
        }
        return switch (mode) {
            case LIFE_ONLY -> i18n.t("ui.hologram.mode.life");
            case FANTUAN_COIN -> i18n.t("ui.hologram.mode.fantuan");
            case KUNKUN_COIN -> i18n.t("ui.hologram.mode.money");
        };
    }

    private boolean isLobbyPhase(GamePhase phase) {
        return phase == GamePhase.MODE_SELECTION || phase == GamePhase.JOINING;
    }

    private Location hologramLocation(String tableId) {
        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) {
            return null;
        }
        return new Location(
                center.getWorld(),
                center.getBlockX() + 0.5D,
                center.getBlockY() + HOLOGRAM_Y_OFFSET,
                center.getBlockZ() + 0.5D
        );
    }

    private String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private int asInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private GamePhase asPhase(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return GamePhase.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private TableMode asMode(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TableMode.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class LobbyState {
        private int joinedCount;
        private int wagerPerPlayer;
        private GamePhase phase;
        private TableMode selectedMode;

        private static LobbyState idle() {
            LobbyState state = new LobbyState();
            state.phase = GamePhase.MODE_SELECTION;
            state.joinedCount = 0;
            state.wagerPerPlayer = 1;
            state.selectedMode = null;
            return state;
        }

        private void resetToIdle() {
            phase = GamePhase.MODE_SELECTION;
            joinedCount = 0;
            wagerPerPlayer = 1;
            selectedMode = null;
        }
    }
}
