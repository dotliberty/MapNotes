package ru.main.explorer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import java.util.function.LongSupplier
import ru.main.explorer.motion.MotionClassifier
import ru.main.explorer.motion.MovementType
import ru.main.explorer.motion.PersonalMotionProfile

/**
 * Main-looper owner: GPS, receiver callbacks and UI commands are serialized, not parallel
 * collectors.
 */
object MotionRuntime {
    private val classifier = MotionClassifier(LongSupplier { SystemClock.elapsedRealtime() })
    private var profile = PersonalMotionProfile()
    private var rawEventLast = -1L
    private var stillLast = -1L
    private var heldType = MovementType.UNKNOWN
    private var manual: MovementType? = null
    private var requestGeneration = 0
    private var activeRequest: PendingIntent? = null
    var recognitionActive = false
        private set

    @JvmStatic
    fun reset(context: Context) {
        classifier.reset()
        rawEventLast = -1L
        stillLast = -1L
        heldType = MovementType.UNKNOWN
        reloadProfile(context)
        setMode(context, mode(context))
    }

    @JvmStatic
    fun reloadProfile(context: Context) {
        profile =
            try {
                MovementStore(context).use { it.profile() }
            } catch (_: Exception) {
                PersonalMotionProfile()
            }
    }

    fun mode(context: Context): MovementType? {
        val value = context.getSharedPreferences("motion", 0).getString("mode", "AUTO")
        return MovementType.values().firstOrNull { it.name == value }
    }

    fun setMode(context: Context, type: MovementType?) {
        require(
            type == null ||
                type in listOf(MovementType.WALK, MovementType.RUN, MovementType.VEHICLE)
        )
        context
            .getSharedPreferences("motion", 0)
            .edit()
            .putString("mode", type?.name ?: "AUTO")
            .apply()
        manual = type
        heldType = MovementType.UNKNOWN
        stillLast = -1L
        classifier.setManual(type)
        if (WalkService.running) {
            if (type == null) start(context)
            else {
                requestGeneration++
                recognitionActive = false
                unregister(context.applicationContext, activeRequest)
                activeRequest = null
            }
        }
    }

    @JvmStatic
    fun type(context: Context, speed: Double): MovementType {
        var result = classifier.current(SystemClock.elapsedRealtime())
        if (manual == null && stopped() && result == MovementType.UNKNOWN) result = heldType
        if (result != MovementType.UNKNOWN) heldType = result
        if (
            manual == null &&
                result == MovementType.ON_FOOT &&
                context.getSharedPreferences("motion", 0).getBoolean("personal", true)
        )
            result = profile.suggest(speed, result)
        return result
    }

    @JvmStatic fun source(): String = if (manual == null) "auto" else "manual"

    @JvmStatic
    fun stopped(): Boolean = stillLast >= 0 && SystemClock.elapsedRealtime() - stillLast <= 15_000L

    fun receive(context: Context, result: ActivityRecognitionResult) {
        if (!WalkService.running || mode(context) != null) return
        val now = SystemClock.elapsedRealtime()
        val event = result.elapsedRealtimeMillis
        if (event <= rawEventLast || event > now || now - event > 15_000L) return
        rawEventLast = event
        // ON_FOOT is a parent class: do not let its high score hide a specific WALK/RUN result.
        val specific =
            result.probableActivities
                .filter { it.type != DetectedActivity.ON_FOOT }
                .maxByOrNull { it.confidence }
        val foot = result.getActivityConfidence(DetectedActivity.ON_FOOT)
        if (specific?.type == DetectedActivity.STILL && specific.confidence >= 60) {
            val previous = classifier.current(now)
            if (previous != MovementType.UNKNOWN) heldType = previous
            stillLast = now
            classifier.stopped(now)
            return
        }
        stillLast = -1L
        val detected =
            when (specific?.type) {
                DetectedActivity.WALKING -> MovementType.WALK
                DetectedActivity.RUNNING -> MovementType.RUN
                DetectedActivity.IN_VEHICLE -> MovementType.VEHICLE
                else -> MovementType.UNKNOWN
            }
        val confidence = specific?.confidence ?: 0
        if (detected != MovementType.UNKNOWN && confidence >= 60) {
            classifier.observe(detected, confidence, now)
        } else if (
            foot >= 60 && (specific == null || specific.type != DetectedActivity.ON_BICYCLE)
        ) {
            classifier.observe(MovementType.ON_FOOT, foot, now)
        } else {
            classifier.observe(MovementType.UNKNOWN, confidence, now)
        }
    }

    fun canRecognize(context: Context): Boolean =
        try {
            (Build.VERSION.SDK_INT < 29 ||
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED) &&
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                    ConnectionResult.SUCCESS
        } catch (_: RuntimeException) {
            false
        }

    private fun pending(context: Context, generation: Int): PendingIntent {
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            generation,
            Intent(context, MotionReceiver::class.java).setAction(context.packageName + ".MOTION"),
            flags,
        )
    }

    @JvmStatic
    fun start(context: Context) {
        val generation = ++requestGeneration
        recognitionActive = false
        val application = context.applicationContext
        unregister(application, activeRequest)
        activeRequest = null
        if (!WalkService.running || mode(application) != null || !canRecognize(application)) return
        val request = pending(application, generation)
        activeRequest = request
        try {
            ActivityRecognition.getClient(application)
                .requestActivityUpdates(5_000L, request)
                .addOnSuccessListener {
                    if (generation == requestGeneration && WalkService.running)
                        recognitionActive = true
                    else unregister(application, request)
                }
                .addOnFailureListener {
                    if (generation == requestGeneration) recognitionActive = false
                }
        } catch (_: Exception) {
            recognitionActive = false
        }
    }

    @JvmStatic
    fun stop(context: Context) {
        requestGeneration++
        recognitionActive = false
        unregister(context.applicationContext, activeRequest)
        activeRequest = null
        classifier.reset()
        rawEventLast = -1L
        stillLast = -1L
        heldType = MovementType.UNKNOWN
    }

    private fun unregister(context: Context, request: PendingIntent?) {
        if (request == null) return
        try {
            ActivityRecognition.getClient(context).removeActivityUpdates(request)
        } catch (_: Exception) {
            // Permission revocation must not prevent stopping GPS recording.
        }
    }
}

class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != context.packageName + ".MOTION" || !WalkService.running) return
        try {
            ActivityRecognitionResult.extractResult(intent)?.let {
                MotionRuntime.receive(context, it)
            }
        } catch (_: RuntimeException) {
            // Ignore malformed/stale provider results, never terminate the recording service.
        }
    }
}
