package com.nozzleitall.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.design.NozzleTokens
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.ConnectionRoute
import com.nozzleitall.printer.label

@Composable
fun Txt(text: String, style: TextStyle = Nz.type.body, color: Color = Nz.colors.text, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** A visible focus ring for keyboard users; hover never replaces it. */
fun Modifier.focusRing(focused: Boolean, color: Color, shape: RoundedCornerShape): Modifier =
    if (focused) this.border(NozzleTokens.Border.focus, color, shape) else this

enum class ButtonKind { PRIMARY, SECONDARY, QUIET, DANGER }

@Composable
fun NzButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.SECONDARY, icon: NzIcon? = null,
             enabled: Boolean = true, testTag: String? = null) {
    val c = Nz.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(NozzleTokens.Radius.control)
    val (bg, fg, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(c.accent, c.onAccent, c.accent)
        ButtonKind.DANGER -> Triple(c.danger, c.onDanger, c.danger)
        ButtonKind.SECONDARY -> Triple(if (hovered) c.surfaceRaised else c.surface, c.text, c.lineStrong)
        ButtonKind.QUIET -> Triple(if (hovered) c.surfaceRaised else Color.Transparent, c.text, Color.Transparent)
    }
    Row(modifier.alpha(if (enabled) 1f else 0.45f).heightIn(min = 36.dp).clip(shape).background(bg).border(1.dp, border, shape).focusRing(focused, c.focus, shape)
        .hoverable(interaction).clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .semantics { if (testTag != null) this.testTag = testTag }
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, fg, 18.dp)
        Txt(text, Nz.type.label, fg)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, raised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Nz.colors
    val shape = RoundedCornerShape(NozzleTokens.Radius.card)
    Column(modifier.clip(shape).background(if (raised) c.surfaceRaised else c.surface).border(1.dp, c.line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

fun stateIcon(state: PrinterState) = when (state) {
    PrinterState.OFFLINE -> NzIcon.OFFLINE
    PrinterState.CONNECTING, PrinterState.STARTING -> NzIcon.NETWORK
    PrinterState.READY -> NzIcon.CHECK
    PrinterState.PRINTING -> NzIcon.TOOLHEAD
    PrinterState.PAUSED -> NzIcon.PAUSE
    PrinterState.FINISHED -> NzIcon.CHECK
    PrinterState.CANCELLED -> NzIcon.STOP
    PrinterState.ERROR -> NzIcon.ALERT
    PrinterState.UNKNOWN -> NzIcon.QUESTION
}

/** State is always shown as colour + icon + words, never colour alone. */
@Composable
fun StatusPill(state: PrinterState, modifier: Modifier = Modifier) {
    val color = Nz.status.forState(state.glossaryId)
    Row(modifier.clip(RoundedCornerShape(NozzleTokens.Radius.chip)).border(1.dp, color, RoundedCornerShape(NozzleTokens.Radius.chip))
        .padding(horizontal = 10.dp, vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Printer state: ${state.label}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(stateIcon(state), color, 14.dp)
        Txt(state.label, Nz.type.label, color)
    }
}

@Composable
fun RouteBadge(route: ConnectionRoute) {
    val c = Nz.colors
    val icon = when (route) { ConnectionRoute.LAN -> NzIcon.NETWORK; ConnectionRoute.PRIVATE_NETWORK -> NzIcon.PRIVATE_NETWORK; ConnectionRoute.VENDOR_CLOUD -> NzIcon.CLOUD
        ConnectionRoute.NONE -> return } // export-only printers have no connection to show
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, c.textMuted, 14.dp)
        Txt(route.label, Nz.type.bodySmall, c.textMuted)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color = Nz.colors.accent, modifier: Modifier = Modifier, label: String = "Progress") {
    val c = Nz.colors
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceSunken)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f); contentDescription = label }) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

enum class BannerKind { INFO, WARNING, DANGER, SUCCESS }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, action: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier) {
    val c = Nz.colors
    val s = Nz.status
    val (color, icon) = when (kind) { BannerKind.INFO -> c.accent to NzIcon.QUESTION; BannerKind.WARNING -> s.paused to NzIcon.ALERT
        BannerKind.DANGER -> c.danger to NzIcon.ALERT; BannerKind.SUCCESS -> s.finished to NzIcon.CHECK }
    val shape = RoundedCornerShape(NozzleTokens.Radius.control)
    Row(modifier.fillMaxWidth().clip(shape).background(c.surfaceRaised).border(1.dp, color, shape).padding(12.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, color, 20.dp)
        Txt(text, Nz.type.body, c.text, Modifier.weight(1f))
        action?.let { (label, fn) -> NzButton(label, fn, kind = ButtonKind.SECONDARY) }
    }
}

@Composable
fun EmptyState(icon: NzIcon, title: String, body: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Nz.colors
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceRaised), contentAlignment = Alignment.Center) { Icon(icon, c.accent, 30.dp) }
        Txt(title, Nz.type.title)
        Txt(body, Nz.type.body, c.textMuted, Modifier.widthIn(max = 520.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", secret: Boolean = false,
          hint: String? = null, error: String? = null, focusRequester: FocusRequester? = null, onSubmit: (() -> Unit)? = null) {
    val c = Nz.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NozzleTokens.Radius.small)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, Nz.type.label, c.text)
        Box(Modifier.fillMaxWidth().clip(shape).background(c.surfaceSunken).border(if (focused) 2.dp else 1.dp, if (error != null) c.danger else if (focused) c.focus else c.lineStrong, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) Txt(placeholder, Nz.type.body, c.textMuted)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label }.let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
                .onPreviewKeyEvent { e -> if (onSubmit != null && e.type == KeyEventType.KeyDown && e.key == Key.Enter) { onSubmit(); true } else false },
                textStyle = Nz.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent), singleLine = true, interactionSource = interaction,
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions.Default)
        }
        if (error != null) Txt(error, Nz.type.bodySmall, c.danger) else if (hint != null) Txt(hint, Nz.type.bodySmall, c.textMuted)
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    val c = Nz.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).focusRing(focused, c.focus, RoundedCornerShape(8.dp))
        .toggleable(checked, interaction, null, role = Role.Switch, onValueChange = onChange).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (checked) c.accent else c.surfaceSunken).border(1.dp, c.lineStrong, RoundedCornerShape(12.dp))) {
            Box(Modifier.padding(3.dp).size(18.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).clip(RoundedCornerShape(9.dp)).background(if (checked) c.onAccent else c.textMuted))
        }
        Column(Modifier.weight(1f)) {
            Txt(label, Nz.type.body)
            description?.let { Txt(it, Nz.type.bodySmall, c.textMuted) }
        }
    }
}

/**
 * The confirmation every printer-changing action goes through. The confirm button names the action; Escape cancels;
 * focus starts on Cancel so Enter never confirms by accident.
 */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, destructive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, detail: String? = null) {
    DialogWindow(onCloseRequest = onDismiss, title = title, state = rememberDialogState(width = 460.dp, height = 300.dp), resizable = false,
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false }) {
        val c = Nz.colors
        val cancelFocus = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(title, Nz.type.title)
            Txt(body, Nz.type.body, c.text)
            detail?.let { Txt(it, Nz.type.bodySmall, c.textMuted) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Box(Modifier.focusRequester(cancelFocus).focusable()) { NzButton("Don't send", onDismiss, kind = ButtonKind.SECONDARY) }
                NzButton(confirmLabel, onConfirm, kind = if (destructive) ButtonKind.DANGER else ButtonKind.PRIMARY, testTag = "confirm-action")
            }
        }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, Nz.type.headline)
            subtitle?.let { Txt(it, Nz.type.body, Nz.colors.textMuted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Nz.colors.text) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Txt(label, Nz.type.bodySmall, Nz.colors.textMuted)
        Txt(value, Nz.type.metric, color)
    }
}
