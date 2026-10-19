#!/usr/bin/env python3
"""Replay shipped schema7 -> reviewed schema9 on synthetic data, never app data."""
import json
from pathlib import Path
import re
import sqlite3
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "c41680a18fcf90a9a9016f575157176d2a958337"
BASELINE = "5f057685964fe09214ab2fdd4ef87393f32eef45"
SCHEMAS = "app/schemas/net.jamesjennison.klippercompanion.project.AppDatabase"


def source(path, revision=SOURCE):
    return subprocess.check_output(["git", "show", f"{revision}:{path}"], cwd=ROOT, text=True)


def verify(code_override=None, report=True):
    old = json.loads(source(f"{SCHEMAS}/7.json", BASELINE))["database"]
    new = json.loads(source(f"{SCHEMAS}/9.json"))["database"]
    code = code_override or source("app/src/main/java/net/jamesjennison/klippercompanion/project/AppDatabase.kt")
    assert ").addMigrations(" in code
    assert ".fallbackToDestructiveMigration(" not in code
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for entity in sorted(old["entities"], key=lambda e: e["tableName"] != "projects"):
        table = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        columns = [f["columnName"] for f in entity["fields"]]
        # Include non-null values even in optional columns: migration must retain them.
        values = [1.25 if f["affinity"] == "REAL" else 123 if f["affinity"] == "INTEGER"
                  else "fixture-project" if f["columnName"] == "projectId"
                  else "fixture-project" if table == "projects" and f["columnName"] == "id"
                  else f"fixture-{table}-{f['columnName']}" for f in entity["fields"]]
        db.execute(f"INSERT INTO `{table}` ({','.join('`'+c+'`' for c in columns)}) VALUES ({','.join('?' for _ in columns)})", values)
    before = {e["tableName"]: db.execute(f"SELECT * FROM `{e['tableName']}`").fetchall() for e in old["entities"]}
    for start, end in [(7, 8), (8, 9)]:
        # Narrow grammar for these pinned additive migrations, not a general Kotlin parser.
        # Match the entire object/method shape; unsupported nested/multiline code fails closed.
        block = re.search(
            rf"val MIGRATION_{start}_{end} = object : androidx\.room\.migration\.Migration\({start}, {end}\)\s*"
            r'\{\s*override fun migrate\(db: SupportSQLiteDatabase\)\s*\{(?:\s*db\.execSQL\("[^"\\]*"\)\s*)+\}\s*\}', code)
        assert block, f"Missing {start}->{end} migration"
        statements = re.findall(r'db\.execSQL\("([^"\\]*)"\)', block.group())
        assert len(statements) == block.group().count("db.execSQL(") == (2 if start == 7 else 1), "Unrecognized or additional migration SQL; fail closed"
        for statement in statements:
            assert statement.startswith("ALTER TABLE ") and " ADD COLUMN " in statement
            db.execute(statement)
    for entity in old["entities"]:
        table = entity["tableName"]
        columns = ','.join('`'+f['columnName']+'`' for f in entity["fields"])
        assert db.execute(f"SELECT {columns} FROM `{table}`").fetchall() == before[table]
    for entity in new["entities"]:
        actual = db.execute(f"PRAGMA table_info(`{entity['tableName']}`)").fetchall()
        assert [(r[1], r[2], bool(r[3])) for r in actual] == [(f["columnName"], f["affinity"], f.get("notNull", False)) for f in entity["fields"]]
        keys = entity["primaryKey"]["columnNames"]
        for row, field in zip(actual, entity["fields"]):
            assert row[4] == field.get("defaultValue"), "Unexpected column default"
            assert row[5] == (keys.index(field["columnName"]) + 1 if field["columnName"] in keys else 0), "Primary key drift"
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
    db.close()
    if report:
        print(json.dumps({"result": "PASS", "source": SOURCE, "baseline": BASELINE, "from_schema": 7, "to_schema": 9,
                      "preserved_fixture_tables": len(before), "physical_owner_data": False}))


if __name__ == "__main__":
    verify()
    code = source("app/src/main/java/net/jamesjennison/klippercompanion/project/AppDatabase.kt")
    for replacement in ["processPreset INTEGER", "processPreset TEXT DEFAULT 'bad'", 'processPreset TEXT\\n']:
        broken = code.replace("processPreset TEXT", replacement)
        try:
            verify(broken, report=False)
        except (AssertionError, sqlite3.DatabaseError):
            continue
        raise AssertionError("Negative fixture was not rejected")
    print(json.dumps({"negative_fixtures_rejected": 3, "result": "PASS"}))
