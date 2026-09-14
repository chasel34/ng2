package com.chasel.ng2n.data.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AiSchemaLifecycleTest {
  private val entities by lazy {
    val relative = "app/schemas/com.chasel.ng2n.data.db.Ng2nDatabase/3.json"
    val schema = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
      .map { File(it, relative) }.first { it.isFile }
    Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
      .getValue("entities").jsonArray.associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
  }
  @Test fun conversationDeletionCascadesToRecoveryDataButNeverUsage() {
    for (name in listOf("ai_message", "ai_reading_range", "ai_source", "ai_run", "ai_working_state")) {
      val key = entities.getValue(name).getValue("foreignKeys").jsonArray.single().jsonObject
      assertEquals(name, "ai_conversation", key.getValue("table").jsonPrimitive.content)
      assertEquals(name, "CASCADE", key.getValue("onDelete").jsonPrimitive.content)
    }
    assertTrue(entities.getValue("ai_usage")["foreignKeys"]?.jsonArray.orEmpty().isEmpty())
  }
  @Test fun historyHasNoAccountPartitionAndCitationTableHasNoOriginalContent() {
    fun fields(table: String) = entities.getValue(table).getValue("fields").jsonArray.map {
      it.jsonObject.getValue("columnName").jsonPrimitive.content
    }
    assertFalse(fields("ai_conversation").any { it.contains("uid", true) || it.contains("account", true) })
    assertEquals(setOf("conversationId", "sourceId", "tid", "pid", "floor", "page", "author", "postedAt", "readAt", "contentHash"), fields("ai_source").toSet())
  }
}
