# android-project

Android project-model and selected-target state for Emacs 28.1+. This library
owns editor metadata/cache/selection, not an Android minor mode, device
connection, or independent Gradle CLI.

## One shared backend

Install the Kotlin/JVM [`compose-preview`](https://github.com/cat-emacs/compose-preview)
CLI (`make install-local` in that checkout). It provides the shared command:

```sh
compose-preview project sync --project /work/android-project --json
```

The command uses the project's wrapper through Gradle Tooling API, reads build
structure and AGP V2 basic models, and supplements target fields through the
bundled public AGP Variant API/KGP collector. `android-project` no longer ships
or executes its own Gradle init script. No second CLI or renderer is needed by
this package. Project sync does not render images.

```elisp
(use-package android-project
  :vc (:url "https://github.com/cat-emacs/android-project")
  :custom
  (android-project-cli-program "compose-preview"))
```

An absolute path to the repository's `bin/compose-preview` also works after
building its distribution. Missing/incompatible CLI reports failed sync while
keeping an available last-known model. Startup never installs or builds the
CLI automatically. Preview/Logcat consume this library's public APIs; they do
not maintain separate selected-module/variant state.

## Interface

- `android-root`: project root containing gradlew.
- `android-project-variants`: all candidate variants.
- `android-project-targets`: one selected target per module.
- `android-project-target`: selected or exact module variant.
- `android-target-for-source-file`: source ownership and selected component.
- `android-current-target` / `android-current-application-id`: current source
  or remembered selection; prompting persists module and variant.
- `android-project-application-ids`: project application and test IDs.
- `android-project-model-status` / `android-refresh-project-model`: sync state
  and shared asynchronous refresh.

Missing/stale metadata starts one CLI process per root. Public reads immediately
return last-known metadata without waiting for Gradle. Completion callbacks
receive `(DATA ERROR)`; state changes notify
`android-project-model-state-changed-hook`, and successful publication runs
`android-project-model-updated-hook`. stdout is exclusively the shared JSON
protocol; CLI progress/build diagnostics use a separate stderr buffer.

Targets retain the public plist contract: composite-build-safe module IDs,
module/build roots, candidate variants, plugin/namespace, application/test IDs,
components/source roots, preview task and default preferences. User-selected
variant is an Emacs overlay, not whichever model record happens to appear first.
Malformed schemas or wrong project roots are rejected. Inputs changing during
collection/parsing cause old results to be discarded and callbacks preserved
for a new asynchronous refresh.

`android-project-cache-directory` controls selection/model persistence. Cache
schema 10 forces a refresh after migration to the shared backend. Native file
notifications/save hooks and lightweight idle metadata checks invalidate model
inputs. `android-mode-cache-dir` remains an alias. The old collector-script
variable/parser are removed because collection belongs to the shared CLI.
Loading the library enables no minor mode and installs no find-file/dired hook.

## Current backend limits

Tooling API/AGP V2 basic structure is available, but full dependency resolution,
phased sync and dependency-driven variant propagation are deferred. KMP source
ownership/default/application IDs are still supplemented with the public
Variant API/KGP data; capability flags state this explicitly. Model sync disables
configuration cache independently of Preview task execution. External settings
expressions or inputs not discovered by the current watcher can still require
manual refresh.

## Development

Run `make lint build test`. Unit lifecycle tests use fake shared CLI JSON and do
not need a JDK/SDK. Real Android/KMP integration is tested through the shared CLI.
Licensed under GPL-3.0-or-later; extracted from cat-emacs/android-mode.
