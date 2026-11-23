package app.aircontrol.service

import android.graphics.Bitmap
import app.aircontrol.gesture.ControlEvent
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

    /** Latest landmarks; only published while someone collects it (overlay skeleton / HUD). */
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
    /** Push-to-click: recent growth of ln(hand scale). */
    val push: Float = 0f,
    /** Flat-hand orientation: V, H or -. */
    val blade: String = "-",
    val handVisible: Boolean = false,
    val bodyVisible: Boolean = false,
)

data class TrackingSnapshot(
    val timestampMs: Long,
    val aspect: Float,
    val hands: List<HandObservation>,
    val pose: PoseObservation?,
)

data class PreviewFrame(val bitmap: Bitmap, val mirrored: Boolean, val snapshot: TrackingSnapshot)
