package com.nozzleitall.desktop.screens

import androidx.compose.runtime.*
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one path from a button to the printer: prepare against the state the user sees, confirm in a dialog that names
 * the action, execute exactly once, and report the outcome in plain words. An unknown outcome blocks further commands
 * (ActionGuard) until "Check printer" has re-read the state.
 */
class ActionFlow(private val entry: PrinterEntry) {
    var pending by mutableStateOf<PreparedAction?>(null)
    var message by mutableStateOf<Pair<String, BannerKind>?>(null)
    var busy by mutableStateOf(false)

    fun request(action: PrinterAction) {
        val guard = entry.guard ?: run { message = "${entry.config.identity.displayName} isn't connected." to BannerKind.WARNING; return }
        pending = try { guard.prepare(action, entry.status.value) } catch (e: ActionRefused) { message = (e.message ?: "Not available now.") to BannerKind.WARNING; null }
    }

    suspend fun confirm() {
        val prepared = pending ?: return
        val guard = entry.guard ?: return
        pending = null; busy = true
        val outcome = withContext(Dispatchers.IO) {
            try { guard.execute(prepared, guard.confirm(prepared)) } catch (e: ActionRefused) { ActionOutcome.Rejected(e.message ?: "Not sent.") }
        }
        busy = false
        message = when (outcome) {
            ActionOutcome.Accepted -> "${Glossary.label("outcome.accepted")}: ${prepared.summary}" to BannerKind.SUCCESS
            is ActionOutcome.Rejected -> "${Glossary.label("outcome.rejected")}. ${outcome.reason}" to BannerKind.WARNING
            is ActionOutcome.Unknown -> "${Glossary.label("outcome.unknown")}. ${outcome.reason}" to BannerKind.DANGER
        }
    }

    suspend fun reconcile() {
        val guard = entry.guard ?: return
        busy = true
        val reading = withContext(Dispatchers.IO) { guard.reconcile() }
        entry.status.value = reading
        busy = false
        message = if (guard.needsReconcile) "The printer still can't be checked (${reading.state.label}). Commands stay blocked until it answers." to BannerKind.WARNING
            else "Checked: the printer is ${reading.state.label.lowercase()}. You can send commands again." to BannerKind.SUCCESS
    }
}

@Composable
fun rememberActionFlow(entry: PrinterEntry) = remember(entry) { ActionFlow(entry) }

@Composable
fun ActionFlowUi(flow: ActionFlow, entry: PrinterEntry) {
    val scope = rememberCoroutineScope()
    flow.message?.let { (text, kind) ->
        val guard = entry.guard
        Banner(text, kind, if (guard?.needsReconcile == true) "Check printer" to { scope.launch { flow.reconcile() } } else "Dismiss" to { flow.message = null })
    }
    flow.pending?.let { p ->
        val term = Glossary.term(p.action.glossaryId)
        ConfirmDialog(title = "${term.confirm.ifBlank { term.label }} on ${entry.config.identity.displayName}?", body = p.summary,
            confirmLabel = term.confirm.ifBlank { term.label }, destructive = term.destructive,
            detail = "Nozzle checks the printer is still ${p.reviewedState.label.lowercase()} before sending, and never repeats a command on its own.",
            onConfirm = { scope.launch { flow.confirm() } }, onDismiss = { flow.pending = null })
    }
}
