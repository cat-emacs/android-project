# android-project

An independent Android Project System with a Kotlin/JVM core, CLI and Emacs
frontend. It discovers Gradle modules, variants, application/test IDs and source
ownership, and runs Gradle tasks through the Tooling API.

It does not require Compose Preview, a renderer or a device connection.

## Install

Requirements: **JDK 17+** for the CLI, **Emacs 28.1+** for the optional frontend.
Real projects need their Gradle wrapper and any SDK/toolchain required by their
build. Set `JAVA_HOME` to the project's supported JDK.

From this checkout:

```sh
make install-local                 # installs under ~/.local; override PREFIX if needed
android-project doctor --json
```

Add `~/.local/bin` to PATH. The installed CLI includes its runtime dependencies;
no Kotlin compiler is needed. To use the checkout without installing, run
`./gradlew :cli:installDist`, then `./bin/android-project`.

### Emacs

Install the CLI separately, then configure the frontend:

```elisp
(use-package android-project
  :vc (:url "https://github.com/cat-emacs/android-project")
  :custom
  (android-project-cli-program "android-project"))
```

Emacs never builds or installs the CLI automatically. Loading the library does
not enable a minor mode or add find-file/dired hooks.

## CLI

```sh
# Discover project metadata.
android-project sync --project /work/app --json

# Run a task using the project's wrapper distribution.
android-project build --project /work/app --task :app:assembleDebug --json

# Pass Gradle options or a consumer-owned init script.
android-project build --project /work/app --task :app:assembleDebug \
  --gradle-arg=--configuration-cache --init-script /path/init.gradle --json
```

- `sync` returns `modules`, `buildRoots`, `targets` and capability flags.
  Included-build module identity is `(buildRoot, modulePath)`.
- `--timeout SECONDS` bounds operations; `--result-file FILE` atomically writes
  the same JSON result.
- stdout contains one schemaVersion=1 JSON object; build/progress logs use stderr.
  Exit **0** means success, **2** means failure with `error.kind`/`error.message`.
- Tooling API uses a daemon; `--no-daemon` is unsupported. Cancellation affects
  only the owned operation, never shared Gradle/Kotlin daemon lifetime.

## Emacs API

| API | Purpose |
|---|---|
| `android-root` | Locate the project root |
| `android-project-variants` | All candidate variants |
| `android-project-targets` / `android-project-target` | Selected or exact module targets |
| `android-target-for-source-file` | Source/component ownership |
| `android-current-target` / `android-current-application-id` | Current or remembered target |
| `android-project-application-ids` | Project application and test IDs |
| `android-project-model-status` / `android-refresh-project-model` | Sync status and asynchronous refresh |

Queries return immediately with fresh or last-known data. Stale metadata starts
one asynchronous sync per root. Failures retain the last-known model; inputs
changing during sync cause stale results to be discarded and retried.

Refresh callbacks receive `(DATA ERROR)`. State/success notifications use
`android-project-model-state-changed-hook` and
`android-project-model-updated-hook`. Module/variant selection and model caches
are stored under `android-project-cache-directory`.

## Shared core

```text
android-project CLI -> android-project-core <- compose-preview CLI
```

`core/` owns Tooling API/AGP models and shared IO/JSON/hash helpers; `cli/` is a
command adapter. Preview embeds the library in its JVM, not the project CLI.
The Emacs frontend remains in `android-project.el`.

Published artifact: **`org.cat-emacs:android-project-core:0.1.1`**
Registry: `https://maven.pkg.github.com/cat-emacs/android-project`

GitHub Maven Packages requires authentication even for public packages.
Consumers need a classic PAT with `read:packages` or an authorized Actions token.
Provide `GITHUB_ACTOR`/`GITHUB_TOKEN` securely; never commit credentials.

Releases use the manual **Publish Project Core** workflow: test, publish, then
download and compare the jar. It supports `verify_only` for existing versions.
Local publication uses `:core:publishCorePublicationToGitHubPackagesRepository`
and requires a classic PAT with `write:packages`.

## Limits and development

Sync combines AGP V2 basic models with a public Variant API/KGP supplement. It
is not full Android Studio phased sync: dependency resolution and dependency-led
variant propagation are not implemented. Sync alone disables configuration cache
and configure-on-demand; task execution keeps the caller's policy. Undiscovered
external build inputs can require manual refresh.

```sh
make lint build test                         # Emacs frontend
make cli-test                                # core + CLI, including real Gradle fixtures
./gradlew :cli:installDist :cli:distZip        # distributions

# Publish core to an isolated local repository for consumer development.
./gradlew :core:publishCorePublicationToProjectCoreRepository \
  -PandroidProjectRepository=/tmp/project-core-repository
```

Licensed under GPL-3.0-or-later; extracted from cat-emacs/android-mode.
