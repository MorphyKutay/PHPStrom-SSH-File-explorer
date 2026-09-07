package com.kutay.sshexplorer.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.kutay.sshexplorer.model.RemoteFile
import com.kutay.sshexplorer.settings.SshAuthType
import com.kutay.sshexplorer.settings.SshConnectionConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SshOperationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thin, synchronous SFTP wrapper around a single JSch session.
 *
 * A [ChannelSftp] is not thread safe, so every remote call is serialized through
 * [lock]. All calls block — callers must run them off the EDT.
 */
class SftpClient(val config: SshConnectionConfig) {

    private val lock = ReentrantLock()
    private var session: Session? = null
    private var channel: ChannelSftp? = null

    val isConnected: Boolean
        get() = lock.withLock { session?.isConnected == true && channel?.isConnected == true }

    fun connect(secret: String?) = lock.withLock {
        disconnectInternal()
        val jsch = JSch()
        loadKnownHosts(jsch)

        if (config.authType == SshAuthType.KEY_PAIR) {
            val keyPath = config.privateKeyPath.trim()
            if (keyPath.isEmpty()) throw SshOperationException("No private key file configured.")
            if (!File(keyPath).isFile) throw SshOperationException("Private key not found: $keyPath")
            try {
                if (secret.isNullOrEmpty()) jsch.addIdentity(keyPath) else jsch.addIdentity(keyPath, secret)
            } catch (e: JSchException) {
                throw SshOperationException("Private key could not be read: ${e.message}", e)
            }
        }

        val newSession = try {
            jsch.getSession(config.username, config.host, config.port)
        } catch (e: JSchException) {
            throw SshOperationException("Invalid connection settings: ${e.message}", e)
        }

        if (config.authType == SshAuthType.PASSWORD) {
            newSession.setPassword(secret.orEmpty())
            newSession.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        } else {
            newSession.setConfig("PreferredAuthentications", "publickey")
        }
        // Unknown hosts are added on first use instead of failing the connection.
        newSession.setConfig("StrictHostKeyChecking", "accept-new")
        newSession.timeout = CONNECT_TIMEOUT_MS

        try {
            newSession.connect(CONNECT_TIMEOUT_MS)
            val sftp = newSession.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            session = newSession
            channel = sftp
        } catch (e: JSchException) {
            newSession.disconnect()
            throw SshOperationException(describe(e), e)
        }
    }

    fun disconnect() = lock.withLock { disconnectInternal() }

    private fun disconnectInternal() {
        channel?.let { runCatching { it.disconnect() } }
        session?.let { runCatching { it.disconnect() } }
        channel = null
        session = null
    }

    fun homeDirectory(): String = withChannel { RemoteFile.normalize(it.home) }

    fun realPath(path: String): String = withChannel {
        try {
            RemoteFile.normalize(it.realpath(path))
        } catch (e: SftpException) {
            throw SshOperationException("Path not reachable: $path (${e.message})", e)
        }
    }

    fun list(path: String): List<RemoteFile> = withChannel { sftp ->
        val normalized = RemoteFile.normalize(path)
        val entries = try {
            @Suppress("UNCHECKED_CAST")
            sftp.ls(normalized) as java.util.Vector<ChannelSftp.LsEntry>
        } catch (e: SftpException) {
            throw SshOperationException("Cannot list $normalized: ${e.message}", e)
        }

        entries.asSequence()
            .filter { it.filename != "." && it.filename != ".." }
            .map { entry ->
                val attrs = entry.attrs
                val childPath = RemoteFile.join(normalized, entry.filename)
                RemoteFile(
                    path = childPath,
                    name = entry.filename,
                    isDirectory = resolveIsDirectory(sftp, attrs, childPath),
                    isSymlink = attrs.isLink,
                    size = attrs.size,
                    modifiedTime = attrs.mTime.toLong() * 1000L,
                    permissions = attrs.permissionsString,
                )
            }
            .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    /** A symlink reports itself as a link; follow it once so link-to-directory still expands. */
    private fun resolveIsDirectory(sftp: ChannelSftp, attrs: SftpATTRS, path: String): Boolean = when {
        attrs.isDir -> true
        !attrs.isLink -> false
        else -> runCatching { sftp.stat(path).isDir }.getOrDefault(false)
    }

    fun stat(path: String): RemoteFile? = withChannel { sftp ->
        val normalized = RemoteFile.normalize(path)
        val attrs = runCatching { sftp.stat(normalized) }.getOrNull() ?: return@withChannel null
        RemoteFile(
            path = normalized,
            name = if (normalized == "/") "/" else normalized.substringAfterLast('/'),
            isDirectory = attrs.isDir,
            isSymlink = attrs.isLink,
            size = attrs.size,
            modifiedTime = attrs.mTime.toLong() * 1000L,
            permissions = attrs.permissionsString,
        )
    }

    fun download(remotePath: String, target: File) = withChannel { sftp ->
        target.parentFile?.mkdirs()
        try {
            FileOutputStream(target).use { out -> sftp.get(remotePath, out) }
        } catch (e: SftpException) {
            throw SshOperationException("Download failed for $remotePath: ${e.message}", e)
        }
    }

    fun upload(source: File, remotePath: String) = withChannel { sftp ->
        try {
            FileInputStream(source).use { input -> sftp.put(input, remotePath, ChannelSftp.OVERWRITE) }
        } catch (e: SftpException) {
            throw SshOperationException("Upload failed for $remotePath: ${e.message}", e)
        }
    }

    fun createDirectory(remotePath: String) = withChannel { sftp ->
        try {
            sftp.mkdir(remotePath)
        } catch (e: SftpException) {
            throw SshOperationException("Cannot create directory $remotePath: ${e.message}", e)
        }
    }

    fun createEmptyFile(remotePath: String) = withChannel { sftp ->
        try {
            sftp.put(ByteArray(0).inputStream(), remotePath, ChannelSftp.OVERWRITE)
        } catch (e: SftpException) {
            throw SshOperationException("Cannot create file $remotePath: ${e.message}", e)
        }
    }

    fun rename(from: String, to: String) = withChannel { sftp ->
        try {
            sftp.rename(from, to)
        } catch (e: SftpException) {
            throw SshOperationException("Cannot rename $from: ${e.message}", e)
        }
    }

    fun delete(file: RemoteFile) = withChannel { sftp -> deleteRecursively(sftp, file) }

    private fun deleteRecursively(sftp: ChannelSftp, file: RemoteFile) {
        try {
            // A symlink is unlinked, never followed — deleting its target would be a surprise.
            if (file.isDirectory && !file.isSymlink) {
                @Suppress("UNCHECKED_CAST")
                val children = sftp.ls(file.path) as java.util.Vector<ChannelSftp.LsEntry>
                children.asSequence()
                    .filter { it.filename != "." && it.filename != ".." }
                    .forEach { entry ->
                        val childPath = RemoteFile.join(file.path, entry.filename)
                        val child = RemoteFile(
                            path = childPath,
                            name = entry.filename,
                            isDirectory = entry.attrs.isDir,
                            isSymlink = entry.attrs.isLink,
                            size = entry.attrs.size,
                            modifiedTime = entry.attrs.mTime.toLong() * 1000L,
                            permissions = entry.attrs.permissionsString,
                        )
                        deleteRecursively(sftp, child)
                    }
                sftp.rmdir(file.path)
            } else {
                sftp.rm(file.path)
            }
        } catch (e: SftpException) {
            throw SshOperationException("Cannot delete ${file.path}: ${e.message}", e)
        }
    }

    private fun <T> withChannel(block: (ChannelSftp) -> T): T = lock.withLock {
        val sftp = channel?.takeIf { it.isConnected && session?.isConnected == true }
            ?: throw SshOperationException("Not connected to ${config.host}.")
        block(sftp)
    }

    private fun loadKnownHosts(jsch: JSch) {
        val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts")
        if (knownHosts.isFile) runCatching { jsch.setKnownHosts(knownHosts.absolutePath) }
    }

    private fun describe(e: JSchException): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", ignoreCase = true) ||
                message.contains("Auth cancel", ignoreCase = true) ->
                "Authentication failed for ${config.username}@${config.host}."
            message.contains("UnknownHostKey", ignoreCase = true) ->
                "Host key of ${config.host} is not trusted: $message"
            message.contains("timeout", ignoreCase = true) ->
                "Connection to ${config.host}:${config.port} timed out."
            else -> "Cannot connect to ${config.host}:${config.port}: $message"
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
    }
}
