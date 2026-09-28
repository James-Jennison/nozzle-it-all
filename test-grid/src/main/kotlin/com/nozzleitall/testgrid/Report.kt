package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject

/**
 * The maintainers' record of which bundles they inspected. A bundle nobody accepted still appears in the report, marked
 * unreviewed; a rejected one stays in history but never becomes a row's current result.
 */
class AcceptanceLedger(val accepted: Map<String, JSONObject>, val rejected: Map<String, String>) {
    companion object {
        const val FORMAT = "nozzle.evidence-acceptance"
        val EMPTY = AcceptanceLedger(emptyMap(), emptyMap())
        fun parse(text: String): AcceptanceLedger {
            val o = JSONObject(text)
            require(o.optString("format") == FORMAT) { "Not an evidence acceptance ledger." }
            require(o.optJSONArray("version")?.optInt(0) == 1) { "Unsupported ledger version." }
            return AcceptanceLedger(o.objects("accepted").associateBy { it.getString("bundleDigest") },
                o.objects("rejected").associate { it.getString("bundleDigest") to it.optString("reason") })
        }
    }
}

data class RowKey(val manufacturer: String, val model: String, val firmwareFamily: String, val firmwareVersion: String,
                  val nozzleVersion: String, val adapter: String, val scope: MaterialScope) {
    val sortKey get() = listOf(manufacturer, model, firmwareFamily, firmwareVersion, nozzleVersion, adapter, scope.id).joinToString("\u0000")
}

data class RunEntry(
    val runId: String, val bundleDigest: String, val contentDigest: String, val completedAt: Long, val targetKind: TargetKind,
    val suiteId: String, val suiteVersion: String, val coverage: String, val source: String, val grades: Map<Category, ResultState>,
    val review: String, val supersedes: List<String>, var supersededBy: String? = null,
)

data class Row(val key: RowKey, val current: RunEntry?, val history: List<RunEntry>, val note: String)

data class CompatibilityReport(val rows: List<Row>, val rejected: List<Pair<String, List<String>>>, val duplicates: List<Pair<String, String>>)

object ReportBuilder {
    fun build(bundles: List<Pair<String, ByteArray>>, suites: List<Suite>, ledger: AcceptanceLedger = AcceptanceLedger.EMPTY): CompatibilityReport {
        val rejected = mutableListOf<Pair<String, List<String>>>()
        val duplicates = mutableListOf<Pair<String, String>>()
        val seen = mutableMapOf<String, String>()
        val entries = mutableMapOf<RowKey, MutableList<RunEntry>>()
        bundles.sortedBy { it.first }.forEach { (source, bytes) ->
            when (val r = BundleReader.read(bytes)) {
                is BundleReader.Result.Invalid -> rejected += source to r.reasons
                is BundleReader.Result.Valid -> {
                    val b = r.bundle
                    seen[b.bundleDigest]?.let { duplicates += source to it; return@forEach }
                    seen[b.bundleDigest] = source
                    val ev = b.evidence
                    val target = ev.getJSONObject("target")
                    val firmware = target.getJSONObject("firmware")
                    val run = ev.getJSONObject("run")
                    val grades = ev.optJSONObject("grades") ?: JSONObject()
                    val review = when {
                        b.bundleDigest in ledger.rejected -> "rejected by maintainer: ${ledger.rejected[b.bundleDigest]}"
                        b.bundleDigest in ledger.accepted -> "accepted"
                        else -> "unreviewed"
                    }
                    // Only scopes the suite actually tested get a row: a single-material run never creates a multi-material grade.
                    MaterialScope.entries.forEach { scope ->
                        val g = grades.optJSONObject(scope.id) ?: return@forEach
                        if (Category.entries.none { (g.optJSONObject(it.id)?.optJSONArray("tests")?.length() ?: 0) > 0 }) return@forEach
                        val key = RowKey(target.optString("manufacturer"), target.optString("model"), firmware.optString("family"),
                            firmware.optString("version").ifBlank { "unreported" }, ev.getJSONObject("producer").optString("version"),
                            target.optString("adapter"), scope)
                        val kind = TargetKind.parse(target.optString("kind")) ?: TargetKind.SIMULATED
                        val cells = Category.entries.associateWith { c ->
                            val raw = ResultState.parse(g.optJSONObject(c.id)?.optString("result").orEmpty()) ?: ResultState.UNVERIFIED
                            // Simulated evidence never grades hardware, whatever the bundle claims.
                            if (kind == TargetKind.SIMULATED) ResultState.UNVERIFIED else raw
                        }
                        entries.getOrPut(key) { mutableListOf() } += RunEntry(run.optString("runId"), b.bundleDigest, b.contentDigest, run.optLong("completedAt"), kind,
                            ev.getJSONObject("suite").optString("id"), ev.getJSONObject("suite").optString("version"), ev.getJSONObject("suite").optString("coverage"),
                            source, cells, review, run.strings("supersedes"))
                    }
                }
            }
        }

        val rows = entries.map { (key, runs) ->
            val history = runs.sortedWith(compareBy<RunEntry> { it.completedAt }.thenBy { it.bundleDigest })
            // Newest eligible physical run is current; a newer run supersedes older ones but never deletes them.
            val eligible = history.filter { !it.review.startsWith("rejected") }
            val current = eligible.lastOrNull { it.targetKind == TargetKind.PHYSICAL } ?: eligible.lastOrNull()
            history.forEach { old ->
                old.supersededBy = history.firstOrNull { it.supersedes.contains(old.runId) }?.runId
                    ?: if (old !== current && current != null && old.targetKind == current.targetKind && old.completedAt <= current.completedAt) current.runId else null
            }
            val note = when {
                current == null -> "Every bundle for this configuration was rejected by a maintainer."
                current.targetKind == TargetKind.SIMULATED -> "Simulated runs only: software exercised, hardware UNVERIFIED."
                else -> ""
            }
            Row(key, current, history, note)
        }.toMutableList()

        // Configurations a suite covers but no bundle does: listed, every category UNVERIFIED.
        suites.forEach { s ->
            s.tests.map { it.scope }.distinct().forEach { scope ->
                val covered = rows.any { r -> r.key.manufacturer == s.target.manufacturer && r.key.model == s.target.model &&
                    r.key.firmwareFamily == s.target.firmwareFamily && r.key.adapter == s.target.adapter && r.key.scope == scope }
                if (!covered)
                    rows += Row(RowKey(s.target.manufacturer, s.target.model, s.target.firmwareFamily, "—", "—", s.target.adapter, scope), null, emptyList(),
                        "No physical evidence yet (${s.coverage.id} suite ${s.id} ${s.version}).")
            }
        }
        return CompatibilityReport(rows.sortedBy { it.key.sortKey }, rejected, duplicates)
    }

    fun cell(row: Row, c: Category): String {
        val cur = row.current ?: return ResultState.UNVERIFIED.name
        val r = cur.grades[c] ?: ResultState.UNVERIFIED
        return if (cur.targetKind == TargetKind.PHYSICAL && cur.review == "unreviewed" && r != ResultState.UNVERIFIED) "${r.name} (unreviewed)" else r.name
    }

    fun markdown(report: CompatibilityReport, title: String = "Nozzle Test Grid compatibility report"): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("Generated from verified evidence bundles. Each category is graded only from tests of that category; a result for one")
        appendLine("firmware family, adapter, Nozzle build or material scope is never evidence for another. Simulated runs and")
        appendLine("configurations without physical evidence are UNVERIFIED. \"(unreviewed)\" means no maintainer has accepted the bundle yet.")
        appendLine()
        appendLine("| Printer | Firmware | Nozzle build | Adapter | Scope | " + Category.entries.joinToString(" | ") { it.label } + " | Current evidence |")
        appendLine("|" + "---|".repeat(6 + Category.entries.size))
        report.rows.forEach { r ->
            val fw = if (r.key.firmwareVersion == "—") r.key.firmwareFamily else "${r.key.firmwareFamily} ${r.key.firmwareVersion}"
            val ev = r.current?.let { "${it.targetKind.id} run `${it.runId.take(8)}` (${it.suiteId} ${it.suiteVersion}), bundle `${it.bundleDigest.take(12)}`" } ?: r.note
            appendLine("| ${r.key.manufacturer} ${r.key.model} | $fw | ${r.key.nozzleVersion} | ${r.key.adapter} | ${r.key.scope.label} | " +
                Category.entries.joinToString(" | ") { cell(r, it) } + " | $ev |")
        }
        val withHistory = report.rows.filter { it.history.isNotEmpty() }
        if (withHistory.isNotEmpty()) {
            appendLine(); appendLine("## History"); appendLine()
            appendLine("Every run is kept. A newer run supersedes an older one for the current column; the older run and its bundle digest remain here.")
            withHistory.forEach { r ->
                appendLine(); appendLine("### ${r.key.manufacturer} ${r.key.model} · ${r.key.firmwareFamily} ${r.key.firmwareVersion} · Nozzle ${r.key.nozzleVersion} · ${r.key.adapter} · ${r.key.scope.label}")
                appendLine()
                appendLine("| Run | Kind | Suite | Completed (UTC) | " + Category.entries.joinToString(" | ") { it.label } + " | Review | Status | Bundle digest | Source |")
                appendLine("|" + "---|".repeat(8 + Category.entries.size))
                r.history.forEach { h ->
                    val status = when { h === r.current -> "current"; h.supersededBy != null -> "superseded by `${h.supersededBy!!.take(8)}`"; else -> "historical" }
                    appendLine("| `${h.runId.take(8)}` | ${h.targetKind.id} | ${h.suiteId} ${h.suiteVersion} | ${java.time.Instant.ofEpochMilli(h.completedAt)} | " +
                        Category.entries.joinToString(" | ") { (h.grades[it] ?: ResultState.UNVERIFIED).name } + " | ${h.review} | $status | `${h.bundleDigest}` | ${h.source} |")
                }
            }
        }
        if (report.rejected.isNotEmpty() || report.duplicates.isNotEmpty()) {
            appendLine(); appendLine("## Bundles not used"); appendLine()
            report.rejected.forEach { (src, reasons) -> appendLine("- **$src** rejected: ${reasons.joinToString("; ")}") }
            report.duplicates.forEach { (src, first) -> appendLine("- **$src** is identical to $first (same bundle digest); counted once.") }
        }
    }

    fun json(report: CompatibilityReport): JSONObject = JSONObject()
        .put("format", "nozzle.compatibility-report").put("version", JSONArray().put(1).put(0))
        .put("rows", JSONArray(report.rows.map { r ->
            JSONObject().put("printer", JSONObject().put("manufacturer", r.key.manufacturer).put("model", r.key.model))
                .put("firmware", JSONObject().put("family", r.key.firmwareFamily).put("version", r.key.firmwareVersion))
                .put("nozzleVersion", r.key.nozzleVersion).put("adapter", r.key.adapter).put("scope", r.key.scope.id)
                .put("grades", JSONObject(Category.entries.associate { it.id to cell(r, it) }))
                .put("current", r.current?.runId ?: JSONObject.NULL).put("note", r.note)
                .put("history", JSONArray(r.history.map { h ->
                    JSONObject().put("runId", h.runId).put("kind", h.targetKind.id).put("suite", "${h.suiteId} ${h.suiteVersion}").put("completedAt", h.completedAt)
                        .put("grades", JSONObject(h.grades.mapKeys { it.key.id }.mapValues { it.value.name })).put("review", h.review)
                        .put("supersededBy", h.supersededBy ?: JSONObject.NULL).put("bundleDigest", h.bundleDigest).put("contentDigest", h.contentDigest).put("source", h.source)
                }))
        }))
        .put("rejected", JSONArray(report.rejected.map { JSONObject().put("source", it.first).put("reasons", JSONArray(it.second)) }))
        .put("duplicates", JSONArray(report.duplicates.map { JSONObject().put("source", it.first).put("sameAs", it.second) }))
}

/**
 * Local evidence store: bundles filed under their bundle digest. Adding never overwrites; the same bundle twice is a
 * no-op, and nothing is ever deleted by the tooling. This is what "a newer run never overwrites history" rests on.
 */
class EvidenceStore(val dir: java.io.File) {
    sealed class AddResult { data class Added(val file: java.io.File) : AddResult(); data class AlreadyPresent(val file: java.io.File) : AddResult(); data class Rejected(val reasons: List<String>) : AddResult() }

    fun add(zip: ByteArray): AddResult = when (val r = BundleReader.read(zip)) {
        is BundleReader.Result.Invalid -> AddResult.Rejected(r.reasons)
        is BundleReader.Result.Valid -> {
            dir.mkdirs()
            val f = java.io.File(dir, "${r.bundle.bundleDigest}.nozzle-evidence.zip")
            if (f.exists()) {
                if (Canon.sha256(f.readBytes()) == Canon.sha256(zip) || (BundleReader.read(f.readBytes()) as? BundleReader.Result.Valid)?.bundle?.bundleDigest == r.bundle.bundleDigest) AddResult.AlreadyPresent(f)
                else AddResult.Rejected(listOf("A different file already exists at ${f.name}; refusing to overwrite it."))
            } else {
                val tmp = java.io.File(dir, f.name + ".tmp"); tmp.writeBytes(zip); check(tmp.renameTo(f)) { "Could not store the bundle." }
                AddResult.Added(f)
            }
        }
    }

    fun all(): List<Pair<String, ByteArray>> = dir.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name }?.map { it.name to it.readBytes() } ?: emptyList()
}
