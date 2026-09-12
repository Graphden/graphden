;;; devtour-el-test.el --- batch tests for docs/devtour/devtour.el  -*- lexical-binding: t; -*-

;; Run from the repo root:  emacs -Q --batch -l tools/devtour-el-test.el
;; (or `bb devtour-el', which skips when emacs is not installed).
;;
;; What this guards: the emacs reading path resolves the SAME anchors the
;; generator baked — every step's head line is findable in the live file — plus
;; navigation, progress, the eldoc annotation and the org-protocol handler.

;;; Code:

(require 'ert)
(require 'cl-lib)

(defconst devtour-test-root (expand-file-name default-directory))

(setq devtour-progress-file (make-temp-file "devtour-progress" nil ".eld"))
(load (expand-file-name "docs/devtour/devtour.el" devtour-test-root) nil t)
(setq devtour-repo-root devtour-test-root
      devtour-data-file (expand-file-name "docs/devtour/tour.eld" devtour-test-root))

(ert-deftest devtour-data-loads ()
  (devtour--load)
  (should (> (length devtour--steps) 100))
  (should (= (length devtour--steps) (hash-table-count devtour--by-key)))
  (should (stringp (plist-get devtour--tour :title)))
  (should (> (plist-get devtour--tour :mins) 0)))

(ert-deftest devtour-every-anchor-resolves-in-the-live-file ()
  "Each step's head line must exist in its file — this is the emacs-side
equivalent of `bb devtour-check', and it is what makes the org links and the
`devtour--visit' jump trustworthy."
  (devtour--load)
  (let ((bad '()))
    (dolist (step (append devtour--steps nil))
      (let ((path (expand-file-name (plist-get step :file) devtour-repo-root)))
        (if (not (file-readable-p path))
            (push (format "missing file %s" (plist-get step :file)) bad)
          (with-temp-buffer
            (insert-file-contents path)
            (goto-char (point-min))
            (unless (search-forward (plist-get step :head) nil t)
              (push (format "%s: head %S not found in %s"
                            (plist-get step :key) (plist-get step :head)
                            (plist-get step :file))
                    bad))))))
    (should (equal bad '()))))

(ert-deftest devtour-baked-line-hits-the-form ()
  "The baked :line should land straight on the head line (a stale line only
costs a search, but it should not be stale right after a bake)."
  (devtour--load)
  (let ((off '()))
    (dolist (step (append devtour--steps nil))
      (let ((path (expand-file-name (plist-get step :file) devtour-repo-root)))
        (with-temp-buffer
          (insert-file-contents path)
          (goto-char (point-min))
          (forward-line (1- (plist-get step :line)))
          (unless (looking-at-p (regexp-quote (plist-get step :head)))
            (push (plist-get step :key) off)))))
    (should (equal off '()))))

(ert-deftest devtour-navigation-and-progress ()
  (devtour--load)
  (clrhash devtour--seen)
  (setq devtour--history nil devtour--pos nil)
  (devtour--show 0)
  (should (= devtour--pos 0))
  (should (get-buffer "*devtour*"))
  (should (string-match-p (plist-get (devtour--step) :defn)
                          (with-current-buffer "*devtour*" (buffer-string))))
  (devtour-next)
  (should (= devtour--pos 1))
  (devtour-back)
  (should (= devtour--pos 0))
  (should (= 2 (hash-table-count devtour--seen)))
  ;; progress survives a reload
  (devtour--save-progress)
  (devtour--load-progress)
  (should (= 2 (hash-table-count devtour--seen)))
  (should (equal devtour--last (plist-get (aref devtour--steps 0) :key))))

(ert-deftest devtour-visit-puts-point-on-the-form ()
  (devtour--load)
  (dolist (i (list 0 7 (/ (length devtour--steps) 2) (1- (length devtour--steps))))
    (let* ((step (aref devtour--steps i))
           (buf (devtour--visit step)))
      (with-current-buffer buf
        (should (looking-at-p (regexp-quote (plist-get step :head))))))))

(ert-deftest devtour-annotation-finds-the-step-at-point ()
  (devtour--load)
  (let* ((step (aref devtour--steps 0))
         (path (expand-file-name (plist-get step :file) devtour-repo-root)))
    (with-current-buffer (find-file-noselect path)
      (goto-char (point-min))
      (forward-line (plist-get step :line))   ; inside the form, not on line 1
      (let ((hit (devtour-step-at-point)))
        (should hit)
        (should (equal (plist-get hit :key) (plist-get step :key))))
      ;; and eldoc renders a one-liner for it
      (let ((out nil))
        (should (devtour--eldoc (lambda (s &rest _) (setq out s))))
        (should (string-match-p "devtour · " out)))
      ;; a file the tour does not cover reports nothing
      (goto-char (point-min))
      (should-not (with-temp-buffer (devtour-step-at-point))))))

(ert-deftest devtour-org-protocol-opens-at-line ()
  (devtour--load)
  (let* ((step (aref devtour--steps 3))
         (res (devtour-org-protocol-open
               (list :file (plist-get step :file)
                     :line (number-to-string (plist-get step :line))))))
    (should (null res))
    (should (equal (expand-file-name (plist-get step :file) devtour-repo-root)
                   buffer-file-name))
    (should (= (line-number-at-pos) (plist-get step :line)))))

(ert-deftest devtour-index-and-goto-candidates-build ()
  (devtour--load)
  (should (devtour--label (aref devtour--steps 0)))
  (cl-letf (((symbol-function 'completing-read)
             (lambda (_p cands &rest _) (car (car cands)))))
    (devtour-goto)
    (should (= devtour--pos 0))
    (devtour-index)
    (should (= devtour--pos 0))))

(ert-deftest devtour-see-also-follows-a-link ()
  (devtour--load)
  (let ((i (cl-position-if (lambda (s) (plist-get s :see))
                           (append devtour--steps nil))))
    (should i)
    (devtour--show i)
    (cl-letf (((symbol-function 'completing-read)
               (lambda (_p cands &rest _) (car cands))))
      (devtour-see-also))
    (should (/= devtour--pos i))))

(ert-deftest devtour-org-links-open-the-real-form ()
  "The generated org tree must link back into THIS checkout: the relative
prefix resolves, and org's `::<head>' search lands on the anchored form."
  (require 'org)
  (let* ((org-dir (expand-file-name "docs/devtour/org" devtour-test-root))
         (file (expand-file-name "executor.org" org-dir))
         (src '()) (xrefs '()))
    (should (file-readable-p file))
    (with-temp-buffer
      (insert-file-contents file)
      (goto-char (point-min))
      (while (re-search-forward "^- source :: \\[\\[file:\\([^]]+?\\)::\\([^]]+\\)\\]" nil t)
        (push (cons (match-string 1) (match-string 2)) src))
      (goto-char (point-min))
      (while (re-search-forward "\\[\\[file:\\([^]]+\\.org\\)::#\\([^]]+\\)\\]" nil t)
        (push (cons (match-string 1) (match-string 2)) xrefs)))
    (should (> (length src) 5))
    (dolist (link src)
      (let ((path (expand-file-name (car link) org-dir)))
        (should (file-readable-p path))
        (with-temp-buffer
          (insert-file-contents path)
          (goto-char (point-min))
          (should (search-forward (cdr link) nil t)))))
    ;; every cross-block link names a CUSTOM_ID that exists over there
    (should (> (length xrefs) 3))
    (dolist (x xrefs)
      (let ((path (expand-file-name (car x) org-dir)))
        (should (file-readable-p path))
        (with-temp-buffer
          (insert-file-contents path)
          (goto-char (point-min))
          (should (search-forward (concat ":CUSTOM_ID: " (cdr x)) nil t)))))
    ;; and one real org link-follow, end to end
    (find-file file)
    (goto-char (point-min))
    (re-search-forward "^- source :: ")
    (org-open-at-point)
    (should (string-suffix-p ".clj" (or buffer-file-name "")))
    (beginning-of-line)
    (should (looking-at-p "(def"))))


(let ((ert-quiet nil))
  (ert-run-tests-batch-and-exit))
