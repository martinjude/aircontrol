package app.aircontrol.service

import android.graphics.Bitmap
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.EngineDebug
import app.aircontrol.gesture.HandObservation
import app.aircontrol.gesture.PoseObservation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process hub between the tracking service, the accessibility service and the UI. */
object ControlBus {
    /** Gesture engine output (and test events from the app UI). */
    val events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Coarse tracking status (updated a few times per second). */
    val state = MutableStateFlow(TrackingState())

    /** Latest landmarks + engine state; only published while someone collects it (overlay preview). */
    val snapshot = MutableStateFlow<TrackingSnapshot?>(null)

    /** Latest analysed frame + landmarks; only published while the calibration screen is open. */
    val preview = MutableStateFlow<PreviewFrame?>(null)
}

data class TrackingState(
    val running: Boolean = false,
    val armed: Boolean = false,
    val sourceLabel: String = "",
    val sourceStatus: String = "",
    val sourceError: Boolean = false,
    val fps: Float = 0f,
    val inferenceMs: Long = 0,
    val delegate: String = "",
    val sign: String = "None",
    val pinch: Float = 0f,
    val handVisible: Boolean = false,
    val bodyVisible: Boolean = false,
)

data class TrackingSnapshot(
    val timestampMs: Long,
    val aspect: Float,
    val hands: List<HandObservation>,
    val pose: PoseObservation?,
    /** What the engine decided for this very frame. */
    val debug: EngineDebug,
)

data class PreviewFrame(val bitmap: Bitmap, val mirrored: Boolean, val snapshot: TrackingSnapshot)
