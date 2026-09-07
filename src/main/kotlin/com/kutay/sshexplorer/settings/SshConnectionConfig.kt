package com.kutay.sshexplorer.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.util.xmlb.annotations.Tag
import java.util.UUID

enum class SshAuthType { PASSWORD, KEY_PAIR }

/**
 * Serializable description of a single SSH target. Secrets never live here —
 * they are kept in the IDE PasswordSafe and looked up by [id].
 */
@Tag("connection")
class SshConnectionConfig {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var host: String = ""
    var port: Int = 22
    var username: String = ""
    var authType: SshAuthType = SshAuthType.PASSWORD
    var privateKeyPath: String = ""
    var rootPath: String = "/"
    var saveSecret: Boolean = true

    val displayName: String
        get() = name.ifBlank { "$username@$host" }

    fun copy(): SshConnectionConfig = SshConnectionConfig().also {
        it.id = id
        it.name = name
        it.host = host
        it.port = port
        it.username = username
        it.authType = authType
        it.privateKeyPath = privateKeyPath
        it.rootPath = rootPath
        it.saveSecret = saveSecret
    }

    /** Password for PASSWORD auth, private key passphrase for KEY_PAIR auth. */
    fun loadSecret(): String? =
        PasswordSafe.instance.get(credentialAttributes())?.getPasswordAsString()?.takeIf { it.isNotEmpty() }

    fun storeSecret(secret: String?) {
        val attributes = credentialAttributes()
        if (secret.isNullOrEmpty() || !saveSecret) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials(username, secret))
        }
    }

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName("SSH File Explorer", id))
}
