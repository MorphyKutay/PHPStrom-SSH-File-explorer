// SSH File Explorer — Copyright (C) 2026 Kutay Aydogdu
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kutay.sshexplorer.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.kutay.sshexplorer.model.RemoteFile
import com.kutay.sshexplorer.ssh.SshConnectionService
import com.kutay.sshexplorer.ssh.SshNotifier
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the mapping between a remote file opened for editing and its local mirror.
 *
 * A remote file is downloaded into the IDE system directory, opened as a normal
 * local file, and pushed back to the server whenever the document is saved.
 */
class RemoteFileEditingManager {

    data class Mirror(
        val projectLocator: String,
        val connectionId: String,
        val remotePath: String,
        val localPath: String,
    )

    private val mirrors = ConcurrentHashMap<String, Mirror>()

    fun openInEditor(project: Project, remoteFile: RemoteFile) {
        val service = SshConnectionService.getInstance(project)
        val config = service.activeConfig ?: run {
            SshNotifier.error(project, "Not connected.")
            return
        }
        object : Task.Backgroundable(project, "Downloading ${remoteFile.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val local = localMirrorFor(config.id, remoteFile.path)
                try {
                    service.requireClient().download(remoteFile.path, local)
                } catch (e: Exception) {
                    SshNotifier.error(project, e.message ?: "Download failed.")
                    return
                }
                mirrors[local.absolutePath] = Mirror(project.locationHash, config.id, remoteFile.path, local.absolutePath)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local)
                    if (vf == null) {
                        SshNotifier.error(project, "Local copy of ${remoteFile.name} could not be opened.")
                        return@invokeLater
                    }
                    vf.refresh(false, false)
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        }.queue()
    }

    fun mirrorFor(file: VirtualFile): Mirror? = mirrors[File(file.path).absolutePath]

    /**
     * Uploads the about-to-be-saved content of [document] back to the server.
     * Called from the save listener; the document text — not the on-disk copy —
     * is the source of truth because disk has not been written yet.
     */
    fun uploadOnSave(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val mirror = mirrorFor(file) ?: return
        val project = ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed && it.locationHash == mirror.projectLocator } ?: return

        val service = SshConnectionService.getInstance(project)
        val activeId = service.activeConfig?.id
        if (activeId != mirror.connectionId || !service.isConnected) {
            SshNotifier.error(project, "${mirror.remotePath} was saved locally only — its SSH connection is not active.")
            return
        }

        val bytes = document.text.toByteArray(file.charset)
        object : Task.Backgroundable(project, "Uploading ${file.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val staging = File.createTempFile("ssh-upload-", ".tmp")
                try {
                    staging.writeBytes(bytes)
                    service.requireClient().upload(staging, mirror.remotePath)
                    SshNotifier.info(project, "Uploaded ${mirror.remotePath}")
                } catch (e: Exception) {
                    SshNotifier.error(project, "Upload failed for ${mirror.remotePath}: ${e.message}")
                } finally {
                    staging.delete()
                }
            }
        }.queue()
    }

    private fun localMirrorFor(connectionId: String, remotePath: String): File {
        val relative = remotePath.trimStart('/').ifEmpty { "root" }
        return Paths.get(PathManager.getSystemPath(), "ssh-file-explorer", connectionId, relative).toFile()
    }

    companion object {
        fun getInstance(): RemoteFileEditingManager =
            ApplicationManager.getApplication().getService(RemoteFileEditingManager::class.java)
    }
}
