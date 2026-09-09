// SSH File Explorer — Copyright (C) 2026 Kutay Aydogdu
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kutay.sshexplorer.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManagerListener

/** Mirrors every save of a downloaded remote file back to the server. */
class RemoteFileSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        RemoteFileEditingManager.getInstance().uploadOnSave(document)
    }
}
