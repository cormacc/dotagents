/**
 * Pure functions for generating Emacs Lisp code and parsing emacsclient output.
 *
 * All functions here are side-effect free — they produce elisp strings or parse
 * result strings. The actual emacsclient invocation lives in emacsclient.ts.
 */

// ---------------------------------------------------------------------------
// Elisp string escaping
// ---------------------------------------------------------------------------

/**
 * Escape a string for embedding inside an Emacs Lisp double-quoted string.
 * Handles backslashes, double quotes, and newlines.
 */
export function escapeElispString(s: string): string {
  return s
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\n/g, "\\n");
}

// ---------------------------------------------------------------------------
// Elisp generators
// ---------------------------------------------------------------------------

/**
 * Build elisp that returns a JSON-encoded list of buffer metadata.
 */
/**
 * Build elisp that runs a tree-sitter query against a buffer, optionally
 * executing an action expression for each match.
 *
 * The query string is a tree-sitter S-expression pattern with @captures.
 * The action is elisp evaluated per match, with captures bound as variables.
 * If no action is given, matched node text is returned.
 */
export function buildTsQueryElisp(
  buffer: string,
  query: string,
  lang?: string,
  action?: string
): string {
  const bufExpr = `(or (get-buffer "${escapeElispString(buffer)}")
       (find-buffer-visiting "${escapeElispString(buffer)}")
       (let ((buf (find-file-noselect "${escapeElispString(buffer)}")))
         (unless buf (error "Cannot open buffer for: ${escapeElispString(buffer)}"))
         buf))`;

  const langExpr = lang
    ? `(or (treesit-language-at (point-min)) '${lang})`
    : "(treesit-language-at (point-min))";

  // Default action: return the text of the first capture's node
  const actionExpr = action
    ? action
    : "(treesit-node-text node t)";

  // Count the number of @captures in the query to know how to group
  const captureCount = (query.match(/@\w+/g) || []).length;
  return `(json-encode
  (with-current-buffer ${bufExpr}
    (let* ((lang ${langExpr})
           (root (treesit-buffer-root-node lang))
           (query-compiled (treesit-query-compile lang "${escapeElispString(query)}"))
           (captures (treesit-query-capture root query-compiled))
           (results '())
           (capture-count ${captureCount}))
      ;; Group consecutive captures into matches
      ;; treesit-query-capture returns captures in order, with all captures
      ;; from a single match appearing consecutively
      (let ((i 0))
        (while (< i (length captures))
          ;; Collect capture-count captures for this match
          (let* ((match-captures (cl-subseq captures i (min (+ i capture-count) (length captures))))
                 ;; Extract capture names and nodes
                 (capture-names (mapcar (lambda (cap) (intern (symbol-name (car cap))))
                                       match-captures))
                 (capture-nodes (mapcar 'cdr match-captures))
                 ;; Build a lambda: (lambda (name body ...) (let ((node <first-param>)) <action>))
                 (lambda-body (list 'let (list (list 'node (car capture-names)))
                                   (car (read-from-string "${escapeElispString(actionExpr)}"))))
                 (lambda-form (list 'lambda capture-names lambda-body))
                 (result (condition-case err
                           (apply (eval lambda-form) capture-nodes)
                         (error (format "ERROR: %s" (error-message-string err))))))
            (push (if (stringp result) result (format "%S" result)) results)
            (setq i (+ i capture-count)))))
      (nreverse results)))))`;
}

/**
 * Build elisp for evaluating an arbitrary expression and returning the
 * JSON-encoded result.
 */
export function buildEvalElisp(expression: string): string {
  return `(json-encode
  (let ((result (progn ${expression})))
    (cond
      ((stringp result) result)
      ((null result) :json-false)
      ((eq result t) t)
      ((numberp result) result)
      ((listp result) result)
      (t (format "%S" result)))))`;
}

/**
 * Wrap an elisp expression in a robust transport envelope.
 *
 * `emacsclient --eval` prints the Lisp result using Emacs string syntax, which
 * is awkward to parse reliably across platforms/builds when the payload itself
 * contains newlines or other control characters. To avoid that, we:
 *
 *   1. Evaluate the original elisp expression.
 *   2. Tag the result with a simple type descriptor.
 *   3. JSON-encode that tagged payload.
 *   4. UTF-8 encode it and base64 the bytes.
 *
 * The final `emacsclient` stdout is then just a quoted base64 ASCII string,
 * which is safe to parse on every platform.
 */
export function buildTransportElisp(elisp: string): string {
  return `(let* ((result (progn ${elisp}))
         (payload
          (cond
            ((stringp result) (list (cons "type" "string") (cons "value" result)))
            ((null result) (list (cons "type" "null")))
            ((eq result t) (list (cons "type" "boolean") (cons "value" t)))
            ((eq result :json-false) (list (cons "type" "boolean") (cons "value" :json-false)))
            ((numberp result) (list (cons "type" "number") (cons "value" result)))
            (t (list (cons "type" "printed") (cons "value" (format "%S" result)))))))
    (base64-encode-string
      (encode-coding-string (json-encode payload) 'utf-8)
      t))`;
}

// ---------------------------------------------------------------------------
// Result parsing
// ---------------------------------------------------------------------------

/**
 * Unescape an Emacs prin1-printed string (the content between outer quotes).
 *
 * Emacs prin1 escapes only two things inside strings:
 *   \  →  \\
 *   "  →  \"
 *
 * We reverse this with a single character-by-character pass so that
 * sequences like \\n (prin1-escaped backslash before 'n') correctly
 * become \n (the JSON escape for newline) rather than a literal newline.
 */
function unescapeElispString(s: string): string {
  let result = "";
  for (let i = 0; i < s.length; i++) {
    if (s[i] === "\\" && i + 1 < s.length) {
      result += s[i + 1];
      i++;
    } else {
      result += s[i];
    }
  }
  return result;
}

/**
 * Parse the output of `emacsclient --eval`, which prints an Emacs Lisp value.
 *
 * For our purposes, the result is always a JSON string (from json-encode),
 * which emacsclient prints as an elisp string literal: "\"...\"".
 * We need to strip the outer quotes and unescape the prin1 escaping,
 * then parse the resulting JSON.
 *
 * Escaping layers:
 *   1. json-encode produces a JSON string with standard JSON escapes
 *      (\n for newline, \\ for backslash, \" for quote, etc.)
 *   2. Emacs prin1 wraps in double quotes and escapes \ → \\ and " → \"
 *   3. We undo layer 2, then JSON.parse handles layer 1
 */
export function parseEmacsclientOutput(raw: string): unknown {
  const trimmed = raw.trim();

  // emacsclient wraps string results in double quotes
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
    // Remove outer quotes and undo prin1 string escaping
    const inner = unescapeElispString(trimmed.slice(1, -1));
    return JSON.parse(inner);
  }

  // Non-string results (numbers, nil, t) — shouldn't happen with json-encode
  // but handle gracefully
  if (trimmed === "nil") return null;
  if (trimmed === "t") return true;
  if (trimmed === ":json-false") return false;
  if (trimmed === ":json-null") return null;
  const num = Number(trimmed);
  if (!isNaN(num) && isFinite(num)) return num;

  // Last resort: return raw string
  return trimmed;
}

interface EmacsclientTransportPayload {
  type: "string" | "number" | "boolean" | "null" | "printed";
  value?: string | number | boolean;
}

/**
 * Parse the base64 transport output produced by `buildTransportElisp()`.
 */
export function parseEmacsclientTransportOutput(raw: string): unknown {
  const trimmed = raw.trim();

  // Fall back to the legacy parser if we weren't given a quoted string.
  if (!trimmed.startsWith('"') || !trimmed.endsWith('"')) {
    return parseEmacsclientOutput(trimmed);
  }

  const base64 = trimmed.slice(1, -1);
  const payloadJson = Buffer.from(base64, "base64").toString("utf8");
  const payload = JSON.parse(payloadJson) as EmacsclientTransportPayload;

  switch (payload.type) {
    case "null":
      return null;
    case "number":
    case "boolean":
      return payload.value;
    case "printed":
      return payload.value ?? "";
    case "string": {
      const text = typeof payload.value === "string" ? payload.value : "";
      try {
        return JSON.parse(text);
      } catch {
        return text;
      }
    }
  }
}

/**
 * Parse an emacsclient error output. Emacs errors look like:
 *   *ERROR*: Some error message
 * or the process may exit non-zero with a message on stderr.
 */
export function parseEmacsclientError(stderr: string): string {
  const trimmed = stderr.trim();
  // Strip common prefixes
  const match = trimmed.match(/^\*?ERROR\*?:\s*(.*)/s);
  return match ? match[1].trim() : trimmed;
}

// ---------------------------------------------------------------------------
// Read tool elisp builder
// ---------------------------------------------------------------------------

/**
 * Build elisp for the custom 'read' tool.
 *
 * This generates a comprehensive elisp expression that:
 * - Opens/finds a buffer by path or name
 * - Optionally moves point
 * - Extracts a snippet of content
 * - Collects extensive metadata
 * - Optionally restores state (temp mode)
 */
export function buildReadElisp(
  name: string,
  options: {
    pos?: number;
    line?: number;
    col?: number;
    length?: number;
    lines?: number;
    temp?: boolean;
    /** When true, point is left at the end of what was read.
     *  When false (default), point is restored to its original position. */
    move?: boolean;
    span?: string;
  } = {},
  maxLength: number = 51200
): string {
  // A name is a path if it starts with /, ./, or ../
  const isPath = name.startsWith('/') || name.startsWith('./') || name.startsWith('../');
  // A buffer name containing special chars should NOT be file-associated when newly created
  const bufferHasSpecialChars = /[/*<>]/.test(name);
  const temp = options.temp ?? false;
  // move: false (default) → restore point after reading; true → leave point at content end
  const move = options.move ?? false;

  // Determine the effective length to read
  const requestedLength = options.length;
  const requestedLines = options.lines;
  const effectiveMaxLength = Math.min(maxLength, requestedLength ?? maxLength);

  // Build the elisp expression by composing smaller, verifiable chunks
  // Each chunk is a self-contained piece of Elisp that's easier to validate

  const getOrCreateBuffer = isPath ? `
    ;; Path: use find-file or find-buffer-visiting
    (or (find-buffer-visiting "${escapeElispString(name)}")
        (progn
          (setq was-new t)
          (find-file-noselect "${escapeElispString(name)}")))
  ` : `
    ;; Buffer name: get existing buffer or create new one
    (or (get-buffer "${escapeElispString(name)}")
        (progn
          (setq was-new t)
          ${bufferHasSpecialChars
            // Special chars (*, /, <, >) => create bare buffer with no file association
            ? `(get-buffer-create "${escapeElispString(name)}")`
            // Plain name => treat as relative path (as if preceded by ./)
            : `(find-file-noselect "./${escapeElispString(name)}")`
          }))
  `;
  const allSpans = `
    (when (or ${options.span !== undefined ? 't' : 'nil'}
              (not ${temp ? 't' : 'nil'}))
      ;; Get all span calculators (or use empty list as fallback)
      (let ((calculators (if (boundp 'warbo-span-calculators)
                           warbo-span-calculators
                           nil)))
        ;; Call each calculator and append results
        (apply 'append
          (mapcar (lambda (calc)
                    (condition-case err
                      (funcall calc (current-buffer))
                      (error nil)))
                  calculators))))
  `;
  const contentEnd = `
    (save-excursion
      ${requestedLines !== undefined
        ? `
            (forward-line ${requestedLines})
            (min (point) (+ content-start ${effectiveMaxLength}))`
        : `
            (min (point-max) (+ content-start ${effectiveMaxLength}))`})
  `;
  const spansJson = `
    (when selected-spans
      (let ((spans-obj nil))
        (dolist (span selected-spans)
          (let ((span-id (symbol-name (car span)))
                (span-data (cdr span)))
            (when span-data
              (let ((span-value (alist-get 'value span-data)))
                (when span-value
                  (push (cons span-id span-value) spans-obj))))))
        (when spans-obj
          (nreverse spans-obj))))
  `;

  // Span narrowing logic: narrows buffer to requested span if provided
  const spanNarrowing = options.span !== undefined ? `
    ;; If span ID is provided, try to narrow to it
    (let ((span-id "${escapeElispString(options.span)}"))
      (let ((found-span (assoc (intern span-id) all-spans)))
        (if found-span
            (let ((span-data (cdr found-span)))
              (let ((span-start (alist-get 'start span-data))
                    (span-end (alist-get 'end span-data)))
                (when (and span-start span-end)
                  (narrow-to-region span-start span-end)
                  (setq narrowed-to-span t)
                  (goto-char (point-min)))))
          ;; Span not found, set warning
          (setq span-warning (format "Span '%s' not found (maybe its content changed?), used whole buffer." span-id)))))
  ` : '';

  // Position movement: moves point to requested position/line/column
  const movePoint = `
    ${options.pos !== undefined && options.pos !== 0 ? `
    (goto-char (if (< ${options.pos} 0)
                   (max (point-min) (+ (point) ${options.pos}))
                 ${options.pos}))` : ''}
    ${(options.pos === undefined || options.pos === 0) && options.line !== undefined ? `
    (let ((target-line ${options.line}))
      (if (< target-line 0)
          (forward-line target-line)
        ;; goto-line equivalent for programmatic use
        (progn
          (goto-char (point-min))
          (forward-line (1- target-line)))))
    ${options.col !== undefined ? `(move-to-column ${options.col})` : ''}` : ''}
  `;

  // Process info extraction: reads /proc/<pid>/cmdline if available
  const processInfo = `
    (when proc
      (let ((proc-id (process-id proc)))
        (when proc-id
          (condition-case err
              (let ((cmdline-file (format "/proc/%d/cmdline" proc-id)))
                (when (file-exists-p cmdline-file)
                  (with-temp-buffer
                    (insert-file-contents-literally cmdline-file)
                    (buffer-string))))
            (error nil)))))
  `;

  // TRAMP remote extraction: determines remote host if using TRAMP
  const trampRemote = `
    (when (file-remote-p default-directory)
      (let ((method-user-host (file-remote-p default-directory 'method-user-host)))
        (when (and method-user-host
                   (string-match "^/\\\\\\\\([^:]+\\\\\\\\):" method-user-host))
          (match-string 1 method-user-host))))
  `;

  // Selected spans: filters and selects spans for output
  const selectedSpans = `
    (when (and all-spans (not ${temp ? 't' : 'nil'}) ${options.span === undefined ? 't' : 'nil'})
      (let ((selector (if (and (boundp 'warbo-span-selector)
                             (functionp warbo-span-selector))
                       warbo-span-selector
                     ;; Default: take first 3
                     (lambda (spans) (seq-take spans 3)))))
        (condition-case err
            (funcall selector all-spans)
          (error nil))))
  `;

  // Point object: final cursor position with line and column.
  // When move: false, point is restored to original-point after reading, so we
  // report that restored position rather than where the reading happened to leave
  // point mid-expression.
  const pointObj = !move ? `
    (save-excursion
      (goto-char original-point)
      (list
        (cons "pos" (point))
        (cons "line" (line-number-at-pos))
        (cons "col" (current-column))))
  ` : `
    (list
      (cons "pos" (point))
      (cons "line" (line-number-at-pos))
      (cons "col" (current-column)))
  `;

  // Process object: buffer process information if present
  const processObj = `
    (if proc
      (list
        (cons "state" (symbol-name (process-status proc)))
        (cons "cmd" (or proc-info "")))
      nil)
  `;

  const region = `
    (if region-active
      (list
       (cons "content" region-content)
       (cons "truncated" (if region-truncated t :json-false))
       (cons "start" (list
                      (cons "pos" (region-beginning))
                      (cons "line" (line-number-at-pos (region-beginning)))
                      (cons "col" (save-excursion
                                   (goto-char (region-beginning))
                                   (current-column)))))
       (cons "end" (list
                    (cons "pos" (region-end))
                    (cons "line" (line-number-at-pos (region-end)))
                    (cons "col" (save-excursion
                                 (goto-char (region-end))
                                 (current-column))))))
     nil)
  `;
  const got = `
    (list
      (cons "content" content)
      (cons "length" content-length)
      (cons "lines" content-line-count)
      (cons "start" (list
                     (cons "pos" content-start)
                     (cons "line" (line-number-at-pos content-start))
                     (cons "col" (save-excursion
                                  (goto-char content-start)
                                  (current-column)))))
      (cons "end" (list
                   (cons "pos" content-end)
                   (cons "line" (line-number-at-pos content-end))
                   (cons "col" (save-excursion
                                (goto-char content-end)
                                (current-column)))))
      (cons "truncated" (if truncated t :json-false)))
  `;
  const result = `
    (delq nil (list
      (cons "name" (buffer-name))
      (cons "path" (buffer-file-name))
      (cons "exists" (if (buffer-file-name)
                        (if (file-exists-p (buffer-file-name)) t :json-false)
                      nil))
      (cons "unsaved" (if (buffer-modified-p) t :json-false))
      (cons "outdated" (if (buffer-file-name)
                          (if (not (verify-visited-file-modtime (current-buffer))) t :json-false)
                        :json-false))
      (cons "size" (buffer-size))
      (cons "lines" (count-lines (point-min) (point-max)))
      (cons "mode" (symbol-name major-mode))
      (cons "eglot" (if (bound-and-true-p eglot--managed-mode) t :json-false))
      (cons "ts" (if (and (fboundp 'treesit-available-p)
                        (treesit-available-p)
                        (fboundp 'treesit-language-at)
                        (treesit-language-at (point)))
                    t :json-false))
      (cons "tramp" tramp-remote)
      (cons "new" (if was-new t :json-false))
      (cons "dead" :json-false)  ;; Will update if we kill the buffer
      (cons "process" ${processObj})
      (cons "point" ${pointObj})
      (cons "region" ${region})
      (cons "got" ${got})
      ;; Add spans if present and not empty
      ${options.span === undefined && !temp ? `
      (when (and spans-json (> (length spans-json) 0))
        (cons "spans" spans-json))` : 'nil'}
      ;; Add warning if span was not found
      ${options.span !== undefined ? `
      (when span-warning
        (cons "warning" span-warning))` : 'nil'}))
  `;
  return `(json-encode
  (let* (;; Track whether buffer was newly opened
         (was-new nil)
         ${!move ? `;; Track original point; restored after reading when move is nil
         (original-point nil)` : `;; move is non-nil — point advances to content-end, no restore needed`}
         ;; Get or create the buffer
         (buf ${getOrCreateBuffer}))
    (with-current-buffer buf
      ${!move ? '(setq original-point (point))' : ''}
      ;; Handle span functionality
      (let* ((all-spans ${allSpans})
             (narrowed-to-span nil)
             (span-warning nil))
        ${spanNarrowing}
      ;; Move point if requested
      ${movePoint}

      (let* (;; Calculate content boundaries
             (content-start (point))
             (content-end ${contentEnd})
             ;; Extract content
             (content (buffer-substring-no-properties content-start content-end))
             (content-length (length content))
             (content-line-count (with-temp-buffer
                                   (insert content)
                                   (count-lines (point-min) (point-max))))
             ;; Check if truncated: more content available beyond what we extracted
             (truncated (< content-end (point-max)))
             ;; Get process info
             (proc (get-buffer-process (current-buffer)))
             (proc-info ${processInfo})
             ;; Get TRAMP remote
             (tramp-remote ${trampRemote})
             ;; Select spans for output (only if not in temp mode and no span parameter)
             (selected-spans ${selectedSpans})
             ;; Convert selected spans to JSON object format
             (spans-json ${spansJson})
             ;; Advance point to content-end when move is non-nil; otherwise nil
             ;; (point will be restored to its saved position after the let*).
             (_ ${move ? '(goto-char content-end)' : 'nil'})
             ;; Get region info if active (evaluated after point move, so bounds
             ;; and content are consistent with the current region state).
             (region-active (use-region-p))
             (region-content (when region-active
                              (buffer-substring-no-properties
                               (region-beginning)
                               (min (region-end) (+ (region-beginning) ${maxLength})))))
             (region-truncated (when region-active
                                (> (- (region-end) (region-beginning)) ${maxLength})))
             ;; Build result object (filter out nil values for optional fields)
             (result ${result}))
        ;; Widen if we narrowed to a span
        (when narrowed-to-span
          (widen))
        ${!move ? `
        ;; Restore original point position (move: false is the default).
        (when original-point
          (goto-char original-point))` : ''}
        ${temp ? `
        ;; Kill buffer if it was newly created (temp mode: buffer lifecycle cleanup).
        (when was-new
          (kill-buffer buf)
          ;; Update the dead flag in result
          (setf (alist-get "dead" result nil nil 'equal) t))` : ''}
        result)))))`
}

// ---------------------------------------------------------------------------
// Write tool elisp builder
// ---------------------------------------------------------------------------

/**
 * Build elisp for the custom 'write' tool.
 *
 * This generates an elisp expression that:
 * - Opens/finds a buffer by path or name
 * - Optionally clears the buffer contents
 * - Optionally moves point to a specific position
 * - Optionally inserts text at that position
 * - Optionally simulates a sequence of key presses
 * - Optionally saves the buffer
 * - Optionally restores state (temp mode)
 * - Returns metadata about the operation
 */
export function buildWriteElisp(
  name: string,
  insert: string | undefined,
  options: {
    pos?: number;
    line?: number;
    point?: boolean;
    save?: boolean;
    temp?: boolean;
    replace?: boolean;
    type?: string;
  } = {}
): string {
  // Validate ambiguous position parameters
  const positionParams = [
    options.pos !== undefined,
    options.line !== undefined,
    options.point !== undefined
  ];
  const positionParamCount = positionParams.filter(Boolean).length;

  if (positionParamCount > 1) {
    throw new Error(
      "Ambiguous position parameters: only one of 'pos', 'line', or 'point' can be specified"
    );
  }

  // Validate replace parameter conflicts
  if (options.replace && positionParamCount > 0) {
    throw new Error(
      "Conflicting parameters: 'replace' makes 'pos', 'line', and 'point' meaningless"
    );
  }

  // A name is a path if it starts with /, ./, or ../
  const isPath = name.startsWith('/') || name.startsWith('./') || name.startsWith('../');
  // A buffer name containing special chars should NOT be file-associated when newly created
  const bufferHasSpecialChars = /[/*<>]/.test(name);
  const temp = options.temp ?? false;
  const save = options.save ?? true;

  // Build the elisp expression with save-excursion wrapper if temp is true
  const mainBody = `
    (with-current-buffer buf
      ;; Clear buffer if replace is requested
      ${options.replace ? `
      (delete-region (point-min) (point-max))
      (goto-char (point-min))` : ''}
      ;; Move point if requested
      ${options.pos !== undefined ? `
      (goto-char (if (< ${options.pos} 0)
                     (max (point-min) (+ (point-max) ${options.pos + 1}))
                   ${options.pos}))` : ''}
      ${options.pos === undefined && options.line !== undefined ? `
      ;; goto-line equivalent for programmatic use
      (let ((target-line ${options.line}))
        (if (< target-line 0)
            ;; Negative line: go to end and move back
            (progn
              (goto-char (point-max))
              (forward-line target-line))
          ;; Positive line: go to start and move forward (goto-line equivalent)
          (progn
            (goto-char (point-min))
            (forward-line (1- target-line)))))` : ''}
      ;; Insert text at current point (if provided)
      ${insert !== undefined ? `(insert "${escapeElispString(insert)}")` : ''}
      ;; Execute keyboard macro (if provided)
      ${options.type !== undefined ? `(execute-kbd-macro (kbd "${escapeElispString(options.type)}"))` : ''}
      ;; Save buffer if requested
      ${save ? `
      (when (buffer-file-name)
        (save-buffer))` : ''}
      ;; Build result object
      (list
       (cons "name" (buffer-name))
       (cons "path" (buffer-file-name))
       (cons "length" (point-max))
       (cons "outdated" (if (buffer-file-name)
                          (if (not (verify-visited-file-modtime (current-buffer))) t :json-false)
                        :json-false))
       (cons "saved" (if (buffer-modified-p) :json-false 't))
       (cons "new" (if was-new t :json-false))
       (cons "dead" :json-false)
       (cons "point" (list
         (cons "pos" (point))
         (cons "line" (line-number-at-pos))
         (cons "col" (current-column))))))`;

  return `(json-encode
  (let* (;; Track whether buffer was newly opened
         (was-new nil)
         ;; Get or create the buffer
         (buf ${isPath ? `
                  ;; Path: use find-file or find-buffer-visiting
                  (or (find-buffer-visiting "${escapeElispString(name)}")
                      (progn
                        (setq was-new t)
                        (let ((parent (file-name-directory (expand-file-name "${escapeElispString(name)}"))))
                          (when parent (make-directory parent t)))
                        (find-file-noselect "${escapeElispString(name)}")))` : `
                  ;; Buffer name: get existing buffer or create new one
                  (or (get-buffer "${escapeElispString(name)}")
                      (progn
                        (setq was-new t)
                        ${bufferHasSpecialChars
                          // Special chars => create bare buffer with no file association
                          ? `(get-buffer-create "${escapeElispString(name)}")`
                          // Plain name => treat as relative path (as if preceded by ./)
                          : `(find-file-noselect "./${escapeElispString(name)}")`
                        }))`})
         ;; Perform the operation and get result
         (result ${temp ? `(save-excursion${mainBody})` : mainBody}))
    ;; In temp mode, kill newly created buffers
    ${temp ? `
    (when was-new
      (kill-buffer buf)
      ;; Update the dead flag in result
      (setf (alist-get "dead" result nil nil 'equal) t))` : ''}
    result))`;
}
