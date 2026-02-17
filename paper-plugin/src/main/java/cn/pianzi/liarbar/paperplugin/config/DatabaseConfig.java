package cn.pianzi.liarbar.paperplugin.config;

import org.bukkit.configuration.file.FileConfiguration;

public record DatabaseConfig(
        DatabaseType type,
        String host,
        int port,
        String database,
        String username,
        String password,
        int maxPoolSize
) {
    public enum DatabaseType { H2, MARIADB }

    public static DatabaseConfig fromConfig(FileConfiguration config) {
        String rawType = config.getString("database.type", "h2");
        DatabaseType type = "mariadb".equalsIgnoreCase(rawType) ? DatabaseType.MARIADB : DatabaseType.H2;
        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", 3306);
        String database = config.getString("database.database", "liarbar");
        String username = config.getString("database.username", "root");
        String password = config.getString("database.password", "");
        int maxPoolSize = Math.max(1, config.getInt("database.max-pool-size", 8));
        return new DatabaseConfig(type, host, port, database, username, password, maxPoolSize);
    }
}
