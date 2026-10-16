package org.jellyfin.mobile.setup

import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore.BAD
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore.GOOD
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore.GREAT
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.junit.jupiter.api.Test

class SlopTankCompatibilityTest {
    private val slopTankName = RecommendedServerIssue.InvalidProductName("SlopTank Server")

    private fun score(score: RecommendedServerInfoScore, vararg issues: RecommendedServerIssue) =
        SlopTankCompatibility.effectiveScore(
            RecommendedServerInfo(
                address = "https://example.invalid",
                responseTime = 10,
                score = score,
                issues = issues.toList(),
                systemInfo = Result.success(PublicSystemInfo(productName = "SlopTank Server", version = "12.0.0")),
            ),
        )

    @Test
    fun `a SlopTank server rejected only for its product name is accepted`() {
        score(BAD, slopTankName) shouldBe GOOD
        score(BAD, slopTankName, RecommendedServerIssue.SlowResponse(9000)) shouldBe GOOD
    }

    @Test
    fun `every other SDK check still applies`() {
        score(BAD, RecommendedServerIssue.InvalidProductName("Other")) shouldBe BAD
        score(BAD, slopTankName, RecommendedServerIssue.MissingVersion) shouldBe BAD
        score(GREAT) shouldBe GREAT
    }
}
