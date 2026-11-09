package ru.main.explorer.motion

import kotlin.math.abs
import kotlin.math.max

/** Plain persistence DTO. Import validates it exactly like a fresh explicit confirmation. */
class ConfirmedMotionSample(
    val id: String,
    val type: MovementType,
    speeds: List<Double>,
    val durationMs: Long,
    val accuracy: Double,
) {
    val speeds: List<Double> = speeds.toList()
}

/**
 * Learns only through explicit confirm/import calls, never through suggest or sensor observations.
 * IDs must identify independent, non-overlapping segments: independence cannot be inferred from
 * IDs. Rejected confirmations do not modify an existing sample; use remove to retract it
 * explicitly. Experimental quality/readiness constants are not universal walking/running
 * boundaries.
 */
class PersonalMotionProfile {
    private val samples = linkedMapOf<String, ConfirmedMotionSample>()

    val walkCount: Int
        get() = samples.values.count { it.type == MovementType.WALK }

    val runCount: Int
        get() = samples.values.count { it.type == MovementType.RUN }

    val ready: Boolean
        get() = walkCount >= MIN_SEGMENTS && runCount >= MIN_SEGMENTS

    fun confirm(
        id: String,
        type: MovementType,
        speeds: List<Double>,
        durationMs: Long,
        accuracy: Double,
    ): Boolean {
        if (id.isBlank() || type !in listOf(MovementType.WALK, MovementType.RUN)) {
            return false
        }
        if (
            durationMs < MIN_DURATION_MS ||
                !accuracy.isFinite() ||
                accuracy < 0.0 ||
                accuracy > MAX_ACCURACY_METERS ||
                speeds.isEmpty()
        ) {
            return false
        }
        if (speeds.any { !validSpeed(it) }) {
            return false
        }
        // Equal quantile contributions prevent a long/high-frequency segment dominating the
        // profile.
        val sorted = speeds.sorted()
        val capped =
            (0 until SAMPLES_PER_SEGMENT).map { index ->
                sorted[index * (sorted.size - 1) / (SAMPLES_PER_SEGMENT - 1)]
            }
        samples[id] = ConfirmedMotionSample(id, type, capped, durationMs, accuracy)
        return true
    }

    fun remove(id: String): Boolean = samples.remove(id) != null

    fun reset() {
        samples.clear()
    }

    /**
     * Unknown outside both robust envelopes, or inside their overlap. Never extrapolates a cutoff.
     */
    fun suggest(speed: Double): MovementType {
        if (!ready || !validSpeed(speed)) {
            return MovementType.UNKNOWN
        }
        val walk = envelope(MovementType.WALK).contains(speed)
        val run = envelope(MovementType.RUN).contains(speed)
        return when {
            walk && !run -> MovementType.WALK
            run && !walk -> MovementType.RUN
            else -> MovementType.UNKNOWN
        }
    }

    /** Optional integration helper: a specific sensor label always wins over speed evidence. */
    fun suggest(speed: Double, sensorType: MovementType): MovementType {
        if (sensorType != MovementType.UNKNOWN && sensorType != MovementType.ON_FOOT) {
            return sensorType
        }
        val suggestion = suggest(speed)
        return if (suggestion == MovementType.UNKNOWN) sensorType else suggestion
    }

    /**
     * Bounded, detached samples; serialization format and storage are the caller's responsibility.
     */
    fun exportSamples(): List<ConfirmedMotionSample> =
        samples.values.map {
            ConfirmedMotionSample(it.id, it.type, it.speeds, it.durationMs, it.accuracy)
        }

    /** Replace the profile atomically; invalid or duplicate IDs reject the entire import. */
    fun importSamples(data: List<ConfirmedMotionSample>): Boolean {
        val replacement = PersonalMotionProfile()
        for (sample in data) {
            if (
                replacement.samples.containsKey(sample.id) ||
                    !replacement.confirm(
                        sample.id,
                        sample.type,
                        sample.speeds,
                        sample.durationMs,
                        sample.accuracy,
                    )
            ) {
                return false
            }
        }
        samples.clear()
        samples.putAll(replacement.samples)
        return true
    }

    private fun envelope(type: MovementType): ClosedFloatingPointRange<Double> {
        val speeds = samples.values.filter { it.type == type }.flatMap { it.speeds }.sorted()
        val center = median(speeds)
        val deviation = median(speeds.map { abs(it - center) }.sorted())
        val radius = max(3.0 * deviation, max(0.15 * center, 0.1))
        return (center - radius)..(center + radius)
    }

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun validSpeed(speed: Double): Boolean =
        speed.isFinite() && speed > 0.0 && speed <= MAX_SPEED_METERS_PER_SECOND

    companion object {
        const val MIN_SEGMENTS = 3
        const val MIN_DURATION_MS = 60_000L
        const val MAX_ACCURACY_METERS = 20.0
        const val MAX_SPEED_METERS_PER_SECOND = 15.0
        const val SAMPLES_PER_SEGMENT = 9
    }
}
