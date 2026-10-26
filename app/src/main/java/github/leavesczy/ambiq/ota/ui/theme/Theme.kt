package github.leavesczy.ambiq.ota.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 淡蓝色主调：主色与页面背景均为浅蓝系。
 */
object AppColors {
    val Brand = Color(color = 0xFF5B9BD4)
    val BrandSoft = Color(color = 0xFF7EB0DC)
    val Canvas = Color(color = 0xFFE8F2FA)
    val Success = Color(color = 0xFF2F8F72)
    val Muted = Color(color = 0xFF5E7A94)
    val Shadow = Color(color = 0xFF1A3350)
    val AtmosphereTop = Color(color = 0xFFD6E8F6)
    val AtmosphereBottom = Color(color = 0xFFF0F7FC)
    val SurfaceLowest = Color(color = 0xFFF7FBFF)
    val SurfaceLow = Color(color = 0xFFF0F6FC)
    val Surface = Color(color = 0xFFE4EDF6)
    val SurfaceHigh = Color(color = 0xFFD9E6F2)
    val SurfaceHighest = Color(color = 0xFFCDDCEB)
}

object AppDimens {
    val PagePadding = 20.dp
    val SectionGap = 14.dp
    val CardPadding = 18.dp
    val CardInnerGap = 12.dp
    val TextLineGap = 4.dp
    val ButtonSpacing = 10.dp
    val ListItemGap = 10.dp
    val RadiusXl = 20.dp
    val RadiusLg = 14.dp
    val RadiusMd = 10.dp
    val RadiusSm = 6.dp
    val ProgressHeight = 8.dp
    val ButtonHeight = 48.dp
    val CompactButtonHeight = 38.dp
}

val AppPageAtmosphereBrush = Brush.verticalGradient(
    colors = listOf(
        AppColors.AtmosphereTop,
        AppColors.Canvas,
        AppColors.AtmosphereBottom
    )
)

val CardShape = RoundedCornerShape(size = AppDimens.RadiusLg)
val ButtonShape = RoundedCornerShape(size = AppDimens.RadiusMd)

private val AppColorScheme: ColorScheme = lightColorScheme(
    primary = AppColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(color = 0xFFD4E8F8),
    onPrimaryContainer = Color(color = 0xFF12324F),
    secondary = Color(color = 0xFF5B8ABC),
    onSecondary = Color.White,
    secondaryContainer = Color(color = 0xFFD9E6F2),
    onSecondaryContainer = Color(color = 0xFF16324A),
    tertiary = AppColors.Success,
    onTertiary = Color.White,
    tertiaryContainer = Color(color = 0xFFD4F0E6),
    onTertiaryContainer = Color(color = 0xFF0D3B2E),
    error = Color(color = 0xFFB42318),
    onError = Color.White,
    errorContainer = Color(color = 0xFFFEE4E2),
    onErrorContainer = Color(color = 0xFF7A271A),
    background = AppColors.Canvas,
    onBackground = Color(color = 0xFF15283C),
    surface = AppColors.Canvas,
    onSurface = Color(color = 0xFF15283C),
    surfaceVariant = AppColors.SurfaceHighest,
    onSurfaceVariant = AppColors.Muted,
    outline = Color(color = 0xFF7A8FA3),
    outlineVariant = Color(color = 0xFFB8C9D9),
    surfaceContainerLowest = AppColors.SurfaceLowest,
    surfaceContainerLow = AppColors.SurfaceLow,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceHigh,
    surfaceContainerHighest = AppColors.SurfaceHighest
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.1).sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.1.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 14.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        letterSpacing = (-0.3).sp,
        lineHeight = 32.sp
    )
)

@Composable
fun AmbiqOtaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content
    )
}

@Composable
fun AtmosphereScaffold(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = AppPageAtmosphereBrush),
        content = content
    )
}
