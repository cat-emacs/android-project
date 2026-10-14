# android-project

Shared Android Gradle project model for Emacs 28.1+. This library owns project
metadata and target selection, not an Android minor mode or device connection.

## Installation

```elisp
(use-package android-project
  :vc (:url "https://github.com/cat-emacs/android-project"))
```

## Interface

Existing public function names are preserved:

- `android-root`: project root containing gradlew.
- `android-project-variants`: all module variant metadata.
- `android-project-targets`: one selected target per module.
- `android-project-target`: selected or exact module variant.
- `android-target-for-source-file`: source ownership and selected component.
- `android-current-target` / `android-current-application-id`: current source
  or remembered project selection; prompting persists module and variant.
- `android-project-application-ids`: project application and test IDs.
- `android-project-model-status` / `android-refresh-project-model`: lifecycle
  status and shared asynchronous refresh.

Missing or stale metadata starts one Gradle refresh per root. Reads return
last-known in-memory metadata without waiting for Gradle. Refresh callbacks
receive DATA and ERROR. State transitions notify
`android-project-model-state-changed-hook` with root and status;
`android-project-model-updated-hook` receives root and data after success.

Targets include composite-build-safe module IDs, module/build roots, variant,
plugin, namespace, application/test IDs, source components, and preview tasks.
The Gradle init script uses the AGP/KGP models; source ownership and default
variant ordering follow Android Studio. Inputs, included builds, and build
logic are watched for invalidation.

`android-project-cache-directory` controls model/selection persistence. The
old `android-mode-cache-dir` and `android-mode-flavor-script` variable names
remain aliases, so existing configuration and cache paths continue working.
Loading this library does not enable minor modes or install find-file/dired
hooks. It does install a save hook for already-watched project model inputs.

## Development

Run `make lint build test`. Lifecycle tests use fake Gradle commands.
Licensed under GPL-3.0-or-later. The model implementation and Gradle script were
extracted from cat-emacs/android-mode.
