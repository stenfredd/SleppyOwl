package ru.stenfredd.sleepyowl.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.ui.details.TrackOption

/** The player's side panels. */
enum class PlayerPanel { Audio, Subtitles, Quality, Info }

/** One choice in a panel list or chip row; the screen applies the pick. */
@Immutable
data class PanelChoice(val key: String, val label: String, val detail: String?, val selected: Boolean, val swatch: Color? = null)

@Immutable
data class PanelGroup(val key: String, val title: String, val choices: List<PanelChoice>)

/** Size, colour and background choices of text subtitles, for the player's panel and the series tray. */
object SubtitleChoices {
    private val colors = listOf(SubtitleStyle.WHITE to "Белый", SubtitleStyle.YELLOW to "Жёлтый", SubtitleStyle.LIGHT_BLUE to "Голубой")
    private val backgrounds = listOf(SubtitleStyle.Background.NONE to "Нет", SubtitleStyle.Background.SHADOW to "Тень", SubtitleStyle.Background.BOX to "Плашка")

    fun sizes(style: SubtitleStyle) = SubtitleStyle.Size.entries.map { PanelChoice("size/$it", it.name, null, it == style.size) }

    fun colors(style: SubtitleStyle) = colors.map { (argb, name) -> PanelChoice("color/$argb", name, null, argb == style.color, Color(argb)) }

    fun backgrounds(style: SubtitleStyle) = backgrounds.map { (bg, name) -> PanelChoice("bg/$bg", name, null, bg == style.background) }

    /** [style] with [choice] (one of the lists above) applied. */
    fun apply(style: SubtitleStyle, choice: PanelChoice): SubtitleStyle {
        val value = choice.key.substringAfter('/')
        return when (choice.key.substringBefore('/')) {
            "size" -> style.copy(size = SubtitleStyle.Size.valueOf(value))
            "color" -> style.copy(color = value.toInt())
            "bg" -> style.copy(background = SubtitleStyle.Background.valueOf(value))
            else -> style
        }
    }
}

fun List<TrackOption>.toChoices(selected: Int?): List<PanelChoice> =
    map { PanelChoice(it.key, it.label, it.detail, it.index == selected) }
