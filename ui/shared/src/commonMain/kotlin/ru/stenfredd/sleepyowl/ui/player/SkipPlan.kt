package ru.stenfredd.sleepyowl.ui.player

import androidx.compose.runtime.Immutable
import ru.stenfredd.sleepyowl.core.data.model.MediaSegment
import ru.stenfredd.sleepyowl.core.data.model.SegmentKind
import ru.stenfredd.sleepyowl.playback.core.PlaybackDefaults
import ru.stenfredd.sleepyowl.playback.core.SkipMode
import java.util.UUID

/** The server's segments of one item (they arrive after the item starts playing). */
@Immutable
data class PlayerSegments(val itemId: UUID, val segments: List<MediaSegment>)

/** A segment the player offers to skip ("Пропустить заставку") or skips on its own ([auto]). */
@Immutable
data class Skippable(val segment: MediaSegment, val auto: Boolean)

/**
 * What the player does with an item's segments under the settings.
 * [credits]: the credits of an episode with a next one, from their start to the end of the file: the "next episode"
 * card shows from their start instead of 30 s before the end ([creditsAuto]: the next episode starts right away).
 */
@Immutable
data class SkipPlan(val skips: List<Skippable>, val credits: MediaSegment?, val creditsAuto: Boolean) {
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
data class SegmentSet(val itemId: UUID? = null, val segments: Set<MediaSegment> = emptySet()) {
    fun of(id: UUID?): Set<MediaSegment> = if (id == itemId) segments else emptySet()

    fun plus(id: UUID?, segment: MediaSegment): SegmentSet = SegmentSet(id, of(id) + segment)
}

object SkipLabels {
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

/** The button over the bare video hides after this long; it comes back with the controls. */
const val SKIP_PROMPT_MS = 10_000L

/** With known credits the "next episode" card counts down this long from their start. */
const val CREDITS_COUNTDOWN_MS = 15_000L

const val SKIP_NOTICE_MS = 3_000L

private const val SKIP_END_MARGIN_MS = 1_000L
/** Shorter segments are detection noise. */
private const val MIN_SEGMENT_MS = 2_000L
/** Credits ending this close to the end of the file run to the end (black frames after them, rounding). */
private const val TAIL_MARGIN_MS = 10_000L
/** Credits and a preview this far apart still make one tail. */
private const val TAIL_GAP_MS = 2_000L
