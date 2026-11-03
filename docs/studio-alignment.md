# Android Studio source alignment

## Update: 2026-11-03

The checkout was synced again with the same non-forcing command. All worktrees
were clean before and after. The Darwin Bazel local manifest remains unchanged.
`repo` reported success, with a launcher update notice and a Python semaphore
cleanup warning; this was not a source/build validation failure.

Eleven projects changed. `git rev-list old..new` totals 1130 commits, including
789 in `build/bazel`, 204 in IDEA and 116 in tools/base. These are reachable
commit counts, not a count of changes relevant to this consumer.

| Project | Before | After |
| --- | --- | --- |
| tools/adt/idea | 7c43fb793836 | 6914f2424bea |
| tools/base | 792a24cd3fe0 | e9439e2a8332 |
| build/bazel | 571f0ede10be | 96989d64c15f |
| prebuilts/r8 | fa953c3d3a6a | e8244b9dc752 |
| prebuilts/studio/layoutlib | 9e5a99cba491 | 80b7cdde589a |
| prebuilts/tools | a663476b659b | 6dd0ae7faaae |
| tools/analytics-library | e6e4664f2460 | b40d506c3305 |
| tools/apksig | f3a2e3815114 | 873eb63c1893 |
| tools/buildSrc | 79a2fa95fd34 | 1a3abcdbf8fe |
| tools/external/gradle | 340e2c3ff16c | 504e03168087 |
| tools/studio/google/cloud/testing | 4e6f485816ed | c9ba4f2ac74a |

### Consumer alignment decisions

- [40f7cc432cc](https://android.googlesource.com/platform/tools/adt/idea/+/40f7cc432ccd042c946c4f1d472ef61ee577920f)
  forwards recently saved IDE paths to the Gradle connection before tasks.
  Our CLI has conservative input receipts but no cross-process saved-path
  protocol. Receipts prevent accepting stale results; they do not replace daemon
  VFS notifications. A future implementation must retain pending paths on
  failure, handle old Gradle providers and cover sync/build and wrapper paths.
- [bf0a1770699](https://android.googlesource.com/platform/tools/adt/idea/+/bf0a1770699),
  `319fd7e6176` and `42f6a65f17c` synchronize shared proxy views, exception
  collection and interned models in parallel sync. Our model requests are
  sequential and copy DTOs; adding parallel requests without those protections
  is not a safe optimization.
- [488997af9ec](https://android.googlesource.com/platform/tools/adt/idea/+/488997af9ec)
  merges source sets by artifact name and normalizes paths. Our supplement keeps
  kind/name/root ownership separate; do not replace it with main-source guesses.
- [03b5de26798](https://android.googlesource.com/platform/tools/adt/idea/+/03b5de26798fb63f0fd0d0bc84edc63985714be0)
  normalizes null/fake flavor dimensions. Our consumer does not propagate flavor
  requirements or dereference nullable `ProductFlavor.dimension`. Exact
  VariantAttr mapping remains preferred; an unprovable match remains partial.
- [baaa4bee852](https://android.googlesource.com/platform/tools/adt/idea/+/baaa4bee852d87555f4089660816d012c16c2ae8)
  clears explicit Preview dimensions on device selection. The frontend already
  overrides widthDp/heightDp with undefined dimensions when selecting a device;
  it does not rewrite source annotations.
- `667fb268aa3`, `6c2436fb8e7`, `68300ba144b` and `2c49d27833f` harden classloader
  and warm-renderer invalidation/timeouts. Cold owned renderer processes remain
  the supported policy here; no long-lived JVM or upstream cache backport was
  introduced.

### Published renderer alpha17 probe

Google Maven now lists `0.0.1-alpha17` as latest/release. The JAR contains
`StaticPreviewCallbackRunner`, component-tree classes and warm renderer APIs.
This supersedes the registry observation in the historical check below, but not
its requirement to verify the released binary.

The local launcher initially failed to compile against alpha17:
`PreviewScreenshotResult` now has a seven-argument constructor rather than the
four-argument alpha16 constructor. Both require a non-null image path. The
launcher now selects only those two known signatures and emits an empty path for
a failed card. A compiled ABI probe exercised alpha17, and regression tests cover
both constructor forms. This does not enable component-tree or warm options.

Evidence and remaining gates:

- Android resource/MultiPreview/PreviewParameter fixture: 21 results with alpha17;
  alpha16 regression also produced 21 results. The processes exited normally.
- A mixed batch containing an invalid width and healthy parameterized previews
  returned a card error while retaining healthy images. A missing-method probe
  unexpectedly generated an image without an error: do not treat that as a
  successful diagnostic/partial-failure gate.
- Current IVC KMP fixture was rejected before rendering because its Android host
  test does not enable `isIncludeAndroidResources`. No user build files were
  changed to bypass this. Alpha17 KMP resource support is not newly verified.
- The initial probe kept alpha16 as default; the follow-up below supersedes
  that decision after independent KMP and cross-card state validation.
- No Logcat source changes in the new revision range. AS itself was not built.

### Alpha17 adoption follow-up

The missing-class probe was minimized to healthy -> missing -> healthy cards.
It failed deterministically after a healthy card, but passed with the missing
class first. The same issue occurs on alpha16. Changing the display name did not
fix it; calling alpha17's public `Renderer.refreshClassPath` before each top-level
screenshot restored the error and preserved healthy siblings. The same reset
prevented an intentionally failing composition from leaving its exception in the
next card's logger. This is project-classloader/dispatcher isolation, not a
long-lived warm renderer or a copied internal classloader patch.

The follow-up switched CLI, Gradle and Emacs defaults to alpha17 while retaining
layoutlib 16.1.0-jdk17 and the Java 17 minimum. Tests:

- Android fixture: 21 healthy results, including MultiPreview and parameter
  providers, after per-card project refresh.
- Independent AGP 9.3/Kotlin 2.3 KMP fixture (not IVC): Android string resource
  rendered; an intentionally failing card did not poison four healthy siblings.
- `LaunchedEffect` changed BEFORE_EFFECT to AFTER_EFFECT; its PNG matched the
  static AFTER_EFFECT reference pixel-for-pixel and differed from BEFORE_EFFECT.
- Missing-class card after a healthy card reported an error, then healthy
  parameterized siblings still rendered. Owned processes exited normally.
- Layoutlib 17.0.3 failed on Java 17 with class version 65 vs 61; it is not part
  of the upgrade. Component-tree extraction, inflate-only and warm reuse remain
  disabled. The adapter/receipt identity changes invalidate older rendered data.

### Layoutlib / Java 21 follow-up

The next upgrade uses published layoutlib **17.0.4** with alpha17 on **Java 21**.
Preview CLI bytecode, adapter compilation and CI/release smoke targets are now
Java 21; the independent Project CLI/core remain Java 17-compatible.

The Android fixture cannot simply build on 21: its Java target is 17 while its
Kotlin target follows the running JVM. Rather than rewrite user targets, Preview
accepts `--gradle-java-home` (Emacs `compose-preview-gradle-java-home`). The core
Tooling bridge respects invocation `JAVA_HOME`, just like the wrapper subprocess.
The model task receives the CLI renderer JDK separately as a declared input. Both
JDK identities are covered by receipts, including their release/executable stamps.
This requires unreleased core **0.1.7**, not a mutation of published 0.1.6.

Android: 21 healthy cards, including the full Cat frontend chain. Independent
AGP 9.3 KMP: one intentional failure and four healthy cards, including resources
and settled callbacks. A separate `--no-daemon` callback render also exited
normally. No IVC build files, system JDK defaults or Homebrew installs changed.
Warm/tree/inflate features remain disabled; the earlier Java 17 adoption evidence
is historical, not the current layoutlib pin.

### Gradle JVM policy follow-up

The shared core now distinguishes requested policy from actual build runtime.
It preserves supported Daemon JVM criteria (Gradle 8.8+), then explicit invocation
home, user/project `org.gradle.java.home`, linked IDE JVM settings, and finally
`JAVA_HOME`/client runtime. `#GRADLE_LOCAL_JAVA_HOME` reads local config only when
selected by the matching linked root; arbitrary IDE SDK names fail rather than
falling back to a guessed installation. XML parsing disables external entities.
No IDE SDK-table import, JDK provisioning or project configuration migration is
implemented. Gradle itself may provision under existing project settings.

Ignored IDE/local config, user properties, selected JDK and criteria participate
in metadata/dependency and Preview freshness. Project actions and Preview model
tasks report actual build `java.home`/version; doctor reports only policy without
starting Gradle. Preview keeps its renderer JDK independent.

Cross-JDK verification used a Java 21 client: project properties and the selected
AS local macro both ran builds on 17. With criteria=17 and explicit home=21,
Gradle selected an existing local 17.0.17 JDK. Preview callback rendering passed
with criteria, and with the local macro through Tooling API and `--no-daemon`.
This is not full parity with Studio's SDK management UI or downloading support.

## Historical check: 2026-10-26

Checked on 2026-10-26 against the local `studio-master-dev` checkout after a
successful `repo sync -c -j4 --no-auto-gc --retry-fetches=2`. This is a source
comparison, not an Android Studio build or a claim of released capability.

## Checkout

| Project | Before | After | New commits |
| --- | --- | --- | ---: |
| tools/adt/idea | 001c2a2cb31b | 7c43fb793836 | 40 |
| tools/base | ae69b3ef270c | 792a24cd3fe0 | 17 |
| prebuilts/tools | 5ba300fc3e67 | a663476b659b | 1 |
| tools/external/gradle | 236813ba661e | 340e2c3ff16c | 1 |
| tools/studio/google/cloud/testing | fe3a42013a94 | 4e6f485816ed | 1 |

The checkout was clean before and after sync. The existing local manifest
selecting `main` for Darwin Bazel was retained. No force checkout, reset or
shared-daemon cancellation was used.

## Project sync

### Version-aware dependency model selection

Studio commit [0ccd5c22bbb][dependency-selection] routes phased dependency requests
through `findVariantDependenciesV2Model`. `ModelFetchers.kt` chooses
`VariantDependenciesAdjacencyList` when
`ModelFeature.HAS_ADJACENCY_LIST_DEPENDENCY_GRAPH` is supported; `BasicModules.kt`
gates that feature at AGP `8.2.0-alpha03`. Older AGP uses `VariantDependencies`.
This is version-based selection, not a blanket catch-and-retry of model failures.

Our current consumer deliberately keeps `VariantDependencies` for both full sync
and dependency-only requests. We expose direct/transitive project references.
The adjacency model contains `from`/`to` edges, but no explicit root list.
AGP's [FullDependencyGraphBuilder][graph-builder] emits a self-edge for every
visited node; a self-edge does not establish directness. Inferring roots from
zero inbound edges also loses a dependency declared both directly and
transitively. Switching models without another authoritative direct-root source
would weaken our public contract.

Decision: retain GraphItem roots and their directness semantics. Before adopting
adjacency transport, verify direct-and-transitive duplicates, cycles, leaves,
compile/runtime separation, unresolved nodes, composite identities and exact
producer variants against the old graph. Do not call adjacency lists the removed
flat-list model.

### Flat-list removal and additional Kotlin models

Studio commit [c4c6db438d3][flat-removal] removes the unused flat-list request flag
and option. Our consumer does not request that model or option; no removal is
needed here.

Commit [92298dd972a][additional-models] supplies `IdeSyncExecutionReport` and
Kotlin/KAPT fallback models for Java modules in the phased provider, then clears
its within-sync model cache. Our typed Variant/KGP supplement is not a Kotlin IDE
importer. Do not add these models merely to imitate the provider: they do not
establish missing KMP AGP V2 dependency support. Existing `unsupported`/`partial`
states remain necessary.

## Preview and renderer

The new commits do not change `BuildSystemFilePreviewServices`,
`GradleBuildSystemFilePreviewServices`, the production Compose representation,
`GenerateComposePreviewRunfilesTask` or the standalone CLI entry point. Keep
Preview artifact preparation separate from Project metadata sync.

Studio's [9fb4e5675a7][preview-test] fixes a parameterized UI Check test by waiting
for the rendered-element flow before assertions. It is not a new parameter
provider or rendering capability.

Tools/base commit [26d2ab5f21d][dispatcher-fix] fixes repackaged coroutines
`FastServiceLoader` lookups of `AndroidDispatcherFactory` and
`TestMainDispatcherFactory`, including classes on `projectClassPath`. This matters
to the main-only static callback renderer path, not just to classpath bookkeeping.
Studio commit [630123516ff][resource-interrupt] adds bounded retries for interrupted
ZIP-backed resource reads and restores interrupt status. Both are upstream
implementation fixes, not patches to copy into the Emacs frontend or launcher.

Google Maven [renderer metadata][renderer-metadata] still advertises
`0.0.1-alpha16` as latest/release. Inspection of that installed JAR found no
`StaticPreviewCallbackRunner` class or FastServiceLoader factory string
replacements in `ClassLoaderConstantsKt`. Keep the current renderer pin and
capability limits. [Layoutlib metadata][layoutlib-metadata] advertises `17.0.3`,
but an independently newer layoutlib does not supply the renderer fix or prove
compatibility with alpha16, our adapter and Java 17. Test the complete combination
before changing pins.

Required upgrade checks remain Android/KMP resources, parameter previews,
per-card failures, bounded exit and callback/dispatcher isolation. Add coroutines
1.8.x and 1.9+ dependency/project-classpath cases when a released renderer contains
the dispatcher fix. Neither this source sync nor registry availability satisfies
those checks.

## Logcat

No files under Studio's `logcat/` changed in this revision range. No Logcat
behavior or adapter change is justified by this sync.

[dependency-selection]: https://android.googlesource.com/platform/tools/adt/idea/+/0ccd5c22bbb3650b7202400a849c93bbbb006c54
[graph-builder]: https://android.googlesource.com/platform/tools/base/+/792a24cd3fe0/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/ide/dependencies/FullDependencyGraphBuilder.kt
[flat-removal]: https://android.googlesource.com/platform/tools/adt/idea/+/c4c6db438d3
[additional-models]: https://android.googlesource.com/platform/tools/adt/idea/+/92298dd972a65649051a0d9c77d881515c4220c4
[preview-test]: https://android.googlesource.com/platform/tools/adt/idea/+/9fb4e5675a79cb1e11fc5ac97ec4d41504feaa92
[dispatcher-fix]: https://android.googlesource.com/platform/tools/base/+/26d2ab5f21d013e1a4943a9863f1d063e1bcf4a2
[resource-interrupt]: https://android.googlesource.com/platform/tools/adt/idea/+/630123516ff37252884da0ab330bf0c9d2f1050c
[renderer-metadata]: https://dl.google.com/dl/android/maven2/com/android/tools/compose/compose-preview-renderer/maven-metadata.xml
[layoutlib-metadata]: https://dl.google.com/dl/android/maven2/com/android/tools/layoutlib/layoutlib/maven-metadata.xml
