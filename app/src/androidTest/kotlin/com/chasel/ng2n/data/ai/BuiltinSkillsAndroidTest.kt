package com.chasel.ng2n.data.ai

import ai.koog.agents.ext.tool.file.ReadFileTool
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltinSkillsAndroidTest {
  @Test fun packagedSkillsAreDiscoveredAndReadableOnAndroid() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val catalog = BuiltinSkills(context).prepare()
    assertEquals(10, Regex("<skill>").findAll(catalog.prompt).count())
    @Suppress("UNCHECKED_CAST")
    val read = catalog.registry.tools.single { it.name == "__read_file__" } as ReadFileTool<File>
    val result = read.execute(ReadFileTool.Args(File(catalog.root, "fact-check/SKILL.md").absolutePath))
    assertTrue(result.toString().contains("外部核查未做"))
    assertEquals(setOf("__read_file__", "__list_directory__"), catalog.registry.tools.map { it.name }.toSet())
  }
}
