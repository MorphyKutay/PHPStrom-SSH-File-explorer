package com.kutay.sshexplorer.ui

import com.kutay.sshexplorer.model.RemoteFile
import javax.swing.tree.DefaultMutableTreeNode

/** Tree node backed by a remote entry. Children are fetched the first time the node expands. */
class RemoteFileNode(val file: RemoteFile) : DefaultMutableTreeNode(file, file.isDirectory) {
    var loaded: Boolean = false
    var loading: Boolean = false

    override fun isLeaf(): Boolean = !file.isDirectory
}

/** Placeholder shown while a directory listing is in flight. */
class LoadingNode : DefaultMutableTreeNode("Loading…", false)

/** Placeholder shown instead of a tree when there is nothing to browse. */
class MessageNode(message: String) : DefaultMutableTreeNode(message, false)
