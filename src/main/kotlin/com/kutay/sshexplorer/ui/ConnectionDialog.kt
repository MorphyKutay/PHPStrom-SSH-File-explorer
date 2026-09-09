package com.kutay.sshexplorer.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.kutay.sshexplorer.settings.SshAuthType
import com.kutay.sshexplorer.settings.SshConnectionConfig
import com.kutay.sshexplorer.ssh.SftpClient
import com.kutay.sshexplorer.ssh.UnknownHostKeyException
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Add / edit form for one SSH target. */
class ConnectionDialog(
    private val project: Project?,
    private val config: SshConnectionConfig,
) : DialogWrapper(project, true) {

    private val nameField = JBTextField(config.name)
    private val hostField = JBTextField(config.host)
    private val portField = JBTextField(config.port.toString())
    private val userField = JBTextField(config.username)
    private val authCombo = JComboBox(arrayOf(AUTH_PASSWORD, AUTH_KEY))
    private val secretField = JBPasswordField()
    private val secretLabel = JLabel("Password:")
    private val keyPathField = TextFieldWithBrowseButton()
    private val keyRow = JPanel(java.awt.BorderLayout())
    private val rootField = JBTextField(config.rootPath)
    private val saveSecretBox = JBCheckBox("Save password in the IDE password safe", config.saveSecret)
    private val testButton = JButton("Test Connection")

    init {
        title = if (config.name.isBlank() && config.host.isBlank()) "New SSH Connection" else "Edit SSH Connection"
        authCombo.selectedItem = if (config.authType == SshAuthType.KEY_PAIR) AUTH_KEY else AUTH_PASSWORD
        keyPathField.text = config.privateKeyPath
        secretField.text = config.loadSecret().orEmpty()
        keyPathField.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            FileChooser.chooseFile(descriptor, project, null) { file -> keyPathField.text = file.path }
        }
        authCombo.addActionListener { updateAuthVisibility() }
        testButton.addActionListener { testConnection() }
        init()
        updateAuthVisibility()
    }

    override fun createCenterPanel(): JComponent {
        keyRow.add(keyPathField, java.awt.BorderLayout.CENTER)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("Name:"), nameField, true)
            .addLabeledComponent(JLabel("Host:"), hostField, true)
            .addLabeledComponent(JLabel("Port:"), portField, true)
            .addLabeledComponent(JLabel("User name:"), userField, true)
            .addLabeledComponent(JLabel("Authentication:"), authCombo, true)
            .addLabeledComponent(secretLabel, secretField, true)
            .addLabeledComponent(JLabel("Private key file:"), keyRow, true)
            .addComponent(saveSecretBox)
            .addLabeledComponent(JLabel("Root path:"), rootField, true)
            .addComponentToRightColumn(testButton)
            .panel
        panel.preferredSize = Dimension(480, panel.preferredSize.height)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = if (hostField.text.isBlank()) hostField else nameField

    override fun doValidate(): ValidationInfo? {
        if (hostField.text.isBlank()) return ValidationInfo("Host is required.", hostField)
        if (userField.text.isBlank()) return ValidationInfo("User name is required.", userField)
        val port = portField.text.trim().toIntOrNull()
        if (port == null || port !in 1..65535) return ValidationInfo("Port must be between 1 and 65535.", portField)
        if (selectedAuthType() == SshAuthType.KEY_PAIR && keyPathField.text.isBlank()) {
            return ValidationInfo("Private key file is required for key authentication.", keyPathField)
        }
        return null
    }

    override fun doOKAction() {
        applyTo(config)
        config.storeSecret(String(secretField.password))
        super.doOKAction()
    }

    private fun updateAuthVisibility() {
        val isKey = selectedAuthType() == SshAuthType.KEY_PAIR
        secretLabel.text = if (isKey) "Passphrase:" else "Password:"
        saveSecretBox.text = if (isKey) {
            "Save passphrase in the IDE password safe"
        } else {
            "Save password in the IDE password safe"
        }
        keyRow.isVisible = isKey
        keyRow.parent?.revalidate()
    }

    private fun selectedAuthType(): SshAuthType =
        if (authCombo.selectedItem == AUTH_KEY) SshAuthType.KEY_PAIR else SshAuthType.PASSWORD

    private fun applyTo(target: SshConnectionConfig) {
        target.name = nameField.text.trim()
        target.host = hostField.text.trim()
        target.port = portField.text.trim().toIntOrNull() ?: 22
        target.username = userField.text.trim()
        target.authType = selectedAuthType()
        target.privateKeyPath = keyPathField.text.trim()
        target.rootPath = rootField.text.trim().ifBlank { "/" }
        target.saveSecret = saveSecretBox.isSelected
    }

    private fun testConnection() {
        val validation = doValidate()
        if (validation != null) {
            Messages.showErrorDialog(contentPanel, validation.message, "Invalid Settings")
            return
        }
        val probe = config.copy().also { applyTo(it) }
        val secret = String(secretField.password)

        // The first attempt never trusts an unknown host key; the retry only happens after
        // the user confirmed the fingerprint, which cannot be asked from the progress thread.
        val first = probeConnection(probe, secret, trustUnknownHostKey = false)
        val result = if (first is ProbeResult.UntrustedHostKey) {
            if (!confirmHostKey(project, first.prompt)) return
            probeConnection(probe, secret, trustUnknownHostKey = true)
        } else {
            first
        }

        when (result) {
            is ProbeResult.Ok ->
                Messages.showInfoMessage(contentPanel, "Connected to ${probe.host} successfully.", "Connection OK")
            is ProbeResult.UntrustedHostKey ->
                Messages.showErrorDialog(contentPanel, HOST_KEY_NOT_STORED, "Connection Failed")
            is ProbeResult.Failed ->
                Messages.showErrorDialog(contentPanel, result.message, "Connection Failed")
        }
    }

    private sealed interface ProbeResult {
        object Ok : ProbeResult
        class UntrustedHostKey(val prompt: String) : ProbeResult
        class Failed(val message: String) : ProbeResult
    }

    private fun probeConnection(
        probe: SshConnectionConfig,
        secret: String,
        trustUnknownHostKey: Boolean,
    ): ProbeResult = ProgressManager.getInstance().runProcessWithProgressSynchronously<ProbeResult, Exception>({
        val client = SftpClient(probe)
        try {
            client.connect(secret, trustUnknownHostKey)
            client.list(probe.rootPath)
            ProbeResult.Ok
        } catch (e: UnknownHostKeyException) {
            ProbeResult.UntrustedHostKey(e.prompt)
        } catch (e: Exception) {
            ProbeResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            client.disconnect()
        }
    }, "Testing SSH Connection", true, project)

    private companion object {
        const val AUTH_PASSWORD = "Password"
        const val AUTH_KEY = "Key pair (OpenSSH)"
        const val HOST_KEY_NOT_STORED = "The confirmed host key could not be stored in ~/.ssh/known_hosts."
    }
}
