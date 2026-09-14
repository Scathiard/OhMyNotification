"""Run: python tools/verify-room-migration.py. Checks exported schemas and SQLite data preservation.

This is a host SQLite check; the Android Room migration test remains the device acceptance gate.
"""
import json
import pathlib
import re
import sqlite3

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/io.github.prince_dulb.ohmynotification.data.OmnDatabase"


def verify(from_version):
    before = json.loads((SCHEMAS / f"{from_version}.json").read_text(encoding="utf-8"))["database"]
    after = json.loads((SCHEMAS / "3.json").read_text(encoding="utf-8"))["database"]
    previous = {item["tableName"]: item for item in before["entities"]}
    current = {item["tableName"]: item for item in after["entities"]}
    expected_new = {"notification_revisions", "notification_targets"}
    if from_version == 1:
        expected_new.add("manual_archives")
    assert current.keys() - previous.keys() == expected_new
    for name, entity in previous.items():
        if name != "notification_items":
            assert current[name] == entity, f"Legacy schema changed: {name}"
        else:
            for field in entity["fields"]:
                assert field in current[name]["fields"], f"Legacy field changed: {field}"
            assert current[name]["primaryKey"] == entity["primaryKey"]
            assert current[name]["indices"] == entity["indices"]
    source = (ROOT / "app/src/main/java/io/github/prince_dulb/ohmynotification/data/OmnDatabase.kt").read_text(encoding="utf-8")
    migrations = []
    for version in range(from_version, 3):
        match = re.search(rf'val MIGRATION_{version}_{version + 1} = .*?override fun migrate\(db: SupportSQLiteDatabase\) \{{(.*?)\n            \}}', source, re.S)
        assert match, f"Missing migration {version}"
        statements = re.findall(r'db.execSQL\("([^"]+)"\)', match.group(1))
        assert statements and all(sql.startswith(("CREATE TABLE", "CREATE INDEX", "ALTER TABLE")) for sql in statements)
        migrations.extend(statements)
    with sqlite3.connect(":memory:") as db:
        rows_before = {}
        old_columns = {}
        for name, entity in previous.items():
            db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", name))
            columns = [field["columnName"] for field in entity["fields"]]
            old_columns[name] = columns
            for seed in (1, 2):
                values = [seed if field["affinity"] == "INTEGER" else f"legacy-{seed}-{field['columnName']}" for field in entity["fields"]]
                db.execute(f"INSERT INTO `{name}` ({','.join(columns)}) VALUES ({','.join('?' for _ in values)})", values)
            rows_before[name] = db.execute(f"SELECT * FROM `{name}` ORDER BY rowid").fetchall()
        for sql in migrations:
            db.execute(sql)
        for name, rows in rows_before.items():
            projection = ','.join(f'`{column}`' for column in old_columns[name])
            assert db.execute(f"SELECT {projection} FROM `{name}` ORDER BY rowid").fetchall() == rows
        assert db.execute("SELECT COUNT(*) FROM manual_archives").fetchone()[0] == (0 if from_version == 1 else 2)
        for table in ("notification_revisions", "notification_targets"):
            assert db.execute(f"SELECT COUNT(*) FROM `{table}`").fetchone()[0] == 0
        assert db.execute("SELECT lastListenerConnectionId,lastObservedPostTimeEpochMillis,identityUncertain FROM notification_items").fetchall() == [(None, None, 0)] * 2
        columns = db.execute("PRAGMA table_info(manual_archives)").fetchall()
        assert [(row[1], row[2], row[3], row[5]) for row in columns] == [
            ("itemId", "INTEGER", 1, 1), ("archivedAtEpochMillis", "INTEGER", 1, 0)]
    print(f"PASS schema v{from_version}->v3: all legacy fields preserved; new target/revision tables empty; unknown metadata not fabricated")


if __name__ == "__main__":
    verify(1)
    verify(2)
