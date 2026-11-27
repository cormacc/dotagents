;;; herdr-tabs-tests.el --- Tests for herdr-tabs.el -*- lexical-binding: t; -*-

(require 'ert)
(require 'herdr-tabs)

(defmacro herdr-tabs-test--with-project (reply &rest body)
  "Run BODY with `root' bound to a temp project and a stub `ot' printing REPLY.
REPLY is a format string; %s receives the project root."
  (declare (indent 1))
  `(let* ((root (file-name-as-directory (make-temp-file "herdr-tabs" t)))
          (stub (expand-file-name "ot" root))
          (herdr-tabs-ot-program stub))
     (unwind-protect
         (progn
           (with-temp-file stub
             (insert "#!/bin/sh\ncat <<'EOF'\n" (format ,reply root) "\nEOF\n"))
           (set-file-modes stub #o755)
           ,@body)
       (delete-directory root t))))

(defun herdr-tabs-test--touch (file)
  (make-directory (file-name-directory file) t)
  (write-region "" nil file))

(ert-deftest herdr-tabs-test-plan-prefers-selected-record ()
  (herdr-tabs-test--with-project
      "{\"ok\":true,\"result\":{\"record\":{\"path\":\"%sdesign/log/r.org\"}}}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (herdr-tabs-test--touch (expand-file-name "design/log/r.org" root))
    (should (equal (expand-file-name "design/log/r.org" root)
                   (herdr-tabs--plan-file root)))))

(ert-deftest herdr-tabs-test-plan-falls-back-without-selection ()
  (herdr-tabs-test--with-project
      "{\"ok\":false,\"error\":{\"code\":\"unknown-task\"}}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (should (equal (expand-file-name "TASKS.org" root)
                   (herdr-tabs--plan-file root)))))

(ert-deftest herdr-tabs-test-plan-falls-back-without-record ()
  (herdr-tabs-test--with-project
      "{\"ok\":true,\"result\":{\"record\":null,\"task\":{}}}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (should (equal (expand-file-name "TASKS.org" root)
                   (herdr-tabs--plan-file root)))))

(ert-deftest herdr-tabs-test-plan-falls-back-when-record-file-missing ()
  (herdr-tabs-test--with-project
      "{\"ok\":true,\"result\":{\"record\":{\"path\":\"%sgone.org\"}}}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (should (equal (expand-file-name "TASKS.org" root)
                   (herdr-tabs--plan-file root)))))

(ert-deftest herdr-tabs-test-plan-finds-tasks-above-subdir ()
  (herdr-tabs-test--with-project
      "{\"ok\":false}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (let ((sub (expand-file-name "src/deep/" root)))
      (make-directory sub t)
      (should (equal (expand-file-name "TASKS.org" root)
                     (herdr-tabs--plan-file sub))))))

(ert-deftest herdr-tabs-test-plan-errors-without-tasks ()
  (herdr-tabs-test--with-project
      "{\"ok\":false}"
    (should-error (herdr-tabs--plan-file root) :type 'user-error)))

(ert-deftest herdr-tabs-test-plan-survives-missing-ot ()
  (herdr-tabs-test--with-project
      "{}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (let ((herdr-tabs-ot-program (expand-file-name "no-such-ot" root)))
      (should (equal (expand-file-name "TASKS.org" root)
                     (herdr-tabs--plan-file root))))))

(ert-deftest herdr-tabs-test-plan-shows-one-window ()
  (herdr-tabs-test--with-project
      "{\"ok\":false}"
    (herdr-tabs-test--touch (expand-file-name "TASKS.org" root))
    (split-window)
    (herdr-tabs-plan root)
    (should (= 1 (length (window-list))))
    (should (equal (expand-file-name "TASKS.org" root) (buffer-file-name)))
    (kill-buffer)))

(ert-deftest herdr-tabs-test-fill-frame-from-a-side-window ()
  "Regression: Magit can leave a side window selected, and a plain
`delete-other-windows' there signals `Cannot make side window the only window'."
  (let ((buf (get-buffer-create "*herdr-tabs-target*"))
        (side (display-buffer-in-side-window (get-buffer-create "*herdr-tabs-side*")
                                             '((side . left)))))
    (select-window side)
    (herdr-tabs--fill-frame buf)
    (should (= 1 (length (window-list))))
    (should (eq buf (window-buffer (selected-window))))
    (should-not (window-parameter (selected-window) 'window-side))))

(ert-deftest herdr-tabs-test-fill-frame-ignores-side-window-display-actions ()
  "Regression: with `switch-to-buffer-obey-display-actions', a rule that sends
the buffer to a side window must not move it out of the main window."
  (let* ((buf (get-buffer-create "*herdr-tabs-ruled*"))
         (display-buffer-alist '(("\\*herdr-tabs-ruled\\*" display-buffer-in-side-window (side . right))))
         (switch-to-buffer-obey-display-actions t))
    (herdr-tabs--fill-frame buf)
    (should (= 1 (length (window-list))))
    (should (eq buf (window-buffer (selected-window))))))

;;; herdr-tabs-tests.el ends here
