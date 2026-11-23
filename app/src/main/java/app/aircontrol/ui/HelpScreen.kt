package app.aircontrol.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import app.aircontrol.gesture.SystemAction
import app.aircontrol.settings.AppSettings

@Composable
fun HelpScreen(settings: AppSettings) {
    Text("How to use", fontSize = 26.sp, fontWeight = FontWeight.Bold)

    SectionCard("Gestures") {
        Gesture("🤟", "Hold 1 s", "Turn gesture control on. The screen edge glows. Hold again to turn it off.")
        Gesture("🖐", "Move your hand", "The cursor sits on the centre of your palm. The middle of the camera view is the middle of the screen and the corners of the view are the corners of the screen.")
        Gesture("☝️", "Curl and release the index finger", "Click: bend your index finger and straighten it again, quickly. The click fires on the release, where the cursor was.")
        Gesture("🤏", "Quick pinch, hand upright", "Also a tap. Hold your hand upright (fingers up): a horizontal hand does not pinch.")
        Gesture("🤏", "Pinch, move, release", "Drag / scroll precisely, like a finger on the glass.")
        Gesture("🤏", "Pinch and hold still", "Long press.")
        Gesture("🖐", "Flat hand, palm sideways (0.3 s)", "\"Swipe armed ⟷\": stroke left or right. Stays armed while your hand stays flat.")
        Gesture("🫳", "Flat hand, palm down (0.3 s)", "\"Swipe armed ↕\": stroke up or down.")
        Muted("Gesture control turns itself off after ${settings.autoDisarmSec} s without a hand.")
    }

    SectionCard("Your binds") {
        val active = settings.binds.filter { it.enabled && it.action != SystemAction.NONE }
        if (active.isEmpty()) Muted("No binds. Add some on the Binds tab.")
        active.forEach { Gesture(it.triggerLabel, it.actionLabel, describeBind(it)) }
    }

    SectionCard("Tips for reliable tracking") {
        Muted("• Sit or stand 0.5–2 m from the camera with your head and shoulders in frame.")
        Muted("• Good, even light helps; avoid a bright window behind you.")
        Muted("• Pinch with your hand upright, thumb and index facing the camera.")
        Muted("• Click with a quick curl-and-release of the index finger; a slow curl (like making a fist) never clicks.")
        Muted("• Swipe strokes should be brisk; the way back is ignored for about a second.")
        Muted("• Use Camera → Live tracking, or Settings → Look → Live tracking preview (on top of any app), to see what is detected and why a click was or wasn't recognised.")
    }

    SectionCard("Network camera") {
        Muted("Use an old phone or an IP camera on the same Wi‑Fi, facing you. With the free \"IP Webcam\" Android app: start the server, then enter http://<phone IP>:8080/video on the Camera tab.")
        Muted("MJPEG streams have the lowest latency. RTSP works too but usually lags more.")
    }

    SectionCard("Troubleshooting") {
        Muted("• Accessibility switch greyed out (\"Restricted setting\"): App info → ⋮ → Allow restricted settings.")
        Muted("• Samsung: if installing the APK is blocked, turn off Settings → Security → Auto Blocker temporarily.")
        Muted("• Tracking stops in the background: set Battery to Unrestricted for AirControl.")
        Muted("• Taps do nothing: check that the AirControl accessibility service is still on (some updates turn it off).")
        Muted("• Camera busy: another app (video call, camera) is using it; AirControl resumes when it is free.")
    }

    SectionCard("Privacy") {
        Muted("Video is analysed on this device only and never stored or sent anywhere. The accessibility service only injects gestures and draws the overlay; it does not read what is on your screen.")
    }
}

@Composable
private fun Gesture(icon: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 28.sp, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Muted(desc)
        }
    }
}
