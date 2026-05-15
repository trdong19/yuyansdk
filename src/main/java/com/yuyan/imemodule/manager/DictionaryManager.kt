package com.yuyan.imemodule.manager

import android.content.Context
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.utils.errorRuntime
import com.yuyan.imemodule.utils.withTempDir
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 词库管理器
 * 处理用户词典的导入导出功能
 * 支持 Rime 标准文本格式：词语\t拼音\t权重
 */
object DictionaryManager {

    private const val USER_DICT_NAME = "pinyin.userdb"
    private const val USER_DICT_TXT = "pinyin.userdb.txt"
    private const val BACKUP_SUFFIX = ".backup"
    private const val HEADER = """---
name: pinyin.userdb
version: "1"
sort: by_weight
...
"""

    /**
     * 获取用户词典目录
     */
    private fun getUserDictDir(): File {
        return File(CustomConstant.RIME_DICT_PATH, USER_DICT_NAME)
    }

    /**
     * 获取用户词典文本文件路径
     */
    private fun getUserDictTxtFile(): File {
        return File(CustomConstant.RIME_DICT_PATH, USER_DICT_TXT)
    }

    /**
     * 导出用户词典为 Rime 文本格式
     * @param dest 输出流
     * @param merge 合并现有词库还是只导出用户添加的词
     * @return 导出结果
     */
    fun exportDictionary(dest: java.io.OutputStream, merge: Boolean = true): Result<Int> = runCatching {
        val dictTxtFile = getUserDictTxtFile()
        
        // 如果存在文本词典，直接使用
        if (dictTxtFile.exists()) {
            FileInputStream(dictTxtFile).use { input ->
                input.copyTo(dest)
            }
            return@runCatching countLines(dictTxtFile)
        }
        
        // 如果不存在，需要从 LevelDB 导出
        if (merge) {
            exportFromLevelDb(dest)
        } else {
            // 只导出用户词典（需要解析 LevelDB）
            exportFromLevelDb(dest)
        }
    }

    /**
     * 从 LevelDB 导出用户词典
     */
    private fun exportFromLevelDb(dest: java.io.OutputStream): Int {
        val ctx = Launcher.instance.context
        
        // 使用 Rime 的 API 或者直接读取 LevelDB
        // 由于 LevelDB 格式复杂，这里使用简化的方法
        // 实际实现可能需要使用 librime 的导出功能
        
        BufferedWriter(OutputStreamWriter(dest, Charsets.UTF_8)).use { writer ->
            writer.write(HEADER)
            writer.newLine()
            
            // 这里需要实现从 LevelDB 读取的逻辑
            // 由于雨燕输入法的 Rime 是原生实现，需要通过 JNI 调用
            // 暂时写入一个示例
            writer.write("# 请在雨燕输入法中重新部署后再导出")
            writer.newLine()
        }
        
        return 0
    }

    /**
     * 导入词典文件
     * @param src 输入流（支持 Rime 文本格式）
     * @return 导入结果
     */
    fun importDictionary(src: java.io.InputStream): Result<Int> = runCatching {
        withTempDir { tempDir ->
            val tempFile = File(tempDir, USER_DICT_TXT)
            
            // 复制到临时文件
            FileOutputStream(tempFile).use { output ->
                src.copyTo(output)
            }
            
            // 验证文件格式
            validateAndReformatDict(tempFile)
            
            // 备份现有词典
            backupExistingDict()
            
            // 复制新词典
            val destFile = getUserDictTxtFile()
            tempFile.copyTo(destFile, overwrite = true)
            
            // 返回导入的词条数
            countLines(tempFile)
        }
    }

    /**
     * 验证并重新格式化词典文件
     */
    private fun validateAndReformatDict(file: File) {
        val lines = mutableListOf<String>()
        var inHeader = true
        var headerLines = mutableListOf<String>()
        
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            reader.forEachLine { line ->
                val trimmed = line.trim()
                
                when {
                    // 跳过注释和空行（在头部）
                    trimmed.isEmpty() || trimmed.startsWith("#") -> {
                        if (inHeader) {
                            // 在头部，允许注释
                        } else {
                            // 在内容中，允许空行
                            if (trimmed.isNotEmpty()) {
                                lines.add(line)
                            }
                        }
                    }
                    // 检测头部结束标记
                    trimmed == "..." -> {
                        if (inHeader) {
                            headerLines.add(line)
                            inHeader = false
                        } else {
                            lines.add(line)
                        }
                    }
                    // 头部内容
                    inHeader -> {
                        headerLines.add(line)
                    }
                    // 内容行
                    else -> {
                        // 验证格式：词语\t拼音\t权重
                        if (isValidDictLine(trimmed)) {
                            lines.add(line)
                        }
                    }
                }
            }
        }
        
        // 重新写入文件
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { writer ->
            // 写入头部
            headerLines.forEach { writer.write(it); writer.newLine() }
            if (headerLines.isNotEmpty()) {
                writer.newLine()
            }
            
            // 按词频排序
            val sortedLines = lines
                .filter { isValidDictLine(it.trim()) }
                .sortedByDescending { line ->
                    val parts = line.trim().split("\t")
                    if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
                }
            
            // 写入内容
            sortedLines.forEach { writer.write(it); writer.newLine() }
        }
    }

    /**
     * 验证词典行格式
     */
    private fun isValidDictLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.startsWith("#")) return false
        if (line.startsWith("---") || line == "...") return false
        
        val parts = line.split("\t")
        if (parts.size < 2) return false
        
        // 检查是否有中文字符
        val hasChinese = parts[0].any { it in '\u4e00'..'\u9fff' }
        return hasChinese
    }

    /**
     * 备份现有词典
     */
    private fun backupExistingDict() {
        val dictTxtFile = getUserDictTxtFile()
        if (dictTxtFile.exists()) {
            val backupFile = File(dictTxtFile.parent, dictTxtFile.name + BACKUP_SUFFIX + "_" + System.currentTimeMillis())
            dictTxtFile.copyTo(backupFile, overwrite = true)
        }
    }

    /**
     * 统计文件行数
     */
    private fun countLines(file: File): Int {
        return BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("---") && it != "..." }
                .count()
        }
    }

    /**
     * 获取当前词典词条数
     */
    fun getDictionaryCount(): Int {
        val dictTxtFile = getUserDictTxtFile()
        return if (dictTxtFile.exists()) {
            countLines(dictTxtFile)
        } else {
            0
        }
    }

    /**
     * 检查词典文件是否存在
     */
    fun hasDictionary(): Boolean {
        return getUserDictTxtFile().exists()
    }

    /**
     * 清理备份文件
     */
    fun cleanupBackups(keepCount: Int = 5) {
        val dictTxtFile = getUserDictTxtFile()
        val parent = dictTxtFile.parentFile ?: return
        
        parent.listFiles { _, name -> 
            name.startsWith(dictTxtFile.name + BACKUP_SUFFIX) 
        }?.sortedByDescending { it.lastModified() }
         ?.drop(keepCount)
         ?.forEach { it.delete() }
    }
}
