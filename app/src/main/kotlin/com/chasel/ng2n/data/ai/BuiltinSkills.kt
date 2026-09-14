package com.chasel.ng2n.data.ai

import android.content.Context
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.FileMetadata
import kotlinx.io.Buffer
import ai.koog.rag.base.files.filter
import ai.koog.rag.base.files.filter.TraversalFilter
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import com.chasel.ng2n.BuildConfig
import com.chasel.ng2n.core.ai.BuiltinQuickActions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuiltinSkills @Inject constructor(@ApplicationContext private val context: Context) {
  suspend fun prepare(): SkillCatalog = withContext(Dispatchers.IO) {
    val root = releaseSkills(File(context.filesDir, "ai-skills"), "${BuildConfig.VERSION_CODE}-${BuiltinQuickActions.VERSION}",
      { context.assets.list(it).orEmpty().toList() }, { context.assets.open(it).use { stream -> stream.readBytes() } })
    SkillCatalog.create(root)
  }
}

internal fun releaseSkills(base: File, version: String, list: (String) -> List<String>, read: (String) -> ByteArray): File = synchronized(SkillCatalog::class.java) {
  require(version.matches(Regex("[A-Za-z0-9.-]+")))
  check(base.isDirectory || base.mkdirs())
  val destination = File(base, version)
  if (!File(destination, ".complete").isFile) {
    val staging = File(base, "$version.tmp")
    staging.deleteRecursively()
    check(staging.mkdirs())
    fun copy(asset: String, target: File) {
      val children = list(asset)
      if (children.isEmpty()) target.writeBytes(read(asset))
      else { check(target.isDirectory || target.mkdirs()); children.forEach { copy("$asset/$it", File(target, it)) } }
    }
    try {
      copy("ai-skills", staging)
      File(staging, ".complete").writeText(version)
      destination.deleteRecursively()
      check(staging.renameTo(destination))
    } catch (error: Exception) { staging.deleteRecursively(); throw error }
  }
  base.listFiles().orEmpty().filter { it != destination }.forEach { check(it.deleteRecursively()) }
  destination
}

class SkillCatalog private constructor(val root: File, val prompt: String, val registry: ToolRegistry) {
  val version: String get() = root.name
  companion object {
    suspend fun create(root: File): SkillCatalog {
      val canonical = root.canonicalFile.toPath()
      val fs = SkillFileSystem.filter(TraversalFilter { path, _ ->
        path.canonicalFile.toPath().startsWith(canonical) && path.name != ".complete"
      })
      val skills = discoverSkills(fs, listOf(root.absolutePath))
      check(skills.size == BuiltinQuickActions.all.size) { "内置 skills 不完整" }
      return SkillCatalog(root, generateSkillsPrompt(skills, SkillsPromptFormat.XML), ToolRegistry {
        tool(ListDirectoryTool(fs)); tool(ReadFileTool(fs))
      })
    }
  }
}

// Koog 的 Android artifact 不包含 JVMFileSystemProvider，文件工具通过同一 ReadOnly 接口读取随包文档。
internal object SkillFileSystem : FileSystemProvider.ReadOnly<File> {
  override fun toAbsolutePathString(path: File) = path.absolutePath
  override fun fromAbsolutePathString(path: String) = File(path).also { require(it.isAbsolute) }
  override fun joinPath(base: File, vararg parts: String): File = parts.fold(base) { file, part ->
    require(!File(part).isAbsolute); File(file, part)
  }
  override fun name(path: File) = path.name
  override fun extension(path: File) = path.extension
  override suspend fun metadata(path: File): FileMetadata? = when {
    path.isFile -> FileMetadata(FileMetadata.FileType.File, path.isHidden)
    path.isDirectory -> FileMetadata(FileMetadata.FileType.Directory, path.isHidden)
    else -> null
  }
  override suspend fun getFileContentType(path: File) = if (path.extension == "md") FileMetadata.FileContentType.Text else FileMetadata.FileContentType.Binary
  override suspend fun list(directory: File) = checkNotNull(directory.listFiles()).sortedBy { it.name }
  override fun parent(path: File) = path.parentFile
  override fun relativize(root: File, path: File) = path.relativeToOrNull(root)?.path
  override suspend fun exists(path: File) = path.exists()
  override suspend fun readBytes(path: File) = path.readBytes()
  override suspend fun inputStream(path: File) = Buffer().apply { write(path.readBytes()) }
  override suspend fun size(path: File) = path.length()
}
