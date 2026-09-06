"""Run: python tools/verify-room-migration.py. Checks exported schemas and SQLite data preservation.

This is a host SQLite check; the Android Room migration test remains the device acceptance gate.
"""
import json
import pathlib
import re
import sqlite3

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/io.github.prince_dulb.ohmynotification.data.OmnDatabase"


def verify():
    before = json.loads((SCHEMAS / "1.json").read_text(encoding="utf-8"))["database"]
    after = json.loads((SCHEMAS / "2.json").read_text(encoding="utf-8"))["database"]
    previous = {item["tableName"]: item for item in before["entities"]}
    current = {item["tableName"]: item for item in after["entities"]}
    assert current.keys() - previous.keys() == {"manual_archives"}
    for name, entity in previous.items():
        assert current[name] == entity, f"Legacy schema changed: {name}"
    source = (ROOT / "app/src/main/java/io/github/prince_dulb/ohmynotification/data/OmnDatabase.kt").read_text(encoding="utf-8")
    migration = re.search(r'override fun migrate\(db: SupportSQLiteDatabase\) \{\s*db.execSQL\("([^"]+)"\)\s*\}', source)
    assert migration, "Migration must remain a single additive table statement"
    with sqlite3.connect(":memory:") as db:
        rows_before = {}
        for name, entity in previous.items():
            db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", name))
            columns = [field["columnName"] for field in entity["fields"]]
            for seed in (1, 2):
                values = [seed if field["affinity"] == "INTEGER" else f"legacy-{seed}-{field['columnName']}" for field in entity["fields"]]
                db.execute(f"INSERT INTO `{name}` ({','.join(columns)}) VALUES ({','.join('?' for _ in values)})", values)
            rows_before[name] = db.execute(f"SELECT * FROM `{name}` ORDER BY rowid").fetchall()
        db.execute(migration.group(1))
        for name, rows in rows_before.items():
            assert db.execute(f"SELECT * FROM `{name}` ORDER BY rowid").fetchall() == rows
        assert db.execute("SELECT COUNT(*) FROM manual_archives").fetchone()[0] == 0
        columns = db.execute("PRAGMA table_info(manual_archives)").fetchall()
        assert [(row[1], row[2], row[3], row[5]) for row in columns] == [
            ("itemId", "INTEGER", 1, 1), ("archivedAtEpochMillis", "INTEGER", 1, 0)]
    print("PASS schema v1->v2: all legacy schemas and seeded fields preserved; manual archive table empty")


if __name__ == "__main__":
    verify()
