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

@Composable
fun HelpScreen() {
    Text("How to use", fontSize = 26.sp, fontWeight = FontWeight.Bold)

    SectionCard("Gestures") {
        Gesture("🤟", "Hold 1 s", "Turn gesture control on. The screen edge glows. Hold again to turn it off.")
        Gesture("☝️", "Point", "The pointer follows your index fingertip.")
        Gesture("☝️💨", "Quick push towards the camera", "Tap where the pointer is. The pointer freezes during the push so it lands where you aimed.")
        Gesture("🤏", "Quick pinch", "Also a tap.")
        Gesture("🤏", "Pinch, move, release", "Drag / scroll precisely, like a finger on the glass.")
        Gesture("🤏", "Pinch and hold still", "Long press.")
        Gesture("🖐", "Flat hand, palm sideways (0.3 s)", "\"Swipe armed ⟷\": stroke left or right. Stays armed while your hand stays flat.")
        Gesture("🫳", "Flat hand, palm down (0.3 s)", "\"Swipe armed ↕\": stroke up or down.")
        Gesture("👍", "Thumb up, then point", "Recenter: where you point next becomes the middle of the screen.")
        Gesture("👎", "Thumb down", "Back")
        Gesture("✊", "Hold a fist", "Home")
        Gesture("✌️", "Victory", "Recents")
        Muted("Signs can be remapped in Settings. Gesture control turns itself off after 20 s without a hand.")
    }

    SectionCard("Tips for reliable tracking") {
        Muted("• Sit or stand 0.5–2 m from the camera with your head and shoulders in frame.")
        Muted("• Good, even light helps; avoid a bright window behind you.")
        Muted("• Push with a short, brisk jab (5–10 cm); a slow lean does not click.")
        Muted("• Face your palm towards the camera when pinching.")
        Muted("• Swipe strokes should be brisk; the way back is ignored for about a second.")
        Muted("• Use Camera → Live tracking, or turn on the debug HUD, to see what is detected.")
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
