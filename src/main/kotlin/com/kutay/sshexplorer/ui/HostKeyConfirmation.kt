// SSH File Explorer — Copyright (C) 2026 Kutay Aydogdu
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kutay.sshexplorer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Shows the fingerprint JSch reported for an untrusted host and asks whether it may be
 * written to `~/.ssh/known_hosts`. Must be called on the EDT.
 *
 * @param prompt the raw JSch question, which already contains host name and fingerprint.
 * @return `true` only when the user explicitly accepted the key.
 */
fun confirmHostKey(project: Project?, prompt: String): Boolean =
    Messages.showYesNoDialog(
        project,
        "$prompt\n\nAccept this key only if the fingerprint matches the one you got from the server owner.",
        "Untrusted SSH Host Key",
        "Trust and Connect",
        "Cancel",
        Messages.getWarningIcon(),
    ) == Messages.YES
