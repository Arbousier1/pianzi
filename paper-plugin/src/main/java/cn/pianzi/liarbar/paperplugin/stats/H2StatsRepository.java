package cn.pianzi.liarbar.paperplugin.stats;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
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

public final class H2StatsRepository implements StatsRepository {
    private final HikariDataSource dataSource;

    public H2StatsRepository(Path dataFolder) {
        try {
            Files.createDirectories(dataFolder);
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建插件数据目录: " + dataFolder, ex);
        }
        Path databaseFile = dataFolder.resolve("liarbar_stats");
        String jdbcUrl = "jdbc:h2:file:" + databaseFile.toAbsolutePath() + ";MODE=MYSQL;AUTO_RECONNECT=TRUE";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(2);
        config.setPoolName("liarbar-h2");
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
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_stats (
                        player_id UUID PRIMARY KEY,
                        score INT NOT NULL,
                        games_played INT NOT NULL,
                        wins INT NOT NULL,
                        losses INT NOT NULL,
                        eliminated_count INT NOT NULL,
                        survived_shots INT NOT NULL,
                        current_win_streak INT NOT NULL,
                        best_win_streak INT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_stats_history (
                        season_id INT NOT NULL,
                        archived_at BIGINT NOT NULL,
                        player_id UUID NOT NULL,
                        score INT NOT NULL,
                        games_played INT NOT NULL,
                        wins INT NOT NULL,
                        losses INT NOT NULL,
                        eliminated_count INT NOT NULL,
                        survived_shots INT NOT NULL,
                        current_win_streak INT NOT NULL,
                        best_win_streak INT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE INDEX IF NOT EXISTS idx_liarbar_stats_history_season_score
                    ON liarbar_stats_history (season_id, score DESC, wins DESC)
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_stats_season_meta (
                        id INT PRIMARY KEY,
                        current_season INT NOT NULL
                    )
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    MERGE INTO liarbar_stats_season_meta (id, current_season)
                    KEY(id) VALUES (1, 0)
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS liarbar_tables (
                        table_id VARCHAR(255) PRIMARY KEY,
                        world_name VARCHAR(255) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL
                    )
                    """)) {
                statement.executeUpdate();
            }
        }
    }

    @Override
    public Map<UUID, PlayerStatsSnapshot> loadAll() throws SQLException {
        Map<UUID, PlayerStatsSnapshot> snapshots = new HashMap<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         player_id,
                         score,
                         games_played,
                         wins,
                         losses,
                         eliminated_count,
                         survived_shots,
                         current_win_streak,
                         best_win_streak,
                         updated_at
                     FROM liarbar_stats
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId = rows.getObject("player_id", UUID.class);
                snapshots.put(playerId, new PlayerStatsSnapshot(
                        playerId,
                        rows.getInt("score"),
                        rows.getInt("games_played"),
                        rows.getInt("wins"),
                        rows.getInt("losses"),
                        rows.getInt("eliminated_count"),
                        rows.getInt("survived_shots"),
                        rows.getInt("current_win_streak"),
                        rows.getInt("best_win_streak"),
                        rows.getLong("updated_at")
                ));
            }
        }
        return snapshots;
    }

    @Override
    public void upsertAll(Map<UUID, PlayerStatsSnapshot> snapshots) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     MERGE INTO liarbar_stats (
                         player_id,
                         score,
                         games_played,
                         wins,
                         losses,
                         eliminated_count,
                         survived_shots,
                         current_win_streak,
                         best_win_streak,
                         updated_at
                     ) KEY(player_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            connection.setAutoCommit(false);
            for (PlayerStatsSnapshot snapshot : snapshots.values()) {
                statement.setObject(1, snapshot.playerId());
                statement.setInt(2, snapshot.score());
                statement.setInt(3, snapshot.gamesPlayed());
                statement.setInt(4, snapshot.wins());
                statement.setInt(5, snapshot.losses());
                statement.setInt(6, snapshot.eliminatedCount());
                statement.setInt(7, snapshot.survivedShots());
                statement.setInt(8, snapshot.currentWinStreak());
                statement.setInt(9, snapshot.bestWinStreak());
                statement.setLong(10, snapshot.updatedAtEpochSecond());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    @Override
    public int clearAll() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM liarbar_stats")) {
            return statement.executeUpdate();
        }
    }

    @Override
    public SeasonResetResult archiveAndClear(Map<UUID, PlayerStatsSnapshot> snapshots, long archivedAtEpochSecond) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                int seasonId = nextSeasonId(connection);
                int archivedRows = archiveSeason(connection, seasonId, archivedAtEpochSecond, snapshots);
                int deletedRows;
                try (PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM liarbar_stats")) {
                    deletedRows = deleteStatement.executeUpdate();
                }
                connection.commit();
                connection.setAutoCommit(true);
                return new SeasonResetResult(seasonId, archivedRows, deletedRows);
            } catch (Exception ex) {
                connection.rollback();
                connection.setAutoCommit(true);
                throw ex;
            }
        }
    }

    @Override
    public int countSeasons() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) AS total
                     FROM (
                         SELECT season_id
                         FROM liarbar_stats_history
                         GROUP BY season_id
                     ) t
                     """);
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                return rows.getInt("total");
            }
        }
        return 0;
    }

    @Override
    public List<SeasonHistorySummary> listSeasons(int page, int pageSize) throws SQLException {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;
        List<SeasonHistorySummary> result = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT season_id, MAX(archived_at) AS archived_at, COUNT(*) AS player_count
                     FROM liarbar_stats_history
                     GROUP BY season_id
                     ORDER BY season_id DESC
                     LIMIT ? OFFSET ?
                     """)) {
            statement.setInt(1, safePageSize);
            statement.setInt(2, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new SeasonHistorySummary(
                            rows.getInt("season_id"),
                            rows.getLong("archived_at"),
                            rows.getInt("player_count")
                    ));
                }
            }
        }
        return result;
    }

    @Override
    public List<Integer> listSeasonIds(int limit) throws SQLException {
        int safeLimit = Math.max(1, limit);
        List<Integer> seasonIds = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT season_id
                     FROM liarbar_stats_history
                     GROUP BY season_id
                     ORDER BY season_id DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, safeLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    seasonIds.add(rows.getInt("season_id"));
                }
            }
        }
        return seasonIds;
    }

    @Override
    public Optional<SeasonTopResult> topForSeason(int seasonId, int page, int pageSize, SeasonTopSort sort) throws SQLException {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int totalPlayers = countSeasonPlayers(seasonId);
        if (totalPlayers == 0) {
            return Optional.empty();
        }
        int totalPages = Math.max(1, (totalPlayers + safePageSize - 1) / safePageSize);
        safePage = Math.min(safePage, totalPages);
        int offset = (safePage - 1) * safePageSize;
        List<PlayerStatsSnapshot> entries = new ArrayList<>();
        long archivedAt = 0L;
        String orderBy = switch (sort) {
            case SCORE -> "score DESC, wins DESC, player_id ASC";
            case WINS -> "wins DESC, score DESC, player_id ASC";
        };
        String sql = """
                SELECT
                    archived_at,
                    player_id,
                    score,
                    games_played,
                    wins,
                    losses,
                    eliminated_count,
                    survived_shots,
                    current_win_streak,
                    best_win_streak,
                    updated_at
                FROM liarbar_stats_history
                WHERE season_id = ?
                ORDER BY """ + orderBy + """
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, seasonId);
            statement.setInt(2, safePageSize);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (archivedAt == 0L) {
                        archivedAt = rows.getLong("archived_at");
                    }
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    entries.add(new PlayerStatsSnapshot(
                            playerId,
                            rows.getInt("score"),
                            rows.getInt("games_played"),
                            rows.getInt("wins"),
                            rows.getInt("losses"),
                            rows.getInt("eliminated_count"),
                            rows.getInt("survived_shots"),
                            rows.getInt("current_win_streak"),
                            rows.getInt("best_win_streak"),
                            rows.getLong("updated_at")
                    ));
                }
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SeasonTopResult(
                seasonId,
                archivedAt,
                sort,
                safePage,
                safePageSize,
                totalPlayers,
                totalPages,
                entries
        ));
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
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) AS total
                     FROM liarbar_stats_history
                     WHERE season_id = ?
                     """)) {
            statement.setInt(1, seasonId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getInt("total");
                }
            }
        }
        return 0;
    }

    private int nextSeasonId(Connection connection) throws SQLException {
        int currentSeason = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT current_season
                FROM liarbar_stats_season_meta
                WHERE id = 1
                """);
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                currentSeason = rows.getInt("current_season");
            }
        }
        int nextSeason = currentSeason + 1;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE liarbar_stats_season_meta
                SET current_season = ?
                WHERE id = 1
                """)) {
            statement.setInt(1, nextSeason);
            statement.executeUpdate();
        }
        return nextSeason;
    }

    private int archiveSeason(
            Connection connection,
            int seasonId,
            long archivedAtEpochSecond,
            Map<UUID, PlayerStatsSnapshot> snapshots
    ) throws SQLException {
        if (snapshots.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO liarbar_stats_history (
                    season_id,
                    archived_at,
                    player_id,
                    score,
                    games_played,
                    wins,
                    losses,
                    eliminated_count,
                    survived_shots,
                    current_win_streak,
                    best_win_streak,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (PlayerStatsSnapshot snapshot : snapshots.values()) {
                statement.setInt(1, seasonId);
                statement.setLong(2, archivedAtEpochSecond);
                statement.setObject(3, snapshot.playerId());
                statement.setInt(4, snapshot.score());
                statement.setInt(5, snapshot.gamesPlayed());
                statement.setInt(6, snapshot.wins());
                statement.setInt(7, snapshot.losses());
                statement.setInt(8, snapshot.eliminatedCount());
                statement.setInt(9, snapshot.survivedShots());
                statement.setInt(10, snapshot.currentWinStreak());
                statement.setInt(11, snapshot.bestWinStreak());
                statement.setLong(12, snapshot.updatedAtEpochSecond());
                statement.addBatch();
            }
            int[] changed = statement.executeBatch();
            return changed.length;
        }
    }
}
