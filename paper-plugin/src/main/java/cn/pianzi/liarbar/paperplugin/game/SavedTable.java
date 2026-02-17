package cn.pianzi.liarbar.paperplugin.game;

/**
 * Represents a persisted table location that can be restored after server restart.
 */
public record SavedTable(String tableId, String worldName, int x, int y, int z) {
}
