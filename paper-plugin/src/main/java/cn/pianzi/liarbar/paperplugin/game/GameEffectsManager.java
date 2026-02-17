package cn.pianzi.liarbar.paperplugin.game;

import cn.pianzi.liarbar.paper.presentation.UserFacingEvent;
import cn.pianzi.liarbar.paperplugin.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Visual/audio effects manager driven by UserFacingEvents.
 */
public final class GameEffectsManager {

    private final TableStructureBuilder structureBuilder;
    private final I18n i18n;

    public GameEffectsManager(TableStructureBuilder structureBuilder, I18n i18n) {
        this.structureBuilder = structureBuilder;
        this.i18n = i18n;
    }

    public void handleEvents(List<UserFacingEvent> events) {
        for (UserFacingEvent event : events) {
            String type = event.eventType();
            if (type == null) {
                continue;
            }
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

    private void onPlayerJoined(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        player.playSound(player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, SoundCategory.MASTER, 1f, 1.34f);
    }

    private void onTurnChanged(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        if (playerId == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        player.playSound(player.getLocation(),
                Sound.BLOCK_ANVIL_PLACE, SoundCategory.MASTER, 1f, 1f);

        Title title = Title.title(
                Component.text(i18n.t("ui.title.turn"), NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
        );
        player.showTitle(title);
    }

    private void onChallengeResolved(UserFacingEvent event) {
        UUID challengedId = asUuid(event.data().get("lastPlayer"));
        if (challengedId == null) {
            return;
        }

        Player challenged = Bukkit.getPlayer(challengedId);
        if (challenged == null) {
            return;
        }

        Title title = Title.title(
                Component.text(i18n.t("ui.title.challenged"), NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
        );
        challenged.showTitle(title);
    }

    private void onShotResolved(UserFacingEvent event) {
        UUID playerId = asUuid(event.data().get("playerId"));
        boolean lethal = Boolean.TRUE.equals(event.data().get("lethal"));
        if (playerId == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        Location loc = player.getLocation();
        Location eyeLoc = player.getEyeLocation();

        if (lethal) {
            player.getWorld().playSound(loc,
                    Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1f, 1.51f);

            player.getWorld().spawnParticle(Particle.CRIT,
                    eyeLoc.add(eyeLoc.getDirection().multiply(0.5)), 20,
                    0.1, 0.1, 0.1, 0);

            player.getWorld().spawnParticle(Particle.BLOCK,
                    loc.clone().add(0, 1, 0), 50,
                    0.1, 0.5, 0.1, 0.4,
                    Material.REDSTONE_BLOCK.createBlockData());
        } else {
            player.getWorld().playSound(loc,
                    Sound.BLOCK_STONE_BUTTON_CLICK_OFF, SoundCategory.MASTER, 1f, 0.97f);

            player.getWorld().spawnParticle(Particle.CRIT,
                    eyeLoc.add(eyeLoc.getDirection().multiply(0.5)), 1,
                    0.1, 0.1, 0.1, 0);
        }
    }

    private void onPlayerEliminated(UserFacingEvent event) {
        // no-op
    }

    private void onPlayerForfeited(UserFacingEvent event) {
        // no-op
    }

    private void onGameFinished(UserFacingEvent event) {
        String tableId = asString(event.data().get("tableId"));
        if (tableId == null) {
            return;
        }

        Location center = structureBuilder.locationOf(tableId);
        if (center == null || center.getWorld() == null) {
            return;
        }

        Location fireworkLoc = center.clone().add(0, 2, 0);
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
    }

    public void removeTable(String tableId) {
        // no-op
    }

    public void removeAll() {
        // no-op
    }

    private UUID asUuid(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }
}
