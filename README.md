# android-project

An independent Android Project System with a Kotlin/JVM core, CLI and Emacs
frontend. It discovers Gradle modules, variants, application/test IDs and source
ownership, and runs Gradle tasks through the Tooling API.

It does not require Compose Preview, a renderer or a device connection.

## Install

Requirements: **JDK 17+** for the CLI, **Emacs 28.1+** for the optional frontend.
Real projects need their Gradle wrapper and any SDK/toolchain required by their
build. Set `JAVA_HOME` to the project's supported JDK.

### Package managers

macOS / Linux ([Homebrew tap](https://github.com/cat-emacs/homebrew-tap)):

```sh
brew install cat-emacs/tap/android-project
```

Windows ([Scoop bucket](https://github.com/cat-emacs/scoop-bucket)):

```powershell
scoop bucket add java
scoop bucket add cat-emacs https://github.com/cat-emacs/scoop-bucket
scoop install cat-emacs/android-project
```

Both install a JDK dependency. Set `JAVA_HOME` if a project needs a different JDK.

### Direct download or source build

[Releases](https://github.com/cat-emacs/android-project/releases/latest) provide
versioned ZIP/tar.gz archives and `SHA256SUMS`. Verify, extract and add the
archive's `bin/` to PATH; JDK 17+ must already be installed. CLI versions are
independent of the core library version.

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
| `android-project-dependencies` | Cached dependency state/edges for a module variant |
| `android-project-model-status` / `android-refresh-project-model` | Sync status and asynchronous refresh |

Queries return immediately with fresh or last-known data. Stale metadata starts
one asynchronous sync per root. Failures retain the last-known model; inputs
changing during sync cause stale results to be discarded and retried.

Refresh callbacks receive `(DATA ERROR)`. State/success notifications use
`android-project-model-state-changed-hook` and
`android-project-model-updated-hook`. Module/variant selection and model caches
are stored under `android-project-cache-directory`. Cache schema 14 invalidates
older state. Targets are plists only; the old `android-mode-cache-dir` alias,
private tuple helpers and `previewTask` hint have been removed.

## Shared core

```text
android-project CLI -> android-project-core <- compose-preview CLI
```

`core/` owns Tooling API/AGP models and shared IO/JSON/hash helpers; `cli/` is a
command adapter. `tooling/` is a compiled Gradle plugin; `tooling-model/` holds
wire interfaces compiled into both sides. The plugin jar is bundled as a core
resource, so no extra artifact installation is required. Preview embeds the library in its JVM, not the project CLI.
The Emacs frontend remains in `android-project.el`.

Published artifact: **`org.cat-emacs:android-project-core:0.1.5`**. The current
source uses this version; an explicit local repository is optional for development.
Registry: `https://maven.pkg.github.com/cat-emacs/android-project`

GitHub Maven Packages requires authentication even for public packages.
Consumers need a classic PAT with `read:packages` or an authorized Actions token.
Provide `GITHUB_ACTOR`/`GITHUB_TOKEN` securely; never commit credentials.

Releases use the manual **Publish Project Core** workflow: test, publish, then
download and compare the jar. It supports `verify_only` for existing versions.
Local publication uses `:core:publishCorePublicationToGitHubPackagesRepository`
and requires a classic PAT with `write:packages`.

## Project dependencies

Ordinary sync only collects metadata, without AGP dependency resolution.
`--dependencies requested --selection-file FILE` resolves exact requested targets;
`--dependencies selected` explicitly resolves one selected variant per module.
The default is `--dependencies none`. Resolution uses AGP V2 `VariantDependencies`,
limited to main compile/runtime project dependencies. It retains transitive as
well as direct project references, resolved producer attributes/capabilities,
test-fixtures flags and composite-safe build/module identity. Producer variant
mapping uses resolved attributes and target metadata, never consumer name guesses.
It does not change a dependency module's user-selected variant.

Each target has `dependencies` with `status`, `variant`, `edges` and `issues`.
States are `ready`, `partial`, `unsupported`, `failed` or `not-requested`.
Unmapped identities/variants and unresolved dependencies remain explicit; an empty
edge list alone never proves successful resolution. This is a module-reference
view, not the complete external-library graph or classpath/artifact freshness.

Selection files are JSON arrays of `{buildRoot,modulePath,variant}`. The `selected`
mode follows Studio-style defaults for unspecified modules; `requested` mode never
resolves implicit defaults. Stale selections are reported in `selectionIssues`.
`android-project-dependencies` returns immediately and queues one async request
for a missing graph; repeated reads share the active sync. Requests are combined
per project, updated on variant selection, and passed through a private temporary
file. New demands/selections arriving during sync invalidate its result and retain
callbacks for retry. Metadata-only startup does not request all module graphs.
No persistent dependency cache bypasses input/freshness validation.

The current KMP fixture's AGP V2 dependency model is unavailable for KMP modules;
they report `unsupported`. Ordinary Android modules depending on KMP can expose
project identity with an unmapped producer variant (`partial`). Test-component
graphs and full configuration-aware variant propagation are deferred.

## Limits and development

The staging follows the local Android Studio source design (idea revision
`001c2a2cb31`): `ModelProviderCachedData` shares models within a sync and clears
state afterward; phased providers reuse fetched data and control dependency
classpath requests. This CLI borrows the bounded-lifetime principle, not the
whole IDE phased-sync implementation. Avoiding duplicate model fetches is preferred
to reusing stale Gradle proxies across invocations.


Sync fetches AGP V2 `BasicAndroidProject`, `AndroidProject` and `AndroidDsl` where
available. Namespace, final artifact IDs, build type/flavors, default preferences
and debuggable state prefer those models. Static component roots and KMP source
chains remain a Variant API/KGP supplement, returned as typed Tooling API models.
`modules[].fetchedModels` and `issues` identify model coverage/fallbacks. Default
build-type preferences follow V2 boolean values, fixing the old collector's
truthy-Provider check; persisted user variant selections still take precedence.

The init script only loads the bundled plugin; there is no stdout marker/Base64
protocol or `help` task. A lightweight action reads only Gradle build roots,
without injecting the plugin or fetching AGP models. A second action fetches the
authoritative typed models once, protected by input-signature and build-root
checks. stderr `sync-timing` separates discovery, input baseline, model fetch and
input verification costs. No AGP proxy/model cache survives across syncs. Sync is still not
full Android Studio phased sync; dependency-led variant propagation is not implemented. Sync alone disables configuration cache
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
