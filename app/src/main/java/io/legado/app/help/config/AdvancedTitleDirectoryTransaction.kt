package io.legado.app.help.config

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 目录级原子替换事务。
 *
 * 流程：target -> backup（若存在），staging -> target，随后执行 [afterInstall] 校验。
 * 任一步骤失败都会尽力把 backup 还原回 target，避免半安装状态污染用户数据。
 *
 * 说明：该实现等价于 Archive 仓库的 `BubbleDirectoryTransaction`，只是为了不与
 * Max 既有/未来的同名类冲突而独立命名。
 */
internal class AdvancedTitleDirectoryTransaction(
    private val exchange: AdvancedTitleFileExchange = AdvancedTitleNioFileExchange
) {

    fun <T> install(
        targetDir: File,
        stagingDir: File,
        backupDir: File,
        afterInstall: (File) -> T
    ): T {
        var backupCreated = false
        try {
            if (targetDir.exists()) {
                exchange.move(targetDir, backupDir)
                backupCreated = true
            }
            try {
                exchange.move(stagingDir, targetDir)
            } catch (installError: Exception) {
                if (backupCreated) restoreBackup(backupDir, targetDir, installError)
                throw installError
            }
            val result = try {
                afterInstall(targetDir)
            } catch (commitError: Exception) {
                rollbackInstalled(targetDir, backupDir.takeIf { backupCreated }, commitError)
                throw commitError
            }
            if (backupCreated) deleteBestEffort(backupDir)
            return result
        } finally {
            deleteBestEffort(stagingDir)
        }
    }

    private fun restoreBackup(backupDir: File, targetDir: File, originalError: Exception) {
        try {
            exchange.move(backupDir, targetDir)
        } catch (restoreError: Exception) {
            originalError.addSuppressed(restoreError)
            throw AdvancedTitleRestoreException(backupDir, originalError)
        }
    }

    private fun rollbackInstalled(targetDir: File, backupDir: File?, originalError: Exception) {
        if (backupDir == null) {
            deleteRequired(targetDir, originalError)
            return
        }
        val failedDir = File(targetDir.parentFile, ".${targetDir.name}.failed-${UUID.randomUUID()}")
        try {
            if (targetDir.exists()) exchange.move(targetDir, failedDir)
            exchange.move(backupDir, targetDir)
            deleteBestEffort(failedDir)
        } catch (restoreError: Exception) {
            originalError.addSuppressed(restoreError)
            throw AdvancedTitleRestoreException(backupDir, originalError)
        }
    }

    private fun deleteRequired(file: File, cause: Throwable) {
        try {
            deletePath(file)
        } catch (deleteError: Exception) {
            cause.addSuppressed(deleteError)
            throw IOException("failed to remove newly installed advanced title", cause)
        }
    }

    private fun deleteBestEffort(file: File) {
        runCatching { deletePath(file) }
    }

    private fun deletePath(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            if (!file.deleteRecursively() && file.exists()) {
                throw IOException("failed to delete ${file.absolutePath}")
            }
        } else {
            exchange.delete(file)
        }
    }
}

internal interface AdvancedTitleFileExchange {
    fun move(source: File, target: File)
    fun delete(file: File)
}

internal object AdvancedTitleNioFileExchange : AdvancedTitleFileExchange {
    override fun move(source: File, target: File) {
        if (target.exists()) {
            throw IOException("target already exists: ${target.absolutePath}")
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        } catch (_: UnsupportedOperationException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    override fun delete(file: File) {
        if (file.exists()) {
            Files.delete(file.toPath())
        }
    }
}

internal class AdvancedTitleRestoreException(
    val backupDir: File,
    cause: Throwable
) : IOException(
    "failed to restore advanced title; backup kept at ${backupDir.absolutePath}",
    cause
)
