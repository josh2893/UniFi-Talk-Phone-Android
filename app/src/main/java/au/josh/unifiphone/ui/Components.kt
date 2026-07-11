package au.josh.unifiphone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.josh.unifiphone.core.RegState
import au.josh.unifiphone.ui.theme.DangerRed
import au.josh.unifiphone.ui.theme.SuccessGreen
import au.josh.unifiphone.ui.theme.WarnAmber
import androidx.compose.foundation.clickable

@Composable
fun RegStatusPill(state: RegState, detail: String) {
    val (color, label) = when (state) {
        RegState.OK -> SuccessGreen to "Registered"
        RegState.PROGRESS -> WarnAmber to "Registering"
        RegState.FAILED -> DangerRed to "Failed"
        RegState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant to "Offline"
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private val keypadRows = listOf(
    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    listOf("*" to "", "0" to "+", "#" to ""),
)

@Composable
fun Keypad(onKey: (String) -> Unit, compact: Boolean = false) {
    val keySize = if (compact) 58.dp else 72.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        keypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                row.forEach { (digit, letters) ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(keySize)
                            .clip(CircleShape)
                            .clickable { onKey(digit) },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                digit,
                                fontSize = if (compact) 20.sp else 26.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (letters.isNotEmpty() && !compact) {
                                Text(
                                    letters,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoundActionButton(
    background: Color,
    onClick: () -> Unit,
    size: Int = 68,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
