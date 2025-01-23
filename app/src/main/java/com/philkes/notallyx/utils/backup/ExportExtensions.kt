package com.philkes.notallyx.utils.backup

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.print.PdfPrintListener
import android.print.printPdf
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.NotallyDatabase.Companion.DATABASE_NAME
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Converters
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.toHtml
import com.philkes.notallyx.data.model.toJson
import com.philkes.notallyx.data.model.toTxt
import com.philkes.notallyx.presentation.activity.main.MainActivity
import com.philkes.notallyx.presentation.view.misc.Progress
import com.philkes.notallyx.presentation.viewmodel.BackupFile
import com.philkes.notallyx.presentation.viewmodel.ExportMimeType
import com.philkes.notallyx.presentation.viewmodel.preference.BiometricLock
import com.philkes.notallyx.presentation.viewmodel.preference.Constants.PASSWORD_EMPTY
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences.Companion.EMPTY_PATH
import com.philkes.notallyx.utils.SUBFOLDER_AUDIOS
import com.philkes.notallyx.utils.SUBFOLDER_FILES
import com.philkes.notallyx.utils.SUBFOLDER_IMAGES
import com.philkes.notallyx.utils.createChannelIfNotExists
import com.philkes.notallyx.utils.createReportBugIntent
import com.philkes.notallyx.utils.getExportedPath
import com.philkes.notallyx.utils.getExternalAudioDirectory
import com.philkes.notallyx.utils.getExternalFilesDirectory
import com.philkes.notallyx.utils.getExternalImagesDirectory
import com.philkes.notallyx.utils.listZipFiles
import com.philkes.notallyx.utils.log
import com.philkes.notallyx.utils.logToFile
import com.philkes.notallyx.utils.removeTrailingParentheses
import com.philkes.notallyx.utils.security.decryptDatabase
import com.philkes.notallyx.utils.security.getInitializedCipherForDecryption
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod

private const val TAG = "ExportExtensions"
private const val NOTIFICATION_CHANNEL_ID = "AutoBackups"
private const val NOTIFICATION_ID = 123412
private const val NOTALLYX_BACKUP_LOGS_FILE = "notallyx-backup-logs.txt"
private const val NOTE_MODIFICATION_BACKUP_FILE = "NotallyX_AutoBackup.zip"
private const val OUTPUT_DATA_BACKUP_URI = "backupUri"

const val AUTO_BACKUP_WORK_NAME = "com.philkes.notallyx.AutoBackupWork"
const val OUTPUT_DATA_EXCEPTION = "exception"

fun Context.createBackup(): Result {
    val app = applicationContext as Application
    val preferences = NotallyXPreferences.getInstance(app)
    val (_, maxBackups) = preferences.periodicBackups.value
    val path = preferences.backupsFolder.value

    if (path != EMPTY_PATH) {
        val uri = Uri.parse(path)
        val folder = requireNotNull(DocumentFile.fromTreeUri(app, uri))
        fun log(msg: String? = null, throwable: Throwable? = null, stackTrace: String? = null) {
            logToFile(
                TAG,
                folder,
                NOTALLYX_BACKUP_LOGS_FILE,
                msg = msg,
                throwable = throwable,
                stackTrace = stackTrace,
            )
        }

        if (folder.exists()) {
            val formatter = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.ENGLISH)
            val backupFilePrefix = "NotallyX_"
            val name = "$backupFilePrefix${formatter.format(System.currentTimeMillis())}"
            log(msg = "Creating '$uri/$name.zip'...")
            try {
                val zipUri = requireNotNull(folder.createFile("application/zip", name)).uri
                val exportedNotes =
                    app.exportAsZip(zipUri, password = preferences.backupPassword.value)
                log(msg = "Exported $exportedNotes notes")
                val backupFiles = folder.listZipFiles(backupFilePrefix)
                log(msg = "Found ${backupFiles.size} backups")
                val backupsToBeDeleted = backupFiles.drop(maxBackups)
                if (backupsToBeDeleted.isNotEmpty()) {
                    log(
                        msg =
                            "Deleting ${backupsToBeDeleted.size} oldest backups (maxBackups: $maxBackups): ${backupsToBeDeleted.joinToString { "'${it.name.toString()}'" }}"
                    )
                }
                backupsToBeDeleted.forEach {
                    if (it.exists()) {
                        it.delete()
                    }
                }
                log(msg = "Finished backup to '$zipUri'")
                preferences.periodicBackupLastExecution.save(Date().time)
                return Result.success(
                    Data.Builder().putString(OUTPUT_DATA_BACKUP_URI, zipUri.path!!).build()
                )
            } catch (e: Exception) {
                log(msg = "Failed creating backup to '$uri/$name'", throwable = e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        postErrorNotification(e)
                    }
                } else {
                    postErrorNotification(e)
                }
                return Result.success(
                    Data.Builder().putString(OUTPUT_DATA_EXCEPTION, e.message).build()
                )
            }
        } else {
            log(msg = "Folder '${folder.uri}' does not exist, therefore skipping auto-backup")
        }
    }
    return Result.success()
}

fun ContextWrapper.autoBackupOnSave(backupPath: String, password: String, savedNote: BaseNote?) {
    val backupFolder =
        try {
            DocumentFile.fromTreeUri(this, backupPath.toUri())!!
        } catch (e: Exception) {
            log(
                TAG,
                msg =
                    "Auto backup on note save (${savedNote?.let {"id: '${savedNote.id}, title: '${savedNote.title}'" }}) failed, because auto-backup path is invalid",
                throwable = e,
            )
            return
        }
    try {
        var backupFile = backupFolder.findFile(NOTE_MODIFICATION_BACKUP_FILE)
        if (savedNote == null || backupFile == null || !backupFile.exists()) {
            backupFile = backupFolder.createFile("application/zip", NOTE_MODIFICATION_BACKUP_FILE)
            exportAsZip(backupFile!!.uri, password = password)
        } else {
            NotallyDatabase.getDatabase(this, observePreferences = false).value.checkpoint()
            val files =
                with(savedNote) {
                    images.map {
                        BackupFile(
                            SUBFOLDER_IMAGES,
                            File(getExternalImagesDirectory(), it.localName),
                        )
                    } +
                        files.map {
                            BackupFile(
                                SUBFOLDER_FILES,
                                File(getExternalFilesDirectory(), it.localName),
                            )
                        } +
                        audios.map {
                            BackupFile(SUBFOLDER_AUDIOS, File(getExternalAudioDirectory(), it.name))
                        } +
                        BackupFile(
                            null,
                            NotallyDatabase.getCurrentDatabaseFile(this@autoBackupOnSave),
                        )
                }
            exportToZip(backupFile.uri, files, password)
        }
    } catch (e: Exception) {
        logToFile(
            TAG,
            backupFolder,
            NOTALLYX_BACKUP_LOGS_FILE,
            msg =
                "Auto backup on note save (${savedNote?.let {"id: '${savedNote.id}, title: '${savedNote.title}'" }}) failed",
            throwable = e,
        )
    }
}

fun ContextWrapper.deleteModifiedNoteBackup(backupPath: String) {
    DocumentFile.fromTreeUri(this, backupPath.toUri())
        ?.findFile(NOTE_MODIFICATION_BACKUP_FILE)
        ?.delete()
}

fun ContextWrapper.modifiedNoteBackupExists(backupPath: String): Boolean {
    return DocumentFile.fromTreeUri(this, backupPath.toUri())
        ?.findFile(NOTE_MODIFICATION_BACKUP_FILE)
        ?.exists() ?: false
}

fun ContextWrapper.exportAsZip(
    fileUri: Uri,
    compress: Boolean = false,
    password: String = PASSWORD_EMPTY,
    backupProgress: MutableLiveData<Progress>? = null,
): Int {
    backupProgress?.postValue(Progress(indeterminate = true))

    val tempFile = File.createTempFile("export", "tmp", cacheDir)
    val zipFile =
        ZipFile(tempFile, if (password != PASSWORD_EMPTY) password.toCharArray() else null)
    val zipParameters =
        ZipParameters().apply {
            isEncryptFiles = password != PASSWORD_EMPTY
            if (!compress) {
                compressionLevel = CompressionLevel.NO_COMPRESSION
            }
            encryptionMethod = EncryptionMethod.AES
        }

    val (databaseOriginal, databaseCopy) = copyDatabase()
    zipFile.addFile(databaseCopy, zipParameters.copy(DATABASE_NAME))
    databaseCopy.delete()

    val imageRoot = getExternalImagesDirectory()
    val fileRoot = getExternalFilesDirectory()
    val audioRoot = getExternalAudioDirectory()

    val totalNotes = databaseOriginal.getBaseNoteDao().count()
    val images = databaseOriginal.getBaseNoteDao().getAllImages().toFileAttachments()
    val files = databaseOriginal.getBaseNoteDao().getAllFiles().toFileAttachments()
    val audios = databaseOriginal.getBaseNoteDao().getAllAudios()
    val totalAttachments = images.count() + files.count() + audios.size
    backupProgress?.postValue(Progress(0, totalAttachments))

    val counter = AtomicInteger(0)
    images.export(
        zipFile,
        zipParameters,
        imageRoot,
        SUBFOLDER_IMAGES,
        this,
        backupProgress,
        totalAttachments,
        counter,
    )
    files.export(
        zipFile,
        zipParameters,
        fileRoot,
        SUBFOLDER_FILES,
        this,
        backupProgress,
        totalAttachments,
        counter,
    )
    audios
        .asSequence()
        .flatMap { string -> Converters.jsonToAudios(string) }
        .forEach { audio ->
            try {
                backupFile(zipFile, zipParameters, audioRoot, SUBFOLDER_AUDIOS, audio.name)
            } catch (exception: Exception) {
                log(TAG, throwable = exception)
            } finally {
                backupProgress?.postValue(Progress(counter.incrementAndGet(), totalAttachments))
            }
        }

    zipFile.close()
    contentResolver.openOutputStream(fileUri)?.use { outputStream ->
        FileInputStream(zipFile.file).use { inputStream ->
            inputStream.copyTo(outputStream)
            outputStream.flush()
        }
        zipFile.file.delete()
    }
    backupProgress?.postValue(Progress(inProgress = false))
    return totalNotes
}

fun Context.exportToZip(
    zipUri: Uri,
    files: List<BackupFile>,
    password: String = PASSWORD_EMPTY,
): Boolean {
    val tempDir = File(cacheDir, "tempZip").apply { mkdirs() }
    val zipInputStream = contentResolver.openInputStream(zipUri) ?: return false
    extractZipToDirectory(zipInputStream, tempDir, password)
    files.forEach { file ->
        val targetFile = File(tempDir, "${file.first?.let { "$it/" } ?: ""}${file.second.name}")
        file.second.copyTo(targetFile, overwrite = true)
    }
    val zipOutputStream = contentResolver.openOutputStream(zipUri, "w") ?: return false
    createZipFromDirectory(tempDir, zipOutputStream, password)
    tempDir.deleteRecursively()
    return true
}

private fun extractZipToDirectory(zipInputStream: InputStream, outputDir: File, password: String) {
    try {
        val tempZipFile = File.createTempFile("tempZip", ".zip", outputDir)
        tempZipFile.outputStream().use { zipOutputStream -> zipInputStream.copyTo(zipOutputStream) }
        val zipFile =
            ZipFile(tempZipFile, if (password != PASSWORD_EMPTY) password.toCharArray() else null)
        zipFile.extractAll(outputDir.absolutePath)
        tempZipFile.delete()
    } catch (e: ZipException) {
        e.printStackTrace()
    }
}

private fun createZipFromDirectory(
    sourceDir: File,
    zipOutputStream: OutputStream,
    password: String = PASSWORD_EMPTY,
    compress: Boolean = false,
) {
    try {
        val tempZipFile = File.createTempFile("tempZip", ".zip")
        tempZipFile.deleteOnExit()

        val zipFile =
            ZipFile(tempZipFile, if (password != PASSWORD_EMPTY) password.toCharArray() else null)
        val zipParameters =
            ZipParameters().apply {
                isEncryptFiles = password != PASSWORD_EMPTY
                if (!compress) {
                    compressionLevel = CompressionLevel.NO_COMPRESSION
                }
                encryptionMethod = EncryptionMethod.AES
                isIncludeRootFolder = false
            }
        zipFile.addFolder(sourceDir, zipParameters)
        tempZipFile.inputStream().use { inputStream -> inputStream.copyTo(zipOutputStream) }
        tempZipFile.delete()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun ContextWrapper.copyDatabase(): Pair<NotallyDatabase, File> {
    val database = NotallyDatabase.getDatabase(this, observePreferences = false).value
    database.checkpoint()
    val preferences = NotallyXPreferences.getInstance(this)
    return if (
        preferences.biometricLock.value == BiometricLock.ENABLED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    ) {
        val cipher = getInitializedCipherForDecryption(iv = preferences.iv.value!!)
        val passphrase = cipher.doFinal(preferences.databaseEncryptionKey.value)
        val decryptedFile = File(cacheDir, DATABASE_NAME)
        decryptDatabase(
            this,
            passphrase,
            decryptedFile,
            NotallyDatabase.getCurrentDatabaseName(this),
        )
        Pair(database, decryptedFile)
    } else {
        val dbFile = File(cacheDir, DATABASE_NAME)
        NotallyDatabase.getCurrentDatabaseFile(this).copyTo(dbFile, overwrite = true)
        Pair(database, dbFile)
    }
}

private fun List<String>.toFileAttachments(): Sequence<FileAttachment> {
    return asSequence().flatMap { string -> Converters.jsonToFiles(string) }
}

private fun Sequence<FileAttachment>.export(
    zipFile: ZipFile,
    zipParameters: ZipParameters,
    fileRoot: File?,
    subfolder: String,
    context: ContextWrapper,
    backupProgress: MutableLiveData<Progress>?,
    total: Int,
    counter: AtomicInteger,
) {
    forEach { file ->
        try {
            backupFile(zipFile, zipParameters, fileRoot, subfolder, file.localName)
        } catch (exception: Exception) {
            context.log(TAG, throwable = exception)
        } finally {
            backupProgress?.postValue(Progress(counter.incrementAndGet(), total))
        }
    }
}

fun WorkInfo.PeriodicityInfo.isEqualTo(value: Long, unit: TimeUnit): Boolean {
    return repeatIntervalMillis == unit.toMillis(value)
}

fun List<WorkInfo>.containsNonCancelled(): Boolean = any { it.state != WorkInfo.State.CANCELLED }

fun WorkManager.cancelAutoBackup() {
    Log.d(TAG, "Cancelling auto backup work")
    cancelUniqueWork(AUTO_BACKUP_WORK_NAME)
}

fun WorkManager.updateAutoBackup(workInfos: List<WorkInfo>, autoBackPeriodInDays: Long) {
    Log.d(TAG, "Updating auto backup schedule for period: $autoBackPeriodInDays days")
    val workInfoId = workInfos.first().id
    val updatedWorkRequest =
        PeriodicWorkRequest.Builder(
                AutoBackupWorker::class.java,
                autoBackPeriodInDays.toLong(),
                TimeUnit.DAYS,
            )
            .setId(workInfoId)
            .build()
    updateWork(updatedWorkRequest)
}

fun WorkManager.scheduleAutoBackup(context: ContextWrapper, periodInDays: Long) {
    Log.d(TAG, "Scheduling auto backup for period: $periodInDays days")
    val request =
        PeriodicWorkRequest.Builder(AutoBackupWorker::class.java, periodInDays, TimeUnit.DAYS)
            .build()
    try {
        enqueueUniquePeriodicWork(AUTO_BACKUP_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    } catch (e: IllegalStateException) {
        // only happens in Unit-Tests
        context.log(TAG, "Scheduling auto backup failed", throwable = e)
    }
}

private fun backupFile(
    zipFile: ZipFile,
    zipParameters: ZipParameters,
    root: File?,
    folder: String,
    name: String,
) {
    val file = if (root != null) File(root, name) else null
    if (file != null && file.exists()) {
        zipFile.addFile(file, zipParameters.copy("$folder/$name"))
    }
}

private fun ZipParameters.copy(fileNameInZip: String? = this.fileNameInZip): ZipParameters {
    return ZipParameters(this).apply { this@apply.fileNameInZip = fileNameInZip }
}

fun exportPdfFile(
    app: Application,
    note: BaseNote,
    folder: DocumentFile,
    fileName: String = note.title,
    pdfPrintListener: PdfPrintListener? = null,
    progress: MutableLiveData<Progress>? = null,
    counter: AtomicInteger? = null,
    total: Int? = null,
    duplicateFileCount: Int = 1,
) {
    val filePath = "$fileName.${ExportMimeType.PDF.fileExtension}"
    if (folder.findFile(filePath)?.exists() == true) {
        return exportPdfFile(
            app,
            note,
            folder,
            "${fileName.removeTrailingParentheses()} ($duplicateFileCount)",
            pdfPrintListener,
            progress,
            counter,
            total,
            duplicateFileCount + 1,
        )
    }
    folder.createFile(ExportMimeType.PDF.mimeType, fileName)?.let {
        val file = DocumentFile.fromFile(File(app.getExportedPath(), filePath))
        val html = note.toHtml(NotallyXPreferences.getInstance(app).showDateCreated())
        app.printPdf(
            file,
            html,
            object : PdfPrintListener {
                override fun onSuccess(file: DocumentFile) {
                    app.contentResolver.openOutputStream(it.uri)?.use { outStream ->
                        app.contentResolver.openInputStream(file.uri)?.copyTo(outStream)
                    }
                    progress?.postValue(
                        Progress(current = counter!!.incrementAndGet(), total = total!!)
                    )
                    pdfPrintListener?.onSuccess(file)
                }

                override fun onFailure(message: CharSequence?) {
                    pdfPrintListener?.onFailure(message)
                }
            },
        )
    }
}

suspend fun exportPlainTextFile(
    app: Application,
    note: BaseNote,
    exportType: ExportMimeType,
    folder: DocumentFile,
    fileName: String = note.title,
    progress: MutableLiveData<Progress>? = null,
    counter: AtomicInteger? = null,
    total: Int? = null,
    duplicateFileCount: Int = 1,
): DocumentFile? {
    if (folder.findFile("$fileName.${exportType.fileExtension}")?.exists() == true) {
        return exportPlainTextFile(
            app,
            note,
            exportType,
            folder,
            "${fileName.removeTrailingParentheses()} ($duplicateFileCount)",
            progress,
            counter,
            total,
            duplicateFileCount + 1,
        )
    }
    return withContext(Dispatchers.IO) {
        val file =
            folder.createFile(exportType.mimeType, fileName)?.let {
                app.contentResolver.openOutputStream(it.uri)?.use { stream ->
                    OutputStreamWriter(stream).use { writer ->
                        writer.write(
                            when (exportType) {
                                ExportMimeType.TXT ->
                                    note.toTxt(includeTitle = false, includeCreationDate = false)

                                ExportMimeType.JSON -> note.toJson()
                                ExportMimeType.HTML ->
                                    note.toHtml(
                                        NotallyXPreferences.getInstance(app).showDateCreated()
                                    )

                                else -> TODO("Unsupported MimeType for Export")
                            }
                        )
                    }
                }
                it
            }
        progress?.postValue(Progress(current = counter!!.incrementAndGet(), total = total!!))
        return@withContext file
    }
}

fun Context.exportPreferences(preferences: NotallyXPreferences, uri: Uri): Boolean {
    try {
        contentResolver.openOutputStream(uri)?.use {
            it.write(preferences.toJsonString().toByteArray())
        } ?: return false
        return true
    } catch (e: IOException) {
        if (this is ContextWrapper) {
            log(TAG, throwable = e)
        } else {
            Log.e(TAG, "Export preferences failed", e)
        }
        return false
    }
}

private fun Context.postErrorNotification(e: Throwable) {
    getSystemService<NotificationManager>()?.let { manager ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createChannelIfNotExists(NOTIFICATION_CHANNEL_ID)
        }
        val notification =
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.error)
                .setContentTitle(getString(R.string.auto_backup_failed))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            getString(
                                R.string.auto_backup_error_message,
                                "${e.javaClass.simpleName}: ${e.localizedMessage}",
                            )
                        )
                )
                .addAction(
                    R.drawable.settings,
                    getString(R.string.settings),
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_FRAGMENT_TO_OPEN, R.id.Settings)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .addAction(
                    R.drawable.error,
                    getString(R.string.report_bug),
                    PendingIntent.getActivity(
                        this,
                        0,
                        createReportBugIntent(
                            e.stackTraceToString(),
                            title = "Auto Backup failed",
                            body = "Error occurred during auto backup, see logs below",
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
