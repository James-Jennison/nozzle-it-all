package com.nozzleitall.testgrid.cli

import com.nozzleitall.testgrid.AcceptanceLedger
import com.nozzleitall.testgrid.AcceptanceModel
import com.nozzleitall.testgrid.BundleReader
import com.nozzleitall.testgrid.Canon
import com.nozzleitall.testgrid.EngineInfo
import com.nozzleitall.testgrid.Environment
import com.nozzleitall.testgrid.EvidenceBuilder
import com.nozzleitall.testgrid.EvidenceStore
import com.nozzleitall.testgrid.ManualInstructions
import com.nozzleitall.testgrid.ModelLibrary
import com.nozzleitall.testgrid.Producer
import com.nozzleitall.testgrid.Redactor
import com.nozzleitall.testgrid.ReportBuilder
import com.nozzleitall.testgrid.RunJournal
import com.nozzleitall.testgrid.RunSession
import com.nozzleitall.testgrid.SafetyLevel
import com.nozzleitall.testgrid.ScriptedOperator
import com.nozzleitall.testgrid.SimulatedPrinter
import com.nozzleitall.testgrid.SimulatedSlicer
import com.nozzleitall.testgrid.Suite
import com.nozzleitall.testgrid.SuiteCatalog
import com.nozzleitall.testgrid.TargetCheck
import com.nozzleitall.testgrid.VirtualClock
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Nozzle Test Grid command-line tool. Run from the repository root:
  ./gradlew -q :test-grid:cli --args="<command> ..."

Commands:
  suites                               List bundled suites (id, version, coverage, target, highest safety level).
  validate <suite.json>...             Parse and validate suite files.
  instructions <suite-id> [--out F]    Manual acceptance instructions (Markdown) generated from a suite.
  models --check | --write <dir>       Regenerate the acceptance models and compare with (or write) the bundled files.
  simulate --suite <id> --preset <P> --out <bundle.zip> [--level 0-4] [--fault F]... [--answers answers.json]
           [--supersedes <runId>] [--work <dir>] [--preview]
                                       Run a suite end to end against a SIMULATED printer and export a redacted bundle.
                                       Presets: PAXX_U1 STOCK_U1 COSMOS_CC COSMOS_CC_CANVAS COSMOS_CC_LEGACY GENERIC_KLIPPER.
                                       Faults: lost_ack:<step kind>, reject:<step kind>, guard_disabled.
  verify <bundle.zip>...               Verify integrity and completeness.
  preview <bundle.zip>                 Print exactly what a bundle contains.
  store-add --store <dir> <bundle.zip>...  File verified bundles under their digest (never overwrites).
  index --bundles <dir> --location <text> --out <index.json>
                                       List every verified bundle (digest, size, run, suite, level, printer) and where it is kept.
  report --bundles <dir|zip>... [--ledger acceptance.json] [--markdown out.md] [--json out.json]
                                       Build the compatibility matrix from bundles plus the bundled suites.

This tool never contacts a printer. Physical runs happen only in the app's Test Mode, one approved step at a time."""

fun main(args: Array<String>) {
    val a = args.toList()
    val code = try {
        when (a.firstOrNull()) {
            null, "help", "--help", "-h" -> { println(USAGE); 0 }
            "suites" -> suites()
            "validate" -> validate(a.drop(1))
            "instructions" -> instructions(a.drop(1))
            "models" -> models(a.drop(1))
            "simulate" -> simulate(a.drop(1))
            "verify" -> verify(a.drop(1))
            "preview" -> preview(a.drop(1))
            "store-add" -> storeAdd(a.drop(1))
            "index" -> index(a.drop(1))
            "report" -> report(a.drop(1))
            else -> { System.err.println("Unknown command ${a.first()}.\n\n$USAGE"); 2 }
        }
    } catch (e: Exception) { System.err.println("error: ${e.message}"); 1 }
    exitProcess(code)
}

private fun opt(a: List<String>, name: String): String? = a.indexOf(name).takeIf { it >= 0 }?.let { a.getOrNull(it + 1) ?: throw IllegalArgumentException("$name needs a value") }
private fun opts(a: List<String>, name: String): List<String> = a.indices.filter { a[it] == name }.mapNotNull { a.getOrNull(it + 1) }
private fun positional(a: List<String>, valued: Set<String>): List<String> {
    val out = mutableListOf<String>(); var i = 0
    while (i < a.size) { if (a[i] in valued) i += 2 else if (a[i].startsWith("--")) i++ else out += a[i++] }
    return out
}

private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "test-grid").isDirectory }
    ?: throw IllegalStateException("Run from inside the Nozzle It All repository.")

private fun suites(): Int {
    SuiteCatalog.bundled().forEach { s ->
        println("%-28s %-8s %-9s %-36s max %s".format(s.id, s.version, s.coverage.id, "${s.target.manufacturer} ${s.target.model} / ${s.target.firmwareFamily}", s.maxSafetyLevel.label))
    }
    return 0
}

private fun validate(files: List<String>): Int {
    var bad = 0
    files.forEach { f -> try { val s = Suite.parse(File(f).readText()); println("OK   $f  ${s.id} ${s.version} (${s.tests.size} tests, digest ${s.digest.take(16)}…)") } catch (e: Exception) { bad++; println("FAIL $f\n${e.message}") } }
    return if (bad == 0) 0 else 1
}

private fun instructions(a: List<String>): Int {
    val id = positional(a, setOf("--out")).firstOrNull() ?: throw IllegalArgumentException("instructions <suite-id>")
    val suite = SuiteCatalog.bundled(id) ?: throw IllegalArgumentException("No bundled suite $id.")
    val md = ManualInstructions.markdown(suite, ModelLibrary.fromResources())
    opt(a, "--out")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(md); println("Wrote $it") } ?: print(md)
    return 0
}

private fun models(a: List<String>): Int {
    val generated = AcceptanceModel.files() + ("models.json" to Canon.bytes(AcceptanceModel.manifest()))
    opt(a, "--write")?.let { dir -> File(dir).mkdirs(); generated.forEach { (n, b) -> File(dir, n).writeBytes(b); println("Wrote $dir/$n ${Canon.sha256(b)}") }; return 0 }
    require("--check" in a) { "models --check | --write <dir>" }
    val dir = File(root(), "test-grid/src/main/resources/testgrid/models")
    var bad = 0
    generated.forEach { (n, b) ->
        val f = File(dir, n)
        val ok = f.isFile && f.readBytes().contentEquals(b)
        if (!ok) bad++
        println("${if (ok) "OK  " else "DIFF"} $n ${Canon.sha256(b)}")
    }
    return if (bad == 0) 0 else 1
}

/** Build identity for CLI runs: version from app/build.gradle.kts, source revision from git, engine from ENGINE_PIN.json. */
private fun environment(work: File, clock: VirtualClock): Environment {
    val r = root()
    val version = Regex("""nozzleVersionName"\)\.orNull \?: "([^"]+)"""").find(File(r, "app/build.gradle.kts").readText())?.groupValues?.get(1) ?: "0.0.0"
    fun git(vararg c: String) = runCatching { ProcessBuilder(listOf("git", *c)).directory(r).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 } } }.getOrNull()
    val rev = git("rev-parse", "HEAD")?.let { h -> if (git("status", "--porcelain").isNullOrBlank()) h else "$h+dirty" }
    val pinFile = File(r, "engine/fork/ENGINE_PIN.json")
    val pin = JSONObject(pinFile.readText())
    val engine = EngineInfo(pin.getJSONObject("base").optString("name"), pin.getJSONObject("base").optString("commit"), null, null, Canon.sha256(pinFile.readBytes()), simulated = true)
    return Environment(Producer("Nozzle It All", "jvm-cli", version, "cli", null, rev), engine, ModelLibrary.fromResources(), Environment::resourceFixture, work,
        clock = clock::now, sleep = clock::sleep)
}

private fun simulate(a: List<String>): Int {
    val suite = SuiteCatalog.bundled(opt(a, "--suite") ?: throw IllegalArgumentException("--suite is required")) ?: throw IllegalArgumentException("No such bundled suite.")
    val preset = SimulatedPrinter.Preset.parse(opt(a, "--preset") ?: throw IllegalArgumentException("--preset is required")) ?: throw IllegalArgumentException("Unknown preset.")
    val out = File(opt(a, "--out") ?: throw IllegalArgumentException("--out is required"))
    val level = SafetyLevel.of(opt(a, "--level")?.toInt() ?: 4) ?: throw IllegalArgumentException("--level must be 0 to 4")
    val work = File(opt(a, "--work") ?: File(System.getProperty("java.io.tmpdir"), "nozzle-testgrid-${System.nanoTime()}").path)
    val answers = opt(a, "--answers")?.let { JSONObject(File(it).readText()).let { o -> o.keySet().associateWith { k -> o.getString(k) } } } ?: emptyMap()
    // Simulated time: monitor steps advance the clock instead of sleeping.
    val clock = VirtualClock()
    val env = environment(work, clock)
    val printer = SimulatedPrinter(preset, clock::now, opts(a, "--fault").toMutableSet())
    val profiles = File(root(), "app/src/main/assets/slicer_profiles")
    val slicer = SimulatedSlicer({ id, f -> File(profiles, "$id/$f").takeIf { it.isFile }?.readBytes() }, File(work, "sliced"))
    val snapshot = TargetCheck.inspect(printer)
    val journal = RunJournal(File(work, "run"))
    val session = RunSession.start(suite, printer, snapshot, slicer, env, journal, level, opts(a, "--supersedes"))
    println("SIMULATED run ${session.record.runId}: suite ${suite.id} ${suite.version} against preset $preset (no printer is contacted)")
    ScriptedOperator(answers) { println("  $it") }.run(session, printer)
    val bundle = EvidenceBuilder.build(session, env, Redactor(printer.localSecrets() + work.absolutePath), journal::attachment)
    out.absoluteFile.parentFile.mkdirs(); out.writeBytes(bundle.zip())
    session.record.tests.forEach { println("  %-34s %-10s %s".format(it.testId, it.result, it.reason.take(110))) }
    println("Wrote ${out.path}\n  bundle digest  ${bundle.bundleDigest}\n  content digest ${bundle.contentDigest}")
    if ("--preview" in a) printPreview(bundle.zip())
    return 0
}

private fun verify(files: List<String>): Int {
    var bad = 0
    files.forEach { f ->
        when (val r = BundleReader.read(File(f).readBytes())) {
            is BundleReader.Result.Valid -> println("VALID   $f  bundle ${r.bundle.bundleDigest}  content ${r.bundle.contentDigest}  (unsigned)")
            is BundleReader.Result.Invalid -> { bad++; println("INVALID $f"); r.reasons.forEach { println("  - $it") } }
        }
    }
    return if (bad == 0) 0 else 1
}

private fun printPreview(zip: ByteArray) {
    when (val r = BundleReader.read(zip)) {
        is BundleReader.Result.Invalid -> println("INVALID: ${r.reasons.joinToString("; ")}")
        is BundleReader.Result.Valid -> r.bundle.preview().forEach { e ->
            println("==== ${e.path}  (${e.bytes} bytes, sha256 ${e.sha256})")
            println(e.text ?: "[binary]")
        }
    }
}

private fun preview(a: List<String>): Int { printPreview(File(a.firstOrNull() ?: throw IllegalArgumentException("preview <bundle.zip>")).readBytes()); return 0 }

private fun storeAdd(a: List<String>): Int {
    val store = EvidenceStore(File(opt(a, "--store") ?: throw IllegalArgumentException("--store is required")))
    var bad = 0
    positional(a, setOf("--store")).forEach { f ->
        when (val r = store.add(File(f).readBytes())) {
            is EvidenceStore.AddResult.Added -> println("ADDED   $f -> ${r.file.name}")
            is EvidenceStore.AddResult.AlreadyPresent -> println("PRESENT $f (${r.file.name})")
            is EvidenceStore.AddResult.Rejected -> { bad++; println("REJECT  $f: ${r.reasons.joinToString("; ")}") }
        }
    }
    return if (bad == 0) 0 else 1
}

private fun report(a: List<String>): Int {
    val inputs = opts(a, "--bundles").flatMap { p -> File(p).let { f -> if (f.isDirectory) f.listFiles { x -> x.name.endsWith(".zip") }!!.sortedBy { it.name } else listOf(f) } }
    val ledger = opt(a, "--ledger")?.let { AcceptanceLedger.parse(File(it).readText()) } ?: AcceptanceLedger.EMPTY
    val report = ReportBuilder.build(inputs.map { it.name to it.readBytes() }, SuiteCatalog.bundled(), ledger)
    val md = ReportBuilder.markdown(report)
    opt(a, "--markdown")?.let { File(it).apply { absoluteFile.parentFile.mkdirs() }.writeText(md); println("Wrote $it") } ?: print(md)
    opt(a, "--json")?.let { File(it).apply { absoluteFile.parentFile.mkdirs() }.writeBytes(Canon.bytes(ReportBuilder.json(report))); println("Wrote $it") }
    return if (report.rejected.isEmpty()) 0 else 3
}

/** The committed record of the evidence store: bundles themselves may live outside git (photo bundles are megabytes). */
private fun index(a: List<String>): Int {
    val dir = File(opt(a, "--bundles") ?: throw IllegalArgumentException("--bundles is required"))
    val location = opt(a, "--location") ?: throw IllegalArgumentException("--location is required")
    val out = File(opt(a, "--out") ?: throw IllegalArgumentException("--out is required"))
    val rows = dir.listFiles { f -> f.name.endsWith(".zip") }!!.sortedBy { it.name }.mapNotNull { f ->
        when (val r = BundleReader.read(f.readBytes())) {
            is BundleReader.Result.Invalid -> { println("SKIP ${f.name}: ${r.reasons.first()}"); null }
            is BundleReader.Result.Valid -> {
                val e = r.bundle.evidence; val run = e.getJSONObject("run"); val t = e.getJSONObject("target")
                mapOf("bundleDigest" to r.bundle.bundleDigest, "contentDigest" to r.bundle.contentDigest, "file" to f.name, "bytes" to f.length(),
                    "runId" to run.optString("runId"), "completedAt" to run.optLong("completedAt"), "maxSafetyLevel" to run.optInt("maxSafetyLevel"),
                    "suite" to "${e.getJSONObject("suite").optString("id")} ${e.getJSONObject("suite").optString("version")}",
                    "printer" to "${t.optString("manufacturer")} ${t.optString("model")}", "firmwareFamily" to t.getJSONObject("firmware").optString("family"),
                    "kind" to t.optString("kind"), "attachments" to r.bundle.files.keys.count { it.startsWith("attachments/") }, "location" to location)
            }
        }
    }
    out.absoluteFile.parentFile.mkdirs()
    out.writeBytes(Canon.bytes(mapOf("format" to "nozzle.evidence-index", "version" to listOf(1, 0), "bundles" to rows)))
    println("Wrote ${out.path}: ${rows.size} bundles")
    return 0
}
