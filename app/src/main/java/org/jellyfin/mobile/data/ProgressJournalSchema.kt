package org.jellyfin.mobile.data

/**
 * Database rules of the offline progress journal. An entry is settled when the server has acknowledged it or
 * the conflict was resolved in the server's favour; until then it must never be deleted, not even when its
 * download is removed. The journal therefore has no foreign key to the download table, and a trigger refuses
 * every delete of an entry that is not settled.
 */
object ProgressJournalSchema {
    /** Mirrors [org.jellyfin.mobile.downloads.progress.ProgressEntry.isSettled]. */
    private const val SETTLED = "dirty = 0 AND op_id IS NULL AND state IN ('CLEAN', 'CONFLICTED')"

    /** Deletes the entry only if its download is gone and the entry is settled. */
    const val PURGE_IF_DETACHED = "DELETE FROM offline_progress WHERE download_id = :downloadId AND " + SETTLED +
        " AND NOT EXISTS (SELECT 1 FROM download WHERE id = :downloadId)"

    private const val KEEP_UNSYNCED_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS `offline_progress_keep_unsynced` BEFORE DELETE ON `offline_progress` " +
            "WHEN NOT (OLD.dirty = 0 AND OLD.op_id IS NULL AND OLD.state IN ('CLEAN', 'CONFLICTED')) " +
            "BEGIN SELECT RAISE(ABORT, 'unsynced offline progress cannot be deleted'); END"

    /** Run on every open, so fresh installs, migrated and destructively recreated databases all get the guard. */
    val OPEN = listOf(KEEP_UNSYNCED_TRIGGER)

    private const val COLUMNS = "`download_id`, `server_id`, `user_id`, `item_id`, `server_identity`, " +
        "`base_revision`, `base_position_ticks`, `base_played`, `base_play_count`, `local_position_ticks`, " +
        "`local_played`, `local_completions`, `local_last_played`, `dirty`, `op_id`, `op_sequence`, " +
        "`op_base_revision`, `op_position_ticks`, `op_played`, `op_play_count_increment`, `op_last_played`, `state`"

    /** Version 8 drops the journal's cascading foreign key to the download table; every entry is kept. */
    val MIGRATION_7_8 = listOf(
        "CREATE TABLE IF NOT EXISTS `_new_offline_progress` (`download_id` INTEGER NOT NULL, " +
            "`server_id` INTEGER NOT NULL, `user_id` INTEGER NOT NULL, `item_id` TEXT NOT NULL, " +
            "`server_identity` TEXT, `base_revision` INTEGER, `base_position_ticks` INTEGER, `base_played` INTEGER, " +
            "`base_play_count` INTEGER, `local_position_ticks` INTEGER NOT NULL, `local_played` INTEGER NOT NULL, " +
            "`local_completions` INTEGER NOT NULL, `local_last_played` TEXT, `dirty` INTEGER NOT NULL, " +
            "`op_id` TEXT, `op_sequence` INTEGER, `op_base_revision` INTEGER, `op_position_ticks` INTEGER, " +
            "`op_played` INTEGER, `op_play_count_increment` INTEGER, `op_last_played` TEXT, " +
            "`state` TEXT NOT NULL, PRIMARY KEY(`download_id`))",
        "INSERT INTO `_new_offline_progress` ($COLUMNS) SELECT $COLUMNS FROM `offline_progress`",
        "DROP TABLE `offline_progress`",
        "ALTER TABLE `_new_offline_progress` RENAME TO `offline_progress`",
        "CREATE INDEX IF NOT EXISTS `index_offline_progress_server_id_user_id` " +
            "ON `offline_progress` (`server_id`, `user_id`)",
        KEEP_UNSYNCED_TRIGGER,
    )
}
