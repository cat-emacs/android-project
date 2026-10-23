# android-project

Independent Gradle/Android Project System, with a Kotlin/JVM core, a standalone
CLI and an Emacs 28.1+ frontend. It owns project discovery, AGP models, Tooling
API execution and selected-target metadata. It does **not** depend on Compose
Preview, layoutlib, a renderer, an Android minor mode or a device connection.

## Install

JDK 17+ is required to run the CLI. The wrapper builds a standard application
distribution; an installed CLI does not require a Kotlin compiler or this checkout.

```sh
make install-local                         # default PREFIX=$HOME/.local
# Or: ./gradlew :cli:installDist :cli:distZip
android-project doctor --json
android-project sync --project /work/app --json
```

The repository's `bin/android-project` works after `:cli:installDist`. Installation
is explicit; Emacs startup never builds or installs the CLI.

```elisp
(use-package android-project
  :vc (:url "https://github.com/cat-emacs/android-project")
  :custom
  (android-project-cli-program "android-project"))
```

## Architecture and CLI

```text
core/                    Kotlin project models, Tooling API, AGP V2
cli/                     thin command/protocol adapter depending only on core
android-project.el       asynchronous editor cache/selection/public queries
```

The core packages its Variant API collector as a classpath resource. It has no
Preview imports or assets. Its public `GradleTooling` / `synchronizeProject`
interfaces can be embedded by JVM consumers; `:core:publishToMavenLocal` produces
`org.cat-emacs:android-project-core:0.1.1`. Versions 0.1.0 and 0.1.1 are published
on GitHub Packages; 0.1.1 includes the shared IO/JSON API.
Preview embeds this versioned core library directly in its JVM; it never invokes
the project CLI for its build. Both CLI distributions bundle the library and its
runtime dependencies. Project synchronization in Emacs still uses this CLI.

The `cat.android.project.io` API shares sorted no-follow directory traversal,
file stamps/link identity, SHA-256/content hashing, canonical JSON/type checks,
and same-directory atomic JSON writes. Consumers keep their own scan exclusions,
input suffixes, CLI schemas and cache identity policies. Project signatures retain
the existing encoding; Preview canonical hashes retain the existing JSON encoding.
No shared CLI parser or global cache is introduced.

For an isolated artifact repository (without modifying `~/.m2`), run:

```sh
./gradlew :core:publishCorePublicationToProjectCoreRepository \
  -PandroidProjectRepository=/tmp/project-core-repository
```

Preview resolves that coordinate using the same explicit repository property.
Core publication never builds/installs the project CLI or depends on Preview.

### GitHub Packages publication

Registry: `https://maven.pkg.github.com/cat-emacs/android-project`.
Current published coordinate: `org.cat-emacs:android-project-core:0.1.1`.

The manually dispatched `Publish Project Core` workflow tests the committed main
revision, publishes using its repository-scoped `GITHUB_TOKEN` with
`packages:write`, and downloads/compares the published jar. Dispatch requires the
requested version to match the declared core version. Publishing is explicit,
not an automatic effect of ordinary CI or local installation.

Local publication requires a classic PAT with `write:packages` (and organization
SSO authorization when applicable). Provide `GITHUB_ACTOR` and `GITHUB_TOKEN`
through your shell/credential manager; never commit them or paste them into logs:

```sh
./gradlew :core:publishCorePublicationToGitHubPackagesRepository
```

GitHub's Maven registry requires authentication even for public packages. A
consumer needs a classic PAT with `read:packages`, or an appropriately authorized
Actions token. Ordinary `gh` OAuth login is not a substitute for registry
credentials. Repository configuration alone does not mean a version is published;
verify the publishing workflow and authenticated download before switching consumers.

Commands:

- `doctor --json`: backend versions/capabilities, without starting Gradle.
- `sync --project DIR [--timeout SECONDS] --json`: build/module/target model.
- `build --project DIR --task :app:task [--init-script FILE]
  [--gradle-arg=ARG] [--timeout SECONDS] --json`: general task execution.
- `--result-file FILE`: atomically write the same result JSON.

stdout contains exactly one schemaVersion=1 JSON object; progress and build
output go to stderr. Exit 0 means success, 2 means input/global failure. Errors
carry `error.kind` and `error.message`; no Lisp reader or executable JSON is used.
The CLI inherits the calling environment. `build` reports the exact root/task
identity. Timeout or process termination cancels only its Tooling operation;
shared Gradle/Kotlin daemons are never stopped. Tooling API always uses a daemon;
`--no-daemon` is not a supported CLI flag.

`sync` respects the project's wrapper distribution and reads Gradle structure
and AGP V2 `Versions` / `BasicAndroidProject` through serializable DTOs. The
bundled public Variant API/KGP collector supplements application/test IDs,
defaults, components and KMP source roots. Included builds retain build-root plus
module-path identity. The JSON contains `modules`, `buildRoots`, `targets` and
explicit capabilities. Targets carry `modelBuildRoots` for editor input watches.

## Emacs interface

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

Public reads immediately return fresh/last-known metadata. Missing/stale models
start one asynchronous sync per root; callbacks receive `(DATA ERROR)`.
`android-project-model-state-changed-hook` reports state, and successful
publication runs `android-project-model-updated-hook`. A missing CLI or failed
sync preserves last-known data. Input changes during sync/parsing reject stale
results and preserve callbacks for retry. User selection is an editor overlay,
not whichever variant the backend emits first.

`android-project-cache-directory` controls private selection/model persistence;
cache schema 10 is unchanged because the target protocol has not changed.
Native file notifications/save hooks and idle metadata checks invalidate inputs.
Loading the library enables no minor mode and installs no find-file/dired hook.
Preview and Logcat consume this package's public API; dependency direction is
strictly consumers -> Project System, never Project System -> Preview.

## Limits and development

Full dependency resolution, phased sync and dependency-driven variant propagation
are deferred. Sync performs a model action and a lightweight `help` build, not a
complete Android Studio sync. Only sync disables configuration cache and configure
on demand; task execution preserves consumer configuration-cache policy.
External settings expressions/undiscovered inputs may require manual refresh.

Run `make lint build test` for the frontend (no JDK/SDK required), and
`make cli-test` for core/real Gradle action, failure and cancellation tests.
`./gradlew :cli:installDist :cli:distZip` builds distributable artifacts. CLI
smokes run outside the checkout and require no Preview installation.

Licensed under GPL-3.0-or-later; extracted from cat-emacs/android-mode.
