EMACS ?= emacs
BATCH = $(EMACS) -Q --batch
LOAD_PATH = -L .
PREFIX ?= $(HOME)/.local

.PHONY: install-deps lint build test cli-build cli-test install-local clean
install-deps:
	@true

lint:
	$(BATCH) $(LOAD_PATH) --eval '(checkdoc-file "android-project.el")' \
	  --eval '(with-temp-buffer (insert-file-contents "android-project.el") (emacs-lisp-mode) (check-parens))'

build:
	$(BATCH) $(LOAD_PATH) --eval '(setq byte-compile-error-on-warn t)' -f batch-byte-compile android-project.el

test:
	$(BATCH) $(LOAD_PATH) -l android-project.el -l android-project-tests.el -f ert-run-tests-batch-and-exit

cli-build:
	./gradlew :cli:installDist

cli-test:
	./gradlew :core:test :cli:test

install-local: cli-build
	mkdir -p "$(PREFIX)/lib/android-project" "$(PREFIX)/bin"
	test ! -L "$(PREFIX)/lib/android-project" && test ! -L "$(PREFIX)/lib/android-project/lib"
	rm -rf "$(PREFIX)/lib/android-project/lib"
	cp -R cli/build/install/android-project/. "$(PREFIX)/lib/android-project/"
	printf '%s\n' '#!/bin/sh' 'exec "$(PREFIX)/lib/android-project/bin/android-project" "$$@"' > "$(PREFIX)/bin/android-project"
	chmod +x "$(PREFIX)/bin/android-project"

clean:
	rm -f *.elc
