// SSH File Explorer — Copyright (C) 2026 Kutay Aydogdu
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kutay.sshexplorer.ssh

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.kutay.sshexplorer.settings.SshConnectionConfig
import com.kutay.sshexplorer.settings.SshConnectionSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the single active SFTP connection of a project.
 *
 * Connecting and disconnecting block on network I/O, so both must be called
 * from a background thread.
 */
class SshConnectionService(private val project: Project) : Disposable {

    fun interface StateListener {
        fun connectionStateChanged()
    }

    private val listeners = CopyOnWriteArrayList<StateListener>()

    @Volatile
    var client: SftpClient? = null
        private set

    val isConnected: Boolean
        get() = client?.isConnected == true

    val activeConfig: SshConnectionConfig?
        get() = client?.config

    fun addListener(listener: StateListener, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    /**
     * @param trustUnknownHostKey forwarded to [SftpClient.connect]; only `true` after the
     *   user confirmed the fingerprint of an [UnknownHostKeyException].
     */
    fun connect(config: SshConnectionConfig, secret: String?, trustUnknownHostKey: Boolean = false) {
        disconnect()
        val newClient = SftpClient(config)
        try {
            newClient.connect(secret, trustUnknownHostKey)
        } catch (e: Throwable) {
            newClient.disconnect()
            throw e
        }
        client = newClient
        SshConnectionSettings.getInstance().lastUsedConnectionId = config.id
        fireStateChanged()
    }

    fun disconnect() {
        val current = client ?: return
        client = null
        current.disconnect()
        fireStateChanged()
    }

    /** Throws when no live connection is available, so callers can fail loudly. */
    fun requireClient(): SftpClient =
        client?.takeIf { it.isConnected } ?: throw SshOperationException("Not connected. Use Connect first.")

    private fun fireStateChanged() = listeners.forEach { it.connectionStateChanged() }

    override fun dispose() {
        client?.disconnect()
        client = null
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): SshConnectionService =
            project.getService(SshConnectionService::class.java)
    }
}
