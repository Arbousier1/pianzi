package cn.pianzi.liarbar.paperplugin.stats;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import cn.pianzi.liarbar.paperplugin.game.SavedTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MariaDbStatsRepository implements StatsRepository {
    private final HikariDataSource dataSource;

    public MariaDbStatsRepository(String host, int port, String database, String username, String password, int maxPoolSize) {
        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?autoReconnect=true";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setPoolName("liarbar-mariadb");
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void initSchema() throws SQLException {
        try (Connection conn = connection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_stats (
                        player_id VARCHAR(36) PRIMARY KEY,
                        score INT NOT NULL,
                        games_played INT NOT NULL,
                        wins INT NOT NULL,
                        losses INT NOT NULL,
                        eliminated_count INT NOT NULL,
                        survived_shots INT NOT NULL,
                        current_win_streak INT NOT NULL,
                        best_win_streak INT NOT NULL,
                        updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_stats_history (
                        season_id INT NOT NULL,
                        archived_at BIGINT NOT NULL,
                        player_id VARCHAR(36) NOT NULL,
                        score INT NOT NULL,
                        games_played INT NOT NULL,
                        wins INT NOT NULL,
                        losses INT NOT NULL,
                        eliminated_count INT NOT NULL,
                        survived_shots INT NOT NULL,
                        current_win_streak INT NOT NULL,
                        best_win_streak INT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (season_id, player_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_stats_season_meta (
                        id INT PRIMARY KEY,
                        current_season INT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT IGNORE INTO liarbar_stats_season_meta (id, current_season) VALUES (1, 0)
                    """)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_tables (
                        table_id VARCHAR(255) PRIMARY KEY,
                        world_name VARCHAR(255) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """)) {
                stmt.executeUpdate();
            }
        }
    }

    @Override
    public Map<UUID, PlayerStatsSnapshot> loadAll() throws SQLException {
        Map<UUID, PlayerStatsSnapshot> snapshots = new HashMap<>();
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("""
                     SELECT player_id, score, games_played, wins, losses,
                            eliminated_count, survived_shots, current_win_streak,
                            best_win_streak, updated_at
                     FROM liarbar_stats
                     """);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID playerId = UUID.fromString(rs.getString("player_id"));
                snapshots.put(playerId, readSnapshot(rs, playerId));
            }
        }
        return snapshots;
    }

    @Override
    public void upsertAll(Map<UUID, PlayerStatsSnapshot> snapshots) throws SQLException {
        if (snapshots.isEmpty()) return;
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO liarbar_stats (
                         player_id, score, games_played, wins, losses,
                         eliminated_count, survived_shots, current_win_streak,
                         best_win_streak, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON DUPLICATE KEY UPDATE
                         score = VALUES(score),
                         games_played = VALUES(games_played),
                         wins = VALUES(wins),
                         losses = VALUES(losses),
                         eliminated_count = VALUES(eliminated_count),
                         survived_shots = VALUES(survived_shots),
                         current_win_streak = VALUES(current_win_streak),
                         best_win_streak = VALUES(best_win_streak),
                         updated_at = VALUES(updated_at)
                     """)) {
            conn.setAutoCommit(false);
            for (PlayerStatsSnapshot s : snapshots.values()) {
                stmt.setString(1, s.playerId().toString());
                stmt.setInt(2, s.score());
                stmt.setInt(3, s.gamesPlayed());
                stmt.setInt(4, s.wins());
                stmt.setInt(5, s.losses());
                stmt.setInt(6, s.eliminatedCount());
                stmt.setInt(7, s.survivedShots());
                stmt.setInt(8, s.currentWinStreak());
                stmt.setInt(9, s.bestWinStreak());
                stmt.setLong(10, s.updatedAtEpochSecond());
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
        }
    }

    @Override
    public int clearAll() throws SQLException {
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM liarbar_stats")) {
            return stmt.executeUpdate();
        }
    }

    @Override
    public SeasonResetResult archiveAndClear(Map<UUID, PlayerStatsSnapshot> snapshots, long archivedAtEpochSecond) throws SQLException {
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try {
                int seasonId = nextSeasonId(conn);
                int archivedRows = archiveSeason(conn, seasonId, archivedAtEpochSecond, snapshots);
                int deletedRows;
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM liarbar_stats")) {
                    deletedRows = stmt.executeUpdate();
                }
                conn.commit();
                conn.setAutoCommit(true);
                return new SeasonResetResult(seasonId, archivedRows, deletedRows);
            } catch (Exception ex) {
                conn.rollback();
                conn.setAutoCommit(true);
                throw ex;
            }
        }
    }

    @Override
    public int countSeasons() throws SQLException {
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("""
                     SELECT COUNT(DISTINCT season_id) AS total FROM liarbar_stats_history
                     """);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getInt("total");
        }
        return 0;
    }

    @Override
    public List<SeasonHistorySummary> listSeasons(int page, int pageSize) throws SQLException {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;
        List<SeasonHistorySummary> result = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("""
                     SELECT season_id, MAX(archived_at) AS archived_at, COUNT(*) AS player_count
                     FROM liarbar_stats_history
                     GROUP BY season_id
                     ORDER BY season_id DESC
                     LIMIT ? OFFSET ?
                     """)) {
            stmt.setInt(1, safePageSize);
            stmt.setInt(2, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new SeasonHistorySummary(
                            rs.getInt("season_id"),
                            rs.getLong("archived_at"),
                            rs.getInt("player_count")
                    ));
                }
            }
        }
        return result;
    }

    @Override
    public List<Integer> listSeasonIds(int limit) throws SQLException {
        int safeLimit = Math.max(1, limit);
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("""
                     SELECT season_id FROM liarbar_stats_history
                     GROUP BY season_id ORDER BY season_id DESC LIMIT ?
                     """)) {
            stmt.setInt(1, safeLimit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("season_id"));
            }
        }
        return ids;
    }

    @Override
    public Optional<SeasonTopResult> topForSeason(int seasonId, int page, int pageSize, SeasonTopSort sort) throws SQLException {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int totalPlayers = countSeasonPlayers(seasonId);
        if (totalPlayers == 0) return Optional.empty();
        int totalPages = Math.max(1, (totalPlayers + safePageSize - 1) / safePageSize);
        safePage = Math.min(safePage, totalPages);
        int offset = (safePage - 1) * safePageSize;
        String orderBy = switch (sort) {
            case SCORE -> "score DESC, wins DESC, player_id ASC";
            case WINS -> "wins DESC, score DESC, player_id ASC";
        };
        String sql = """
                SELECT archived_at, player_id, score, games_played, wins, losses,
                       eliminated_count, survived_shots, current_win_streak,
                       best_win_streak, updated_at
                FROM liarbar_stats_history
                WHERE season_id = ?
                ORDER BY """ + orderBy + """
                 LIMIT ? OFFSET ?
                """;
        List<PlayerStatsSnapshot> entries = new ArrayList<>();
        long archivedAt = 0L;
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, seasonId);
            stmt.setInt(2, safePageSize);
            stmt.setInt(3, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (archivedAt == 0L) archivedAt = rs.getLong("archived_at");
                    UUID playerId = UUID.fromString(rs.getString("player_id"));
                    entries.add(readSnapshot(rs, playerId));
                }
            }
        }
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new SeasonTopResult(seasonId, archivedAt, sort, safePage, safePageSize, totalPlayers, totalPages, entries));
    }

    @Override
    public void saveTables(List<SavedTable> tables) throws SQLException {
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM liarbar_tables")) {
                del.executeUpdate();
            }
            if (!tables.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO liarbar_tables (table_id, world_name, x, y, z)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    for (SavedTable t : tables) {
                        stmt.setString(1, t.tableId());
                        stmt.setString(2, t.worldName());
                        stmt.setInt(3, t.x());
                        stmt.setInt(4, t.y());
                        stmt.setInt(5, t.z());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
        }
    }

    @Override
    public List<SavedTable> loadTables() throws SQLException {
        List<SavedTable> tables = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement("SELECT table_id, world_name, x, y, z FROM liarbar_tables");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                tables.add(new SavedTable(
                        rs.getString("table_id"),
                        rs.getString("world_name"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z")
                ));
            }
        }
        return tables;
    }

    private int countSeasonPlayers(int seasonId) throws SQLException {
        try (Connection conn = connection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) AS total FROM liarbar_stats_history WHERE season_id = ?")) {
            stmt.setInt(1, seasonId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("total");
            }
        }
        return 0;
    }

    private int nextSeasonId(Connection conn) throws SQLException {
        int current = 0;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT current_season FROM liarbar_stats_season_meta WHERE id = 1");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) current = rs.getInt("current_season");
        }
        int next = current + 1;
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE liarbar_stats_season_meta SET current_season = ? WHERE id = 1")) {
            stmt.setInt(1, next);
            stmt.executeUpdate();
        }
        return next;
    }

    private int archiveSeason(Connection conn, int seasonId, long archivedAt, Map<UUID, PlayerStatsSnapshot> snapshots) throws SQLException {
        if (snapshots.isEmpty()) return 0;
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO liarbar_stats_history (
                    season_id, archived_at, player_id, score, games_played, wins, losses,
                    eliminated_count, survived_shots, current_win_streak, best_win_streak, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (PlayerStatsSnapshot s : snapshots.values()) {
                stmt.setInt(1, seasonId);
                stmt.setLong(2, archivedAt);
                stmt.setString(3, s.playerId().toString());
                stmt.setInt(4, s.score());
                stmt.setInt(5, s.gamesPlayed());
                stmt.setInt(6, s.wins());
                stmt.setInt(7, s.losses());
                stmt.setInt(8, s.eliminatedCount());
                stmt.setInt(9, s.survivedShots());
                stmt.setInt(10, s.currentWinStreak());
                stmt.setInt(11, s.bestWinStreak());
                stmt.setLong(12, s.updatedAtEpochSecond());
                stmt.addBatch();
            }
            return stmt.executeBatch().length;
        }
    }

    private PlayerStatsSnapshot readSnapshot(ResultSet rs, UUID playerId) throws SQLException {
        return new PlayerStatsSnapshot(
                playerId,
                rs.getInt("score"),
                rs.getInt("games_played"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                rs.getInt("eliminated_count"),
                rs.getInt("survived_shots"),
                rs.getInt("current_win_streak"),
                rs.getInt("best_win_streak"),
                rs.getLong("updated_at")
        );
    }
}
