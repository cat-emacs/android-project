EMACS ?= emacs
BATCH = $(EMACS) -Q --batch
LOAD_PATH = -L .

.PHONY: install-deps lint build test clean
install-deps:
	@true

lint:
	$(BATCH) $(LOAD_PATH) --eval '(checkdoc-file "android-project.el")' \
	  --eval '(with-temp-buffer (insert-file-contents "android-project.el") (emacs-lisp-mode) (check-parens))'

build:
	$(BATCH) $(LOAD_PATH) --eval '(setq byte-compile-error-on-warn t)' -f batch-byte-compile android-project.el

test:
	$(BATCH) $(LOAD_PATH) -l android-project.el -l android-project-tests.el -f ert-run-tests-batch-and-exit

clean:
	rm -f *.elc
