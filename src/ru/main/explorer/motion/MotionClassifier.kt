package ru.main.explorer.motion

import java.util.function.LongSupplier

/**
 * Session-local, Android-independent hysteresis. All times use the injected monotonic clock's
 * millisecond domain (production: System.nanoTime). Reset after reboot or starting a new session.
 * Confidence is an experimental ordinal gate, not a calibrated probability; values are never
 * summed. Calls must be serialized by the owner. Invalid timestamps/confidence leave all state
 * untouched.
 */
class MotionClassifier
@JvmOverloads
constructor(private val clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }) {
    private var manual: MovementType? = null
    private var confirmed = MovementType.UNKNOWN
    private var candidate = MovementType.UNKNOWN
    private var candidateSince = -1L
    private var candidateLast = -1L
    private var candidateCount = 0
    private var activityLast = -1L
    private var eventLast = -1L
    private var timeLast = -1L

    fun observe(type: MovementType, confidence: Int, elapsedMs: Long): MovementType {
        if (confidence !in 0..100 || !validTime(elapsedMs) || elapsedMs <= eventLast) {
            return value()
        }
        advance(elapsedMs)
        eventLast = elapsedMs
        if (manual != null) {
            return value()
        }
        if (type == MovementType.UNKNOWN || confidence < MIN_CONFIDENCE) {
            clearCandidate()
            return value()
        }
        activityLast = elapsedMs
        if (type == confirmed) {
            clearCandidate()
            return value()
        }
        if (candidate != type || elapsedMs - candidateLast > MAX_CANDIDATE_GAP_MS) {
            candidate = type
            candidateSince = elapsedMs
            candidateCount = 0
        }
        candidateLast = elapsedMs
        candidateCount++
        if (candidateCount >= MIN_OBSERVATIONS && elapsedMs - candidateSince >= SUSTAIN_MS) {
            confirmed = candidate
            clearCandidate()
        }
        return value()
    }

    fun current(elapsedMs: Long): MovementType {
        if (validTime(elapsedMs)) {
            advance(elapsedMs)
        }
        return value()
    }

    /** STILL interrupts a pending transition, but neither changes nor refreshes the prior type. */
    fun stopped(elapsedMs: Long): MovementType {
        if (validTime(elapsedMs) && elapsedMs > eventLast) {
            advance(elapsedMs)
            eventLast = elapsedMs
            clearCandidate()
        }
        return value()
    }

    /**
     * Clearing manual mode requires new automatic evidence; pre-override candidates cannot leak.
     */
    fun setManual(type: MovementType?) {
        manual = type
        confirmed = MovementType.UNKNOWN
        activityLast = -1L
        clearCandidate()
    }

    fun reset() {
        manual = null
        confirmed = MovementType.UNKNOWN
        activityLast = -1L
        eventLast = -1L
        timeLast = -1L
        clearCandidate()
    }

    private fun validTime(time: Long): Boolean =
        time >= 0L && time >= timeLast && time <= clock.asLong

    private fun advance(time: Long) {
        timeLast = time
        if (activityLast >= 0L && time - activityLast >= EXPIRES_MS) {
            confirmed = MovementType.UNKNOWN
            clearCandidate()
        }
        if (candidateLast >= 0L && time - candidateLast > MAX_CANDIDATE_GAP_MS) {
            clearCandidate()
        }
    }

    private fun value(): MovementType = manual ?: confirmed

    private fun clearCandidate() {
        candidate = MovementType.UNKNOWN
        candidateSince = -1L
        candidateLast = -1L
        candidateCount = 0
    }

    companion object {
        const val SUSTAIN_MS = 10_000L
        const val EXPIRES_MS = 60_000L
        const val MAX_CANDIDATE_GAP_MS = 10_000L
        const val MIN_CONFIDENCE = 60
        const val MIN_OBSERVATIONS = 3
    }
}
