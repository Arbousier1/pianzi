package cn.pianzi.liarbar.paperplugin.stats;

public record SeasonResetResult(
        int seasonId,
        int archivedRows,
        int deletedRows
) {
}
