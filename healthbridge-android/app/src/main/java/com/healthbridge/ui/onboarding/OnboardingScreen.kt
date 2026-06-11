package com.healthbridge.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.parser.HealthDataType
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbGhostButton
import com.healthbridge.ui.components.HbPrimaryButton
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.Dimens
import com.healthbridge.ui.theme.SurfaceElevated
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * 3-slide onboarding pager.
 *
 *  1. "Your health data, your device." — iPhone -> HealthBridge -> Health Connect flow.
 *  2. Numbered steps to export an Apple Health archive from iPhone.
 *  3. Grant Health Connect access — shows the 7 supported data-type pills and a button
 *     that launches the permission flow then calls [onFinish].
 *
 * The primary button reads "Next" on slides 1-2 and either "Grant access & continue" or,
 * once permissions are granted, "Continue" on the final slide. Dot indicators + the CTA
 * live in the scaffold footer.
 *
 * @param onFinish invoked once the user has completed the final slide (after permissions).
 * @param healthConnectGranted whether the Health Connect write permissions are already
 *   granted; reflected in slide 3's pills + the final CTA label.
 * @param onRequestPermissions launches the real Health Connect permission sheet; wired into
 *   the slide-3 grant button. Invoked before [onFinish] so the user sees the system prompt,
 *   while still finishing optimistically so the nav graph stays traversable.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    healthConnectGranted: Boolean = false,
    onRequestPermissions: () -> Unit = {},
) {
    val slideCount = 3
    val pagerState = rememberPagerState(pageCount = { slideCount })
    val scope = rememberCoroutineScope()
    val isLastSlide = pagerState.currentPage == slideCount - 1

    HbScaffold(
        title = null,
        footer = {
            DotIndicator(
                count = slideCount,
                selectedIndex = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Dimens.s4),
            )
            val lastSlideLabel =
                if (healthConnectGranted) "Continue" else "Grant access & continue"
            HbPrimaryButton(
                text = if (isLastSlide) lastSlideLabel else "Next",
                onClick = {
                    if (isLastSlide) {
                        // Launch the real Health Connect permission sheet. If it is already
                        // granted, this is effectively a no-op on the system side. We finish
                        // optimistically afterwards so the nav graph remains traversable
                        // regardless of the (async) permission result.
                        if (!healthConnectGranted) {
                            onRequestPermissions()
                        }
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isLastSlide) {
                Spacer(Modifier.height(Dimens.s2))
                HbGhostButton(
                    text = "Skip",
                    onClick = { scope.launch { pagerState.animateScrollToPage(slideCount - 1) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        // NOTE: HbScaffold hosts content inside a vertically-scrolling Column, so the pager
        // is given an explicit height rather than a weight (a weighted/fill child inside a
        // vertical scroll would receive an infinite height constraint and crash).
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
        ) { page ->
            when (page) {
                0 -> SlideIntro()
                1 -> SlideExportSteps()
                else -> SlideGrantAccess(healthConnectGranted = healthConnectGranted)
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Slide 1 — Intro / data-flow                                                                    */
/* ---------------------------------------------------------------------------------------------- */

@Composable
private fun SlideIntro() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.s7),
        verticalArrangement = Arrangement.spacedBy(Dimens.s5),
    ) {
        Text(
            text = "Your health data, your device.",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "HealthBridge runs fully offline. It reads your Apple Health export, " +
                "figures out what is new, and writes only those records into Google " +
                "Health Connect. Nothing leaves this phone.",
            color = TextSecondary,
            fontSize = 16.sp,
        )

        HbCard(modifier = Modifier.fillMaxWidth()) {
            FlowRow()
        }
    }
}

/**
 * iPhone -> HealthBridge -> Health Connect flow row.
 */
@Composable
private fun FlowRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.s2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowNode(icon = Icons.Rounded.PhoneIphone, label = "iPhone")
        FlowConnector()
        FlowNode(icon = Icons.Rounded.Sync, label = "HealthBridge", highlighted = true)
        FlowConnector()
        FlowNode(icon = Icons.Rounded.Sync, label = "Health\nConnect")
    }
}

@Composable
private fun FlowNode(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.s2),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Dimens.cardRadius))
                .background(if (highlighted) TealAccent else SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) BackgroundDark else TealAccent,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FlowConnector() {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
        contentDescription = null,
        tint = TextTertiary,
        modifier = Modifier.size(20.dp),
    )
}

/* ---------------------------------------------------------------------------------------------- */
/* Slide 2 — Export steps                                                                          */
/* ---------------------------------------------------------------------------------------------- */

private data class ExportStep(val title: String, val detail: String)

private val exportSteps = listOf(
    ExportStep("Open the Health app", "On your iPhone, launch the built-in Apple Health app."),
    ExportStep("Tap your profile", "Tap your photo or initials in the top-right corner."),
    ExportStep("Export All Health Data", "Scroll to the bottom and choose \"Export All Health Data\"."),
    ExportStep("Transfer the .zip", "AirDrop, email, or copy the generated export.zip onto this device."),
)

@Composable
private fun SlideExportSteps() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.s7),
        verticalArrangement = Arrangement.spacedBy(Dimens.s4),
    ) {
        Text(
            text = "Export from your iPhone",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Apple Health can package everything into a single archive. Grab it once, " +
                "then bring it here.",
            color = TextSecondary,
            fontSize = 16.sp,
        )

        Spacer(Modifier.height(Dimens.s2))

        exportSteps.forEachIndexed { index, step ->
            NumberedStep(number = index + 1, step = step)
        }
    }
}

@Composable
private fun NumberedStep(number: Int, step: ExportStep) {
    HbCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    color = TealAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s1)) {
                Text(
                    text = step.title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = step.detail,
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Slide 3 — Grant Health Connect access                                                          */
/* ---------------------------------------------------------------------------------------------- */

@Composable
private fun SlideGrantAccess(healthConnectGranted: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.s7),
        verticalArrangement = Arrangement.spacedBy(Dimens.s4),
    ) {
        Text(
            text = "Grant Health Connect access",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "HealthBridge needs write permission for the data types below. " +
                "It only ever writes new records — it never reads back or deletes anything.",
            color = TextSecondary,
            fontSize = 16.sp,
        )

        Spacer(Modifier.height(Dimens.s2))

        HbSectionLabel(text = "Supported data types")
        DataTypePills()

        if (healthConnectGranted) {
            Spacer(Modifier.height(Dimens.s2))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = TealAccent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Permissions granted — you're all set.",
                    color = TealAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * The 7 MVP data types rendered as wrapping chips. Laid out as a manual two-column grid so
 * we avoid depending on the experimental [androidx.compose.foundation.layout.FlowRow] API.
 */
@Composable
private fun DataTypePills() {
    val types = HealthDataType.entries
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s2)) {
        types.chunked(2).forEach { rowTypes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.s2),
            ) {
                rowTypes.forEach { type ->
                    HbChip(text = type.displayName, kind = HbChipKind.Ok)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Dot indicator                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

@Composable
private fun DotIndicator(
    count: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.wrapContentHeight(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (selected) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (selected) TealAccent else SurfaceElevated),
            )
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Preview                                                                                        */
/* ---------------------------------------------------------------------------------------------- */

@Preview(showBackground = true, backgroundColor = 0xFF0A0E1A)
@Composable
private fun OnboardingScreenPreview() {
    OnboardingScreen(onFinish = {})
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0E1A)
@Composable
private fun OnboardingScreenGrantedPreview() {
    OnboardingScreen(
        onFinish = {},
        healthConnectGranted = true,
        onRequestPermissions = {},
    )
}
