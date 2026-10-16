package org.jellyfin.mobile.setup

import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue

/**
 * Client-side compatibility adapter for the SDK's server discovery (c203 Phase 0 decision).
 *
 * The pinned Jellyfin SDK scores any server whose ProductName is not "Jellyfin Server" as BAD. A SlopTank server
 * reports "SlopTank Server" and is otherwise the same API, so this adapter forgives exactly that one issue. Every
 * other SDK check (reachability, missing, outdated or unsupported version) still applies, and the server's
 * branding is not changed.
 */
object SlopTankCompatibility {
    const val PRODUCT_NAME = "SlopTank Server"

    fun effectiveScore(server: RecommendedServerInfo): RecommendedServerInfoScore {
        if (server.score != RecommendedServerInfoScore.BAD) return server.score
        val (productIssues, otherIssues) = server.issues.partition { it is RecommendedServerIssue.InvalidProductName }
        val onlySlopTankName = productIssues.size == 1 &&
            (productIssues.single() as RecommendedServerIssue.InvalidProductName).productName == PRODUCT_NAME
        val othersTolerable = otherIssues.all { it is RecommendedServerIssue.SlowResponse }
        return if (onlySlopTankName && othersTolerable) RecommendedServerInfoScore.GOOD else server.score
    }
}
