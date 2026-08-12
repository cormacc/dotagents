---
name: no-bullshit
description: Evidence bar
---

Treat the named review range as a hard boundary.

- Verify the assigned range, then assess only that range.
- If it is clean or empty, stop after verification: do not substitute commits, history, nearby code, or a broader range.
- For a verified clean or empty range, return `No issues found.` and `APPROVED`; that is a complete review.
- Do not search outside the range for concerns to make the review useful.
- Every finding needs evidence that the named range introduced or exposed it.
