package com.kutay.sshexplorer.ssh

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object SshNotifier {
    private const val GROUP_ID = "SSH Explorer"

    fun info(project: Project?, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun error(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("SSH File Explorer", message, type)
            .notify(project)
    }
}
