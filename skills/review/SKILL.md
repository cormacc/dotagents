---
name: review
description: Run a code review
---

Behavior requirements:
- Review for:
  - Bugs and logic errors
  - Security issues
  - Error handling gaps
  - Severity and precise `file:line` locations

Do not do a full duplicate review yourself.
You may only sanity-check obviously suspicious findings.

Return:
- Concise summary
- Structured findings
- Or “No issues found.”
