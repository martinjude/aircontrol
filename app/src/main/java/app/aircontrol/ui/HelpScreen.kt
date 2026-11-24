package app.aircontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aircontrol.gesture.ClickMethod
import app.aircontrol.gesture.SystemAction
import app.aircontrol.settings.AppSettings

@Composable
fun HelpScreen(settings: AppSettings) {
    PageTitle("How to use")

    SectionCard("Gestures") {
        Gesture("🤟", "Hold the arm sign for 1 s", "Turns gesture control on; the screen edge glows. Hold it again to turn it off.")
        Gesture("✋", "Move your hand", "The cursor follows the centre of your palm. Keep the palm facing the screen: the edges turn orange, then red, when it turns away.")
        if (settings.clickMethod != ClickMethod.PUSH) {
            Gesture("👌", "Touch thumb and index", "A quick touch clicks where the cursor was. Keep them together to hold: move to drag or scroll, stay still for a long press. Two arcs close on the cursor as your fingers meet.")
        }
        if (settings.clickMethod != ClickMethod.TAP) {
            Gesture("👉", "Push toward the screen", "Air push. A glow under the cursor grows as your hand nears an invisible plane; pushing through it touches the screen. Pull back to release. While pushed in, the cursor moves slowly for precise drags.")
        }
        if (settings.swipesEnabled) {
            Gesture("🖐", "Still open palm, then flick", "Swipe left, right, up or down. The way back is ignored for a moment.")
        }
        Muted("After any pose (a bind or 🤟), taps, pushes and swipes wait a moment, so changing back to pointing never clicks. Gesture control turns itself off after ${settings.autoDisarmSec} s without a hand.")
    }

    SectionCard("The cursor") {
        Muted("A full ring means the pointer is trusted. A thin arc around it shows the confidence when it drops: palm turned away, shaky tracking, or the hand at the edge of the frame.")
        Muted("A green dot above the ring means your eyes are on the screen (after the gaze calibration).")
    }

    SectionCard("Your binds") {
        val active = settings.binds.filter { it.enabled && it.action != SystemAction.NONE }
        if (active.isEmpty()) Muted("No binds. Add some on the Binds tab.")
        active.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(80.dp)) { TriggerGlyphs(b) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(b.actionLabel, style = AirType.BodyStrong, color = AirColors.Text)
                    Muted(describeBind(b))
                }
            }
        }
    }

    SectionCard("Tips for reliable tracking") {
        Muted("Stay 0.5 to 2 m from the camera with your head and shoulders in view.")
        Muted("Even light helps. Avoid a bright window behind you.")
        Muted("Run Settings, Calibration, Calibrate everything once in your usual spot: hand angle, air plane and gaze.")
        Muted("If clicks still fire by accident, set Strictness to Strict or lengthen the pause after a pose.")
        Muted("To see why a click was or wasn't accepted, set Settings, Look, Tracking view to Diagnostics. Its event log lists every rejection with the reason.")
    }

    SectionCard("Network camera") {
        Muted("Use an old phone or an IP camera on the same Wi‑Fi, facing you. With the free IP Webcam app, start the server and enter http://<phone IP>:8080/video on the Camera tab.")
        Muted("MJPEG streams have the lowest latency. RTSP works too but usually lags more.")
    }

    SectionCard("Troubleshooting") {
        Muted("Accessibility switch greyed out (\"Restricted setting\"): App info, then ⋮, then Allow restricted settings.")
        Muted("Samsung: if installing the APK is blocked, turn off Settings, Security, Auto Blocker for a moment.")
        Muted("Tracking stops in the background: set Battery to Unrestricted for AirControl.")
        Muted("Taps do nothing: check that the AirControl accessibility service is still on; some updates turn it off.")
        Muted("Camera busy: another app is using it. AirControl resumes when it is free.")
    }

    SectionCard("Privacy") {
        Muted("Video is analysed on this device only and never stored or sent anywhere. The accessibility service only injects gestures and draws the overlay; it does not read what is on your screen.")
    }
}

@Composable
private fun Gesture(emoji: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Emoji(emoji, 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AirType.BodyStrong, color = AirColors.Text)
            Muted(desc)
        }
    }
}
