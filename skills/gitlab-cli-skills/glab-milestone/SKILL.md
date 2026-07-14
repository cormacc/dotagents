---
name: glab-milestone
description: Manage GitLab milestones through glab api when explicit GitLab/glab milestone work is requested. The installed glab build has no milestone command group.
---

# GitLab milestones through glab api

Verify first:

```bash
glab version
glab milestone --help  # expected to fail when the group is unavailable
```

For the verified installed build, use the GitLab REST API through `glab api`:

```bash
glab api projects/:id/milestones
glab api projects/:id/milestones/<milestone-id>
glab api --method POST projects/:id/milestones --field title='Release 1.0'
glab api --method PUT projects/:id/milestones/<milestone-id> --field state_event=close
```

Load [`../glab-api/SKILL.md`](../glab-api/SKILL.md) before mutations. Do not claim `glab milestone create`, list, edit, get, view, update, or close unless `glab milestone --help` on the active installation actually exposes them.
