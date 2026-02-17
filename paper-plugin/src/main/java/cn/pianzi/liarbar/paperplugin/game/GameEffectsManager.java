package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified visual/audio effects manager that mirrors the datapack's sound, particle,
 * title, and firework effects. Driven entirely by UserFacingEvents.
 *
 * <p>Datapack parity reference:
 * <ul>
 *   <li>Join:       xylophone pitch 1.34</li>
 *   <li>Turn start: anvil.place pitch 1</li>
 *   <li>Empty shot: stone_button.click_off pitch 0.97 + crit×1</li>
 *   <li>Real shot:  generic.explode pitch 1.51 + crit×20 + redstone_block×50</li>
 *   <li>Challenge:  title ">>>你被质疑<<<" gold bold</li>
 *   <li>Turn:       title ">>>你的回合<<<" green bold</li>
 *   <li>Win:        firework (small_ball, twinkle, trail)</li>
 * </ul>
 */
public final class GameEffectsManager {

    private final TableStructureBuilder structureBuilder;
    private final I18n i18n;

    /** tableId → set of player UUIDs currently at that table */
    private final Map<String, java.util.Set<UUID>> tablePlayers = new ConcurrentHashMap<>();

    /** playerId → tableId (reverse index for O(1) removal) */
    private final Map<UUID, String> playerToTable = new ConcurrentHashMap<>();

    public GameEffectsManager(TableStructureBuilder structureBuilder, I18n i18n) {
        this.structureBuilder = structureBuilder;
        this.i18n = i18n;
    }

    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            String type = event.eventType();
            if (type == null) continue;
            switch (type) {
                case "PLAYER_JOINED" -> onPlayerJoined(event);
                case "TURN_CHANGED" -> onTurnChanged(event);
                case "CHALLENGE_RESOLVED" -> onChallengeResolved(event);
                case "SHOT_RESOLVED" -> onShotResolved(event);
                case "PLAYER_ELIMINATED" -> onPlayerEliminated(event);
                case "GAME_FINISHED" -> onGameFinished(event);
                case "PLAYER_FORFEITED" -> onPlayerForfeited(event);
            }
        }
    }

    // ── Join: xylophone ──────────────────────────────────────────────

    private void onPlayerJoined(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        String tableId = asString(event.data().get("tableId"));
        if (playerId == null || tableId == null) return;

        tablePlayers.computeIfAbsent(tableId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        playerToTable.put(playerId, tableId);

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        player.playSound(player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, SoundCategory.MASTER, 1f, 1.34f);
    }

    // ── Turn start: anvil + title ────────────────────────────────────

    private void onTurnChanged(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        // Sound: anvil place
        player.playSound(player.getLocation(),
                Sound.BLOCK_ANVIL_PLACE, SoundCategory.MASTER, 1f, 1f);

        // Title: localized "your turn"
        Title title = Title.title(
                Component.text(i18n.t("ui.title.turn"), NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
        );
        player.showTitle(title);
    }

    // ── Challenge: title to the challenged player ────────────────────

    private void onChallengeResolved(UserFacingEvent event) {
        // The "challenged" player is the one who played the cards (the "lastPlayer" field from core)
        UUID challengedId = asUuid(event.data().get("lastPlayer"));
        if (challengedId == null) return;

        Player challenged = Bukkit.getPlayer(challengedId);
        if (challenged == null) return;

        Title title = Title.title(
                Component.text(i18n.t("ui.title.challenged"), NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
        );
        challenged.showTitle(title);
    }

    // ── Shot: sound + particles ──────────────────────────────────────

    private void onShotResolved(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        boolean lethal = Boolean.TRUE.equals(event.data().get("lethal"));
        if (playerId == null) return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        Location loc = player.getLocation();
        Location eyeLoc = player.getEyeLocation();

        if (lethal) {
            // Real shot: explode sound + heavy particles
            player.getWorld().playSound(loc,
                    Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1f, 1.51f);

            // Crit particles at eye level (count 20)
            player.getWorld().spawnParticle(Particle.CRIT,
                    eyeLoc.add(eyeLoc.getDirection().multiply(0.5)), 20,
                    0.1, 0.1, 0.1, 0);

            // Redstone block break particles (count 50)
            player.getWorld().spawnParticle(Particle.BLOCK,
                    loc.clone().add(0, 1, 0), 50,
                    0.1, 0.5, 0.1, 0.4,
                    Material.REDSTONE_BLOCK.createBlockData());
        } else {
            // Empty shot: click sound + small crit
            player.getWorld().playSound(loc,
                    Sound.BLOCK_STONE_BUTTON_CLICK_OFF, SoundCategory.MASTER, 1f, 0.97f);

            // Crit particles at eye level (count 1)
            player.getWorld().spawnParticle(Particle.CRIT,
                    eyeLoc.add(eyeLoc.getDirection().multiply(0.5)), 1,
                    0.1, 0.1, 0.1, 0);
        }
    }

    // ── Elimination: remove from tracking ────────────────────────────

    private void onPlayerEliminated(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;
        removePlayerFromTracking(playerId);
    }

    private void onPlayerForfeited(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) return;
        removePlayerFromTracking(playerId);
    }

    private void removePlayerFromTracking(UUID playerId) {
        String tableId = playerToTable.remove(playerId);
        if (tableId != null) {
            java.util.Set<UUID> set = tablePlayers.get(tableId);
            if (set != null) {
                set.remove(playerId);
            }
        }
    }

    // ── Win: firework ────────────────────────────────────────────────

    private void onGameFinished(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        if (tableId == null) return;

        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) return;

        Location fireworkLoc = center.clone().add(0, 2, 0);

        // Spawn firework matching datapack: small_ball, twinkle, trail, color #F9A31D, fade #FEEB1D
        Firework firework = (Firework) center.getWorld().spawnEntity(fireworkLoc, EntityType.FIREWORK_ROCKET);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL)
                .withColor(Color.fromRGB(0xF9A31D))
                .withFade(Color.fromRGB(0xFEEB1D))
                .flicker(true)
                .trail(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);

        // Clean up table player tracking
        java.util.Set<UUID> removed = tablePlayers.remove(tableId);
        if (removed != null) {
            for (UUID pid : removed) {
                playerToTable.remove(pid);
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    public void removeTable(String tableId) {
        java.util.Set<UUID> removed = tablePlayers.remove(tableId);
        if (removed != null) {
            for (UUID pid : removed) {
                playerToTable.remove(pid);
            }
        }
    }

    public void removeAll() {
        tablePlayers.clear();
        playerToTable.clear();
    }

    // ── Util ─────────────────────────────────────────────────────────

    private UUID asUuid(Object raw) {
        if (raw instanceof UUID uuid) return uuid;
        if (raw instanceof String text) {
            try { return UUID.fromString(text); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }
}
