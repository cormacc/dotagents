;;; tasks-org.el --- Lightweight org-tasks helpers -*- lexical-binding: t; -*-

;; Author: Cormac Cannon
;; URL: https://github.com/cormacc/dotagents
;; Keywords: org, tasks, productivity
;; Package-Requires: ((emacs "27.1") (org "9.4") (projectile "2.0"))

;;; Commentary:

;; Thin Emacs companion for the org-tasks protocol.  It intentionally does not
;; implement lifecycle automation; the pi tasks extension remains the
;; standalone implementation.  This package provides editor conveniences for
;; locating TASKS.org, registering an org-capture template, toggling between a
;; task and its linked change-record, and toggling TASKS.local.org selection.

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'org-capture)
(require 'org-id)
(require 'subr-x)

(declare-function projectile-project-root "projectile" (&optional dir))

(defgroup tasks-org nil
  "Emacs helpers for the org-tasks protocol."
  :group 'org
  :prefix "tasks-org-")

(defcustom tasks-org-tasks-file-name "TASKS.org"
  "Project-root task index file name."
  :type 'string
  :group 'tasks-org)

(defcustom tasks-org-local-file-name "TASKS.local.org"
  "Project-root local selection / draft file name."
  :type 'string
  :group 'tasks-org)

(defcustom tasks-org-capture-key "t"
  "Key used for the tag-prompting capture template."
  :type 'string
  :group 'tasks-org)

(defcustom tasks-org-capture-no-tags-key "T"
  "Key used for the no-tags capture template."
  :type 'string
  :group 'tasks-org)

(defcustom tasks-org-capture-section "Improvements"
  "Level-1 section used by the tasks-org capture template."
  :type 'string
  :group 'tasks-org)

(defun tasks-org--project-root ()
  "Return the current project root without walking beyond the project boundary."
  (file-name-as-directory
   (or (and (fboundp 'projectile-project-root)
            (ignore-errors (projectile-project-root)))
       (locate-dominating-file default-directory ".git")
       default-directory)))

(defun tasks-org--tasks-file ()
  "Return the project-root TASKS.org path."
  (expand-file-name tasks-org-tasks-file-name (tasks-org--project-root)))

(defun tasks-org--local-file ()
  "Return the project-root TASKS.local.org path."
  (expand-file-name tasks-org-local-file-name (tasks-org--project-root)))

;;;###autoload
(defun tasks-org-find-tasks-file ()
  "Open the project-root TASKS.org file, or signal a hard error when absent."
  (interactive)
  (let ((file (tasks-org--tasks-file)))
    (unless (file-exists-p file)
      (user-error "No TASKS.org at %s; run /tasks bootstrap first" (tasks-org--project-root)))
    (find-file file)))

(defun tasks-org--timestamp ()
  "Return an inactive org timestamp body with minute precision."
  (format-time-string "[%Y-%m-%d %a %H:%M]"))

(defun tasks-org--uuid ()
  "Return a fresh UUID v4 string."
  (if (fboundp 'org-id-uuid)
      (org-id-uuid)
    (let ((hex "0123456789abcdef"))
      (cl-labels ((r (n) (apply #'string (cl-loop repeat n collect (aref hex (random 16))))))
        (format "%s-%s-4%s-%s%s-%s"
                (r 8) (r 4) (r 3)
                (string (aref "89ab" (random 4))) (r 3) (r 12))))))

(defun tasks-org--capture-metadata ()
  "Return protocol metadata drawers for a new captured task."
  (let ((id (tasks-org--uuid))
        (ts (tasks-org--timestamp)))
    (format ":PROPERTIES:\n:CUSTOM_ID: %s\n:CREATED: %s\n:END:\n:LOGBOOK:\n- Created %s\n:END:" id ts ts)))

;;;###autoload
(defun tasks-org-register-capture-template ()
  "Register org-tasks capture templates in `org-capture-templates'.

`t' (customisable via `tasks-org-capture-key') prompts for tags.
`T' (customisable via `tasks-org-capture-no-tags-key') captures without tags."
  (interactive)
  (let ((tagged `(,tasks-org-capture-key
                  "Org task"
                  entry
                  (file+headline tasks-org--tasks-file ,tasks-org-capture-section)
                  "** TODO [#%^{Priority|B|A|C|D}] %^{Summary} %^G\n%(tasks-org--capture-metadata)\n%?"
                  :empty-lines 1))
        (untagged `(,tasks-org-capture-no-tags-key
                    "Org task (no tags)"
                    entry
                    (file+headline tasks-org--tasks-file ,tasks-org-capture-section)
                    "** TODO [#%^{Priority|B|A|C|D}] %^{Summary}\n%(tasks-org--capture-metadata)\n%?"
                    :empty-lines 1)))
    (setq org-capture-templates
          (append (list tagged untagged)
                  (cl-remove-if (lambda (entry)
                                  (and (consp entry)
                                       (member (car entry)
                                               (list tasks-org-capture-key
                                                     tasks-org-capture-no-tags-key))))
                                org-capture-templates)))))

(defun tasks-org--file-content ()
  "Return current buffer content as a string."
  (buffer-substring-no-properties (point-min) (point-max)))

(defun tasks-org--file-keyword (name)
  "Return file keyword NAME from the current buffer, or nil."
  (tasks-org--file-keyword-in-current-buffer name))

(defun tasks-org--file-keyword-in-current-buffer (name)
  "Return file keyword NAME from the current buffer, or nil."
  (save-excursion
    (goto-char (point-min))
    (let ((case-fold-search t))
      (when (re-search-forward (format "^[ \t]*#[+]%s[ \t]*:[ \t]*\\(.*?\\)[ \t]*$" (regexp-quote name)) nil t)
        (match-string-no-properties 1)))))

(defun tasks-org--file-keyword-in-file (file name)
  "Return file keyword NAME from FILE, or nil."
  (when (file-readable-p file)
    (with-temp-buffer
      (insert-file-contents file)
      (tasks-org--file-keyword-in-current-buffer name))))

(defun tasks-org--org-link-target (value)
  "Return org link target from VALUE, stripping a leading file: prefix."
  (when (string-match "\\`\\[\\[\\(?:file:\\)?\\([^]]+?\\)\\]\\(?:\\[[^]]*\\]\\)?\\]\\'" (string-trim value))
    (string-trim (match-string 1 value))))

(defun tasks-org--keyword-target (value)
  "Return VALUE as an org-link target or trimmed raw keyword value."
  (when value
    (or (tasks-org--org-link-target value) (string-trim value))))

(defun tasks-org--setupfile ()
  "Return the resolved #+SETUPFILE path for the current buffer, or nil."
  (let ((target (tasks-org--keyword-target (tasks-org--file-keyword "SETUPFILE"))))
    (when (and target (buffer-file-name))
      (expand-file-name target (file-name-directory (buffer-file-name))))))

(defun tasks-org--link-template-in-current-buffer (prefix)
  "Return the #+LINK template for PREFIX from the current buffer, or nil."
  (save-excursion
    (goto-char (point-min))
    (let ((case-fold-search t))
      (when (re-search-forward (format "^[ \t]*#[+]LINK:[ \t]+%s[ \t]+\\(.+?\\)[ \t]*$" (regexp-quote prefix)) nil t)
        (match-string-no-properties 1)))))

(defun tasks-org--link-template (prefix)
  "Return the effective #+LINK template for PREFIX, following one setupfile."
  (or (tasks-org--link-template-in-current-buffer prefix)
      (let ((setup (tasks-org--setupfile)))
        (when (and setup (file-readable-p setup))
          (with-temp-buffer
            (insert-file-contents setup)
            (tasks-org--link-template-in-current-buffer prefix))))))

(defun tasks-org--parent-link ()
  "Return the parsed #+PARENT target from a change-record buffer, or nil."
  (let ((raw (tasks-org--file-keyword "PARENT")))
    (and raw (tasks-org--org-link-target raw))))

(defun tasks-org--parent-id-from-target (target)
  "Extract a CUSTOM_ID anchor from a #+PARENT link TARGET."
  (when (and target (string-match "::#\\([^[:space:]#]+\\)\\'" target))
    (match-string 1 target)))

(defun tasks-org--parent-file-from-target (target)
  "Extract the file portion from a #+PARENT link TARGET."
  (when target
    (replace-regexp-in-string "::#.*\\'" "" target)))

(defun tasks-org--find-heading-by-custom-id (id)
  "Move point to the heading whose CUSTOM_ID property equals ID."
  (goto-char (point-min))
  (let ((found nil))
    (while (and (not found) (re-search-forward org-heading-regexp nil t))
      (org-back-to-heading t)
      (when (equal (org-entry-get (point) "CUSTOM_ID") id)
        (setq found t))
      (unless found (forward-line 1)))
    (unless found
      (user-error "No heading with CUSTOM_ID %s in %s" id (buffer-file-name)))
    (point)))

(defun tasks-org--current-import-link ()
  "Return the current heading's #+IMPORT target, or nil."
  (unless (org-before-first-heading-p)
    (save-excursion
      (org-back-to-heading t)
      (let ((end (save-excursion (org-end-of-subtree t t))))
        (when (re-search-forward "^[ \t]*#[+]IMPORT[ \t]*:[ \t]*\\(.*?\\)[ \t]*$" end t)
          (tasks-org--org-link-target (match-string-no-properties 1)))))))

(defun tasks-org--expand-import-target (target)
  "Return (FILE . BASE) for an import TARGET.
BASE is either `project' for org-link abbreviations or `buffer' for ordinary
file/path targets."
  (if (and target
           (string-match "\\`\\([A-Za-z][A-Za-z0-9+.-]*\\):\\(.+\\)\\'" target)
           (not (member (match-string 1 target) '("file" "http" "https"))))
      (let* ((prefix (match-string 1 target))
             (key (match-string 2 target))
             (template (tasks-org--link-template prefix)))
        (if template
            (let ((expanded (replace-regexp-in-string "%s" key template t t)))
              (cons (if (string-prefix-p "file:" expanded)
                        (substring expanded 5)
                      expanded)
                    'project))
          (cons target 'buffer)))
    (cons target 'buffer)))

(defun tasks-org--open-target (file &optional other-window)
  "Open FILE, optionally in OTHER-WINDOW."
  (if other-window
      (find-file-other-window file)
    (find-file file)))

(defun tasks-org--toggle-task-and-plan (&optional other-window)
  "Shared implementation for task/plan toggling."
  (let ((parent-target (tasks-org--parent-link)))
    (cond
     (parent-target
      (let* ((parent-id (tasks-org--parent-id-from-target parent-target))
             (parent-file (tasks-org--parent-file-from-target parent-target)))
        (unless (and parent-id parent-file)
          (user-error "Malformed #+PARENT link"))
        (tasks-org--open-target (expand-file-name parent-file (file-name-directory (buffer-file-name))) other-window)
        (tasks-org--find-heading-by-custom-id parent-id)))
     (t
      (let ((import (tasks-org--current-import-link)))
        (unless import
          (user-error "Point is neither on a task with #+IMPORT nor in a change-record with #+PARENT"))
        (let* ((expanded (tasks-org--expand-import-target import))
               (base (if (eq (cdr expanded) 'project)
                         (tasks-org--project-root)
                       (file-name-directory (buffer-file-name)))))
          (tasks-org--open-target (expand-file-name (car expanded) base) other-window)))))))

;;;###autoload
(defun tasks-org-toggle-task-and-plan ()
  "Toggle between the current task and its linked change-record."
  (interactive)
  (tasks-org--toggle-task-and-plan nil))

;;;###autoload
(defun tasks-org-toggle-task-and-plan-other-window ()
  "Toggle between the current task and its linked change-record in another window."
  (interactive)
  (tasks-org--toggle-task-and-plan t))

(defun tasks-org--in-tasks-file-p ()
  "Return non-nil when the current buffer visits TASKS.org or TASKS.local.org."
  (let ((name (file-name-nondirectory (or (buffer-file-name) ""))))
    (member name (list tasks-org-tasks-file-name tasks-org-local-file-name))))

(defun tasks-org--top-level-task-id ()
  "Return the surrounding top-level task CUSTOM_ID for selection toggling."
  (cond
   ((tasks-org--in-tasks-file-p)
    (save-excursion
      (unless (org-at-heading-p) (org-back-to-heading t))
      (while (> (org-current-level) 2)
        (unless (org-up-heading-safe) (user-error "Point is not in a task subtree")))
      (unless (= (org-current-level) 2)
        (user-error "Point is not in a top-level task subtree"))
      (or (org-entry-get (point) "CUSTOM_ID")
          (user-error "Top-level task has no CUSTOM_ID"))))
   (t
    (or (tasks-org--parent-id-from-target (tasks-org--parent-link))
        (user-error "Current buffer has no parseable #+PARENT link")))))

(defun tasks-org--read-selected-id ()
  "Read the selected UUID from TASKS.local.org, or nil."
  (let ((file (tasks-org--local-file)))
    (when (file-exists-p file)
      (with-temp-buffer
        (insert-file-contents file)
        (goto-char (point-min))
        (when (re-search-forward "^[ \t]*#[+]SELECTED[ \t]*:[ \t]*\\([^ \t\r\n]+\\)" nil t)
          (match-string-no-properties 1))))))

(defun tasks-org--write-local-content (content)
  "Atomically replace TASKS.local.org with CONTENT."
  (let* ((file (tasks-org--local-file))
         (tmp (concat file ".tmp")))
    (make-directory (file-name-directory file) t)
    (with-temp-file tmp (insert content))
    (rename-file tmp file t)))

(defun tasks-org--set-selected-id (id)
  "Set #+SELECTED to ID, preserving all other TASKS.local.org content."
  (let* ((file (tasks-org--local-file))
         (existing (if (file-exists-p file)
                       (with-temp-buffer (insert-file-contents file) (buffer-string))
                     ""))
         (line (format "#+SELECTED: %s" id))
         (updated (if (string-match-p "^#[+]SELECTED:" existing)
                      (replace-regexp-in-string "^#[+]SELECTED:.*$" line existing nil nil)
                    (concat line "\n" existing))))
    (tasks-org--write-local-content updated)))

(defun tasks-org--clear-selected ()
  "Remove only the #+SELECTED line from TASKS.local.org."
  (let* ((file (tasks-org--local-file))
         (existing (if (file-exists-p file)
                       (with-temp-buffer (insert-file-contents file) (buffer-string))
                     ""))
         (lines (split-string existing "\n"))
         (kept (cl-remove-if (lambda (line)
                               (string-prefix-p "#+SELECTED:" line))
                             lines))
         (updated (string-join kept "\n")))
    (tasks-org--write-local-content updated)))

;;;###autoload
(defun tasks-org-toggle-selected ()
  "Toggle selection of the surrounding top-level task in TASKS.local.org."
  (interactive)
  (let* ((id (tasks-org--top-level-task-id))
         (current (tasks-org--read-selected-id)))
    (if (equal id current)
        (progn
          (tasks-org--clear-selected)
          (message "Selection cleared"))
      (tasks-org--set-selected-id id)
      (message "Selected: %s" id))))

(provide 'tasks-org)
;;; tasks-org.el ends here
