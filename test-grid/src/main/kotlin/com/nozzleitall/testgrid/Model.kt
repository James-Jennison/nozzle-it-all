package com.nozzleitall.testgrid

/**
 * The five things a printer configuration is graded on. Each is graded only from tests of its own category: a pass in
 * one never counts toward another (see [CategoryGrades]).
 */
enum class Category(val id: String, val label: String) {
    SLICING("slicing", "Slicing"),
    FILE_TRANSFER("file_transfer", "File transfer"),
    MONITORING("monitoring", "Monitoring"),
    CONTROLS("controls", "Controls"),
    PHYSICAL_PRINT("physical_print", "Physical printing");

    companion object { fun parse(id: String): Category? = entries.firstOrNull { it.id == id } }
}

/** A graded outcome. UNVERIFIED means no evidence either way: never run, simulated, or an unknown command outcome. */
enum class ResultState { PASS, FAIL, PARTIAL, SKIPPED, BLOCKED, UNVERIFIED;
    companion object { fun parse(s: String): ResultState? = entries.firstOrNull { it.name == s } }
}

/** How far a test may reach into the printer. A run's operator chooses the highest level they allow. */
enum class SafetyLevel(val level: Int, val label: String) {
    SOFTWARE(0, "Level 0 · software-only validation"),
    READ_ONLY(1, "Level 1 · read-only discovery and telemetry"),
    REVERSIBLE_FILES(2, "Level 2 · reversible file operations"),
    SUPERVISED_CONTROLS(3, "Level 3 · supervised controls"),
    PHYSICAL_PRINT(4, "Level 4 · physical printing");

    companion object { fun of(level: Int): SafetyLevel? = entries.firstOrNull { it.level == level } }
}

/**
 * Single-material acceptance is kept apart from anything that changes material or tool mid-print (multicolour, CANVAS,
 * AMS, MMU, toolchangers): a single-material pass says nothing about those, and the report grades them separately.
 */
enum class MaterialScope(val id: String, val label: String) {
    SINGLE("single_material", "Single material"),
    MULTI("multi_material", "Multi-material / tool changing");

    companion object { fun parse(id: String): MaterialScope? = entries.firstOrNull { it.id == id } }
}

/** Where the evidence came from. Only [PHYSICAL] evidence can grade a printer; simulated runs exercise the software. */
enum class TargetKind(val id: String) { PHYSICAL("physical"), SIMULATED("simulated");
    companion object { fun parse(id: String): TargetKind? = entries.firstOrNull { it.id == id } }
}

/**
 * Grades a set of test results for one category. Pure; the evidence bundle and the compatibility report both use it,
 * so a category's grade always means the same thing.
 */
object CategoryGrades {
    fun grade(results: List<ResultState>): ResultState = when {
        results.isEmpty() -> ResultState.UNVERIFIED
        ResultState.FAIL in results -> ResultState.FAIL
        results.all { it == ResultState.PASS } -> ResultState.PASS
        results.any { it == ResultState.PASS || it == ResultState.PARTIAL } -> ResultState.PARTIAL
        results.all { it == ResultState.SKIPPED } -> ResultState.SKIPPED
        ResultState.BLOCKED in results -> ResultState.BLOCKED
        else -> ResultState.UNVERIFIED
    }
}

/** Dotted numeric version compare ("0.1.0" vs "0.10.2"); non-numeric suffixes ("-debug", ".paxx") are ignored. */
object Versions {
    fun parse(v: String): List<Int>? {
        val core = Regex("""^\s*v?(\d+(?:\.\d+)*)""").find(v)?.groupValues?.get(1) ?: return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Null when either side can't be parsed: the caller must not guess. */
    fun atLeast(actual: String, required: String): Boolean? {
        val a = parse(actual) ?: return null
        val r = parse(required) ?: return null
        for (i in 0 until maxOf(a.size, r.size)) {
            val x = a.getOrElse(i) { 0 }; val y = r.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}
