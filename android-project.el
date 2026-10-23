;;; android-project.el --- Android Gradle project model -*- lexical-binding: t; -*-

;; Copyright (C) 2009-2018 R.W van 't Veer
;; Version: 0.1.0
;; Package-Requires: ((emacs "28.1"))
;; URL: https://github.com/cat-emacs/android-project
;; Keywords: tools projects

;; This program is free software; you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation; either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.

;;; Commentary:

;; Shared asynchronous Android project metadata, source ownership, and target
;; selection.  Loading this library does not enable any minor mode.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'json)
(require 'filenotify nil t)

(defgroup android-project nil
  "Android Gradle project metadata."
  :group 'tools)

(defcustom android-project-cli-program "android-project"
  "Independent Project System CLI used for Gradle/AGP synchronization.
Project metadata and Tooling API belong to this package, not to Preview."
  :type 'string
  :group 'android-project)

(defvar android-project--selected-modules nil
  "Alist of current Android module IDs keyed by project root.")

(defvar android-project--selected-variants nil
  "Alist of per-module selected variants keyed by project root.
Each value is an alist whose keys are module IDs and values are variants.")


(defun android-project--log (format-string &rest args)
  "Log project model message FORMAT-STRING with ARGS."
  (apply #'message (concat "android-project: " format-string) args))

;;;###autoload
(defun android-root ()
  "Find the root directory of the Android project.
The root is the directory containing the project's `gradlew` file."
  (locate-dominating-file default-directory "gradlew"))

(defmacro android-in-directory (chosen-dir &rest body)
  "Execute BODY form with CHOSEN-DIR as `default-directory'.
The form is not executed when no project root directory can be found."
  `(let ((dir ,chosen-dir))
     (if dir
         (let ((default-directory dir))
           ,@body)
       (error "Can't find project root or relevant directory"))))

;; --- Flavor data source (module / variant / appId) ---

(defcustom android-project-cache-directory
  (concat user-emacs-directory ".cache/android/")
  "Directory for persisting Android project models and target selections."
  :type 'string
  :group 'android-project)

(defconst android-project--flavor-cache-version 14
  "Flavor cache schema version.")

(defvar android-project-model-updated-hook nil
  "Hook run after an Android project model refresh succeeds.")

(defvar android-project-model-state-changed-hook nil
  "Hook run after an Android project model state changes.
Each function receives PROJECT-ROOT and the new status plist.")

(defvar android-project--project-model-states (make-hash-table :test #'equal)
  "Project model status plists keyed by normalized project root.")

(defvar android-project--project-refresh-processes (make-hash-table :test #'equal)
  "Active project model refresh processes keyed by project root.")

(defvar android-project--project-refresh-callbacks (make-hash-table :test #'equal)
  "Pending project model refresh callbacks keyed by project root.")

(defvar android-project--project-refresh-invalidated (make-hash-table :test #'equal)
  "Roots changed while their project model refresh was running.")

(defvar android-project--selection-loaded-roots nil
  "Project roots whose persisted target selection has been loaded.")

(defvar android-project--flavor-cache nil
  "Cached module variant plists for the current project.
Includes module/build identity, application IDs, source components,
preview tasks, and default variant preferences.")

(defvar android-project--flavor-cache-root nil
  "Project root owning the cached flavor data.")

(defvar android-project--flavor-cache-fingerprint nil
  "Input fingerprint for the cached flavor data.")

(defvar android-project--flavor-cache-stale-p t
  "Non-nil when the in-memory project model needs refresh.")

(defvar android-project--project-watch-descriptors (make-hash-table :test #'equal)
  "File notification descriptors keyed by project root.")

(defvar android-project--project-watch-roots (make-hash-table :test #'equal)
  "Project roots keyed by file notification descriptor.")

(defvar android-project--project-watch-inputs (make-hash-table :test #'equal)
  "Tracked project model input files keyed by project root.")

(defvar android-project--project-watch-fingerprints (make-hash-table :test #'equal)
  "Last lightweight input signatures keyed by project root.")

(defvar android-project--project-watch-timer nil
  "Idle timer used when native file notifications are unavailable.")

(defun android-project--set-project-model-state (root state &optional diagnostic)
  "Set ROOT model STATE and optional DIAGNOSTIC, then notify listeners."
  (let* ((root (android-project--project-root root))
         (old (gethash root android-project--project-model-states))
         (status (list :state state
                       :diagnostic diagnostic
                       :last-model-available-p
                       (and android-project--flavor-cache
                            (equal root android-project--flavor-cache-root))
                       :changed-at (current-time))))
    (unless (and (eq state (plist-get old :state))
                 (equal diagnostic (plist-get old :diagnostic))
                 (eq (plist-get status :last-model-available-p)
                     (plist-get old :last-model-available-p)))
      (puthash root status android-project--project-model-states)
      (run-hook-with-args
       'android-project-model-state-changed-hook root status))
    status))

(defun android-project-model-status (&optional project-root)
  "Return structured project model status for PROJECT-ROOT.
The :state value is one of `not-loaded', `needs-sync', `syncing', `ready', or
`failed'.  :diagnostic contains the latest refresh failure, and
:last-model-available-p reports whether stale target metadata remains usable."
  (when-let* ((root (android-project--project-root project-root)))
    (or (gethash root android-project--project-model-states)
        (list :state
              (cond
               ((gethash root android-project--project-refresh-processes) 'syncing)
               ((and android-project--flavor-cache
                     (equal root android-project--flavor-cache-root))
                (if android-project--flavor-cache-stale-p 'needs-sync 'ready))
               (t 'not-loaded))
              :diagnostic nil
              :last-model-available-p
              (and android-project--flavor-cache
                   (equal root android-project--flavor-cache-root))))))

(defun android-project--selection-file (root)
  "Return the target selection file path for project ROOT."
  (let ((key (md5 (directory-file-name (expand-file-name root)))))
    (expand-file-name (concat key "-selection.eld") android-project-cache-directory)))

(defconst android-project--project-input-file-regexp
  "\\.\\(?:gradle\\(?:\\.kts\\)?\\|groovy\\|java\\|kt\\|properties\\|toml\\)\\'"
  "File suffixes that can affect the Android project model.")

(defun android-project--directory-input-files (directory)
  "Return relevant model input files below DIRECTORY."
  (when (file-directory-p directory)
    (let (result)
      (dolist (entry
               (directory-files directory t directory-files-no-dot-files-regexp))
        (cond
         ((and (file-directory-p entry)
               (not (member (file-name-nondirectory entry)
                            '(".git" ".gradle" "build"))))
          (setq result (nconc result (android-project--directory-input-files entry))))
         ((string-match-p android-project--project-input-file-regexp entry)
          (push entry result))))
      result)))

(defun android-project--file-signature (file)
  "Return a lightweight metadata signature for FILE."
  (if (file-directory-p file)
      (mapcar (lambda (entry)
                (cons (file-relative-name entry file)
                      (android-project--file-signature entry)))
              (sort (android-project--directory-input-files file) #'string<))
    (when-let* ((attributes (file-attributes file 'string)))
      (list (file-attribute-type attributes)
            (file-attribute-size attributes)
            (file-attribute-modification-time attributes)
            (file-attribute-status-change-time attributes)))))

(defun android-project--included-build-roots (build-root)
  "Return literal included-build directories declared below BUILD-ROOT."
  (let (result)
    (dolist (name '("settings.gradle" "settings.gradle.kts"))
      (let ((file (expand-file-name name build-root)))
        (when (file-readable-p file)
          (with-temp-buffer
            (insert-file-contents file)
            (goto-char (point-min))
            (while (re-search-forward
                    "includeBuild[[:space:]]*(?[[:space:]]*['\"]\\([^'\"]+\\)['\"]"
                    nil t)
              (push (file-name-as-directory
                     (expand-file-name (match-string-no-properties 1)
                                       build-root))
                    result))))))
    (delete-dups result)))

(defun android-project--project-model-input-files (root &optional data)
  "Return model input files for ROOT and cached model DATA."
  (let* ((build-roots
          (delete-dups
           (cons root (append (delq nil (mapcar (lambda (entry)
                                                  (plist-get entry :build-root)) data))
                              (apply #'append (mapcar (lambda (entry)
                                                        (plist-get entry :model-build-roots)) data))))))
         (module-roots
          (delete-dups (delq nil (mapcar (lambda (entry)
                                           (plist-get entry :module-root))
                                         data))))
         (included-build-roots
          (delete-dups
           (cl-loop for dir in build-roots
                    append (android-project--included-build-roots dir))))
         files)
    (dolist (dir (append build-roots module-roots))
      (dolist (name '("build.gradle" "build.gradle.kts" "gradle.properties"
                      "settings.gradle" "settings.gradle.kts"))
        (push (expand-file-name name dir) files)))
    (dolist (dir build-roots)
      (push (expand-file-name "buildSrc" dir) files)
      (push (expand-file-name "gradle/wrapper/gradle-wrapper.properties" dir)
            files)
      (let ((catalog-dir (expand-file-name "gradle" dir)))
        (push catalog-dir files)
        (when (file-directory-p catalog-dir)
          (setq files (nconc (directory-files catalog-dir t "\\.toml\\'" t)
                             files)))))
    (setq files (nconc included-build-roots files))
    (sort (delete-dups files) #'string<)))

(defun android-project--project-model-fingerprint (root &optional data inputs)
  "Return a lightweight signature of project inputs for ROOT and DATA.
When INPUTS is non-nil, use that exact path list."
  (secure-hash
   'sha256
   (prin1-to-string
    (mapcar (lambda (file)
              (cons file (android-project--file-signature file)))
            (or inputs (android-project--project-model-input-files root data))))))

(defun android-project--flavor-cache-file (root)
  "Return the disk cache file path for project ROOT."
  (let ((key (md5 (directory-file-name (expand-file-name root)))))
    (expand-file-name (concat key ".eld") android-project-cache-directory)))

(defun android-project--atomic-write (file value)
  "Write Lisp VALUE atomically to FILE without broad permissions."
  (make-directory (file-name-directory file) t)
  (let ((temporary (make-temp-file
                    (expand-file-name ".android-mode-" (file-name-directory file)))))
    (unwind-protect
        (progn
          (set-file-modes temporary #o600)
          (with-temp-file temporary
            (prin1 value (current-buffer)))
          (rename-file temporary file t))
      (when (file-exists-p temporary)
        (delete-file temporary)))))

(defun android-project--flavor-cache-save (root data)
  "Persist flavor DATA for project ROOT to disk."
  (let* ((file (android-project--flavor-cache-file root))
         (inputs (android-project--project-model-input-files root data))
         (fingerprint (android-project--project-model-fingerprint root data inputs)))
    (android-project--log "saving flavor cache for %s to %s" root file)
    (android-project--atomic-write
     file
     (list :version android-project--flavor-cache-version
           :root root :time (current-time)
           :inputs inputs :fingerprint fingerprint :data data))
    (setq android-project--flavor-cache-fingerprint fingerprint
          android-project--flavor-cache-stale-p nil)
    (android-project--watch-project-inputs root inputs)))

(defun android-project--flavor-cache-valid-p (data)
  "Return non-nil when DATA matches the current flavor cache schema."
  (and (listp data)
       (seq-every-p
        (lambda (entry)
          (and (keywordp (car-safe entry))
               (plist-get entry :module-id)
               (plist-get entry :build-root)
               (plist-get entry :module-path)
               (plist-get entry :module-root)
               (plist-get entry :plugin-id)
               (plist-member entry :project-type)
               (plist-get entry :variant)
               (plist-member entry :namespace)
               (plist-member entry :debuggable-p)
               (plist-member entry :components)
               (plist-member entry :dependencies)
               (plist-member entry :source-roots)
               (plist-member entry :build-type)
               (plist-member entry :product-flavors)
               (plist-member entry :preferred-build-type-p)
               (plist-member entry :preferred-product-flavors)))
        data)))

(defun android-project--flavor-cache-load (root)
  "Load schema-valid cached flavor data for project ROOT.
Return a plist with :data and :fresh-p, or nil for an invalid cache."
  (let ((file (android-project--flavor-cache-file root)))
    (when (file-exists-p file)
      (android-project--log "loading flavor cache for %s from %s" root file)
      (ignore-errors
        (with-temp-buffer
          (insert-file-contents file)
          (let* ((plist (read (current-buffer)))
                 (data (plist-get plist :data))
                 (inputs (plist-get plist :inputs))
                 (fingerprint (plist-get plist :fingerprint))
                 (current-fingerprint
                  (when (and (listp inputs) (listp data))
                    (android-project--project-model-fingerprint
                     root data inputs))))
            (when (and (= (or (plist-get plist :version) 0)
                          android-project--flavor-cache-version)
                       (string= (plist-get plist :root) root)
                       (android-project--flavor-cache-valid-p data))
              (list :data data :inputs inputs
                    :fingerprint fingerprint
                    :current-fingerprint current-fingerprint
                    :fresh-p (equal fingerprint current-fingerprint)))))))))

(defun android-project--module-id-p (value)
  "Return non-nil when VALUE is a stable Android module ID."
  (and (consp value) (stringp (car value)) (stringp (cdr value))))

(defun android-project--selection-state-valid-p (state root)
  "Return non-nil when persisted selection STATE is valid for ROOT."
  (let ((module (plist-get state :module))
        (variants (plist-get state :variants)))
    (and (= (or (plist-get state :version) 0) 1)
         (string= (plist-get state :root) root)
         (or (null module) (android-project--module-id-p module))
         (listp variants)
         (seq-every-p
          (lambda (entry)
            (and (android-project--module-id-p (car entry))
                 (stringp (cdr entry))))
          variants))))

(defun android-project--selection-load (root)
  "Load persisted target selection for ROOT once."
  (unless (member root android-project--selection-loaded-roots)
    (push root android-project--selection-loaded-roots)
    (let ((file (android-project--selection-file root)))
      (when (file-readable-p file)
        (ignore-errors
          (with-temp-buffer
            (insert-file-contents file)
            (let ((state (read (current-buffer))))
              (when (android-project--selection-state-valid-p state root)
                (setf (alist-get root android-project--selected-modules
                                 nil nil #'string=)
                      (plist-get state :module))
                (setf (alist-get root android-project--selected-variants
                                 nil nil #'string=)
                      (plist-get state :variants))))))))))

(defun android-project--selection-save (root)
  "Persist the current target selection for ROOT."
  (android-project--atomic-write
   (android-project--selection-file root)
   (list :version 1 :root root
         :module (cdr (assoc root android-project--selected-modules))
         :variants (cdr (assoc root android-project--selected-variants)))))

(defun android-project--file-under-input-p (file input)
  "Return non-nil when FILE is INPUT or lies below directory INPUT."
  (or (equal file input)
      (and (file-directory-p input)
           (file-in-directory-p file input))))

(defun android-project--project-watch-event-relevant-p (root event)
  "Return non-nil when file notification EVENT affects model ROOT."
  (let ((inputs (gethash root android-project--project-watch-inputs)))
    (seq-some
     (lambda (file)
       (and file
            (seq-some (lambda (input)
                        (android-project--file-under-input-p file input))
                      inputs)))
     (cddr event))))

(defun android-project--mark-project-model-stale (root)
  "Mark ROOT stale and start one asynchronous refresh."
  (when (gethash root android-project--project-refresh-processes)
    (puthash root t android-project--project-refresh-invalidated))
  (when (equal root android-project--flavor-cache-root)
    (setq android-project--flavor-cache-stale-p t))
  (android-project--set-project-model-state root 'needs-sync)
  (android-refresh-project-model root))

(defun android-project--project-watch-callback (event)
  "Mark a project model stale in response to file notification EVENT."
  (when-let* ((descriptor (car event))
              (root (gethash descriptor android-project--project-watch-roots))
              ((android-project--project-watch-event-relevant-p root event)))
    (android-project--mark-project-model-stale root)))

(defun android-project--check-project-watch-inputs ()
  "Refresh watched projects whose lightweight input signature changed."
  (maphash
   (lambda (root inputs)
     (let ((fingerprint (android-project--project-model-fingerprint root nil inputs)))
       (unless (equal fingerprint
                      (gethash root android-project--project-watch-fingerprints))
         (puthash root fingerprint android-project--project-watch-fingerprints)
         (android-project--mark-project-model-stale root))))
   android-project--project-watch-inputs))

(defun android-project--project-input-saved ()
  "Refresh a project when the saved buffer is a tracked model input."
  (when buffer-file-name
    (maphash
     (lambda (root inputs)
       (when (seq-some
              (lambda (input)
                (android-project--file-under-input-p buffer-file-name input))
              inputs)
         (android-project--mark-project-model-stale root)))
     android-project--project-watch-inputs)))

(defun android-project--watch-project-inputs (root inputs)
  "Watch model INPUTS for ROOT and install an idle fallback."
  (dolist (descriptor (gethash root android-project--project-watch-descriptors))
    (remhash descriptor android-project--project-watch-roots)
    (ignore-errors (file-notify-rm-watch descriptor)))
  (let (descriptors)
    (when (fboundp 'file-notify-add-watch)
      (dolist (file inputs)
        (let ((target (if (file-exists-p file)
                          file
                        (file-name-directory file))))
          (when (and target (file-exists-p target))
            (ignore-errors
              (let ((descriptor
                     (file-notify-add-watch
                      target '(change attribute-change)
                      #'android-project--project-watch-callback)))
                (puthash descriptor root android-project--project-watch-roots)
                (push descriptor descriptors)))))))
    (puthash root descriptors android-project--project-watch-descriptors)
    (puthash root inputs android-project--project-watch-inputs)
    (puthash root (android-project--project-model-fingerprint root nil inputs)
             android-project--project-watch-fingerprints)
    (unless (timerp android-project--project-watch-timer)
      (setq android-project--project-watch-timer
            (run-with-idle-timer 2 t #'android-project--check-project-watch-inputs)))))

(defun android-project--flavor-cache-current-p (root)
  "Return non-nil when the in-memory model for ROOT is not marked stale."
  (and android-project--flavor-cache
       (string= root android-project--flavor-cache-root)
       (not android-project--flavor-cache-stale-p)))

(defun android-project--gradle-model-command (root)
  "Return the independent project CLI synchronization command for ROOT."
  (let ((program (executable-find android-project-cli-program)))
    (unless program
      (user-error "Project CLI missing: install android-project or set android-project-cli-program"))
    (list program "sync" "--project" root "--json")))

(defun android-project--json-string-list-p (value)
  "Return non-nil when VALUE is a JSON array of strings."
  (and (vectorp value) (seq-every-p #'stringp value)))

(defun android-project--parse-dependencies (value build-root module-path variant)
  "Validate dependency VALUE for BUILD-ROOT, MODULE-PATH and VARIANT."
  (unless (and (equal (alist-get 'buildRoot value) build-root)
               (equal (alist-get 'modulePath value) module-path)
               (equal (alist-get 'variant value) variant)
               (member (alist-get 'status value) '("ready" "partial" "unsupported" "failed" "not-requested"))
               (vectorp (alist-get 'edges value))
               (android-project--json-string-list-p (alist-get 'issues value)))
    (error "Invalid dependency snapshot"))
  (let ((edges
         (mapcar
          (lambda (edge)
            (dolist (key '(modulePath buildPath status))
              (unless (stringp (alist-get key edge)) (error "Invalid dependency %s" key)))
            (dolist (key '(buildRoot variant buildType))
              (unless (or (stringp (alist-get key edge)) (eq (alist-get key edge) :json-null))
                (error "Invalid optional dependency %s" key)))
            (unless (and (member (alist-get 'status edge)
                                 '("resolved" "unknown-build" "non-android-or-unmodeled" "unmapped-variant"))
                         (member (alist-get 'scope edge) '("compile" "runtime"))
                         (memq (alist-get 'direct edge) '(t :json-false))
                         (memq (alist-get 'testFixtures edge) '(t :json-false))
                         (android-project--json-string-list-p (alist-get 'capabilities edge)))
              (error "Invalid dependency scope/flags"))
            (dolist (key '(productFlavors attributes))
              (unless (and (assq key edge) (listp (alist-get key edge))
                           (seq-every-p (lambda (pair) (and (symbolp (car pair)) (stringp (cdr pair))))
                                        (alist-get key edge)))
                (error "Invalid dependency attributes")))
            (when (equal (alist-get 'status edge) "resolved")
              (unless (and (stringp (alist-get 'buildRoot edge)) (stringp (alist-get 'variant edge)))
                (error "Resolved dependency has no exact target")))
            (list :build-root (unless (eq (alist-get 'buildRoot edge) :json-null) (alist-get 'buildRoot edge))
                  :module-path (alist-get 'modulePath edge)
                  :variant (unless (eq (alist-get 'variant edge) :json-null) (alist-get 'variant edge))
                  :build-path (alist-get 'buildPath edge) :status (intern (alist-get 'status edge))
                  :scope (intern (alist-get 'scope edge)) :direct-p (eq (alist-get 'direct edge) t)
                  :test-fixtures-p (eq (alist-get 'testFixtures edge) t)
                  :build-type (unless (eq (alist-get 'buildType edge) :json-null) (alist-get 'buildType edge))
                  :attributes (alist-get 'attributes edge) :product-flavors (alist-get 'productFlavors edge)
                  :capabilities (append (alist-get 'capabilities edge) nil)))
          (append (alist-get 'edges value) nil))))
    (list :state (intern (alist-get 'status value)) :variant variant :edges edges
          :issues (append (alist-get 'issues value) nil))))

(defun android-project--parse-cli-model (output root)
  "Validate project CLI OUTPUT for ROOT and adapt targets to public plists."
  (let* ((json-object-type 'alist) (json-array-type 'vector) (json-key-type 'symbol)
         (json-false :json-false) (json-null :json-null)
         (model (json-read-from-string output))
         (targets (alist-get 'targets model)))
    (unless (and (equal (alist-get 'schemaVersion model) 1)
                 (equal (alist-get 'kind model) "android-project")
                 (equal (alist-get 'status model) "ready")
                 (equal (file-name-as-directory (expand-file-name (alist-get 'projectRoot model)))
                        (file-name-as-directory (expand-file-name root)))
                 (vectorp targets) (> (length targets) 0))
      (error "Invalid or incompatible shared project model"))
    (mapcar
     (lambda (entry)
       (dolist (key '(buildRoot modulePath moduleRoot variant pluginId namespace projectType))
         (unless (stringp (alist-get key entry)) (error "Missing target field %s" key)))
       (dolist (key '(applicationId testApplicationId buildType))
         (unless (stringp (alist-get key entry)) (error "Invalid target field %s" key)))
       (dolist (key '(sourceRoots productFlavors preferredProductFlavors))
         (unless (android-project--json-string-list-p (alist-get key entry))
           (error "Invalid target array %s" key)))
       (dolist (key '(debuggable preferredBuildType))
         (unless (memq (alist-get key entry) '(t :json-false)) (error "Invalid boolean %s" key)))
       (unless (vectorp (alist-get 'components entry)) (error "Invalid components"))
       (dolist (component (append (alist-get 'components entry) nil))
         (unless (and (stringp (alist-get 'kind component))
                      (stringp (alist-get 'name component))
                      (android-project--json-string-list-p (alist-get 'sourceRoots component)))
           (error "Invalid component source ownership")))
       (when (assq 'modelBuildRoots entry)
         (unless (android-project--json-string-list-p (alist-get 'modelBuildRoots entry))
           (error "Invalid included build roots")))
       (let ((build-root (alist-get 'buildRoot entry))
             (module-path (alist-get 'modulePath entry)))
         (list :module-id (cons build-root module-path)
               :build-root build-root :model-build-roots (append (alist-get 'modelBuildRoots entry) nil)
               :module-path module-path
               :module-name (string-remove-prefix ":" module-path)
               :module-root (alist-get 'moduleRoot entry)
               :variant (alist-get 'variant entry)
               :dependencies (android-project--parse-dependencies
                              (alist-get 'dependencies entry) build-root module-path (alist-get 'variant entry))
               :plugin-id (alist-get 'pluginId entry)
               :namespace (alist-get 'namespace entry)
               :project-type (alist-get 'projectType entry)
               :debuggable-p (eq (alist-get 'debuggable entry) t)
               :application-id (or (alist-get 'applicationId entry) "")
               :test-application-id (or (alist-get 'testApplicationId entry) "")
               :source-roots (append (alist-get 'sourceRoots entry) nil)
               :components (mapcar (lambda (component)
                                     (list :kind (alist-get 'kind component)
                                           :name (alist-get 'name component)
                                           :source-roots (append (alist-get 'sourceRoots component) nil)))
                                   (append (alist-get 'components entry) nil))
               :build-type (or (alist-get 'buildType entry) "")
               :product-flavors (append (alist-get 'productFlavors entry) nil)
               :preferred-build-type-p (eq (alist-get 'preferredBuildType entry) t)
               :preferred-product-flavors (append (alist-get 'preferredProductFlavors entry) nil))))
     (append targets nil))))

(defun android-project--finish-project-refresh (root data error-data)
  "Finish the project model refresh for ROOT with DATA or ERROR-DATA."
  (let ((callbacks (prog1 (gethash root android-project--project-refresh-callbacks)
                     (remhash root android-project--project-refresh-callbacks))))
    (remhash root android-project--project-refresh-processes)
    (if data
        (progn
          (setq android-project--flavor-cache data
                android-project--flavor-cache-root root)
          (android-project--flavor-cache-save root data)
          (android-project--set-project-model-state root 'ready)
          (run-hook-with-args 'android-project-model-updated-hook root data))
      (android-project--set-project-model-state root 'failed error-data))
    (dolist (callback callbacks)
      (funcall callback data error-data))))

(defvar android-project--dependency-requests (make-hash-table :test #'equal)
  "Exact dependency targets requested per project root.")

(defun android-project--selection-signature (root)
  "Return the variant selection identity used by a sync for ROOT."
  (secure-hash 'sha256 (prin1-to-string
                       (list (cdr (assoc root android-project--selected-variants))
                             (gethash root android-project--dependency-requests)))))

(defun android-project--project-refresh-stale-p (root start-fingerprint inputs)
  "Return non-nil if ROOT's INPUTS changed from START-FINGERPRINT."
  (or (gethash root android-project--project-refresh-invalidated)
      (not (equal start-fingerprint
                  (android-project--project-model-fingerprint root nil inputs)))))

(defun android-project--restart-project-refresh (root callbacks)
  "Restart a stale refresh for ROOT and preserve CALLBACKS."
  (remhash root android-project--project-refresh-processes)
  (puthash root callbacks android-project--project-refresh-callbacks)
  (android-project--log "project inputs changed during refresh; restarting %s" root)
  (android-project--set-project-model-state root 'needs-sync)
  (android-refresh-project-model root))

(defun android-project--cli-failure (output)
  "Return structured CLI error details from OUTPUT, or nil."
  (condition-case nil
      (let* ((json-object-type 'alist) (json-array-type 'vector) (json-key-type 'symbol)
             (model (json-read-from-string output)))
        (when (equal (alist-get 'status model) "failed")
          (alist-get 'error model)))
    (error nil)))

(defun android-project--project-refresh-sentinel (process event)
  "Handle project model refresh PROCESS completion described by EVENT."
  (when (memq (process-status process) '(exit signal))
    (let* ((root (process-get process 'android-project-root))
           (buffer (process-buffer process))
           (current (gethash root android-project--project-refresh-processes))
           (output (when (buffer-live-p buffer)
                     (with-current-buffer buffer (buffer-string))))
           (diagnostics (process-get process 'android-project-diagnostics)))
      (unwind-protect
          (when (eq process current)
            (let ((start-fingerprint
		   (process-get process 'android-project-input-fingerprint))
		  (inputs (process-get process 'android-project-inputs)))
              (if (or (android-project--project-refresh-stale-p root start-fingerprint inputs)
                      (not (equal (process-get process 'android-project-selection-signature)
                                  (android-project--selection-signature root)))
                      (equal (alist-get 'kind (android-project--cli-failure (or output ""))) "stale-model"))
		  (progn
                    (remhash root android-project--project-refresh-invalidated)
                    (android-project--restart-project-refresh
                     root (gethash root android-project--project-refresh-callbacks)))
		(if (and (= (process-exit-status process) 0) output)
                    (let ((data (condition-case err
                                    (android-project--parse-cli-model output root)
				  (error
				   (android-project--log "model response rejected: %s" (error-message-string err))
				   nil))))
                      (cond
                       ((or (android-project--project-refresh-stale-p root start-fingerprint inputs)
                            (not (equal (process-get process 'android-project-selection-signature)
                                        (android-project--selection-signature root))))
			(remhash root android-project--project-refresh-invalidated)
			(android-project--restart-project-refresh
			 root (gethash root android-project--project-refresh-callbacks)))
                       (data
			(android-project--log "refreshed %d flavor entries" (length data))
			(android-project--finish-project-refresh root data nil))
                       (t
			(android-project--finish-project-refresh
			 root nil "Gradle returned no Android project model"))))
		  (android-project--finish-project-refresh
		   root nil (or (alist-get 'message (android-project--cli-failure (or output "")))
                                (string-trim (or output event))))))))
        (when-let* ((timer (process-get process 'android-project-completion-timer)))
          (cancel-timer timer)
          (process-put process 'android-project-completion-timer nil))
        (when-let* ((file (process-get process 'android-project-selection-file)))
          (when (file-exists-p file) (delete-file file)))
        (when (buffer-live-p diagnostics) (kill-buffer diagnostics))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))))))

(defun android-project--start-cli-process (root command)
  "Start ROOT model COMMAND, cleaning temporary buffers on launch failure."
  (let ((buffer (generate-new-buffer " *android-project-model*"))
        (diagnostics (generate-new-buffer " *android-project-model-log*"))
        selection-file)
    (condition-case err
        (progn
          (when (equal (cadr command) "sync")
            (android-project--selection-load root)
            (setq selection-file (make-temp-file "android-project-selection-" nil ".json"))
            (with-temp-file selection-file
              (insert (json-encode
                       (vconcat
                        (delq nil
                              (mapcar
                               (lambda (selection)
                                 (when (consp (car selection))
                                   `((buildRoot . ,(caar selection)) (modulePath . ,(cdar selection))
                                     (variant . ,(cdr selection)))))
                               (gethash root android-project--dependency-requests)))))))
            (setq command (append command (list "--selection-file" selection-file
                                               "--dependencies" "requested"))))
          (let ((process (make-process
                        :name (format "android-project-model-%s"
                                      (file-name-nondirectory (directory-file-name root)))
                        :buffer buffer :stderr diagnostics :command command
                        :connection-type 'pipe :noquery t
                        :sentinel (lambda (process event)
                                    (if (process-get process 'android-project-ready)
                                        (android-project--project-refresh-sentinel process event)
                                      (process-put process 'android-project-pending-event event))))))
          (process-put process 'android-project-diagnostics diagnostics)
          (process-put process 'android-project-selection-file selection-file)
          process))
      (error
       (when (and selection-file (file-exists-p selection-file)) (delete-file selection-file))
       (kill-buffer buffer)
       (kill-buffer diagnostics)
       (signal (car err) (cdr err))))))

(defun android-refresh-project-model (&optional project-root callback)
  "Refresh PROJECT-ROOT asynchronously and return its process.
CALLBACK, when non-nil, is called with two arguments DATA and ERROR.  Multiple
requests for the same root share one Gradle process."
  (when-let* ((root (android-project--project-root project-root)))
    (when callback
      (puthash root
               (append (gethash root android-project--project-refresh-callbacks)
                       (list callback))
               android-project--project-refresh-callbacks))
    (or (gethash root android-project--project-refresh-processes)
        (condition-case err
            (let* ((inputs (android-project--project-model-input-files
                            root (and (string= root android-project--flavor-cache-root)
                                      android-project--flavor-cache)))
		   (fingerprint (android-project--project-model-fingerprint root nil inputs))
		   (command (android-project--gradle-model-command root))
                   (default-directory root)
                   (process (android-project--start-cli-process root command))
                   (buffer (process-buffer process)))
              (with-current-buffer buffer
		(setq-local default-directory root))
              (process-put process 'android-project-root root)
              (process-put process 'android-project-selection-signature (android-project--selection-signature root))
              (process-put process 'android-project-inputs inputs)
              (process-put process 'android-project-input-fingerprint fingerprint)
              (remhash root android-project--project-refresh-invalidated)
              (puthash root process android-project--project-refresh-processes)
              (android-project--set-project-model-state root 'syncing)
              (process-put process 'android-project-ready t)
              ;; Some Emacs versions can omit pipe termination notifications.
              (process-put process 'android-project-completion-timer
                           (run-at-time .1 .1 #'android-project--project-refresh-sentinel
                                        process "completion check\n"))
              ;; Keep the original sentinel: replacing it can lose an exit notice.
              (when (or (process-get process 'android-project-pending-event)
                        (memq (process-status process) '(exit signal)))
                (run-at-time 0 nil #'android-project--project-refresh-sentinel
                             process "finished during setup\n"))
              (android-project--log "refreshing project model for %s asynchronously" root)
              process)
          (error
           (android-project--finish-project-refresh root nil (error-message-string err))
           nil)))))

(defun android-project--get-flavors (&optional refresh)
  "Return flavor data while refreshing stale metadata asynchronously.
With REFRESH non-nil, always request a refresh.  This function never waits for
Gradle and keeps returning an available last-known in-memory model."
  (when-let* ((root (android-project--project-root)))
    (android-project--selection-load root)
    (let ((memory (and android-project--flavor-cache
                       (string= root android-project--flavor-cache-root)
                       android-project--flavor-cache)))
      (cond
       ((and (not refresh) (android-project--flavor-cache-current-p root)) memory)
       ((and (not refresh)
             (let ((cache (android-project--flavor-cache-load root)))
               (when cache
                 (setq android-project--flavor-cache (plist-get cache :data)
                       android-project--flavor-cache-root root
                       android-project--flavor-cache-fingerprint
                       (plist-get cache :fingerprint)
                       android-project--flavor-cache-stale-p
                       (not (plist-get cache :fresh-p)))
                 (android-project--watch-project-inputs root (plist-get cache :inputs))
                 (android-project--set-project-model-state
                  root (if android-project--flavor-cache-stale-p 'needs-sync 'ready))
                 (when android-project--flavor-cache-stale-p
                   (android-refresh-project-model root))
                 t)))
        android-project--flavor-cache)
       (t
        (android-refresh-project-model root)
        memory)))))







(defun android-project--file-in-directory-p (file directory)
  "Return non-nil when FILE is inside DIRECTORY."
  (let ((file (file-truename file))
        (directory (file-name-as-directory (file-truename directory))))
    (string-prefix-p directory file)))

(defun android-project--target-source-root-score (file module-root source-root)
  "Return match score when FILE is under SOURCE-ROOT in MODULE-ROOT."
  (let ((root (expand-file-name source-root
                                (file-name-as-directory module-root))))
    (when (android-project--file-in-directory-p file root)
      (length (file-truename root)))))

(defun android-project--component-score (file module-root component)
  "Return best match score for FILE in COMPONENT under MODULE-ROOT."
  (let ((scores
         (delq nil
               (mapcar (lambda (source-root)
                         (android-project--target-source-root-score
                          file module-root source-root))
                       (plist-get component :source-roots)))))
    (when scores (apply #'max scores))))

(defun android-project--target-component-for-file (file entry)
  "Return the component in ENTRY that most specifically owns FILE."
  (let ((module-root (plist-get entry :module-root)) best best-score)
    (dolist (component (plist-get entry :components))
      (let ((score (android-project--component-score file module-root component)))
        (when (and score (or (not best-score) (> score best-score)))
          (setq best component
                best-score score))))
    best))

(defun android-project--target-score (file entry)
  "Return source-root match score for FILE and module metadata ENTRY."
  (let ((module-root (plist-get entry :module-root)))
    (when (and module-root (android-project--file-in-directory-p file module-root))
      (or (android-project--component-score
           file module-root
           (list :source-roots (plist-get entry :source-roots)))
          (length (file-truename module-root))))))

(defun android-project--target-for-source-file (file project-root &optional entries)
  "Return best Android module metadata for FILE under PROJECT-ROOT.
ENTRIES defaults to `android-project--get-flavors'."
  (let* ((file (expand-file-name file))
         (entries (or entries
                      (let ((default-directory project-root))
                        (android-project--get-flavors))))
         best-entry
         best-score)
    (dolist (entry entries)
      (when (keywordp (car-safe entry))
        (let ((score (android-project--target-score file entry)))
          (when (and score (or (not best-score) (> score best-score)))
            (setq best-entry entry
                  best-score score)))))
    best-entry))

(defun android-project--root-for-file (file)
  "Return an Android project root for FILE, or nil."
  (ignore-errors
    (let ((default-directory (file-name-directory (expand-file-name file))))
      (android-root))))

(defun android-project--variant-sort-key (entry flavor-dimensions)
  "Return Studio-compatible key for ENTRY over FLAVOR-DIMENSIONS."
  (let* ((preferred-flavors (plist-get entry :preferred-product-flavors))
         (flavors (cl-subseq (plist-get entry :product-flavors)
                             0 flavor-dimensions)))
    (append
     (list (if (plist-get entry :preferred-build-type-p) 0 1))
     (mapcar (lambda (flavor)
               (if (member flavor preferred-flavors) 0 1))
             flavors)
     (list (if (equal (plist-get entry :build-type) "debug") 0 1))
     flavors
     (list (plist-get entry :build-type)))))

(defun android-project--variant-key-less-p (left right)
  "Return non-nil when variant key LEFT sorts before RIGHT."
  (catch 'result
    (while left
      (let ((left-value (pop left))
            (right-value (pop right)))
        (unless (equal left-value right-value)
          (throw 'result
                 (if (numberp left-value)
                     (< left-value right-value)
                   (string< left-value right-value))))))
    nil))

(defun android-project--default-module-target (entries)
  "Return Android Studio-compatible default target from ENTRIES."
  (when entries
    (let* ((flavor-dimensions
            (apply #'min
                   (mapcar (lambda (entry)
                             (length (plist-get entry :product-flavors)))
                           entries)))
           (best (car entries))
           (best-key (android-project--variant-sort-key best flavor-dimensions)))
      (dolist (entry (cdr entries))
        (let ((key (android-project--variant-sort-key entry flavor-dimensions)))
          (when (android-project--variant-key-less-p key best-key)
            (setq best entry
                  best-key key))))
      best)))

(defun android-project--project-root (&optional project-root)
  "Return normalized PROJECT-ROOT or the current Android project root."
  (when-let* ((root (or project-root (ignore-errors (android-root)))))
    (file-name-as-directory (expand-file-name root))))

(defun android-project-variants (&optional project-root refresh)
  "Return available Android variant metadata for PROJECT-ROOT.
Each entry is a plist describing one Gradle module variant.  PROJECT-ROOT
defaults to `android-root'.  Stale or missing metadata starts an asynchronous
refresh; a last-known in-memory model remains available during the refresh.
With REFRESH non-nil, always start a refresh."
  (when-let* ((root (android-project--project-root project-root)))
    (android-project--selection-load root)
    (let ((default-directory root))
      (android-project--mark-selected-variants
       root (copy-tree (android-project--get-flavors refresh))))))

(defun android-project--module-key (entry)
  "Return stable module identity for target ENTRY."
  (or (plist-get entry :module-id)
      (plist-get entry :module-name)))

(defun android-project--selected-module-variant (root module entries)
  "Return selected variant under ROOT for MODULE from ENTRIES.
MODULE is a stable module identity as returned by `android-project--module-key'."
  (let* ((module-selections (cdr (assoc root android-project--selected-variants)))
         (remembered (cdr (assoc module module-selections)))
         (variants (seq-filter
                    (lambda (entry)
                      (equal (android-project--module-key entry) module))
                    entries)))
    (or (and remembered
             (seq-find (lambda (entry)
                         (string= (plist-get entry :variant) remembered))
                       variants))
        (android-project--default-module-target variants))))

(defun android-project--mark-selected-variants (root entries)
  "Return copies of ENTRIES marked with selected state under ROOT."
  (let ((selected
         (mapcar
          (lambda (module)
            (android-project--selected-module-variant root module entries))
          (delete-dups (mapcar #'android-project--module-key entries)))))
    (mapcar
     (lambda (entry)
       (let ((copy (copy-tree entry)))
         (plist-put copy :selected-p
                    (and (memq entry selected) t))))
     entries)))

(defun android-project-targets (&optional project-root refresh)
  "Return the selected Android target for each module in PROJECT-ROOT.
This mirrors Android Studio's display modules and each module's selected
variant.  Use `android-project-variants' to inspect every available variant.
With REFRESH non-nil, refresh Gradle metadata."
  (when-let* ((root (android-project--project-root project-root))
              (entries (android-project-variants root refresh)))
    (seq-filter (lambda (entry) (plist-get entry :selected-p)) entries)))

(defun android-project-application-ids (&optional project-root refresh)
  "Return application and test IDs for all Android variants in PROJECT-ROOT.
The result mirrors Android Studio's project application-ID set: main IDs from
application, dynamic-feature, and standalone test modules, plus every available
instrumentation test ID.  Missing or stale metadata follows the asynchronous
refresh behavior of `android-project-variants'.  With REFRESH non-nil, always
start a refresh."
  (delete-dups
   (apply
    #'append
    (mapcar
     (lambda (target)
       (let ((plugin-id (plist-get target :plugin-id))
             (application-id (plist-get target :application-id))
             (test-application-id (plist-get target :test-application-id)))
         (delq
          nil
          (list
           (and (member plugin-id
                        '("com.android.application"
                          "com.android.dynamic-feature"
                          "com.android.test"))
                (stringp application-id)
                (not (string-empty-p application-id))
                application-id)
           (and (stringp test-application-id)
                (not (string-empty-p test-application-id))
                test-application-id)))))
     (android-project-variants project-root refresh)))))

(defun android-project-dependencies (module &optional variant project-root refresh)
  "Return last-known project dependencies for MODULE and VARIANT.
PROJECT-ROOT and REFRESH follow `android-project-target'.  The result includes
:state, :variant, :edges and :issues.  The selected or explicit VARIANT is
resolved on demand; unrequested variants enqueue one asynchronous sync.
This query never waits."
  (when-let* ((root (android-project--project-root project-root))
              (target (android-project-target module variant root refresh)))
    (let* ((snapshot (plist-get target :dependencies))
           (request (cons (cons (plist-get target :build-root) (plist-get target :module-path))
                          (plist-get target :variant)))
           (requests (gethash root android-project--dependency-requests)))
      (when (or refresh (and (eq (plist-get snapshot :state) 'not-requested)
                             (not (member request requests))))
        (unless (member request requests)
          (puthash root (cons request
                              (seq-remove (lambda (old) (equal (car old) (car request))) requests))
                   android-project--dependency-requests))
        (unless (gethash root android-project--project-refresh-processes)
          (android-refresh-project-model root)))
      snapshot)))

(defun android-project-target (module &optional variant project-root refresh)
  "Return a target for MODULE under PROJECT-ROOT.
MODULE accepts a module ID, name, or Gradle path.  Without VARIANT, return the
module's selected target.  With VARIANT, return that exact candidate and mark
whether it is selected.  Ambiguous string module names return nil.  With
REFRESH non-nil, refresh Gradle metadata."
  (let* ((entries (android-project-variants project-root refresh))
         (matches
          (if (consp module)
              (seq-filter
               (lambda (entry)
                 (equal (plist-get entry :module-id) module))
               entries)
            (let ((name (string-remove-prefix ":" module)))
              (seq-filter
               (lambda (entry)
                 (or (string= (plist-get entry :module-name) name)
                     (string= (plist-get entry :module-path) module)))
               entries))))
         (module-ids (delete-dups (mapcar #'android-project--module-key matches))))
    (when (= (length module-ids) 1)
      (if variant
          (seq-find (lambda (entry)
                      (string= (plist-get entry :variant) variant))
                    matches)
        (seq-find (lambda (entry) (plist-get entry :selected-p)) matches)))))

(defun android-target-for-source-file (file &optional project-root refresh)
  "Return the selected Android target owning FILE under PROJECT-ROOT.
The file first resolves to a Gradle module, then to that module's selected
variant, matching Android Studio's build-target lookup.  When the selected
variant has an owning source component, include it as :component.  With
REFRESH non-nil, refresh Gradle metadata."
  (let* ((file (expand-file-name file))
         (root (android-project--project-root
                (or project-root (android-project--root-for-file file))))
         (entries (and root (android-project-variants root refresh)))
         (owner (and entries
                     (android-project--target-for-source-file file root entries))))
    (when owner
      (let* ((target
              (copy-tree
               (android-project--selected-module-variant
                root (android-project--module-key owner) entries)))
             (component (android-project--target-component-for-file file target)))
        (when component
          (setq target (plist-put target :component component)))
        (plist-put target :selected-p t)))))

;; --- Interactive selection ---

(defun android-project--module-display-name (target duplicates)
  "Return display name for TARGET, disambiguated by DUPLICATES."
  (let ((name (plist-get target :module-name)))
    (if (member name duplicates)
        (format "%s  [%s]" name (plist-get target :build-root))
      name)))

(defun android-project--select-module-target ()
  "Prompt for and return one selected module target."
  (let* ((targets (or (android-project-targets)
                      (user-error
                       "Android project model is loading; retry shortly")))
         (names (mapcar (lambda (entry) (plist-get entry :module-name)) targets))
         (duplicates
          (seq-filter
           (lambda (name) (> (seq-count (lambda (item) (string= item name)) names) 1))
           (delete-dups (copy-sequence names))))
         (choices
          (mapcar (lambda (target)
                    (cons (android-project--module-display-name target duplicates) target))
                  targets))
         (choice (if (= (length choices) 1)
                     (caar choices)
                   (completing-read "Module: " choices nil t))))
    (cdr (assoc choice choices))))

(defun android-project--select-module ()
  "Prompt user to select a module and return its module name string."
  (let* ((target (android-project--select-module-target))
         (module (plist-get target :module-name)))
    (android-project--log "selected module %s" module)
    module))

(defun android-project--select-target-variant (target)
  "Prompt for a variant of module TARGET and return its name."
  (let* ((module-id (android-project--module-key target))
         (module (plist-get target :module-name))
         (variants
          (mapcar
           (lambda (entry) (plist-get entry :variant))
           (seq-filter
            (lambda (entry)
              (equal (android-project--module-key entry) module-id))
            (android-project-variants))))
         (variant (if (= (length variants) 1)
                      (car variants)
                    (completing-read (format "Variant (%s): " module)
                                     variants nil t))))
    (android-project--log "selected variant %s for module %s" variant module)
    variant))

(defun android-project--select-variant (module)
  "Prompt for a variant of uniquely named MODULE and return its name."
  (when-let* ((target (android-project-target module)))
    (android-project--select-target-variant target)))

(defun android-project--remember-target (root module-id variant)
  "Remember and persist MODULE-ID and VARIANT as the target under ROOT."
  (setq root (android-project--project-root root))
  (android-project--selection-load root)
  (setf (alist-get root android-project--selected-modules nil nil #'string=) module-id)
  (let ((selections (copy-tree
                     (cdr (assoc root android-project--selected-variants)))))
    (setf (alist-get module-id selections nil nil #'equal) variant)
    (setf (alist-get root android-project--selected-variants nil nil #'string=)
          selections))
  (android-project--selection-save root)
  (when-let* ((requests (gethash root android-project--dependency-requests))
              ((assoc module-id requests)))
    (puthash root (cons (cons module-id variant)
                        (seq-remove (lambda (request) (equal (car request) module-id)) requests))
             android-project--dependency-requests))
  (android-project--mark-project-model-stale root))

(defun android-project--select-target (&optional prompt)
  "Return selected (MODULE . VARIANT) for the current project.
Reuse the current module's selected variant unless PROMPT is non-nil."
  (let* ((root (android-project--project-root))
         (targets (android-project-targets root))
         (current-module (cdr (assoc root android-project--selected-modules)))
         (current (and current-module
                       (seq-find
                        (lambda (entry)
                          (equal (android-project--module-key entry) current-module))
                        targets))))
    (if (and current (not prompt))
        (cons (plist-get current :module-name)
              (plist-get current :variant))
      (let* ((target (android-project--select-module-target))
             (module (plist-get target :module-name))
             (module-id (android-project--module-key target))
             (variant (android-project--select-target-variant target)))
        (android-project--log "selected module %s" module)
        (android-project--remember-target root module-id variant)
        (cons module variant)))))

(defun android-current-target (&optional prompt file project-root)
  "Return the current selected Android target metadata.
When PROMPT is non-nil, prompt for a module and variant and remember both.
Otherwise resolve FILE to its module and selected variant, then use the last
selected module, then the sole Android module.  FILE defaults to the variable
`buffer-file-name', and PROJECT-ROOT defaults to `android-root'."
  (let* ((file (or file buffer-file-name))
         (root (android-project--project-root
                (or project-root
                    (if file
                        (android-project--root-for-file file)
                      (ignore-errors (android-root)))))))
    (when root
      (if prompt
          (let ((default-directory root))
            (pcase-let ((`(,_module . ,variant) (android-project--select-target t)))
              (when-let* ((module-id
                           (cdr (assoc root android-project--selected-modules))))
                (android-project-target module-id variant root))))
        (or (and file (android-target-for-source-file file root))
            (when-let* ((module-id (cdr (assoc root android-project--selected-modules))))
              (seq-find
               (lambda (entry)
                 (equal (android-project--module-key entry) module-id))
               (android-project-targets root)))
            (let ((targets (android-project-targets root)))
              (and (= (length targets) 1) (car targets))))))))

(defun android-current-application-id (&optional prompt file project-root)
  "Return the current selected Android target's application ID.
Return nil when there is no unambiguous current target or its project model
does not expose an application ID.  PROMPT, FILE, and PROJECT-ROOT have the
same meaning as in `android-current-target'."
  (when-let* ((target (android-current-target prompt file project-root))
              (application-id (plist-get target :application-id))
              ((not (string-empty-p application-id))))
    application-id))

(add-hook 'after-save-hook #'android-project--project-input-saved)

(provide 'android-project)
;;; android-project.el ends here
