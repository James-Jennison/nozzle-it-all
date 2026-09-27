package com.nozzleitall.printer

// Printer-changing actions. Monitoring is a separate, read-only path (PrinterSession.status/cameras); nothing in the
// read path can move, heat or start anything.
//
// Safety rules enforced here rather than left to each screen:
//  1. Every action needs an explicit Confirmation, and a Confirmation can only be made from the PreparedAction the
//     user reviewed (it carries the prepared token and the state it was reviewed against).
//  2. A PreparedAction goes stale when the printer state it was reviewed against changes; executing it then fails.
//  3. An action is never retried automatically. When the outcome is unknown (timeout, lost reply), ActionGuard refuses
//     to execute the same action again until a fresh status reading taken after the failure has been reconciled.

sealed class PrinterAction(val glossaryId: String) {
    /** States in which the action is offered at all. */
    abstract val allowedStates: Set<PrinterState>
    abstract val summary: String

    data class StartJob(val remotePath: String, val toolheadMap: List<Int> = emptyList()) : PrinterAction("action.start") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary get() = "Start printing $remotePath"
    }
    object Pause : PrinterAction("action.pause") {
        override val allowedStates = setOf(PrinterState.PRINTING)
        override val summary = "Pause the current print"
    }
    object Resume : PrinterAction("action.resume") {
        override val allowedStates = setOf(PrinterState.PAUSED)
        override val summary = "Resume the paused print"
    }
    object Cancel : PrinterAction("action.cancel") {
        override val allowedStates = setOf(PrinterState.PRINTING, PrinterState.PAUSED)
        override val summary = "Cancel the current print. This cannot be undone."
    }
    data class SetNozzleTemperature(val toolhead: Int, val celsius: Int) : PrinterAction("action.heat") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED, PrinterState.PRINTING, PrinterState.PAUSED)
        override val summary get() = if (celsius == 0) "Turn off toolhead ${toolhead + 1} heater" else "Heat toolhead ${toolhead + 1} to $celsius °C"
    }
    data class SetBedTemperature(val celsius: Int) : PrinterAction("action.heat") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED, PrinterState.PRINTING, PrinterState.PAUSED)
        override val summary get() = if (celsius == 0) "Turn off the bed heater" else "Heat the bed to $celsius °C"
    }
    object HomeAll : PrinterAction("action.home") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary = "Home all axes. The toolhead and bed will move."
    }
    data class Jog(val axis: Char, val millimetres: Double) : PrinterAction("action.move") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary get() = "Move $axis by $millimetres mm"
    }
    data class LoadMaterial(val toolhead: Int) : PrinterAction("action.load") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary get() = "Load material into toolhead ${toolhead + 1}. The toolhead heats and the feeder moves."
    }
    data class UnloadMaterial(val toolhead: Int) : PrinterAction("action.unload") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary get() = "Unload material from toolhead ${toolhead + 1}. The toolhead heats and the feeder moves."
    }
    data class SetMaterialInfo(val toolhead: Int, val material: Material) : PrinterAction("action.edit-material") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary get() = "Record ${material.label} in toolhead ${toolhead + 1}"
    }
    data class SelectToolhead(val toolhead: Int) : PrinterAction("action.toolchange") {
        override val allowedStates = setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
        override val summary get() = "Change to toolhead ${toolhead + 1}. The toolhead changer will move."
    }
}

/** The action as reviewed by the user, pinned to the printer state they saw. */
class PreparedAction internal constructor(
    val action: PrinterAction,
    val printerId: String,
    val reviewedState: PrinterState,
    val reviewedAtMillis: Long,
    internal val token: Long,
) {
    val summary: String get() = action.summary
}

/** Proof that a person confirmed exactly this prepared action. Only [ActionGuard.confirm] can create one. */
class Confirmation internal constructor(internal val token: Long, val printerId: String)

sealed class ActionOutcome {
    object Accepted : ActionOutcome()
    data class Rejected(val reason: String) : ActionOutcome()
    /** The request may or may not have reached the printer. Reconcile before offering the action again. */
    data class Unknown(val reason: String) : ActionOutcome()
}

class ActionRefused(message: String) : IllegalStateException(message)

/**
 * Owns the confirm/execute/reconcile rules for one printer. Adapters implement [PrinterSession.perform]; screens go
 * through this guard, never straight to the session.
 */
class ActionGuard(private val session: PrinterSession, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = java.security.SecureRandom()
    private val issued = HashMap<Long, PreparedAction>()
    // Set when an outcome was Unknown; cleared only by a status reading observed after it.
    private var unresolvedSince: Long? = null
    private var unresolvedAction: PrinterAction? = null
    val needsReconcile: Boolean @Synchronized get() = unresolvedSince != null
    val unresolved: PrinterAction? @Synchronized get() = unresolvedAction

    @Synchronized
    fun prepare(action: PrinterAction, current: PrinterStatus): PreparedAction {
        if (unresolvedSince != null) throw ActionRefused("The last command's result is unknown. Check the printer's current state before sending another command.")
        if (current.state !in action.allowedStates) throw ActionRefused("${action.summary} is not available while the printer is ${current.state.name.lowercase()}.")
        val prepared = PreparedAction(action, session.identity.id, current.state, clock(), random.nextLong())
        issued[prepared.token] = prepared
        return prepared
    }

    @Synchronized
    fun confirm(prepared: PreparedAction): Confirmation {
        if (issued[prepared.token] !== prepared) throw ActionRefused("This confirmation is no longer valid. Review the command again.")
        return Confirmation(prepared.token, prepared.printerId)
    }

    /** Re-reads the printer, refuses if anything the user reviewed has changed, then performs the action once. */
    fun execute(prepared: PreparedAction, confirmation: Confirmation): ActionOutcome {
        synchronized(this) {
            if (confirmation.token != prepared.token || issued.remove(prepared.token) !== prepared) throw ActionRefused("This confirmation does not match the reviewed command.")
            if (unresolvedSince != null) throw ActionRefused("The last command's result is unknown. Check the printer's current state first.")
        }
        val fresh = try { session.status() } catch (e: Exception) { return ActionOutcome.Rejected("The printer could not be reached to re-check its state. Nothing was sent.") }
        if (fresh.state != prepared.reviewedState || fresh.state !in prepared.action.allowedStates)
            return ActionOutcome.Rejected("The printer changed from ${prepared.reviewedState.name.lowercase()} to ${fresh.state.name.lowercase()} after you reviewed this command. Nothing was sent; review it again.")
        val outcome = try { session.perform(prepared.action) } catch (e: Exception) { ActionOutcome.Unknown(e.message ?: "No reply from the printer.") }
        if (outcome is ActionOutcome.Unknown) synchronized(this) { unresolvedSince = clock(); unresolvedAction = prepared.action }
        return outcome
    }

    /** Clears an unknown outcome with a reading taken after it. Returns that reading for the UI to show. */
    fun reconcile(): PrinterStatus {
        val since = synchronized(this) { unresolvedSince }
        val reading = session.status()
        synchronized(this) {
            if (since != null && reading.observedAtMillis >= since && reading.state != PrinterState.OFFLINE && reading.state != PrinterState.UNKNOWN) {
                unresolvedSince = null; unresolvedAction = null
            }
        }
        return reading
    }
}
