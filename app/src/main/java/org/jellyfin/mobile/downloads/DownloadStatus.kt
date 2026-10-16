package org.jellyfin.mobile.downloads

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,

    /** Stopped by the user; the partial data is kept and the download resumes from it. */
    PAUSED,
    DOWNLOADED,
    ERROR,
    CANCELLED,
}
