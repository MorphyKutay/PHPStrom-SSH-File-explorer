// SSH File Explorer — Copyright (C) 2026 Kutay Aydogdu
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kutay.sshexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kutay.sshexplorer.editor.RemoteFileEditingManager
import com.kutay.sshexplorer.model.RemoteFile
import com.kutay.sshexplorer.settings.SshConnectionConfig
import com.kutay.sshexplorer.settings.SshConnectionSettings
import com.kutay.sshexplorer.ssh.SftpClient
import com.kutay.sshexplorer.ssh.SshConnectionService
import com.kutay.sshexplorer.ssh.SshNotifier
import com.kutay.sshexplorer.ssh.UnknownHostKeyException
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JList
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** The "SSH Explorer" tool window content: a lazy remote file tree plus its actions. */
class SshExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SshConnectionService.getInstance(project)
    private val settings = SshConnectionSettings.getInstance()
    private val treeModel = DefaultTreeModel(MessageNode(NOT_CONNECTED))
    private val tree = Tree(treeModel)
    private val statusLabel = JBLabel(NOT_CONNECTED)

    init {
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.cellRenderer = RemoteTreeCellRenderer()
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? RemoteFileNode ?: return
                if (!node.loaded) loadChildren(node)
            }

            override fun treeCollapsed(event: TreeExpansionEvent) = Unit
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || e.button != MouseEvent.BUTTON1) return
                val node = tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? RemoteFileNode ?: return
                if (!node.file.isDirectory) {
                    e.consume()
                    openInEditor(node.file)
                }
            }
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val node = tree.selectionPath?.lastPathComponent as? RemoteFileNode ?: return
                if (!node.file.isDirectory) {
                    e.consume()
                    openInEditor(node.file)
                }
            }
        })

        val actionGroup = buildActionGroup()
        val toolbar = ActionManager.getInstance().createActionToolbar("SshExplorerToolbar", actionGroup, true)
        toolbar.targetComponent = this
        val paddedToolbar = JBUI.Panels.simplePanel(toolbar.component)
            .withBorder(JBUI.Borders.empty(4, 8))
        setToolbar(paddedToolbar)

        PopupHandler.installPopupMenu(tree, buildContextMenuGroup(), "SshExplorerPopup")

        statusLabel.border = JBUI.Borders.empty(2, 6)
        statusLabel.foreground = UIUtil.getContextHelpForeground()

        val content = com.intellij.util.ui.JBUI.Panels.simplePanel()
            .addToCenter(JBScrollPane(tree))
            .addToBottom(statusLabel)
        setContent(content)

        service.addListener({ ApplicationManager.getApplication().invokeLater(::refreshRoot) }, this)
    }

    // ---------------------------------------------------------------- actions

    private fun buildActionGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        add(ConnectAction())
        add(DisconnectAction())
        add(RefreshAction())
        add(Separator.getInstance())
        add(NewFolderAction())
        add(NewFileAction())
        add(Separator.getInstance())
        add(UploadAction())
        add(DownloadAction())
        add(Separator.getInstance())
        add(RenameAction())
        add(DeleteAction())
        add(Separator.getInstance())
        add(ManageConnectionsAction())
    }

    private fun buildContextMenuGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        add(OpenAction())
        add(RefreshAction())
        add(Separator.getInstance())
        add(NewFolderAction())
        add(NewFileAction())
        add(UploadAction())
        add(DownloadAction())
        add(Separator.getInstance())
        add(RenameAction())
        add(DeleteAction())
        add(Separator.getInstance())
        add(CopyPathAction())
    }

    private inner class ConnectAction : DumbAwareAction("Connect", "Open an SSH connection", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.isConnected
        }

        override fun actionPerformed(e: AnActionEvent) = chooseConnectionAndConnect()
    }

    private inner class DisconnectAction :
        DumbAwareAction("Disconnect", "Close the SSH connection", AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected
        }

        override fun actionPerformed(e: AnActionEvent) {
            runInBackground("Disconnecting") { service.disconnect() }
        }
    }

    private inner class RefreshAction : DumbAwareAction("Refresh", "Reload the selected folder", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedDirectoryNode() ?: (treeModel.root as? RemoteFileNode) ?: return
            node.loaded = false
            loadChildren(node)
        }
    }

    private inner class OpenAction : DumbAwareAction("Open in Editor", "Download and open the file", AllIcons.Actions.MenuOpen) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedFiles().any { !it.isDirectory }
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedFiles().filter { !it.isDirectory }.forEach(::openInEditor)
        }
    }

    private inner class NewFolderAction : DumbAwareAction("New Folder", "Create a remote folder", AllIcons.Actions.NewFolder) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected && targetDirectory() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val parent = targetDirectory() ?: return
            val name = Messages.showInputDialog(project, "Folder name:", "New Folder", null) ?: return
            if (name.isBlank()) return
            val path = RemoteFile.join(parent.file.path, name.trim())
            runInBackground("Creating $path") {
                service.requireClient().createDirectory(path)
                reloadOnEdt(parent)
            }
        }
    }

    private inner class NewFileAction : DumbAwareAction("New File", "Create an empty remote file", AllIcons.Actions.AddFile) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected && targetDirectory() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val parent = targetDirectory() ?: return
            val name = Messages.showInputDialog(project, "File name:", "New File", null) ?: return
            if (name.isBlank()) return
            val path = RemoteFile.join(parent.file.path, name.trim())
            runInBackground("Creating $path") {
                service.requireClient().createEmptyFile(path)
                reloadOnEdt(parent)
            }
        }
    }

    private inner class RenameAction : DumbAwareAction("Rename", "Rename the selected entry", AllIcons.Actions.Edit) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected && selectedNodes().size == 1 && selectedNodes().first().parent != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedNodes().singleOrNull() ?: return
            val parent = node.parent as? RemoteFileNode ?: return
            val newName = Messages.showInputDialog(project, "New name:", "Rename", null, node.file.name, null) ?: return
            if (newName.isBlank() || newName == node.file.name) return
            val target = RemoteFile.join(node.file.parentPath, newName.trim())
            runInBackground("Renaming ${node.file.name}") {
                service.requireClient().rename(node.file.path, target)
                reloadOnEdt(parent)
            }
        }
    }

    private inner class DeleteAction : DumbAwareAction("Delete", "Delete the selected entries", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected && selectedNodes().any { it.parent != null }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val nodes = selectedNodes().filter { it.parent != null }
            if (nodes.isEmpty()) return
            val what = if (nodes.size == 1) nodes.first().file.path else "${nodes.size} entries"
            val confirmed = Messages.showYesNoDialog(
                project,
                "Delete $what from the server? Folders are deleted with all their content. This cannot be undone.",
                "Delete Remote Files",
                Messages.getWarningIcon(),
            ) == Messages.YES
            if (!confirmed) return

            val parents = nodes.mapNotNull { it.parent as? RemoteFileNode }.distinct()
            runInBackground("Deleting remote files") {
                val client = service.requireClient()
                nodes.forEach { client.delete(it.file) }
                parents.forEach { reloadOnEdt(it) }
            }
        }
    }

    private inner class DownloadAction :
        DumbAwareAction("Download…", "Copy the selection to a local folder", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected && selectedFiles().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val files = selectedFiles()
            if (files.isEmpty()) return
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            val destination = FileChooser.chooseFile(descriptor, project, null) ?: return
            val targetDir = File(destination.path)
            runInBackground("Downloading ${files.size} item(s)") {
                val client = service.requireClient()
                files.forEach { downloadRecursively(client, it, File(targetDir, it.name)) }
                SshNotifier.info(project, "Downloaded to ${targetDir.absolutePath}")
            }
        }
    }

    private inner class UploadAction :
        DumbAwareAction("Upload…", "Upload local files into the selected folder", AllIcons.Actions.Upload) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected && targetDirectory() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val parent = targetDirectory() ?: return
            val descriptor = FileChooserDescriptorFactory.createAllButJarContentsDescriptor()
                .withTitle("Select Files to Upload")
            val chosen = FileChooser.chooseFiles(descriptor, project, null)
            if (chosen.isEmpty()) return
            runInBackground("Uploading ${chosen.size} item(s)") {
                val client = service.requireClient()
                chosen.forEach { uploadRecursively(client, File(it.path), RemoteFile.join(parent.file.path, it.name)) }
                reloadOnEdt(parent)
                SshNotifier.info(project, "Uploaded into ${parent.file.path}")
            }
        }
    }

    private inner class CopyPathAction : DumbAwareAction("Copy Remote Path", "Copy the absolute remote path", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedFiles().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = selectedFiles().joinToString("\n") { it.path }
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                .setContents(java.awt.datatransfer.StringSelection(text))
        }
    }

    private inner class ManageConnectionsAction :
        DumbAwareAction("Connections…", "Add, edit or remove SSH targets", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            ConnectionListDialog(project).showAndGet()
        }
    }

    // ------------------------------------------------------------ connecting

    private fun chooseConnectionAndConnect() {
        val configs = settings.connections.toList()
        if (configs.isEmpty()) {
            val created = SshConnectionConfig()
            if (!ConnectionDialog(project, created).showAndGet()) return
            settings.upsert(created)
            connect(created)
            return
        }

        val items = configs.map { it.displayName } + MANAGE_ENTRY
        val popupBuilder = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Connect to Server")
            .setMinSize(Dimension(JBUI.scale(230), JBUI.scale(104)))
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
        popupBuilder.setRenderer(object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(
                list: JList<out String>,
                value: String,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                ipad = JBUI.insets(4, 8)
                icon = if (value == MANAGE_ENTRY) AllIcons.General.Settings else AllIcons.General.Web
                append(
                    value,
                    if (value == MANAGE_ENTRY) SimpleTextAttributes.GRAYED_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                )
            }
        })
        popupBuilder.setItemChosenCallback { chosen ->
            if (chosen == MANAGE_ENTRY) {
                val dialog = ConnectionListDialog(project)
                if (dialog.showAndGet()) dialog.selectedConfig?.let(::connect)
            } else {
                configs.firstOrNull { it.displayName == chosen }?.let(::connect)
            }
        }
        val popup = popupBuilder.createPopup()
        val anchor = toolbar ?: this
        popup.show(RelativePoint(anchor, Point(JBUI.scale(16), anchor.height + JBUI.scale(8))))
    }

    private fun connect(config: SshConnectionConfig) {
        var secret = config.loadSecret()
        if (secret.isNullOrEmpty()) {
            val prompt = if (config.authType == com.kutay.sshexplorer.settings.SshAuthType.KEY_PAIR) {
                "Passphrase for ${config.privateKeyPath} (leave empty if the key has none):"
            } else {
                "Password for ${config.username}@${config.host}:"
            }
            secret = Messages.showPasswordDialog(project, prompt, "SSH Authentication", null) ?: return
            if (config.saveSecret && secret.isNotEmpty()) config.storeSecret(secret)
        }

        connect(config, secret, trustUnknownHostKey = false)
    }

    private fun connect(config: SshConnectionConfig, secret: String?, trustUnknownHostKey: Boolean) {
        object : Task.Backgroundable(project, "Connecting to ${config.host}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    service.connect(config, secret, trustUnknownHostKey)
                    SshNotifier.info(project, "Connected to ${config.username}@${config.host}")
                } catch (e: UnknownHostKeyException) {
                    ApplicationManager.getApplication().invokeLater({
                        if (confirmHostKey(project, e.prompt)) connect(config, secret, trustUnknownHostKey = true)
                    }, project.disposed)
                } catch (e: Exception) {
                    SshNotifier.error(project, e.message ?: "Connection failed.")
                }
            }
        }.queue()
    }

    // ----------------------------------------------------------------- tree

    private fun refreshRoot() {
        val config = service.activeConfig
        if (config == null || !service.isConnected) {
            treeModel.setRoot(MessageNode(NOT_CONNECTED))
            statusLabel.text = NOT_CONNECTED
            return
        }
        statusLabel.text = "${config.username}@${config.host}:${config.port}"
        object : Task.Backgroundable(project, "Opening ${config.rootPath}", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val rootPath = try {
                    service.requireClient().realPath(config.rootPath.ifBlank { "/" })
                } catch (e: Exception) {
                    SshNotifier.error(project, e.message ?: "Root path is not reachable.")
                    config.rootPath.ifBlank { "/" }
                }
                ApplicationManager.getApplication().invokeLater {
                    val rootNode = RemoteFileNode(RemoteFile.directory(rootPath))
                    treeModel.setRoot(rootNode)
                    loadChildren(rootNode)
                    tree.expandPath(TreePath(rootNode.path))
                }
            }
        }.queue()
    }

    private fun loadChildren(node: RemoteFileNode) {
        if (node.loading) return
        node.loading = true
        node.removeAllChildren()
        node.add(LoadingNode())
        treeModel.nodeStructureChanged(node)

        object : Task.Backgroundable(project, "Listing ${node.file.path}", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = runCatching { service.requireClient().list(node.file.path) }
                ApplicationManager.getApplication().invokeLater {
                    node.loading = false
                    node.removeAllChildren()
                    result.onSuccess { children ->
                        node.loaded = true
                        children.forEach { node.add(RemoteFileNode(it)) }
                    }.onFailure { error ->
                        node.loaded = false
                        node.add(MessageNode(error.message ?: "Listing failed"))
                        SshNotifier.error(project, error.message ?: "Listing failed for ${node.file.path}")
                    }
                    treeModel.nodeStructureChanged(node)
                }
            }
        }.queue()
    }

    private fun reloadOnEdt(node: RemoteFileNode) = ApplicationManager.getApplication().invokeLater {
        node.loaded = false
        loadChildren(node)
    }

    private fun openInEditor(file: RemoteFile) =
        RemoteFileEditingManager.getInstance().openInEditor(project, file)

    private fun selectedNodes(): List<RemoteFileNode> =
        (tree.selectionPaths ?: emptyArray()).mapNotNull { it.lastPathComponent as? RemoteFileNode }

    private fun selectedFiles(): List<RemoteFile> = selectedNodes().map { it.file }

    private fun selectedDirectoryNode(): RemoteFileNode? =
        selectedNodes().firstOrNull { it.file.isDirectory }

    /** Where "create" and "upload" operations land: the selected folder, its parent, or the tree root. */
    private fun targetDirectory(): RemoteFileNode? {
        val selected = selectedNodes().firstOrNull()
        return when {
            selected == null -> treeModel.root as? RemoteFileNode
            selected.file.isDirectory -> selected
            else -> selected.parent as? RemoteFileNode
        }
    }

    // ------------------------------------------------------------- transfers

    private fun downloadRecursively(client: SftpClient, file: RemoteFile, target: File) {
        if (file.isDirectory) {
            target.mkdirs()
            client.list(file.path).forEach { downloadRecursively(client, it, File(target, it.name)) }
        } else {
            client.download(file.path, target)
        }
    }

    private fun uploadRecursively(client: SftpClient, source: File, remotePath: String) {
        if (source.isDirectory) {
            runCatching { client.createDirectory(remotePath) }
            source.listFiles()?.forEach { uploadRecursively(client, it, RemoteFile.join(remotePath, it.name)) }
        } else {
            client.upload(source, remotePath)
        }
    }

    private fun runInBackground(title: String, block: () -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    block()
                } catch (e: Exception) {
                    SshNotifier.error(project, e.message ?: "$title failed.")
                }
            }
        }.queue()
    }

    override fun dispose() = Unit

    private class RemoteTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (value) {
                is RemoteFileNode -> {
                    val file = value.file
                    icon = when {
                        file.isDirectory -> AllIcons.Nodes.Folder
                        else -> FileTypeManager.getInstance().getFileTypeByFileName(file.name).icon
                            ?: AllIcons.FileTypes.Any_type
                    }
                    append(file.name)
                    if (file.isSymlink) append(" ⇢", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (!file.isDirectory) {
                        append("  ${formatSize(file.size)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }

                is LoadingNode -> {
                    icon = AllIcons.Process.Step_1
                    append("Loading…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is DefaultMutableTreeNode -> append(
                    value.userObject?.toString().orEmpty(),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024L * 1024 * 1024)} GB"
        }
    }

    private companion object {
        const val NOT_CONNECTED = "Not connected"
        const val MANAGE_ENTRY = "Connections…"
    }
}
