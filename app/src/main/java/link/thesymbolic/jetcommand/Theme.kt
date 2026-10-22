package link.thesymbolic.jetcommand

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed, not dynamic, and dark whatever the system says: it's read in a dark bedroom. Muted blue-greys, no reds.
private val Night = darkColorScheme(
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

@Composable
fun JetCommandTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Night, content = content)
