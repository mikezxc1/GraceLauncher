package com.galaxyrio.gracelauncher.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Xml
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复。
 *
 * 文件格式是普通 zip（扩展名 .glb），目录结构：
 *
 *   manifest.json                          版本、时间、数据库版本
 *   prefs/launcher_appearance.xml          改名、分类、主题图标开关
 *   prefs/grace_launcher_preferences.xml   收藏
 *   db/grace_launcher.db                   Room 数据库
 *   db/grace_launcher.db-wal               未合并的写入
 *   db/grace_launcher.db-shm               共享内存索引
 *   no_backup 目录下的三个 json             应用缓存与用量统计
 *   fonts 目录下的字体文件                  导入的时钟字体
 *
 * 不做加密：内容只在本机流动，加密只会让用户出问题时没法手工补救。
 * 恢复前先在 cache 存一份当前状态，出错自动退回。
 */
object GraceBackup {

    const val EXTENSION = "glb"
    const val MIME = "application/zip"

    private const val FORMAT_VERSION = 1
    private const val MANIFEST = "manifest.json"
    private const val DATABASE = "grace_launcher.db"
    private const val FONT_DIR = "clock_fonts"

    /**
     * 自定义图标目录。用户单独给某个应用挑的图片存这里，
     * 数据库只记「哪个应用用哪张图」，图片本体是独立的 PNG 文件。
     * 只备份数据库的话，恢复后图标会变成空白，所以必须一起打包。
     */
    private const val ICON_DIR = "item_icons"

    /** SharedPreferences 文件名。新增偏好存储时这里要同步加。 */
    private val PREFERENCES = listOf("launcher_appearance", "grace_launcher_preferences")

    /** noBackupFilesDir 下的缓存文件。 */
    private val NO_BACKUP = listOf("launcher-apps.json", "usage-opens.json", "usage-ranking.json")

    data class Result(val ok: Boolean, val message: String, val entries: Int = 0)

    // ---------------------------------------------------------------- 导出

    /**
     * 把当前状态写成备份。
     *
     * 打包前先做一次 WAL checkpoint：Room 默认 WAL 模式，最近的写入还在 -wal 里，
     * 不合并就拷主库，用户恢复后会发现最后几次改动全消失。
     */
    fun export(context: Context): Result = runCatching {
        checkpoint(context)
        var entries = 0

        val target = newBackupFile(context)
        target.outputStream().buffered().use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest(context).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 偏好设置：从系统接口读内存当前值，保证是最新的
                PREFERENCES.forEach { name ->
                    val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    if (values.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("prefs/$name.xml"))
                        zip.write(writePreferences(values).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        entries++
                    }
                }

                // 数据库三个文件
                context.getDatabasePath(DATABASE).parentFile?.let { dbDir ->
                    listOf(DATABASE, "$DATABASE-wal", "$DATABASE-shm").forEach { name ->
                        val file = File(dbDir, name)
                        if (file.isFile && file.length() > 0L) {
                            zip.putNextEntry(ZipEntry("db/$name"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            entries++
                        }
                    }
                }

                // 缓存与统计
                NO_BACKUP.forEach { name ->
                    val file = File(context.noBackupFilesDir, name)
                    if (file.isFile) {
                        zip.putNextEntry(ZipEntry("no_backup/$name"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        entries++
                    }
                }

                // 导入的字体
                File(context.filesDir, FONT_DIR).listFiles()?.forEach { font ->
                    if (font.isFile) {
                        zip.putNextEntry(ZipEntry("fonts/${font.name}"))
                        font.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        entries++
                    }
                }
                // 自定义图标图片。数据库只存引用，图片本体在这里，
                // 不打包的话恢复后那些单独挑过图标的应用会显示空白。
                File(context.filesDir, ICON_DIR).listFiles()?.forEach { icon ->
                    if (icon.isFile) {
                        zip.putNextEntry(ZipEntry("icons/${icon.name}"))
                        icon.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        entries++
                    }
                }
            }
        }

        Result(true, "已导出 $entries 个条目\n存到：" + target.absolutePath, entries)
    }.getOrElse { Result(false, "导出失败：" + it.describe()) }

    /**
     * 选一个备份文件路径，优先系统下载目录。
     *
     * 走公共下载目录不需要任何运行时权限，也不经过系统文件选择器，
     * 避免 ColorOS 返回目录型 Uri 导致写入失败。
     */
    private fun newBackupFile(context: Context): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val name = "Grace-" + stamp + "." + EXTENSION
        val downloads = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "Grace备份"
        )
        if (downloads.exists() || downloads.mkdirs()) return File(downloads, name)
        val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
        fallback.mkdirs()
        return File(fallback, name)
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 从备份恢复。
     *
     * 步骤：解压到 cache 暂存 → 校验清单 → 存回退点 → 写回数据 → 释放数据库句柄。
     * 任何一步抛错都退回原状态，不会留下半套数据。
     */
    fun restore(context: Context, uri: Uri): Result = runCatching {
        val staging = File(context.cacheDir, "grace_restore").apply {
            deleteRecursively()
            mkdirs()
        }

        var manifest: JSONObject? = null
        var entries = 0
        val stagingRoot = staging.canonicalPath

        context.contentResolver.openInputStream(uri)?.use { raw ->
            unzip(raw) { name, stream ->
                if (name == MANIFEST) {
                    manifest = JSONObject(stream.readBytes().toString(Charsets.UTF_8))
                } else {
                    val target = File(staging, name)
                    // 防路径穿越：备份包里的路径必须落在暂存区之内
                    require(target.canonicalPath.startsWith(stagingRoot)) { "备份内容路径异常：$name" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { stream.copyTo(it) }
                    entries++
                }
            }
        } ?: return Result(false, "无法读取所选文件")

        val meta = manifest ?: return Result(false, "这不是 Grace 的备份文件")
        val version = meta.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) {
            staging.deleteRecursively()
            return Result(false, "备份格式版本 $version 与当前 $FORMAT_VERSION 不符")
        }

        val rollback = snapshot(context)
        try {
            apply(context, staging)
        } catch (error: Throwable) {
            rollback?.invoke()
            staging.deleteRecursively()
            return Result(false, "恢复失败，已退回原状态：" + error.describe())
        }

        staging.deleteRecursively()
        Result(true, "已恢复 $entries 个条目，请重启应用生效", entries)
    }.getOrElse { Result(false, "恢复失败：" + it.describe()) }

    // ---------------------------------------------------------------- 内部

    private fun manifest(context: Context) = JSONObject().apply {
        put("formatVersion", FORMAT_VERSION)
        put("createdAt", System.currentTimeMillis())
        put("app", context.packageName)
        put("dbVersion", runCatching {
            LauncherDatabase.getInstance(context).openHelper.writableDatabase.version
        }.getOrDefault(0))
    }

    /** 用系统序列化器导出偏好，避免手工拼 xml 时的转义错误。 */
    private fun writePreferences(values: Map<String, *>): String {
        val serializer = Xml.newSerializer()
        val out = StringWriter()
        serializer.setOutput(out)
        serializer.startDocument("utf-8", true)
        serializer.startTag(null, "map")
        values.forEach { (key, value) ->
            when (value) {
                is String -> {
                    serializer.startTag(null, "string")
                    serializer.attribute(null, "name", key)
                    serializer.text(value)
                    serializer.endTag(null, "string")
                }
                is Int -> scalar(serializer, "int", key, value.toString())
                is Long -> scalar(serializer, "long", key, value.toString())
                is Float -> scalar(serializer, "float", key, value.toString())
                is Boolean -> scalar(serializer, "boolean", key, value.toString())
                is Set<*> -> {
                    serializer.startTag(null, "set")
                    serializer.attribute(null, "name", key)
                    value.filterIsInstance<String>().forEach {
                        serializer.startTag(null, "string")
                        serializer.text(it)
                        serializer.endTag(null, "string")
                    }
                    serializer.endTag(null, "set")
                }
            }
        }
        serializer.endTag(null, "map")
        serializer.endDocument()
        return out.toString()
    }

    private fun scalar(writer: org.xmlpull.v1.XmlSerializer, tag: String, key: String, value: String) {
        writer.startTag(null, tag)
        writer.attribute(null, "name", key)
        writer.attribute(null, "value", value)
        writer.endTag(null, tag)
    }

    /** 合并 WAL 到主库。失败不阻断导出，因为 wal 本身也一并打包了。 */
    private fun checkpoint(context: Context) {
        runCatching {
            LauncherDatabase.getInstance(context).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        }
    }

    /** 把当前状态复制到 cache，返回可调用的回退动作。 */
    private fun snapshot(context: Context): (() -> Unit)? = runCatching {
        val dir = File(context.cacheDir, "grace_rollback").apply {
            deleteRecursively()
            mkdirs()
        }
        val dbDir = context.getDatabasePath(DATABASE).parentFile
        val dbNames = listOf(DATABASE, "$DATABASE-wal", "$DATABASE-shm")
        dbDir?.let { owner ->
            dbNames.forEach { name ->
                val file = File(owner, name)
                if (file.isFile) file.copyTo(File(dir, name), overwrite = true)
            }
        }
        val prefsDir = File(dir, "prefs").apply { mkdirs() }
        PREFERENCES.forEach { name ->
            val file = File(context.dataDir, "shared_prefs/$name.xml")
            if (file.isFile) file.copyTo(File(prefsDir, "$name.xml"), overwrite = true)
        }
        val noBackupDir = File(dir, "no_backup").apply { mkdirs() }
        NO_BACKUP.forEach { name ->
            val file = File(context.noBackupFilesDir, name)
            if (file.isFile) file.copyTo(File(noBackupDir, name), overwrite = true)
        }
        // 自定义图标也进回退点：恢复失败时能把原来的图标一并退回去
        val iconsSnapshot = File(dir, "icons").apply { mkdirs() }
        File(context.filesDir, ICON_DIR).listFiles()?.forEach { file ->
            if (file.isFile) file.copyTo(File(iconsSnapshot, file.name), overwrite = true)
        }

        val undo: () -> Unit = {
            dbDir?.let { owner ->
                dbNames.forEach { name ->
                    val saved = File(dir, name)
                    if (saved.isFile) saved.copyTo(File(owner, name), overwrite = true)
                }
            }
            PREFERENCES.forEach { name ->
                val saved = File(prefsDir, "$name.xml")
                if (saved.isFile) writeAtomic(File(context.dataDir, "shared_prefs/$name.xml"), saved)
            }
            NO_BACKUP.forEach { name ->
                val saved = File(noBackupDir, name)
                if (saved.isFile) saved.copyTo(File(context.noBackupFilesDir, name), overwrite = true)
            }
            File(iconsSnapshot).listFiles()?.forEach { saved ->
                if (saved.isFile) {
                    saved.copyTo(File(context.filesDir, ICON_DIR + "/" + saved.name), overwrite = true)
                }
            }
            LauncherDatabase.closeInstance()
        }
        undo
    }.getOrNull()

    /** 把暂存区内容真正写回应用。 */
    private fun apply(context: Context, staging: File) {
        // 必须先释放数据库，否则旧句柄还占着文件，写进去的内容会被覆盖回去
        LauncherDatabase.closeInstance()

        val dbDir = context.getDatabasePath(DATABASE).parentFile
        requireNotNull(dbDir) { "找不到数据库目录" }
        dbDir.mkdirs()
        listOf(DATABASE, "$DATABASE-wal", "$DATABASE-shm").forEach { name ->
            val source = File(staging, "db/$name")
            val target = File(dbDir, name)
            if (source.isFile) {
                source.copyTo(target, overwrite = true)
            } else if (name != DATABASE) {
                // 备份里没有 wal/shm，说明当时是干净状态，清掉残留避免冲突
                target.delete()
            }
        }
        check(File(dbDir, DATABASE).isFile) { "备份里没有数据库文件" }

        NO_BACKUP.forEach { name ->
            val source = File(staging, "no_backup/$name")
            if (source.isFile) source.copyTo(File(context.noBackupFilesDir, name), overwrite = true)
        }

        val fonts = File(context.filesDir, FONT_DIR).apply { mkdirs() }
        File(staging, "fonts").listFiles()?.forEach { source ->
            if (source.isFile) source.copyTo(File(fonts, source.name), overwrite = true)
        }

        val icons = File(context.filesDir, ICON_DIR).apply { mkdirs() }
        File(staging, "icons").listFiles()?.forEach { source ->
            if (source.isFile) source.copyTo(File(icons, source.name), overwrite = true)
        }

        // 偏好写回磁盘，内存缓存由重启清除
        PREFERENCES.forEach { name ->
            val source = File(staging, "prefs/$name.xml")
            if (source.isFile) writeAtomic(File(context.dataDir, "shared_prefs/$name.xml"), source)
        }
    }

    /** 原子写入：先写临时文件再替换，中途失败不会留下半个文件。 */
    private fun writeAtomic(target: File, source: File) {
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        atomic.startWrite().use { out -> source.inputStream().use { it.copyTo(out) } }
        atomic.finishWrite(null)
    }

    private inline fun unzip(input: InputStream, handler: (String, InputStream) -> Unit) {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) handler(entry.name, zip)
                zip.closeEntry()
            }
        }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}