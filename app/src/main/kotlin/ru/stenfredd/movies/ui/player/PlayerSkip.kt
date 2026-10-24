package ru.stenfredd.movies.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenfredd.movies.core.data.model.MediaSegment
import ru.stenfredd.movies.core.data.model.SegmentKind
import ru.stenfredd.movies.playback.core.PlaybackDefaults
import ru.stenfredd.movies.playback.core.SkipMode
import ru.stenfredd.movies.ui.components.FocusStyle
import ru.stenfredd.movies.ui.components.focusCard
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType
import java.util.UUID

/** The server's segments of one item (they arrive after the item starts playing). */
@Immutable
data class PlayerSegments(val itemId: UUID, val segments: List<MediaSegment>)

/** A segment the player offers to skip ("Пропустить заставку") or skips on its own ([auto]). */
@Immutable
internal data class Skippable(val segment: MediaSegment, val auto: Boolean)

/**
 * What the player does with an item's segments under the settings.
 * [credits]: the credits of an episode with a next one, from their start to the end of the file: the "next episode"
 * card shows from their start instead of 30 s before the end ([creditsAuto]: the next episode starts right away).
 */
@Immutable
internal data class SkipPlan(val skips: List<Skippable>, val credits: MediaSegment?, val creditsAuto: Boolean) {
    /** Parts drawn on the timeline. */
    val spans: List<MediaSegment> = skips.map { it.segment } + listOfNotNull(credits)

    /** The skippable part [positionMs] is in (not counting its last second: nothing left to skip there). */
    fun skipAt(positionMs: Long): Skippable? = skips.firstOrNull { positionMs >= it.segment.startMs && positionMs < it.segment.endMs - SKIP_END_MARGIN_MS }

    /** The part a seek to [positionMs] lands in: such a part is not skipped on its own, the user went there. */
    fun spanAt(positionMs: Long): MediaSegment? = spans.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

    companion object {
        /**
         * Credits (and previews after them) that run to the end of the file count as the end of the episode: with a
         * next episode they bring the "next" card, without one (a movie, the last episode) there is nothing to skip to.
         * Credits followed by more of the episode (an epilogue) get "Пропустить титры".
         */
        fun of(segments: List<MediaSegment>, durationMs: Long, defaults: PlaybackDefaults, hasNext: Boolean): SkipPlan? {
            if (durationMs <= 0) return null
            val useful = segments.filter { it.endMs - it.startMs >= MIN_SEGMENT_MS && it.startMs < durationMs }
            var tail: MediaSegment? = null
            for (s in useful.filter { it.kind == SegmentKind.Outro || it.kind == SegmentKind.Preview }.sortedByDescending { it.startMs }) {
                val reaches = tail?.let { s.endMs >= it.startMs - TAIL_GAP_MS } ?: (s.endMs >= durationMs - TAIL_MARGIN_MS)
                if (!reaches) break
                tail = MediaSegment(SegmentKind.Outro, s.startMs, durationMs)
            }
            val tailStart = tail?.startMs ?: Long.MAX_VALUE
            val skips = useful.filter { it.startMs < tailStart }.mapNotNull { s ->
                val mode = if (s.kind == SegmentKind.Outro) defaults.skipCredits else defaults.skipIntro
                if (mode == SkipMode.Off) null else Skippable(s, auto = mode == SkipMode.Auto)
            }
            val credits = tail?.takeIf { hasNext && defaults.skipCredits != SkipMode.Off }
            return SkipPlan(skips, credits, creditsAuto = defaults.skipCredits == SkipMode.Auto)
        }
    }
}

/** Segments of one item something already happened to; an item change forgets them. */
@Immutable
internal data class SegmentSet(val itemId: UUID? = null, val segments: Set<MediaSegment> = emptySet()) {
    fun of(id: UUID?): Set<MediaSegment> = if (id == itemId) segments else emptySet()

    fun plus(id: UUID?, segment: MediaSegment): SegmentSet = SegmentSet(id, of(id) + segment)
}

internal object SkipLabels {
    fun button(kind: SegmentKind): String = when (kind) {
        SegmentKind.Intro -> "Пропустить заставку"
        SegmentKind.Recap -> "Пропустить пересказ"
        SegmentKind.Preview -> "Пропустить анонс"
        SegmentKind.Outro -> "Пропустить титры"
        SegmentKind.Commercial -> "Пропустить рекламу"
    }

    fun skipped(kind: SegmentKind): String = when (kind) {
        SegmentKind.Intro -> "Заставка пропущена"
        SegmentKind.Recap -> "Пересказ пропущен"
        SegmentKind.Preview -> "Анонс пропущен"
        SegmentKind.Outro -> "Титры пропущены"
        SegmentKind.Commercial -> "Реклама пропущена"
    }
}

private val SkipColor = Color(0xFF10121C)
private val SkipBorder = Color(0x40FFFFFF)
/** `0 0 0 3px #a594ff, 0 0 40px 8px rgba(124,92,255,.65)` */
private val SkipFocus = FocusStyle(scale = 1f, corner = 20.dp, glows = listOf(FocusStyle.Glow(20.dp, 4.dp, Color(0xA67C5CFF))), animate = false)

/**
 * "Пропустить заставку 1:12" (time left of the part) in the corner where the "next episode" card goes. Opaque, as
 * that card: a translucent one over video costs a blend every frame.
 */
@Composable
internal fun SkipButton(label: String, left: String, focusRequester: FocusRequester, onClick: () -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .focusRequester(focusRequester)
            .focusCard(SkipFocus)
            .height(40.dp)
            .background(if (focused) AppColors.FocusFill else SkipColor, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else SkipBorder, shape)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (focused) AppColors.OnFocusFill else Color.White
        BasicText(label, style = AppType.Button.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color))
        BasicText(left, style = AppType.MonoMeta.copy(fontSize = 10.sp, color = color.copy(alpha = 0.7f)))
    }
}

/** "Заставка пропущена" for a few seconds after an automatic skip; not focusable. */
@Composable
internal fun SkipNotice(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    BasicText(
        text,
        modifier
            .background(SkipColor, shape)
            .border(0.5.dp, SkipBorder, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = AppType.MonoLabel.copy(color = AppColors.TextSecondary),
    )
}

/** The button over the bare video hides after this long; it comes back with the controls. */
internal const val SKIP_PROMPT_MS = 10_000L

/** With known credits the "next episode" card counts down this long from their start. */
internal const val CREDITS_COUNTDOWN_MS = 15_000L

internal const val SKIP_NOTICE_MS = 3_000L

private const val SKIP_END_MARGIN_MS = 1_000L
/** Shorter segments are detection noise. */
private const val MIN_SEGMENT_MS = 2_000L
/** Credits ending this close to the end of the file run to the end (black frames after them, rounding). */
private const val TAIL_MARGIN_MS = 10_000L
/** Credits and a preview this far apart still make one tail. */
private const val TAIL_GAP_MS = 2_000L
