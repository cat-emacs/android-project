;;; android-project-tests.el --- Tests for Android project metadata -*- lexical-binding: t; -*-

;;; Commentary:

;; Tests for shared Android project metadata and lifecycle.

;;; Code:

(require 'ert)
(require 'android-project)

(defun android-project-tests--target (module variant &rest properties)
  "Return target metadata for MODULE, VARIANT, and PROPERTIES."
  (let* ((root (format "/tmp/project/%s" module))
         (target
          (list :module-id (cons "/tmp/project" (concat ":" module))
                :build-root "/tmp/project"
                :module-path (concat ":" module)
                :module-name module
                :module-root root
                :plugin-id "com.android.application"
                :project-type "application"
                :variant variant
                :namespace (format "com.example.%s" module)
                :debuggable-p (equal variant "debug")
                :components nil
                :application-id (format "com.example.%s" module)
                :test-application-id ""
                :source-roots '("src/main/kotlin")
                :preview-task (format "assemble%s" (capitalize variant))
                :build-type variant
                :product-flavors nil
                :preferred-build-type-p nil
                :preferred-product-flavors nil)))
    (while properties
      (setq target (plist-put target (pop properties) (pop properties))))
    target))

(ert-deftest android-project-parse-gradle-flavors ()
  "Gradle flavor output is parsed between marker lines."
  (should
   (equal
    (android-parse-gradle-flavors
     "ignored\n===FLAVORS_START===\n:app|/tmp/project/app|/tmp/project|demoDebug|com.example|src/main/kotlin;src/demo/kotlin;src/androidTest/kotlin|testDemoDebugUnitTest|debug|demo|true|demo|com.android.application|com.example.test|com.example.namespace|bWFpbg==:ZGVtb0RlYnVn:c3JjL21haW4va290bGluO3NyYy9kZW1vL2tvdGxpbg==,YW5kcm9pZFRlc3Q=:ZGVtb0RlYnVnQW5kcm9pZFRlc3Q=:c3JjL2FuZHJvaWRUZXN0L2tvdGxpbg==|application|true\n===FLAVORS_END===\nignored\n")
    '((:module-id ("/tmp/project" . ":app")
       :build-root "/tmp/project"
       :module-path ":app"
       :module-name "app"
       :module-root "/tmp/project/app"
       :plugin-id "com.android.application"
       :project-type "application"
       :variant "demoDebug"
       :namespace "com.example.namespace"
       :debuggable-p t
       :application-id "com.example"
       :test-application-id "com.example.test"
       :components ((:kind "main" :name "demoDebug"
                     :source-roots ("src/main/kotlin" "src/demo/kotlin"))
                    (:kind "androidTest" :name "demoDebugAndroidTest"
                     :source-roots ("src/androidTest/kotlin")))
       :source-roots ("src/main/kotlin" "src/demo/kotlin"
                      "src/androidTest/kotlin")
       :preview-task "testDemoDebugUnitTest"
       :build-type "debug"
       :product-flavors ("demo")
       :preferred-build-type-p t
       :preferred-product-flavors ("demo"))))))

(ert-deftest android-project-flavor-helpers-use-cache ()
  "Flavor helper functions read module, variant and applicationId from cache."
  (cl-letf (((symbol-function 'android-root)
             (lambda () default-directory)))
    (let ((android-project--flavor-cache '(("app" "debug" "com.example")
                                    ("app" "release" "com.example.release")))
          (android-project--flavor-cache-root
           (file-name-as-directory (expand-file-name default-directory)))
          (android-project--flavor-cache-fingerprint nil))
      (should (equal (android-project--flavor-modules) '("app")))
      (should (equal (android-project--flavor-variants "app") '("debug" "release")))
      (should (equal (android-project--flavor-appid "app" "release")
                     "com.example.release")))))

(ert-deftest android-project-public-target-api-mirrors-selected-variants ()
  "Public targets expose one selected variant per Android module."
  (let* ((root "/tmp/project/")
         (app-debug (android-project-tests--target "app" "debug"))
         (app-release (android-project-tests--target "app" "release"))
         (feature (android-project-tests--target "feature" "release"))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--selection-loaded-roots nil)
         (android-project--selected-modules nil)
         (android-project--selected-variants nil))
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional _refresh)
                 (list app-release app-debug feature))))
      (let ((targets (android-project-targets root)))
        (should (= (length targets) 2))
        (should (equal (mapcar (lambda (entry)
                                (cons (plist-get entry :module-name)
                                      (plist-get entry :variant)))
                              targets)
                       '(("app" . "debug") ("feature" . "release"))))
        (should (seq-every-p (lambda (entry)
                               (plist-get entry :selected-p))
                             targets)))
      (android-project--remember-target root (plist-get app-debug :module-id) "release")
      (should (equal (plist-get
                      (android-target-for-source-file
                       "/tmp/project/app/src/main/kotlin/Foo.kt" root)
                      :variant)
                     "release"))
      (should-not (plist-get (android-project-target "app" "debug" root)
                             :selected-p))
      (should (plist-get (android-project-target "app" nil root)
                         :selected-p)))))

(ert-deftest android-project-current-target-follows-file-module ()
  "Current target resolves a file's module before its selected variant."
  (let* ((root "/tmp/project/")
         (app (android-project-tests--target "app" "debug"
                                         :application-id "com.example.app"))
         (feature (android-project-tests--target
                   "feature" "release" :application-id "com.example.feature"))
         (android-project--selected-modules
          (list (cons root (plist-get app :module-id))))
         (android-project--selected-variants nil))
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional _refresh) (list app feature))))
      (should (equal
               (plist-get
                (android-current-target
                 nil "/tmp/project/feature/src/main/kotlin/Foo.kt" root)
                :module-name)
               "feature"))
      (should (equal
               (android-current-application-id
                nil "/tmp/project/feature/src/main/kotlin/Foo.kt" root)
               "com.example.feature")))))

(ert-deftest android-project-current-application-id-requires-current-module ()
  "Application ID resolution does not guess across multiple modules."
  (let ((android-project--selected-modules nil)
        (android-project--selected-variants nil))
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional _refresh)
                 (list (android-project-tests--target "app" "debug"
                                                   :application-id "com.example")
                       (android-project-tests--target "feature" "debug"
                                                   :application-id "com.example")))))
      (should-not (android-current-application-id nil nil "/tmp/project/")))))

(ert-deftest android-project-project-targets-forwards-refresh ()
  "Public project metadata forwards its root and refresh request."
  (let (seen-root seen-refresh)
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional refresh)
                 (setq seen-root default-directory seen-refresh refresh)
                 (list (android-project-tests--target "app" "debug")))))
      (should (android-project-targets "/tmp/project" t))
      (should (equal seen-root "/tmp/project/"))
      (should seen-refresh))))

(ert-deftest android-project-default-variant-matches-studio-ordering ()
  "Default variants follow Studio preferences and common flavor dimensions."
  (let ((release (android-project-tests--target "app" "release"))
        (debug (android-project-tests--target "app" "debug"))
        (demo (android-project-tests--target
               "app" "demoRelease" :build-type "release"
               :product-flavors '("demo")
               :preferred-product-flavors '("demo")))
        (production-debug (android-project-tests--target
                           "app" "productionDebug" :build-type "debug"
                           :product-flavors '("production")
                           :preferred-product-flavors '("demo")))
        (longer (android-project-tests--target
                 "app" "demoZetaRelease" :build-type "release"
                 :product-flavors '("demo" "zeta")))
        (shorter (android-project-tests--target
                  "app" "demoRelease" :build-type "release"
                  :product-flavors '("demo"))))
    (should (eq (android-project--default-module-target (list release debug)) debug))
    ;; With no dimension shared by every candidate, Studio skips flavors.
    (should (eq (android-project--default-module-target (list debug demo)) debug))
    (should (eq (android-project--default-module-target
                 (list production-debug demo))
                demo))
    ;; Studio compares only the number of flavor dimensions shared by all
    ;; candidates, so these tie and retain the first model entry.
    (should (eq (android-project--default-module-target (list longer shorter))
                longer))))

(ert-deftest android-project-project-target-rejects-ambiguous-module-path ()
  "String module paths do not cross composite-build identities."
  (let* ((first (android-project-tests--target "app" "debug"))
         (second (copy-tree first))
         (android-project--selected-modules nil)
         (android-project--selected-variants nil))
    (plist-put second :module-id '("/tmp/included" . ":app"))
    (plist-put second :build-root "/tmp/included")
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional _refresh) (list first second))))
      (should-not (android-project-target ":app" nil "/tmp/project/"))
      (should (equal
               (plist-get
                (android-project-target
                 '("/tmp/included" . ":app") nil "/tmp/project/")
                :build-root)
               "/tmp/included")))))

(ert-deftest android-project-public-target-api-ignores-non-project-files ()
  "Public target APIs return nil outside an Android project."
  (let ((android-project--selected-modules nil)
        (android-project--selected-variants nil))
    (cl-letf (((symbol-function 'android-root)
               (lambda () (error "No Android project"))))
      (should-not (android-project-targets))
      (should-not (android-target-for-source-file "/missing/Foo.kt"))
      (should-not (android-current-target nil "/missing/Foo.kt"))
      (should-not (android-current-application-id nil "/missing/Foo.kt")))))

(ert-deftest android-project-flavor-cache-rejects-old-schema ()
  "Flavor cache entries without the current schema are ignored."
  (let* ((root "/tmp/project/")
         (android-project-cache-directory (make-temp-file "android-project-cache" t))
         (file (android-project--flavor-cache-file root)))
    (make-directory (file-name-directory file) t)
    (with-temp-file file
      (prin1 (list :root root :time (current-time) :data '(("app" "debug" "com.example")))
             (current-buffer)))
    (should-not (android-project--flavor-cache-load root))))

(ert-deftest android-project-flavor-cache-rejects-stale-preview-task-schema ()
  "Flavor caches created before JVM preview discovery are ignored."
  (let* ((root "/tmp/project/")
         (android-project-cache-directory (make-temp-file "android-project-cache" t))
         (file (android-project--flavor-cache-file root))
         (entry (list :module-path ":composeApp"
                      :module-root "/tmp/project/composeApp"
                      :variant "androidMain"
                      :source-roots '("src/commonMain/kotlin")
                      :preview-task "assembleAndroidMain")))
    (make-directory (file-name-directory file) t)
    (with-temp-file file
      (prin1 (list :version 2 :root root :time (current-time) :data (list entry))
             (current-buffer)))
    (should-not (android-project--flavor-cache-load root))))

(ert-deftest android-project-target-for-source-file-prefers-kmp-source-set ()
  "Target lookup maps KMP source-set files to their owning Gradle module."
  (let* ((project-root "/tmp/project/")
         (entry (list :module-path ":composeApp"
                      :module-name "composeApp"
                      :module-root "/tmp/project/composeApp"
                      :variant "debug"
                      :application-id "com.example"
                      :source-roots '("src/commonMain/kotlin"
                                      "src/androidMain/kotlin"))))
    (should
     (equal
      (android-project--target-for-source-file
       "/tmp/project/composeApp/src/commonMain/kotlin/example/Foo.kt"
       project-root
       (list entry))
      entry))))

(ert-deftest android-project-model-script-uses-plugin-models-for-kmp ()
  "Flavor script should use AGP/KGP models instead of hard-coded KMP paths."
  (with-temp-buffer
    (insert-file-contents android-project-model-script)
    (let ((script (buffer-string)))
      (should (string-match-p "androidComponents" script))
      (should (string-match-p "com.android.test" script))
      (should (string-match-p "variant.androidTest.applicationId" script))
      (should (string-match-p "component.*sources" script))
      (should (string-match-p "directories.*static" script))
      (should (string-match-p "extensions.findByName(\"kotlin\")" script))
      (should (string-match-p "compilations" script))
      (should (string-match-p "platformType == \"jvm\"" script))
      (should (string-match-p "desktopTest" script))
      (should-not (string-match-p "src/commonMain/kotlin" script))
      (should-not (string-match-p "src/androidMain/kotlin" script)))))

(ert-deftest android-project-target-for-source-file-identifies-component ()
  "Source lookup reports the selected variant's owning component."
  (let* ((root "/tmp/project/")
         (debug
          (android-project-tests--target
           "app" "debug"
           :source-roots '("src/main/kotlin" "src/androidTest/kotlin")
           :components '((:kind "main" :name "debug"
                           :source-roots ("src/main/kotlin"))
                          (:kind "androidTest" :name "debugAndroidTest"
                           :source-roots ("src/androidTest/kotlin")))))
         (release
          (android-project-tests--target
           "app" "release"
           :source-roots '("src/main/kotlin" "src/release/kotlin")
           :components '((:kind "main" :name "release"
                           :source-roots ("src/main/kotlin"
                                          "src/release/kotlin")))))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--selection-loaded-roots nil)
         (android-project--selected-modules nil)
         (android-project--selected-variants nil))
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional _refresh) (list release debug))))
      (let* ((target
              (android-target-for-source-file
               "/tmp/project/app/src/androidTest/kotlin/FooTest.kt" root))
             (component (plist-get target :component)))
        (should (equal (plist-get target :variant) "debug"))
        (should (equal (plist-get component :kind) "androidTest"))
        (should (equal (plist-get component :name) "debugAndroidTest"))))))

(ert-deftest android-project-target-for-source-file-omits-inactive-component ()
  "Source lookup never attaches a component from a non-selected variant."
  (let* ((root "/tmp/project/")
         (debug (android-project-tests--target
                 "app" "debug" :source-roots '("src/main/kotlin")
                 :components '((:kind "main" :name "debug"
                                 :source-roots ("src/main/kotlin")))))
         (release (android-project-tests--target
                   "app" "release" :source-roots '("src/release/kotlin")
                   :components '((:kind "main" :name "release"
                                   :source-roots ("src/release/kotlin")))))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--selection-loaded-roots nil)
         (android-project--selected-modules nil)
         (android-project--selected-variants nil))
    (cl-letf (((symbol-function 'android-project--get-flavors)
               (lambda (&optional _refresh) (list release debug))))
      (let ((target
             (android-target-for-source-file
              "/tmp/project/app/src/release/kotlin/Foo.kt" root)))
        (should (equal (plist-get target :variant) "debug"))
        (should-not (plist-get target :component))))))

(ert-deftest android-project-hot-model-read-does-not-stat-project-inputs ()
  "A normal read of a fresh in-memory model performs no filesystem scan."
  (let* ((root "/tmp/project/")
         (entry (android-project-tests--target "app" "debug"))
         (android-project--flavor-cache (list entry))
         (android-project--flavor-cache-root root)
         (android-project--flavor-cache-stale-p nil))
    (let ((default-directory root))
      (cl-letf (((symbol-function 'android-root) (lambda () root))
                ((symbol-function 'android-project--project-model-fingerprint)
                 (lambda (&rest _args)
                   (ert-fail "hot read fingerprinted project inputs")))
                ((symbol-function 'android-project--selection-load) #'ignore))
        (should (equal (android-project--get-flavors) (list entry)))))))

(ert-deftest android-project-stale-disk-model-remains-available ()
  "A stale schema-valid disk model is returned while refresh starts."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--flavor-cache nil)
         (android-project--flavor-cache-root nil)
         (android-project--flavor-cache-fingerprint nil)
         (android-project--flavor-cache-stale-p t)
         (entry (android-project-tests--target "app" "debug"))
         (settings (expand-file-name "settings.gradle.kts" root))
         refreshed)
    (plist-put entry :build-root (directory-file-name root))
    (plist-put entry :module-root (expand-file-name "app" root))
    (make-directory (plist-get entry :module-root) t)
    (with-temp-file settings (insert "include(\":app\")\n"))
    (android-project--flavor-cache-save root (list entry))
    (setq android-project--flavor-cache nil
          android-project--flavor-cache-root nil
          android-project--flavor-cache-fingerprint nil
          android-project--flavor-cache-stale-p t)
    (with-temp-file settings (insert "include(\":app\", \":feature\")\n"))
    (let ((default-directory root))
      (cl-letf (((symbol-function 'android-root) (lambda () root))
                ((symbol-function 'android-refresh-project-model)
                 (lambda (&optional _root _callback)
                   (setq refreshed t))))
        (should (equal (android-project--get-flavors) (list entry)))
        (should refreshed)
        (should android-project--flavor-cache-stale-p)))))

(ert-deftest android-project-cache-read-disables-read-time-evaluation ()
  "Persisted state cannot execute read-time Lisp forms."
  (let* ((root "/tmp/project/")
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project-tests--read-evaluated nil)
         (selection (android-project--selection-file root)))
    (make-directory (file-name-directory selection) t)
    (with-temp-file selection
      (insert "#.(setq android-project-tests--read-evaluated t)"))
    (let ((android-project--selection-loaded-roots nil))
      (android-project--selection-load root))
    (should-not android-project-tests--read-evaluated)))

(ert-deftest android-project-selection-reports-loading-model ()
  "Interactive target selection does not open an empty completion UI."
  (cl-letf (((symbol-function 'android-project-targets) #'ignore))
    (should-error (android-project--select-module-target)
                  :type 'user-error)))

(ert-deftest android-project-project-model-fingerprint-detects-input-changes ()
  "Project model fingerprints cover settings, module builds, and catalogs."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (module-root (expand-file-name "app" root))
         (catalog (expand-file-name "gradle/libs.versions.toml" root))
         (entry (android-project-tests--target "app" "debug")))
    (make-directory module-root t)
    (make-directory (file-name-directory catalog) t)
    (plist-put entry :build-root (directory-file-name root))
    (plist-put entry :module-root module-root)
    (with-temp-file (expand-file-name "settings.gradle.kts" root)
      (insert "include(\":app\")\n"))
    (with-temp-file (expand-file-name "build.gradle.kts" module-root)
      (insert "plugins {}\n"))
    (with-temp-file catalog (insert "[versions]\nagp = \"9.3.0\"\n"))
    (let ((before (android-project--project-model-fingerprint root (list entry))))
      (with-temp-file catalog (insert "[versions]\nagp = \"9.3.1\"\n"))
      (should-not
       (equal before
              (android-project--project-model-fingerprint root (list entry)))))))

(ert-deftest android-project-flavor-cache-rejects-changed-project-inputs ()
  "A disk model is invalidated when a tracked Gradle input changes."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--flavor-cache-fingerprint nil)
         (entry (android-project-tests--target "app" "debug"))
         (settings (expand-file-name "settings.gradle.kts" root)))
    (plist-put entry :build-root (directory-file-name root))
    (plist-put entry :module-root (expand-file-name "app" root))
    (make-directory (plist-get entry :module-root) t)
    (with-temp-file settings (insert "include(\":app\")\n"))
    (android-project--flavor-cache-save root (list entry))
    (should (plist-get (android-project--flavor-cache-load root) :fresh-p))
    (with-temp-file settings (insert "include(\":app\", \":feature\")\n"))
    (let ((cache (android-project--flavor-cache-load root)))
      (should cache)
      (should-not (plist-get cache :fresh-p)))))

(ert-deftest android-project-target-selection-persists-by-module-id ()
  "Selected modules and variants survive a fresh in-memory session."
  (let* ((root "/tmp/project/")
         (module-id '("/tmp/project" . ":app"))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--selected-modules nil)
         (android-project--selected-variants nil)
         (android-project--selection-loaded-roots nil))
    (android-project--remember-target root module-id "release")
    (setq android-project--selected-modules nil
          android-project--selected-variants nil
          android-project--selection-loaded-roots nil)
    (android-project--selection-load root)
    (should (equal (cdr (assoc root android-project--selected-modules)) module-id))
    (should (equal (cdr (assoc module-id
                               (cdr (assoc root android-project--selected-variants))))
                   "release"))))

(ert-deftest android-project-project-application-ids-match-studio-model ()
  "Project application IDs include all main and instrumentation APK IDs."
  (let ((targets
         (list
          (android-project-tests--target
           "app" "debug" :test-application-id "com.example.app.test")
          (android-project-tests--target
           "app" "release" :application-id "com.example.app.release"
           :test-application-id "com.example.app.test")
          (android-project-tests--target
           "feature" "debug" :plugin-id "com.android.dynamic-feature"
           :test-application-id "com.example.feature.test")
          (android-project-tests--target
           "library" "debug" :plugin-id "com.android.library"
           :application-id "com.example.library.namespace"
           :test-application-id "com.example.library.test")
          (android-project-tests--target
           "instrumentation" "debug" :plugin-id "com.android.test"
           :application-id "com.example.standalone.test"))))
    (cl-letf (((symbol-function 'android-project-variants)
               (lambda (&optional _root _refresh) targets)))
      (should (equal (android-project-application-ids "/tmp/project/")
                     '("com.example.app" "com.example.app.test"
                       "com.example.app.release" "com.example.feature"
                       "com.example.feature.test" "com.example.library.test"
                       "com.example.standalone.test"))))))

(ert-deftest android-project-project-model-inputs-include-build-logic ()
  "Project model fingerprints include buildSrc and convention logic."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (build-src (expand-file-name "buildSrc/src/main/kotlin/Plugin.kt" root))
         (build-logic
          (expand-file-name "build-logic/src/main/kotlin/ConventionPlugin.kt" root))
         (catalog (expand-file-name "gradle/libs.versions.toml" root))
         (settings (expand-file-name "settings.gradle.kts" root)))
    (make-directory (file-name-directory build-src) t)
    (make-directory (file-name-directory build-logic) t)
    (make-directory (file-name-directory catalog) t)
    (with-temp-file build-src (insert "class Plugin\n"))
    (with-temp-file build-logic (insert "class ConventionPlugin\n"))
    (with-temp-file catalog (insert "[versions]\n"))
    (with-temp-file settings (insert "includeBuild(\"build-logic\")\n"))
    (let ((inputs (android-project--project-model-input-files root))
          (logic-root (file-name-as-directory
                       (expand-file-name "build-logic" root))))
      (should (member (expand-file-name "buildSrc" root) inputs))
      (should (member (expand-file-name "gradle" root) inputs))
      (should (member logic-root inputs))
      (should (android-project--file-under-input-p build-src
                                           (expand-file-name "buildSrc" root)))
      (should (android-project--file-under-input-p build-logic logic-root))
      (let ((before (android-project--project-model-fingerprint root nil inputs)))
        (with-temp-file build-logic (insert "class ChangedConventionPlugin\n"))
        (should-not (equal before
                           (android-project--project-model-fingerprint
                            root nil inputs)))))))

(ert-deftest android-project-project-model-status-tracks-lifecycle ()
  "Project model status preserves diagnostics and stale model availability."
  (let* ((root "/tmp/project/")
         (android-project--project-model-states (make-hash-table :test #'equal))
         (android-project--flavor-cache (list (android-project-tests--target "app" "debug")))
         (android-project--flavor-cache-root root)
         events
         (android-project-model-state-changed-hook
          (list (lambda (_root status) (push status events)))))
    (android-project--set-project-model-state root 'syncing)
    (android-project--set-project-model-state root 'failed "Gradle failed")
    (let ((status (android-project-model-status root)))
      (should (eq (plist-get status :state) 'failed))
      (should (equal (plist-get status :diagnostic) "Gradle failed"))
      (should (plist-get status :last-model-available-p)))
    (android-project--set-project-model-state root 'ready)
    (should (eq (plist-get (android-project-model-status root) :state) 'ready))
    (should (= (length events) 3))))

(ert-deftest android-project-project-refresh-is-asynchronous-and-shared ()
  "Concurrent model refresh requests share a process and notify all callers."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--project-refresh-processes (make-hash-table :test #'equal))
         (android-project--project-refresh-callbacks (make-hash-table :test #'equal))
         (android-project--flavor-cache nil)
         (android-project--flavor-cache-root nil)
         callbacks)
    (cl-letf (((symbol-function 'android-project--gradle-model-command)
               (lambda (_root)
                 (list shell-file-name shell-command-switch
                       "printf '===FLAVORS_START===\\n:app|/tmp/app|/tmp|debug|com.example|src/main/kotlin|assembleDebug|debug||false||com.android.application\\n===FLAVORS_END===\\n'"))))
      (let ((first (android-refresh-project-model
                    root (lambda (data error-data)
                           (push (list data error-data) callbacks))))
            second)
        (setq second
              (android-refresh-project-model
               root (lambda (data error-data)
                      (push (list data error-data) callbacks))))
        (should (eq first second))
        (while (and (< (length callbacks) 2)
                    (or (process-live-p first)
                        (gethash root android-project--project-refresh-processes)))
          (accept-process-output nil 0.1))
        (should (= (length callbacks) 2))
        (should (seq-every-p (lambda (result)
                               (and (car result) (null (cadr result))))
                             callbacks))
        (should-not (gethash root android-project--project-refresh-processes))))))

(ert-deftest android-project-project-refresh-restarts-after-input-change ()
  "A refresh whose inputs change is discarded and restarted."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (settings (expand-file-name "settings.gradle.kts" root))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--project-refresh-processes (make-hash-table :test #'equal))
         (android-project--project-refresh-callbacks (make-hash-table :test #'equal))
         launches done result failure)
    (with-temp-file settings (insert "include(\":app\")\n"))
    (cl-letf (((symbol-function 'android-project--gradle-model-command)
               (lambda (_root)
                 (setq launches (1+ (or launches 0)))
                 (list shell-file-name shell-command-switch
                       "sleep 0.1; printf '===FLAVORS_START===\\n:app|/tmp/app|/tmp|debug|com.example|src/main/kotlin|assembleDebug|debug||false||com.android.application\\n===FLAVORS_END===\\n'"))))
      (android-refresh-project-model
       root (lambda (data error-data)
              (setq result data failure error-data done t)))
      (with-temp-file settings (insert "include(\":app\", \":feature\")\n"))
      (while (and (not done) (< launches 3))
        (accept-process-output nil 0.2))
      (should done)
      (should (= launches 2))
      (should result)
      (should-not failure))))

(ert-deftest android-project-project-refresh-rechecks-after-parsing ()
  "Inputs changed during parsing prevent stale model publication."
  (let* ((root (file-name-as-directory (make-temp-file "android-project" t)))
         (settings (expand-file-name "settings.gradle.kts" root))
         (android-project-cache-directory (make-temp-file "android-cache" t))
         (android-project--project-refresh-processes (make-hash-table :test #'equal))
         (android-project--project-refresh-callbacks (make-hash-table :test #'equal))
         (android-project--project-refresh-invalidated (make-hash-table :test #'equal))
         (real-parser (symbol-function 'android-parse-gradle-flavors))
         parsed launches done result)
    (with-temp-file settings (insert "include(\":app\")\n"))
    (cl-letf (((symbol-function 'android-project--gradle-model-command)
               (lambda (_root)
                 (setq launches (1+ (or launches 0)))
                 (list shell-file-name shell-command-switch
                       "printf '===FLAVORS_START===\\n:app|/tmp/app|/tmp|debug|com.example|src/main/kotlin|assembleDebug|debug||false||com.android.application\\n===FLAVORS_END===\\n'")))
              ((symbol-function 'android-parse-gradle-flavors)
               (lambda (output)
                 (unless parsed
                   (setq parsed t)
                   (with-temp-file settings
                     (insert "include(\":app\", \":feature\")\n")))
                 (funcall real-parser output))))
      (android-refresh-project-model
       root (lambda (data _error-data) (setq result data done t)))
      (while (and (not done) (< launches 3))
        (accept-process-output nil 0.2))
      (should done)
      (should (= launches 2))
      (should result))))

(provide 'android-project-tests)

;;; android-project-tests.el ends here
