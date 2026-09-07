package com.kutay.sshexplorer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/** Application level store of every saved SSH target. */
@State(name = "SshFileExplorerSettings", storages = [Storage("sshFileExplorer.xml")])
class SshConnectionSettings : PersistentStateComponent<SshConnectionSettings> {

    @XCollection(propertyElementName = "connections")
    var connections: MutableList<SshConnectionConfig> = mutableListOf()

    var lastUsedConnectionId: String = ""

    override fun getState(): SshConnectionSettings = this

    override fun loadState(state: SshConnectionSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun find(id: String): SshConnectionConfig? = connections.firstOrNull { it.id == id }

    fun upsert(config: SshConnectionConfig) {
        val index = connections.indexOfFirst { it.id == config.id }
        if (index >= 0) connections[index] = config else connections.add(config)
    }

    fun remove(id: String) {
        connections.removeAll { it.id == id }
        if (lastUsedConnectionId == id) lastUsedConnectionId = ""
    }

    companion object {
        fun getInstance(): SshConnectionSettings =
            ApplicationManager.getApplication().getService(SshConnectionSettings::class.java)
    }
}
