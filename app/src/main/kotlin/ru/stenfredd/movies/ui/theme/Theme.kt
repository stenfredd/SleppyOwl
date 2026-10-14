package ru.stenfredd.movies.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The app background is the window background (`values/themes.xml`), drawn once
 * per frame by the system; Compose screens don't paint a background of their own unless they need a different one.
 * The tv-material theme is only for the old prototype screens; new screens use [AppColors]/[AppType].
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
