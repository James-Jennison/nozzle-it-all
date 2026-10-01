package com.nozzleitall.testgrid

import org.json.JSONObject

class SuiteFormatException(message: String) : Exception(message)
class IncompatibleSuiteException(val major: Int, message: String) : Exception(message)

/**
 * The closed step vocabulary. A suite can only ask for these; there is no "send this G-code" or "run this macro" step,
 * so a suite file can never become a remote-command channel. Each kind carries its own safety properties; a manifest
 * cannot weaken them (see [SuiteValidator]).
 *
 * [consequential]: needs the operator's explicit approval of this exact step, every time, with the target shown.
 */
enum class StepKind(val id: String, val level: SafetyLevel, val mutates: Boolean, val consequential: Boolean, val usesPrinter: Boolean, val operator: Boolean = false) {
    VERIFY_MODEL("verify_model", SafetyLevel.SOFTWARE, false, false, false),
    SLICE("slice", SafetyLevel.SOFTWARE, false, false, false),
    SCAN_GCODE("scan_gcode", SafetyLevel.SOFTWARE, false, false, false),
    /** Feeds a known-unsafe file to the upload guard and expects a refusal. The file is never sent. */
    UPLOAD_GUARD("upload_guard", SafetyLevel.READ_ONLY, false, false, true),
    READ_IDENTITY("read_identity", SafetyLevel.READ_ONLY, false, false, true),
    PROFILE_MATCH("profile_match", SafetyLevel.READ_ONLY, false, false, true),
    READ_STATUS("read_status", SafetyLevel.READ_ONLY, false, false, true),
    CHECK_CAPABILITIES("check_capabilities", SafetyLevel.READ_ONLY, false, false, true),
    LIST_CAMERAS("list_cameras", SafetyLevel.READ_ONLY, false, false, true),
    CAMERA_SNAPSHOT("camera_snapshot", SafetyLevel.READ_ONLY, false, false, true),
    LIST_FILES("list_files", SafetyLevel.READ_ONLY, false, false, true),
    READ_MATERIAL_SLOTS("read_material_slots", SafetyLevel.READ_ONLY, false, false, true),
    MONITOR("monitor", SafetyLevel.READ_ONLY, false, false, true),
    UPLOAD("upload", SafetyLevel.REVERSIBLE_FILES, true, true, true),
    /** Deletes only the file this run itself uploaded; never a name from the suite. */
    DELETE_UPLOADED("delete_uploaded", SafetyLevel.REVERSIBLE_FILES, true, true, true),
    /**
     * Deletes earlier Test Grid files (`nozzle-testgrid-*.gcode` in the G-code root) the printer no longer has loaded and
     * this run no longer needs. The exact names are shown for approval; only those are deleted.
     */
    DELETE_LEFTOVERS("delete_leftovers", SafetyLevel.REVERSIBLE_FILES, true, true, true),
    /**
     * For printers that only take a file together with a print start (Bambu LAN, PrusaLink, OctoPrint, Elegoo stock):
     * sends the file sliced in this run and starts it, as the app's own "Send and print" does. One approval covers both.
     */
    SEND_AND_START("send_and_start", SafetyLevel.PHYSICAL_PRINT, true, true, true),
    SET_TEMPERATURE("set_temperature", SafetyLevel.SUPERVISED_CONTROLS, true, true, true),
    HOME("home", SafetyLevel.SUPERVISED_CONTROLS, true, true, true),
    JOG("jog", SafetyLevel.SUPERVISED_CONTROLS, true, true, true),
    START_PRINT("start_print", SafetyLevel.PHYSICAL_PRINT, true, true, true),
    PAUSE("pause", SafetyLevel.PHYSICAL_PRINT, true, true, true),
    RESUME("resume", SafetyLevel.PHYSICAL_PRINT, true, true, true),
    CANCEL("cancel", SafetyLevel.PHYSICAL_PRINT, true, true, true),
    OBSERVE("observe", SafetyLevel.SOFTWARE, false, false, false, operator = true),
    ATTACH("attach", SafetyLevel.SOFTWARE, false, false, false, operator = true);

    companion object { fun parse(id: String): StepKind? = entries.firstOrNull { it.id == id } }
}

enum class TimeoutBehavior(val id: String) {
    FAIL("fail"), BLOCKED("blocked"), OUTCOME_UNKNOWN("outcome_unknown");
    companion object { fun parse(id: String?): TimeoutBehavior? = if (id == null) FAIL else entries.firstOrNull { it.id == id } }
}

enum class Coverage(val id: String) {
    /** Written for hardware the maintainers own and run. */
    REFERENCE("reference"),
    /** Written ahead of evidence, for hardware nobody on the project has run it on. Grades stay UNVERIFIED until a
     *  bundle from matching physical hardware exists. */
    FIXTURE("fixture");
    companion object { fun parse(id: String): Coverage? = entries.firstOrNull { it.id == id } }
}

/** [kind] is null when this build does not know [kindId] (a newer minor version); a test containing one is BLOCKED. */
data class Step(
    val id: String, val kindId: String, val kind: StepKind?, val title: String,
    val params: JSONObject, val expect: JSONObject, val requiresConfirmation: Boolean,
    val timeoutSeconds: Int?, val onTimeout: TimeoutBehavior, val unknown: JSONObject,
)

/** [check] names an automatic check ("printer_idle"); without one the operator attests to [text]. */
data class Precondition(val id: String, val text: String, val check: String?)

data class RequiredEvidence(val id: String, val kind: String, val description: String)

data class TestCase(
    val id: String, val title: String, val description: String, val category: Category, val scope: MaterialScope,
    val safetyLevel: SafetyLevel, val requiredCapabilities: List<String>, val requiredHardware: List<String>,
    val preconditions: List<Precondition>, val steps: List<Step>, val expectedObservations: List<String>,
    val requiredEvidence: List<RequiredEvidence>, val timeoutSeconds: Int?, val onTimeout: TimeoutBehavior,
    val cleanup: List<Step>, val requiresOperatorConfirmation: Boolean, val mutatesPrinterState: Boolean,
    val uncertainResultProhibitsRetry: Boolean, val dependsOn: List<String>, val unknown: JSONObject,
) {
    val unsupportedSteps: List<Step> get() = (steps + cleanup).filter { it.kind == null }
}

data class SuiteTarget(
    val manufacturer: String, val model: String, val firmwareFamily: String, val firmwareVariants: List<String>,
    val printerKinds: List<String>, val adapter: String, val protocol: String, val slicingProfile: String?,
    val hardware: Map<String, Boolean>, val notEquivalentTo: List<String>, val unknown: JSONObject,
)

data class Suite(
    val formatMinor: Int, val id: String, val version: String, val title: String, val description: String,
    val coverage: Coverage, val requiredNozzleVersion: String, val target: SuiteTarget,
    val requiredCapabilities: List<String>, val tests: List<TestCase>, val unknown: JSONObject,
    /** SHA-256 of the canonical suite JSON as read; recorded in evidence so a bundle names the exact suite text. */
    val digest: String,
    /** The canonical JSON the digest covers, unknown fields included, so a saved run can re-read exactly this suite. */
    val source: String,
) {
    val maxSafetyLevel: SafetyLevel get() = tests.maxOfOrNull { it.safetyLevel } ?: SafetyLevel.SOFTWARE
    /** SHA-256 of one test's canonical JSON as read: a test whose definition changed never carries an earlier pass. */
    fun testDigest(id: String): String {
        val tests = org.json.JSONObject(source).optJSONArray("tests") ?: return ""
        val o = (0 until tests.length()).map { tests.getJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return ""
        return Canon.sha256(Canon.write(o).toByteArray(Charsets.UTF_8))
    }

    /** The lowest level at which any test runs; a run limited below it would run nothing. */
    val minSafetyLevel: SafetyLevel get() = tests.minOfOrNull { it.safetyLevel } ?: SafetyLevel.SOFTWARE
    fun test(id: String): TestCase? = tests.firstOrNull { it.id == id }

    companion object {
        const val SCHEMA = "nozzle.test-suite"
        const val MAJOR = 1
        const val MINOR = 0

        fun parse(text: String): Suite = try { parse(JSONObject(text)) } catch (e: org.json.JSONException) { throw SuiteFormatException("The suite is not valid JSON: ${e.message}") }

        fun parse(o: JSONObject): Suite {
            if (o.optString("schema") != SCHEMA) throw SuiteFormatException("This is not a Nozzle test suite (schema must be \"$SCHEMA\").")
            val version = o.optJSONArray("version") ?: throw SuiteFormatException("The suite has no format version.")
            val major = version.optInt(0, -1)
            if (major != MAJOR) throw IncompatibleSuiteException(major,
                if (major > MAJOR) "This suite needs a newer Nozzle It All (suite format $major, this build reads $MAJOR)." else "Unsupported suite format $major.")
            val minor = version.optInt(1, 0)
            val t = o.optJSONObject("target") ?: throw SuiteFormatException("The suite has no target.")
            val target = SuiteTarget(t.optString("manufacturer"), t.optString("model"), t.optString("firmwareFamily"), t.strings("firmwareVariants"),
                t.strings("printerKinds"), t.optString("adapter"), t.optString("protocol"), t.strOrNull("slicingProfile"),
                t.optJSONObject("hardware")?.let { h -> h.keySet().associateWith { h.optBoolean(it) } } ?: emptyMap(),
                t.strings("notEquivalentTo"),
                t.rest("manufacturer", "model", "firmwareFamily", "firmwareVariants", "printerKinds", "adapter", "protocol", "slicingProfile", "hardware", "notEquivalentTo"))
            val tests = o.objects("tests").map { parseTest(it) }
            val suite = Suite(minor, o.optString("id"), o.optString("suiteVersion"), o.optString("title"), o.optString("description"),
                Coverage.parse(o.optString("coverage")) ?: throw SuiteFormatException("Unknown coverage \"${o.optString("coverage")}\" (reference or fixture)."),
                o.optString("requiredNozzleVersion"), target, o.strings("requiredCapabilities"), tests,
                o.rest("schema", "version", "id", "suiteVersion", "title", "description", "coverage", "requiredNozzleVersion", "target", "requiredCapabilities", "tests"),
                Canon.sha256(Canon.bytes(o)), Canon.write(o))
            val problems = SuiteValidator.problems(suite)
            if (problems.isNotEmpty()) throw SuiteFormatException("Suite ${suite.id.ifBlank { "(no id)" }} is invalid:\n" + problems.joinToString("\n") { "- $it" })
            return suite
        }

        private fun parseStep(s: JSONObject): Step {
            val kindId = s.optString("kind")
            val kind = StepKind.parse(kindId)
            return Step(s.optString("id"), kindId, kind, s.optString("title"),
                s.optJSONObject("params") ?: JSONObject(), s.optJSONObject("expect") ?: JSONObject(),
                // A consequential step defaults to needing confirmation; a manifest saying false is rejected by the validator.
                if (s.has("requiresConfirmation")) s.optBoolean("requiresConfirmation") else kind?.consequential ?: true,
                if (s.has("timeoutSeconds")) s.optInt("timeoutSeconds") else null,
                TimeoutBehavior.parse(s.strOrNull("onTimeout")) ?: throw SuiteFormatException("Step ${s.optString("id")}: unknown onTimeout \"${s.optString("onTimeout")}\"."),
                s.rest("id", "kind", "title", "params", "expect", "requiresConfirmation", "timeoutSeconds", "onTimeout"))
        }

        private fun parseTest(t: JSONObject): TestCase {
            val id = t.optString("id")
            val timeout = t.optJSONObject("timeout")
            return TestCase(id, t.optString("title"), t.optString("description"),
                Category.parse(t.optString("category")) ?: throw SuiteFormatException("Test $id: unknown category \"${t.optString("category")}\"."),
                MaterialScope.parse(t.optString("scope", MaterialScope.SINGLE.id)) ?: throw SuiteFormatException("Test $id: unknown scope \"${t.optString("scope")}\"."),
                SafetyLevel.of(t.optInt("safetyLevel", -1)) ?: throw SuiteFormatException("Test $id: safetyLevel must be 0 to 4."),
                t.strings("requiredCapabilities"), t.strings("requiredHardware"),
                t.objects("preconditions").map { Precondition(it.optString("id"), it.optString("text"), it.strOrNull("check")) },
                t.objects("steps").map { parseStep(it) }, t.strings("expectedObservations"),
                t.objects("requiredEvidence").map { RequiredEvidence(it.optString("id"), it.optString("kind"), it.optString("description")) },
                timeout?.let { if (it.has("seconds")) it.optInt("seconds") else null },
                TimeoutBehavior.parse(timeout?.strOrNull("onTimeout")) ?: throw SuiteFormatException("Test $id: unknown timeout behaviour."),
                t.objects("cleanup").map { parseStep(it) },
                t.optBoolean("requiresOperatorConfirmation", false), t.optBoolean("mutatesPrinterState", false),
                t.optBoolean("uncertainResultProhibitsRetry", true), t.strings("dependsOn"),
                t.rest("id", "title", "description", "category", "scope", "safetyLevel", "requiredCapabilities", "requiredHardware", "preconditions",
                    "steps", "expectedObservations", "requiredEvidence", "timeout", "cleanup", "requiresOperatorConfirmation", "mutatesPrinterState",
                    "uncertainResultProhibitsRetry", "dependsOn"))
        }
    }
}

/** Limits a suite cannot exceed. Printing temperatures come from sliced G-code, never from a suite step. */
object StepLimits {
    const val NOZZLE_MAX_C = 120
    const val BED_MAX_C = 70
    const val JOG_MAX_MM = 10.0
    const val MONITOR_MAX_SECONDS = 6 * 3600
    val HEATERS = setOf("nozzle", "bed")
    val AXES = setOf("X", "Y", "Z")
    val MONITOR_CONDITIONS = setOf("printing", "complete", "idle", "paused", "progress_increases", "heater_reaches", "heater_below")
    val RESPONSES = setOf("yes_no", "pass_partial_fail", "number", "text", "choice")
    val GCODE_CHECKS = setOf("non_empty", "no_stock_elegoo_commands", "requires_macro", "within_bed", "centered", "max_tool_index", "uses_tools", "alternates_tools", "heater_targets")
    val PRECONDITION_CHECKS = setOf("printer_idle", "printer_connected")
    /** What an observe step shows the operator alongside the question: what Nozzle itself sees, to compare against. */
    val OBSERVE_SHOWS = setOf("status", "slots", "camera")
}

object SuiteValidator {
    private val ID = Regex("""^[a-z0-9][a-z0-9._-]{0,63}$""")

    fun problems(suite: Suite): List<String> {
        val p = mutableListOf<String>()
        if (!ID.matches(suite.id)) p += "suite id must be lower-case letters, digits, '.', '_' or '-'"
        if (Versions.parse(suite.version) == null) p += "suiteVersion must be a dotted version"
        if (Versions.parse(suite.requiredNozzleVersion) == null) p += "requiredNozzleVersion must be a dotted version"
        if (suite.title.isBlank()) p += "title is required"
        if (suite.target.manufacturer.isBlank() || suite.target.model.isBlank() || suite.target.firmwareFamily.isBlank()) p += "target needs manufacturer, model and firmwareFamily"
        if (suite.tests.isEmpty()) p += "a suite needs at least one test"
        val seen = mutableSetOf<String>()
        suite.tests.forEach { t ->
            fun bad(msg: String) { p += "test ${t.id}: $msg" }
            if (!ID.matches(t.id)) bad("id must be lower-case letters, digits, '.', '_' or '-'")
            if (!seen.add(t.id)) bad("duplicate test id")
            if (t.title.isBlank()) bad("title is required")
            t.dependsOn.forEach { d -> if (d !in seen || d == t.id) bad("dependsOn \"$d\" must name an earlier test") }
            val stepIds = mutableSetOf<String>()
            val all = t.steps + t.cleanup
            if (t.steps.isEmpty()) bad("needs at least one step")
            all.forEach { s -> if (!ID.matches(s.id) || !stepIds.add(s.id)) bad("step ids must be unique and well-formed (\"${s.id}\")") }
            t.preconditions.forEach { c -> if (c.text.isBlank()) bad("precondition ${c.id} has no text"); if (c.check != null && c.check !in StepLimits.PRECONDITION_CHECKS) bad("unknown precondition check ${c.check}") }
            val known = all.mapNotNull { s -> s.kind?.let { s to it } }
            known.forEach { (s, k) ->
                fun badStep(msg: String) { bad("step ${s.id} (${k.id}): $msg") }
                if (k.level > t.safetyLevel) badStep("needs ${k.level.label}, above the test's ${t.safetyLevel.label}")
                if (k.consequential && !s.requiresConfirmation) badStep("changes the printer and must require operator confirmation")
                if (s.timeoutSeconds != null && s.timeoutSeconds !in 1..StepLimits.MONITOR_MAX_SECONDS) badStep("timeoutSeconds out of range")
                stepProblems(s, k, t).forEach { badStep(it) }
            }
            val mutating = known.any { it.second.mutates }
            val consequential = known.any { it.second.consequential }
            if (mutating && !t.mutatesPrinterState) bad("has steps that change the printer, so mutatesPrinterState must be true")
            if (consequential && !t.requiresOperatorConfirmation) bad("has consequential steps, so requiresOperatorConfirmation must be true")
            if (mutating && !t.uncertainResultProhibitsRetry) bad("changes the printer, so an uncertain result must prohibit retry")
            if (t.timeoutSeconds != null && t.timeoutSeconds !in 1..StepLimits.MONITOR_MAX_SECONDS * 2) bad("timeout out of range")
            val evidenceIds = t.requiredEvidence.map { it.id }.toSet()
            t.requiredEvidence.forEach { e ->
                if (e.kind !in setOf("photo", "file", "measurement")) bad("evidence ${e.id}: kind must be photo, file or measurement")
                val collected = all.any { s -> (s.kind == StepKind.ATTACH || s.kind == StepKind.OBSERVE) && s.params.optString("evidence") == e.id }
                if (!collected) bad("evidence ${e.id} is required but no attach/observe step collects it")
            }
            all.filter { it.kind == StepKind.ATTACH }.forEach { s -> if (s.params.optString("evidence") !in evidenceIds) bad("step ${s.id} attaches undeclared evidence \"${s.params.optString("evidence")}\"") }
        }
        return p
    }

    private fun stepProblems(s: Step, k: StepKind, t: TestCase): List<String> {
        val p = mutableListOf<String>()
        val a = s.params
        when (k) {
            StepKind.SLICE -> {
                if (a.optString("model").isBlank()) p += "params.model is required"; if (a.optString("profile").isBlank()) p += "params.profile is required"
                a.optJSONObject("colourMix")?.let { m ->
                    val x = m.optInt("a"); val y = m.optInt("b"); val pc = m.optInt("bPercent", 50)
                    if (x !in 1..16 || y !in 1..16 || x == y) p += "params.colourMix.a and .b must be two different tools, 1 to 16"
                    if (pc !in 1..99) p += "params.colourMix.bPercent must be 1 to 99"
                }
            }
            StepKind.VERIFY_MODEL -> if (a.optString("model").isBlank()) p += "params.model is required"
            StepKind.PROFILE_MATCH -> if (a.optString("profile").isBlank()) p += "params.profile is required"
            StepKind.UPLOAD_GUARD -> if (a.optString("fixture").isBlank()) p += "params.fixture is required"
            StepKind.SCAN_GCODE -> {
                val checks = s.params.optJSONArray("checks")
                if (checks == null || checks.length() == 0) p += "params.checks is required"
                else (0 until checks.length()).mapNotNull { checks.optJSONObject(it) }.forEach { c ->
                    if (c.optString("check") !in StepLimits.GCODE_CHECKS) p += "unknown G-code check \"${c.optString("check")}\""
                }
            }
            StepKind.SET_TEMPERATURE -> {
                val heater = a.optString("heater"); val c = a.optInt("celsius", -1)
                if (heater !in StepLimits.HEATERS) p += "params.heater must be nozzle or bed"
                val max = if (heater == "bed") StepLimits.BED_MAX_C else StepLimits.NOZZLE_MAX_C
                if (c !in 0..max) p += "params.celsius must be 0 to $max for this heater (test steps never reach printing temperatures)"
            }
            StepKind.JOG -> {
                if (a.optString("axis").uppercase() !in StepLimits.AXES) p += "params.axis must be X, Y or Z"
                val mm = a.optDouble("mm", Double.NaN)
                if (!mm.isFinite() || mm == 0.0 || kotlin.math.abs(mm) > StepLimits.JOG_MAX_MM) p += "params.mm must be non-zero and at most ${StepLimits.JOG_MAX_MM} mm"
            }
            StepKind.MONITOR -> {
                if (a.optString("until") !in StepLimits.MONITOR_CONDITIONS) p += "params.until must be one of ${StepLimits.MONITOR_CONDITIONS.sorted()}"
                if (s.timeoutSeconds == null) p += "a monitor step needs timeoutSeconds"
                if (a.optString("until") in setOf("heater_reaches", "heater_below") && a.optString("heater") !in StepLimits.HEATERS) p += "params.heater must be nozzle or bed"
            }
            StepKind.OBSERVE -> {
                if (a.optString("question").isBlank()) p += "params.question is required"
                if (a.optString("response") !in StepLimits.RESPONSES) p += "params.response must be one of ${StepLimits.RESPONSES.sorted()}"
                if (a.optString("response") == "choice" && (a.optJSONArray("choices")?.length() ?: 0) < 2) p += "a choice needs at least two params.choices"
                a.strOrNull("show")?.let { if (it !in StepLimits.OBSERVE_SHOWS) p += "params.show must be one of ${StepLimits.OBSERVE_SHOWS.sorted()}" }
            }
            StepKind.ATTACH -> if (a.optString("evidence").isBlank()) p += "params.evidence is required"
            StepKind.CHECK_CAPABILITIES -> if ((a.optJSONArray("required")?.length() ?: 0) == 0 && t.requiredCapabilities.isEmpty()) p += "params.required or the test's requiredCapabilities must name something"
            else -> {}
        }
        return p
    }
}
