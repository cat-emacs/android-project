---
name: android-project
description: >-
  Discover Android/Gradle modules, variants, application IDs and source ownership;
  query exact project dependency graphs or execute a requested Gradle task with
  the Cat Emacs android-project CLI. Use for Android Project System metadata,
  composite-build target selection and Gradle JVM diagnosis. For Compose Preview
  discovery or PNG rendering, use compose-preview instead.
---

# Android Project

Use the installed `android-project` CLI; no Emacs session, device or Preview
executable is required. Treat project Gradle code as executable code: sync and
build can configure projects, write build/cache files and resolve dependencies.
Run only in a trusted project and within the user's requested operation.

## Preflight

1. Locate the executable (`command -v android-project`, or `Get-Command` on
   Windows); run `android-project --help` and `android-project doctor --json`.
   Require a successful JSON response. Doctor describes the client, not a
   successful project sync or actual build JVM.
2. Resolve the project entry root to an absolute canonical path. Verify its
   `gradle/wrapper/gradle-wrapper.properties`. Installed CLI 0.1.5 needs Java 17+;
   the project may require a different compatible JDK. Check the installed help
   before using options; an old PATH installation may shadow the expected CLI.
3. If missing, offer the [README](https://github.com/cat-emacs/android-project#install)'s Homebrew/Scoop/Release installation
   choices. Install/upgrade or source-build only when authorized. Installed
   distributions contain dependencies and need no GitHub Packages credentials.

Keep stdout JSON and stderr logs separate; capture the process exit code even
when using a result file. Set the outer execution deadline longer than the CLI's
`--timeout` plus startup/cleanup. Use unique temporary files for each request.

## Discover and select an exact target

POSIX examples use canonical `$ROOT` and a unique writable `$WORK` directory.
Adapt shell syntax on Windows; the CLI JSON contract is the same.

```sh
android-project sync --project "$ROOT" --dependencies none \
  --timeout 600 --result-file "$WORK/model.json" --json \
  > "$WORK/sync.json" 2> "$WORK/sync.log"
```

Require exit 0, `schemaVersion: 1`, `status: ready`. Inspect `modules`,
`buildRoots`, `targets`, `selectionIssues`, `gradleJvmSelection` and `gradleJvm`.
The last two distinguish requested policy from the runtime actually used.

Select `(buildRoot, modulePath, variant)` from returned targets; module paths are
local to a build. Preserve the entry root separately from the owning build root
and `moduleRoot`. For source ownership inspect the returned `sourceRoots` and
`components`; overlapping candidates require clarification, not a module-name or
variant-name guess. Do not change another module's user selection to match a
consumer variant. Metadata is not proof that compiled artifacts are fresh.

## Resolve dependencies only when needed

For a one-off graph, write a selection JSON array using exact returned values:

```json
[{"buildRoot":"/absolute/owning-build","modulePath":":app","variant":"debug"}]
```

```sh
android-project sync --project "$ROOT" --dependencies requested \
  --selection-file "$WORK/targets.json" --result-file "$WORK/model.json" --json \
  > "$WORK/graphs.json" 2> "$WORK/graphs.log"
```

For additional requests against the same fresh metadata, extract **only**
`model.json`'s `dependencyContext` to a private temporary `context.json`, then:

```sh
android-project dependencies --project "$ROOT" \
  --metadata-file "$WORK/context.json" --selection-file "$WORK/targets.json" \
  --json > "$WORK/dependencies.json" 2> "$WORK/dependencies.log"
```

Bind returned graphs to the accepted metadata ID and exact target. Treat
`stale-baseline`/`stale-model` as a reason to perform a new full sync, then rebuild
the selections/context. Do not retry an obsolete context unchanged or merge a
response into a newer metadata generation. Keep contexts request/session-local;
remove temporary context and selection files after use.

Check each graph's `status`, `edges` and `issues`: `not-requested`, `unsupported`,
`partial` and `failed` are not a ready empty graph. Coverage is main compile/runtime
**project references**, including direct/transitive edges, not a complete external
library graph. KMP AGP V2 graphs may be unsupported. Use `--dependencies selected`
only when all-module graph resolution is actually wanted.

## Execute a requested task

```sh
android-project build --project "$ROOT" --task :app:assembleDebug \
  --timeout 600 --json > "$WORK/build.json" 2> "$WORK/build.log"
```

Use the exact task requested or established by the project's build documentation;
metadata does not enumerate runnable tasks. Repeat `--gradle-arg=ARG` to pass
Gradle options. `--init-script FILE` executes the supplied script and needs the
same trust as project code. Deployment, publishing, destructive cleanup and
other side-effecting tasks require user scope. `build` success means that task
completed; it does not independently establish test/render success.

The Tooling API requires a daemon. For an explicitly requested `--no-daemon`
build, use the project wrapper directly rather than inventing a CLI option.
Cancel only the owned operation/process, not shared Gradle/Kotlin daemons.

## JVM policy and completion

Core 0.1.7 respects supported Daemon JVM criteria first, explicit invocation
home/`-Dorg.gradle.java.home`, user/project `org.gradle.java.home`, linked IDE
Gradle JVM configuration, then `JAVA_HOME`/client runtime. The selected
`#GRADLE_LOCAL_JAVA_HOME` macro reads `.gradle/config.properties`. Unknown named
IDE SDKs fail explicitly; ask for a JDK path rather than guessing one. This CLI
has **no `--gradle-java-home` option**; build can pass
`--gradle-arg=-Dorg.gradle.java.home=/absolute/jdk`. Criteria still takes precedence.
Preserve project policy; do not rewrite JVM targets or download JDKs as a repair
without authorization. Gradle may provision according to existing project policy.

Exit 0 means success; exit 2 supplies `error.kind`/`error.message`. On failure,
report the exact target/command, error and relevant stderr, without exposing
credentials. Finish with requested versus actual JVM (where reported), accepted
metadata/graph status, or task outcome. Point to artifacts/logs and state coverage
gaps. Route Preview rendering to the separate `compose-preview` skill/CLI.
