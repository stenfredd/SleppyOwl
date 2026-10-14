package ru.stenfredd.movies.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppDimens
import ru.stenfredd.movies.ui.theme.AppType

/** What a poster card shows. Built by view models from server data; composables never see SDK DTOs. */
@Immutable
data class PosterModel(
    val key: String,
    val title: String,
    val caption: String?,
    val image: RemoteImageSpec?,
    /** Format badge at the bottom left: "4K", "1080p". */
    val badge: String? = null,
    /** Watch progress 0..1, or null. */
    val progress: Float? = null,
    val isNew: Boolean = false,
    val watched: Boolean = false,
)

/** Episode still or backdrop with a title over it: "Continue watching" cards. */
@Immutable
data class WideModel(
    val key: String,
    val title: String,
    val subtitle: String,
    val image: RemoteImageSpec?,
    val progress: Float?,
)

private val PosterCorner = 7.dp
private val WideCorner = 8.dp
private val FeaturedCorner = 11.dp

private val WideShade = ImageShade(0.7f, listOf(0f to Color(0xF006070C), 0.75f to Color(0x4006070C), 1f to Color.Transparent))
private val FeaturedShade = ImageShade(0.8f, listOf(0f to Color(0xF506070C), 0.6f to Color(0x9906070C), 1f to Color.Transparent))

@Composable
fun PosterCard(model: PosterModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(AppDimens.POSTER_W.dp)) {
        Box(
            Modifier
                .focusCard(FocusStyle.Poster)
                .size(AppDimens.POSTER_W.dp, AppDimens.POSTER_H.dp)
                .clickable(interactionSource = null, indication = null, onClick = onClick),
        ) {
            RemoteImage(model.image, PosterCorner, Modifier.fillMaxSize())
            if (model.isNew) NewBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            if (model.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            // The mockup's bottom shade under the badge costs a card-sized blend; a small dark plate does the same job.
            model.badge?.let {
                BasicText(
                    it,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0x9906070C), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = AppType.MonoCaption.copy(color = Color(0xD9FFFFFF)),
                )
            }
            model.progress?.let {
                ProgressLine(
                    it,
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 5.dp).fillMaxWidth().height(2.5.dp),
                )
            }
        }
        BasicText(
            model.title,
            Modifier.padding(top = 8.dp),
            style = AppType.PosterTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        model.caption?.let {
            BasicText(it, Modifier.padding(top = 3.dp), style = AppType.MonoCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun WideCard(model: WideModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .focusCard(FocusStyle.Wide)
            .size(AppDimens.WIDE_W.dp, AppDimens.WIDE_H.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        RemoteImage(model.image, WideCorner, Modifier.fillMaxSize(), shade = WideShade)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 11.dp)) {
            BasicText(model.title, style = AppType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                model.subtitle,
                Modifier.padding(top = 3.dp),
                style = AppType.MonoMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        model.progress?.let {
            ProgressLine(it, Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 5.dp).fillMaxWidth().height(3.dp))
        }
    }
}

/** The big first "Continue watching" card on the home screen. */
@Immutable
data class FeaturedModel(
    val key: String,
    val label: String,
    val title: String,
    val subtitle: String,
    val description: String?,
    val image: RemoteImageSpec?,
    val progress: Float,
    val remaining: String?,
)

@Composable
fun FeaturedCard(model: FeaturedModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .focusCard(FocusStyle.Featured)
            .size(410.dp, 230.5.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        RemoteImage(model.image, FeaturedCorner, Modifier.fillMaxSize(), shade = FeaturedShade)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            BasicText(model.label, style = AppType.MonoLabel)
            BasicText(
                model.title,
                Modifier.padding(top = 5.dp),
                style = AppType.Hero,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(model.subtitle, Modifier.padding(top = 4.dp), style = AppType.BodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            model.description?.let {
                BasicText(
                    it,
                    Modifier.padding(top = 7.dp).widthIn(max = 350.dp),
                    style = AppType.Body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayPill("Продолжить")
                Spacer(Modifier.width(10.dp))
                ProgressLine(model.progress, Modifier.weight(1f).height(3.dp))
                model.remaining?.let {
                    Spacer(Modifier.width(10.dp))
                    BasicText(it, style = AppType.MonoMeta.copy(fontSize = AppType.MonoCount.fontSize))
                }
            }
        }
    }
}

/** Non-focusable gradient "▶ label" pill, part of a larger focusable card. */
@Composable
fun PlayPill(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(28.dp)
            .background(AppColors.PrimaryGradient, RoundedCornerShape(14.dp))
            .padding(start = 9.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlayIcon(Modifier.size(12.dp))
        BasicText(label, style = AppType.Button)
    }
}

/** Rounded track with a gradient fill, one draw call, no clipping. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val r = CornerRadius(size.height / 2)
            val fill = size.width * fraction.coerceIn(0f, 1f)
            onDrawBehind {
                drawRoundRect(AppColors.ProgressTrack, cornerRadius = r)
                if (fill > 0f) drawRoundRect(AppColors.ProgressGradient, size = Size(fill, size.height), cornerRadius = r)
            }
        },
    )
}

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    BasicText(
        "НОВОЕ",
        modifier
            .background(AppColors.NewBadge, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.5.dp, vertical = 2.dp),
        style = AppType.MonoBadge.copy(color = Color.White),
    )
}

@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(18.dp)
            .background(Color(0x2E7EE0A3), CircleShape)
            .border(0.5.dp, AppColors.Green, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CheckIcon(Modifier.size(9.dp), AppColors.Green)
    }
}
