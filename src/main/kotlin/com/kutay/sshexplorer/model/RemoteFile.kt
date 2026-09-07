package com.kutay.sshexplorer.model

/**
 * One entry of a remote directory listing.
 *
 * [path] is always absolute and uses '/' separators, the way SFTP reports it.
 */
data class RemoteFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifiedTime: Long,
    val permissions: String,
) {
    val parentPath: String
        get() {
            val idx = path.trimEnd('/').lastIndexOf('/')
            return when {
                idx <= 0 -> "/"
                else -> path.trimEnd('/').substring(0, idx)
            }
        }

    companion object {
        fun directory(path: String): RemoteFile {
            val normalized = normalize(path)
            return RemoteFile(
                path = normalized,
                name = if (normalized == "/") "/" else normalized.substringAfterLast('/'),
                isDirectory = true,
                isSymlink = false,
                size = 0,
                modifiedTime = 0,
                permissions = "",
            )
        }

        fun normalize(path: String): String {
            val trimmed = path.trim().replace('\\', '/')
            if (trimmed.isEmpty()) return "/"
            val collapsed = trimmed.replace(Regex("/+"), "/")
            return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
        }

        fun join(parent: String, child: String): String =
            normalize(if (parent.endsWith("/")) parent + child else "$parent/$child")
    }
}
