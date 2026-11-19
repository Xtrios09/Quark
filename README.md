# Quark

A hackable, fully offline code editor for Android tablets, in the spirit of Atom, with VS Code-style editing.

Everything runs on the device. There is no backend and no telemetry.

<p align="center">
 <img width="1600" height="898" alt="5" src="https://github.com/user-attachments/assets/3ce723ed-8cf2-4e6d-ad65-a3fd875feae0" />
</p>

> **Status:** early development. Expect rough edges and missing features.

---

## Table of contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Project structure](#project-structure)
- [Build and run](#build-and-run)
- [Build flavors](#build-flavors)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## Features

**Editor**
- Custom editor view with line numbers, current-line highlight, indent guides and code folding
- Syntax highlighting with a language indicator in the status bar
- IntelliSense-style completion popup (tags, elements and symbols, with a result count)
- Split view: open two editor panes side by side
- Multiple tabs with per-file icons
- Extra-key row above the soft keyboard: Auto, Tab, Esc, Ctrl, Alt, arrows and common symbols `{ } ( ) [ ] ; : " ' /`
- Hardware keyboard, mouse and touch input

**Projects and files**
- File explorer with create file, create folder, refresh and import actions
- Quick Open by path (`Ctrl+P`)
- Starter templates: Kotlin, Java, JavaScript, TypeScript, Python, C, C++, Rust, Go, SQL (SQLite), HTML / Web
- Open an existing folder, import a zip, and a recent-projects list
- Global search and Git source control panels in the side bar

**Terminal and run**
- Built-in terminal (`xterm-256color`) with multiple session tabs
- Terminal extra-key row: Tab, `^C`, `^L`, arrows, `|`, `/`, `-`, Home, End
- Bottom panel with **Terminal**, **Output** and **Preview** tabs
- Live preview for HTML / Web projects
- Run the active file from the toolbar or with `F5`

**Customization**
- Command palette listing every command with its shortcut
- Themes: One Dark, One Light, Dracula, Nord, Catppuccin Mocha, Tokyo Night, Gruvbox Dark, plus an option to follow the system dark/light setting
- Editor settings: font (JetBrains Mono or system monospace), font size, font ligatures, tab size (2 / 4 / 8), insert spaces for tabs, word wrap, line numbers, indent guides, auto-save, format on save
- Per-project configuration folder: `.editor/` (includes `settings.json`)

---

## Screenshots

### Editor with live preview & Code Completion
HTML editing with the file explorer on the left, the extra-key row under the editor and the Preview tab open at the bottom.
IntelliSense popup suggesting HTML elements while typing, with the soft keyboard open and the editor area resized around it.

<img width="1600" height="891" alt="2" src="https://github.com/user-attachments/assets/1da53b9a-7dcc-4578-b242-89d3ecc682c4" />


### Themes
A theme picker with several built-in palettes and a toggle to follow the system dark/light mode.

<img width="1024" height="592" alt="image" src="https://github.com/user-attachments/assets/2889163c-7dfe-49ad-af18-df3db13cff72" />


### Built-in terminal
The terminal panel with session tabs, a clear button, a `^C` interrupt button and its own extra-key row. Shown here with the light theme.

<img width="1600" height="940" alt="6" src="https://github.com/user-attachments/assets/24eb2d07-af39-420e-92f1-3ab2896af873" />

### Split editor
Two panes showing the same file side by side, with the terminal below.

<img width="1600" height="940" alt="6" src="https://github.com/user-attachments/assets/8be799bf-8197-4615-b39e-eede02a7e788" />

### Command palette
Every command is listed with fuzzy search and its keybinding.

<img width="1600" height="865" alt="8" src="https://github.com/user-attachments/assets/51e70e2b-e1da-4899-a813-6c1cf75a10f0" />


---

## Project structure

Quark is a multi-module Gradle project.

| Module | Purpose |
|---|---|
| `app` | Application shell, layout, settings, command palette, welcome screen |
| `buffer` | Text buffer |
| `editor-view` | Custom editor view and input handling |
| `fs` | Project and file storage |
| `terminal` | Terminal sessions, emulation and rendering |
| `git` | Source control integration |
| `intel` | Completion and language intelligence |

---

## Build and run

### Requirements

- Android Studio (latest stable)
- Android SDK Platform 36 (`compileSdk = 36`)
- NDK and CMake, installed from **Tools > SDK Manager > SDK Tools**, if Gradle asks for them
- JDK 17 or newer (the JDK bundled with Android Studio works)
- A tablet or tablet emulator running Android 8.0 (API 26) or higher

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/YOURNAME/Quark.git
   cd Quark
   ```
2. Open the folder in Android Studio and let Gradle sync.
3. Create a debug keystore in the project root (the build expects `debug.keystore` next to `settings.gradle.kts`).

   Copy the default one on Windows (PowerShell):
   ```powershell
   Copy-Item "$env:USERPROFILE\.android\debug.keystore" .\debug.keystore
   ```
   Or generate one:
   ```bash
   keytool -genkeypair -v -keystore debug.keystore -storepass android \
     -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
     -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```
4. Select the `app` run configuration and the `debug` build variant.
5. Choose a tablet device (a Pixel Tablet AVD works well) and press **Run**.

### Build an APK from the command line

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> The emulator uses x86_64, while most real tablets use arm64-v8a. If a native component does not load on a real device, check the ABI filters of the module that builds native code.

---

## Build flavors

Flavor selection is a Gradle property. Add it to `gradle.properties`, then sync.

| Flavor | Property | Target SDK | Behavior |
|---|---|---|---|
| `play` (default) | `quarkFlavor=play` or omit | 35 | Executables can only come from the app's native library directory |
| `sideload` | `quarkFlavor=sideload` | 28 | Executables can run from the app-private bin directory |

The active flavor and target SDK are shown at the left of the status bar (for example `PLAY SDK 35`).

Android 10 and newer block running binaries from writable app storage for apps that target SDK 29 or higher. This is why the `sideload` flavor targets SDK 28. It is intended for direct APK installs and is not accepted by Google Play.

---

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+P` | Quick Open file by path |
| `Ctrl+S` | Save file |
| `F5` | Compile and run active file |

Open the command palette to see every command and its current keybinding. Additional shortcuts are listed there as they are added.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Keystore file '...\debug.keystore' not found` | Create `debug.keystore` in the project root (see [Build and run](#build-and-run)) |
| Red errors in `ExampleInstrumentedTest.kt` | Delete `app/src/androidTest`, or add the `androidTest` dependencies. This does not affect APK builds |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall the existing copy from the device, then run again (the signing key differs) |
| Emulator is slow or will not start | Enable virtualization in BIOS and turn on Windows Hypervisor Platform. Use an x86_64 image |

---

## Contributing

Issues and pull requests are welcome. Please open an issue first for larger changes.

Sign off your commits with `git commit -s` to confirm you have the right to submit the code under the project license.

---

## License

Copyright 2026-2027 Your Abhijeet Prabhakar

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
