package cn.pianzi.liarbar.paperplugin.stats;

import cn.pianzi.liarbar.paperplugin.game.SavedTable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StatsRepository extends AutoCloseable {

    void initSchema() throws SQLException;

    Map<UUID, PlayerStatsSnapshot> loadAll() throws SQLException;

    void upsertAll(Map<UUID, PlayerStatsSnapshot> snapshots) throws SQLException;

    int clearAll() throws SQLException;

    SeasonResetResult archiveAndClear(Map<UUID, PlayerStatsSnapshot> snapshots, long archivedAtEpochSecond) throws SQLException;

    int countSeasons() throws SQLException;

    List<SeasonHistorySummary> listSeasons(int page, int pageSize) throws SQLException;

    List<Integer> listSeasonIds(int limit) throws SQLException;

    Optional<SeasonTopResult> topForSeason(int seasonId, int page, int pageSize, SeasonTopSort sort) throws SQLException;

    // ── Table persistence ──

    void saveTables(List<SavedTable> tables) throws SQLException;

    List<SavedTable> loadTables() throws SQLException;

    @Override
    void close();
}
