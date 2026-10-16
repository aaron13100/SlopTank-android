package org.jellyfin.mobile.downloads.core


/**
 * Why a download is not (yet) complete. Persisted by name, so never rename an entry.
 */
enum class DownloadErrorReason {
    /** Transient network or server failure; the download is retried automatically. */
    NETWORK,

    /** Not enough free space for the remaining bytes plus the safety reserve. */
    INSUFFICIENT_STORAGE,

    /** The received object failed validation and was discarded; retrying starts from zero. */
    VALIDATION_FAILED,

    /** The item is not portable media (H.264/AAC MP4 direct play), so it is not available offline. */
    NOT_PORTABLE,

    /** The server no longer has the item (HTTP 404/410). */
    SERVER_ITEM_GONE,

    /** The stored credentials were rejected (HTTP 401/403); log in again and retry. */
    AUTH_EXPIRED,
}

/** A failure that a retry cannot fix without a user or server change. */
open class PermanentDownloadException(
    val reason: DownloadErrorReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Free space is too low. Retrying after freeing space resumes from the partial file. */
class InsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
    cause: Throwable? = null,
) : PermanentDownloadException(
    DownloadErrorReason.INSUFFICIENT_STORAGE,
    "Need $requiredBytes bytes but only $availableBytes are available",
    cause,
)

/** The downloaded object is not the object it claims to be. */
class ValidationFailedException(message: String) :
    PermanentDownloadException(DownloadErrorReason.VALIDATION_FAILED, message)

/** The server refused the stored credentials. */
class AuthExpiredException(code: Int) :
    PermanentDownloadException(DownloadErrorReason.AUTH_EXPIRED, "Server answered HTTP $code")

/** The server no longer has the item. */
class ServerItemGoneException(code: Int) :
    PermanentDownloadException(DownloadErrorReason.SERVER_ITEM_GONE, "Server answered HTTP $code")

/** The download was paused, cancelled or removed by the user while it was running. */
class DownloadInterruptedException : Exception("Download interrupted by the user")
