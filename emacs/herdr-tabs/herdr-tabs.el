;;; herdr-tabs.el --- Emacs views for Herdr Emacs tabs -*- lexical-binding: t; -*-

;; Author: Cormac Cannon
;; URL: https://github.com/cormacc/dotagents
;; Keywords: tools, vc, org
;; Package-Requires: ((emacs "28.1"))

;;; Commentary:

;; Entry points that `emacs-tab' evaluates in a new terminal frame.  Each one
;; fills the selected frame with one view, so a Herdr tab shows that view
;; alone.  Magit is loaded on demand, so the plan view works without it.

;;; Code:

(require 'json)
(require 'subr-x)

(declare-function magit-status-setup-buffer "magit-status" (&optional directory))

(defgroup herdr-tabs nil
  "Emacs views for Herdr Emacs tabs."
  :group 'tools
  :prefix "herdr-tabs-")

(defcustom herdr-tabs-ot-program
  (let ((home-ot (expand-file-name "~/.agents/skills/org-tasks/scripts/ot")))
    (if (file-executable-p home-ot) home-ot "ot"))
  "The org-tasks `ot' executable.
The home install is preferred because a frame started from a key binding can
have a PATH without the shell's additions."
  :type 'string
  :group 'herdr-tabs)

(defun herdr-tabs--selected-record (dir)
  "Return the change-record path of the selected task for DIR, or nil.
Nil covers no selection, a task without a record, a missing record file and
any `ot' failure, so the caller can fall back to TASKS.org."
  (let ((default-directory (file-name-as-directory dir)))
    (with-temp-buffer
      (when (eql 0 (ignore-errors
                     (call-process herdr-tabs-ot-program nil (list t nil) nil
                                   "show" "selected" "--format" "json")))
        (let* ((reply (ignore-errors
                        (json-parse-string (buffer-string) :object-type 'alist
                                           :null-object nil :false-object nil)))
               (path (and (alist-get 'ok reply)
                          (alist-get 'path (alist-get 'record (alist-get 'result reply))))))
          (and (stringp path) (not (string-empty-p path))
               (file-exists-p (expand-file-name path))
               (expand-file-name path)))))))

(defun herdr-tabs--fill-frame (buffer)
  "Show BUFFER as the only window of the selected frame.
The user's `display-buffer-alist' can put a buffer in a side window, which
`delete-other-windows' refuses to keep alone, so delete side windows first.
The buffer is set directly, because `switch-to-buffer' can obey the same
display actions and put it back in a side window."
  (dolist (w (window-list nil 'nomini))
    (when (and (window-live-p w) (window-parameter w 'window-side))
      (delete-window w)))
  (select-window (frame-first-window))
  (delete-other-windows)
  (set-window-buffer (selected-window) buffer)
  (set-buffer buffer))

(defun herdr-tabs--plan-file (dir)
  "Return the file the plan view shows for DIR.
That is the selected task's change-record, else the nearest TASKS.org at or
above DIR.  Signal a `user-error' when neither exists."
  (or (herdr-tabs--selected-record dir)
      (when-let ((root (locate-dominating-file dir "TASKS.org")))
        (expand-file-name "TASKS.org" root))
      (user-error "No TASKS.org at or above %s" dir)))

;;;###autoload
(defun herdr-tabs-plan (dir)
  "Show the selected task's change-record for DIR, else its TASKS.org."
  (interactive (list default-directory))
  (herdr-tabs--fill-frame (find-file-noselect (herdr-tabs--plan-file dir))))

;;;###autoload
(defun herdr-tabs-magit (dir)
  "Show `magit-status' for DIR as the only window of the selected frame."
  (interactive (list default-directory))
  (require 'magit)
  (herdr-tabs--fill-frame
   (save-window-excursion
     (magit-status-setup-buffer (file-name-as-directory (expand-file-name dir))))))

(provide 'herdr-tabs)
;;; herdr-tabs.el ends here
