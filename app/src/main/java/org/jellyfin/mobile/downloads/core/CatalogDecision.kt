package org.jellyfin.mobile.downloads.core

import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * What a completed download should do after the server's current view of the item has been read.
 *
 * - [Gone]: the server no longer has the item (or the user lost access). The offline copy stays playable and
 *   is marked as removed from the server; only the user removes it.
 * - [Replaced]: the item's portable media is a different object now (other media source, size or path). The
 *   download is fetched again; the new object is validated before it replaces the old one.
 * - [Unchanged]: nothing to do. A media source that became non-portable also keeps the existing offline copy.
 */
sealed interface CatalogDecision {
    data object Unchanged : CatalogDecision
    data object Gone : CatalogDecision
    data class Replaced(val fresh: BaseItemDto) : CatalogDecision

    companion object {
        fun decide(stored: BaseItemDto, fresh: BaseItemDto?): CatalogDecision {
            if (fresh == null) return Gone
            val old = PortableMediaPolicy.decide(stored) as? PortableMediaPolicy.Decision.Portable
            val new = PortableMediaPolicy.decide(fresh) as? PortableMediaPolicy.Decision.Portable
            if (old == null || new == null) return Unchanged
            val same = old.mediaSource.id == new.mediaSource.id &&
                old.mediaSource.size == new.mediaSource.size &&
                old.mediaSource.path == new.mediaSource.path
            return if (same) Unchanged else Replaced(fresh)
        }
    }
}
