package com.yuyan.imemodule.manager

import android.content.Context
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
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
 * 支持 Rime 文本格式：词语\t拼音\t权重
 */
object DictionaryManager {

    private const val USER_DICT_TXT = "pinyin.userdb.txt"

    /**
     * 导出用户词典为 Rime 文本格式
     * @param dest 输出流
     * @return 导出结果（词条数）
     */
    fun exportDictionary(dest: java.io.OutputStream): Result<Int> = runCatching {
        val phrases = DataBaseKT.instance.phraseDao().getAll()
        
        BufferedWriter(OutputStreamWriter(dest, Charsets.UTF_8)).use { writer ->
            // 写入头部信息
            writer.write("---")
            writer.newLine()
            writer.write("name: pinyin.userdb")
            writer.newLine()
            writer.write("version: \"1\"")
            writer.newLine()
            writer.write("sort: by_weight")
            writer.newLine()
            writer.write("...")
            writer.newLine()
            writer.newLine()
            
            // 写入每个词条
            for (phrase in phrases) {
                // 格式：词语\t拼音\t权重
                // 这里用 qwerty 作为拼音的占位符，权重用时间戳
                writer.write("${phrase.content}\t${phrase.qwerty}\t${phrase.time}")
                writer.newLine()
            }
        }
        
        phrases.size
    }

    /**
     * 导入词典文件
     * @param src 输入流（支持 Rime 文本格式）
     * @return 导入结果（词条数）
     */
    fun importDictionary(src: java.io.InputStream): Result<Int> = runCatching {
        val phrases = mutableListOf<Phrase>()
        
        BufferedReader(InputStreamReader(src, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && 
                    !trimmed.startsWith("#") && 
                    !trimmed.startsWith("---") && 
                    trimmed != "...") {
                    
                    // 尝试解析 Rime 格式：词语\t拼音\t权重
                    val parts = trimmed.split("\t")
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                        val content = parts[0]
                        
                        // 生成简拼
                        val qwerty = PinyinHelper.getPinYinHeadChar(content.take(4))
                        val t9 = qwerty.map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
                        val lx17 = qwerty.map { LX17PinYinUtils.pinyin2Lx17Key(it) }.joinToString("")
                        
                        // 读取权重（如果有）
                        val time = if (parts.size >= 3) {
                            parts[2].toLongOrNull() ?: System.currentTimeMillis()
                        } else {
                            System.currentTimeMillis()
                        }
                        
                        val phrase = Phrase(
                            content = content,
                            isKeep = 0,
                            t9 = t9,
                            qwerty = qwerty,
                            lx17 = lx17,
                            time = time
                        )
                        phrases.add(phrase)
                    }
                }
            }
        }
        
        // 导入到数据库
        if (phrases.isNotEmpty()) {
            DataBaseKT.instance.phraseDao().insertAll(phrases)
        }
        
        phrases.size
    }

    /**
     * 获取当前词典词条数
     */
    fun getDictionaryCount(): Int {
        return DataBaseKT.instance.phraseDao().getAll().size
    }

    /**
     * 检查词典是否为空
     */
    fun hasDictionary(): Boolean {
        return DataBaseKT.instance.phraseDao().getAll().isNotEmpty()
    }
}
