# SSH File Explorer — PhpStorm plugin

An SSH/SFTP file browser for PhpStorm (and other IntelliJ-based IDEs). Once
connected, an **SSH Explorer** tool window opens on the left side of the IDE so
you can browse the remote filesystem and edit any file directly in the editor.

## Features

- Multiple saved SSH connections (password or OpenSSH private key)
- Secrets are stored in the IDE PasswordSafe, never written to the settings file
- Lazy-loading remote file tree in the left tool window
- Double click a file → it is downloaded and opened in the editor; **saving with
  Ctrl+S uploads it back to the server automatically**
- New file / new folder, rename, delete (folders are deleted recursively)
- Download… and Upload… including whole directories
- Context menu, copy remote path, speed search in the tree

## Building

Requires JDK 21. The Gradle wrapper is 9.7.1 and Kotlin is 2.4.20 — PhpStorm
2026.2 ships Kotlin stdlib metadata 2.4, so an older compiler fails with an
"incompatible metadata version" error.

```bash
./gradlew buildPlugin
```

Output: `build/distributions/ssh-file-explorer-1.0.0.zip`

`localIdePath` in `gradle.properties` points at the installed PhpStorm
(`/snap/phpstorm/current`). Leave it empty and Gradle downloads PhpStorm 2026.2
from JetBrains instead.

## Trying it inside a sandbox IDE

```bash
./gradlew runIde
```

## Installing

PhpStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip
above, then restart the IDE.

## Usage

1. Open the **SSH Explorer** tab on the left edge.
2. In the toolbar click **Connect** (▶) → **Connections…** to add a target.
3. Enter host / port / user name / authentication type, verify with
   **Test Connection**, and save.
4. Connect — the tree opens at the configured `Root path`.
5. Double click a file, edit it, save — the change is pushed to the server.

## Notes

- Unknown host keys are added to `~/.ssh/known_hosts` on first connect using the
  `accept-new` policy.
- Remote files are mirrored into the IDE system directory:
  `<IDE system dir>/ssh-file-explorer/<connection-id>/<remote path>`
- If the connection is gone when you save, the file stays local only and the
  plugin shows an error notification instead of failing silently.
