package link.thesymbolic.jetcommand

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import link.thesymbolic.jetcommand.protocol.Mode

// Fixed, not dynamic, and dark whatever the system says: it's read in a dark bedroom. Muted
// blue-greys, no reds.
private val Night =
    darkColorScheme(
        primary = Color(0xFF8FB4D9),
        onPrimary = Color(0xFF0B1A28),
        primaryContainer = Color(0xFF1E3347),
        onPrimaryContainer = Color(0xFFCFE2F5),
        secondary = Color(0xFF9AB0A8),
        onSecondary = Color(0xFF0E1C18),
        secondaryContainer = Color(0xFF22302C),
        onSecondaryContainer = Color(0xFFD2E4DD),
        background = Color(0xFF07090C),
        onBackground = Color(0xFFC8CDD3),
        surface = Color(0xFF07090C),
        onSurface = Color(0xFFC8CDD3),
        surfaceVariant = Color(0xFF1A1F25),
        onSurfaceVariant = Color(0xFF9AA2AB),
        surfaceContainer = Color(0xFF101418),
        inverseSurface = Color(0xFF2A3038),
        inverseOnSurface = Color(0xFFD5DADF),
        inversePrimary = Color(0xFF8FB4D9),
        outline = Color(0xFF3A424B),
    )

// Fixed colours outside the scheme; one token per value, shared where screens used the same one.
val Muted = Color(0xFF9AA2AB)
/** Synced slots and fan lines. */
val Sage = Color(0xFF9AB0A8)
val Amber = Color(0xFFE8B04A)
val AmberBg = Color(0xFF2A2213)
val AmberLine = Color(0xFF5A4520)
val AmberText = Color(0xFFC8B48A)
val CalloutBg = Color(0xFF2A2310)
val CalloutText = Color(0xFFD9CBA8)
val Warm = Color(0xFFF0A070)
val WarmBg = Color(0xFF2A1D14)
val WarmLine = Color(0xFF5A3420)
/** The one red outside the modes: something the unit will refuse. */
val Refused = Color(0xFFF28B82)
val RoomLine = Color(0xFFC8CDD3)
val TargetLine = Color(0xFF8FB4D9)
val AirLine = Color(0xFF4C9AFF)
val ScrubLine = Color(0xFFCFE2F5)
val NowLine = Color(0xFFE8EAED)
/** Chart grid lines, and the ground under the not-recorded hatch. */
val ChartGrid = Color(0xFF1A1F25)
val HatchStripe = Color(0xFF3A2E14)
val AxisLine = Color(0xFF3A424B)
val TickLabel = Color(0xFF6B737D)
val WaitStub = Color(0xFF4A515C)
/** Text on a filled mode button. */
val OnModeFill = Color(0xFF0E1116)

/** Cold to hot, blue to red, so the modes read as a scale wherever they appear. */
fun modeColor(mode: Mode): Color =
    when (mode) {
        Mode.COOL -> Color(0xFF4C9AFF)
        Mode.DRY -> Color(0xFF9C8CE0)
        Mode.EXT_HEAT -> Color(0xFFE8B04A)
        Mode.HEAT -> Color(0xFFF0803C)
        Mode.TURBO -> Color(0xFFF0473F)
        Mode.STANDBY,
        Mode.WAIT -> Color(0xFF6B737D)
    }

@Composable
fun JetCommandTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Night, content = content)
