// SSH File Explorer — Copyright (C) 2026 Kutay Aydogdu
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kutay.sshexplorer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.kutay.sshexplorer.settings.SshConnectionConfig
import com.kutay.sshexplorer.settings.SshConnectionSettings
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList

/** Manage the list of saved SSH targets. Changes are written straight to the settings store. */
class ConnectionListDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val model = DefaultListModel<SshConnectionConfig>()
    private val list = JBList(model)
    private val settings = SshConnectionSettings.getInstance()

    val selectedConfig: SshConnectionConfig?
        get() = list.selectedValue

    init {
        title = "SSH Connections"
        settings.connections.forEach(model::addElement)
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<SshConnectionConfig>() {
            override fun customizeCellRenderer(
                list: JList<out SshConnectionConfig>,
                value: SshConnectionConfig?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                icon = com.intellij.icons.AllIcons.Nodes.Services
                append(value.displayName)
                append("  ${value.username}@${value.host}:${value.port}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        if (!model.isEmpty) list.selectedIndex = 0
        init()
    }

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addConnection() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
        decorated.preferredSize = Dimension(520, 320)
        return decorated
    }

    private fun addConnection() {
        val config = SshConnectionConfig()
        if (ConnectionDialog(project, config).showAndGet()) {
            settings.upsert(config)
            model.addElement(config)
            list.setSelectedValue(config, true)
        }
    }

    private fun editSelected() {
        val index = list.selectedIndex
        val current = model.elementAtOrNull(index) ?: return
        val draft = current.copy()
        if (ConnectionDialog(project, draft).showAndGet()) {
            settings.upsert(draft)
            model.setElementAt(draft, index)
            list.selectedIndex = index
        }
    }

    private fun removeSelected() {
        val index = list.selectedIndex
        val current = model.elementAtOrNull(index) ?: return
        current.saveSecret = false
        current.storeSecret(null)
        settings.remove(current.id)
        model.remove(index)
        if (!model.isEmpty) list.selectedIndex = minOf(index, model.size() - 1)
    }

    private fun DefaultListModel<SshConnectionConfig>.elementAtOrNull(index: Int): SshConnectionConfig? =
        if (index in 0 until size()) elementAt(index) else null
}
