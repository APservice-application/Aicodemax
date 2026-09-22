package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.TrackPath
import com.aicodemax.data.media.TrackPoint

/** CP-80 tracking request: rect in % of frame, range in source ms. */
data class TrackRequest(
    val assetPath: String,
    val x: Int = 40,
    val y: Int = 40,
    val w: Int = 20,
    val h: Int = 20,
    val startMs: Long = 0,
    val endMs: Long = 0,
    /** Sample step in ms (clamped so samples ≤ 48). */
    val stepMs: Long = 250,
)

/** CP-80 stabilize request. */
data class StabRequest(
    val assetPath: String,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val stepMs: Long = 200,
    /** Smoothing window in ms. */
    val smoothMs: Long = 600,
    /** Extra zoom % (null = auto from shake). */
    val zoom: Int? = null,
)

data class TrackAnalysis(
    val path: TrackPath,
    val samples: Int,
    val lost: Int,
    val meanScore: Double,
)

data class StabAnalysis(
    /** Inverse-smoothed correction path (% units, output-relative times). */
    val path: TrackPath,
    val samples: Int,
    val shakePct: Double,
    /** Suggested zoom % to hide borders. */
    val zoom: Int,
)

/**
 * CP-80 motion analysis port (§15/§43). Android decodes real frames;
 * the in-memory fake returns a fixed synthetic path for tests.
 */
interface TrackingPort {
    suspend fun analyzeTrack(request: TrackRequest): Outcome<TrackAnalysis>
    suspend fun analyzeStab(request: StabRequest): Outcome<StabAnalysis>
}

class InMemoryTrackingPort : TrackingPort {
    override suspend fun analyzeTrack(request: TrackRequest): Outcome<TrackAnalysis> {
        if (request.assetPath.isBlank()) {
            return Outcome.Failure(AppError("TRACK_NO_PATH", "ไม่มีไฟล์ให้วิเคราะห์"))
        }
        val end = request.endMs.coerceAtLeast(1000)
        val path = TrackPath(
            listOf(
                TrackPoint(0, 0f, 0f),
                TrackPoint(end / 2, 2f, 1f),
                TrackPoint(end, 4f, 0f),
            ),
        )
        return Outcome.Success(TrackAnalysis(path, samples = 3, lost = 0, meanScore = 0.99))
    }

    override suspend fun analyzeStab(request: StabRequest): Outcome<StabAnalysis> {
        if (request.assetPath.isBlank()) {
            return Outcome.Failure(AppError("STAB_NO_PATH", "ไม่มีไฟล์ให้วิเคราะห์"))
        }
        val end = request.endMs.coerceAtLeast(1000)
        val path = TrackPath(
            listOf(
                TrackPoint(0, 0f, 0f),
                TrackPoint(end / 2, -1f, 0.5f),
                TrackPoint(end, 0f, 0f),
            ),
        )
        return Outcome.Success(StabAnalysis(path, samples = 3, shakePct = 1.1, zoom = request.zoom ?: 8))
    }
}
