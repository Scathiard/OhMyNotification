package io.github.prince_dulb.ohmynotification.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveMigrationTest {
    @Test fun realV1SchemaMigratesWithoutChangingAnyLegacyField() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open(
            "io.github.prince_dulb.ohmynotification.data.OmnDatabase/1.json",
        ).bufferedReader().use { it.readText() }).getJSONObject("database")
        val expected = linkedMapOf<String, List<List<String?>>>()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                repeat(2) { seed ->
                    val values = ContentValues()
                    val fields = entity.getJSONArray("fields")
                    for (i in 0 until fields.length()) {
                        val field = fields.getJSONObject(i)
                        val column = field.getString("columnName")
                        if (field.getString("affinity") == "INTEGER") values.put(column, (seed + 1).toLong())
                        else values.put(column, "legacy-$seed-$column")
                    }
                    old.insertOrThrow(table, null, values)
                }
                old.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { cursor ->
                    expected[table] = buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
                }
            }
            old.version = 1
        }
        val database = Room.databaseBuilder(context, OmnDatabase::class.java, name)
            .addMigrations(OmnDatabase.MIGRATION_1_2).build()
        try {
            val upgraded = database.openHelper.writableDatabase
            assertEquals(2, upgraded.version)
            expected.forEach { (table, rows) ->
                upgraded.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                    val actual = buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
                    assertEquals(table, rows, actual)
                }
            }
            upgraded.query("SELECT COUNT(*) FROM manual_archives").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
