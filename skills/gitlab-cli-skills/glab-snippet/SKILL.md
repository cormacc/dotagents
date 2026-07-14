---
name: glab-snippet
description: Create GitLab snippets with glab. Use only for explicit GitLab/glab snippet creation; the installed command does not expose view or update subcommands.
---

# glab snippet

The verified installed surface creates snippets:

```bash
glab snippet create --title "Example" --filename main.go
glab snippet create --help
glab snippet --help
```

Do not claim `glab snippet view` or `glab snippet update`. Use [`../glab-api/SKILL.md`](../glab-api/SKILL.md) when a requested snippet operation is absent from `glab snippet --help`.
