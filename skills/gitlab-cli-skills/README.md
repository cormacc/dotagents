# GitLab CLI skill router

This directory contains the top-level `gitlab-cli-skills` router plus nested GitLab/`glab` command-group references.

Only [`SKILL.md`](SKILL.md) is intended for global discovery. It names every retained child path and loads only the group relevant to an explicit GitLab/`glab` request; nested skills are not enabled as independent broad triggers.

The parent also owns basic diagnostics:

```bash
glab --help
glab <command> --help
glab version
glab check-update
```

The installed command help is authoritative. Some nested references describe API-backed or version-dependent GitLab domains; verify the active `glab` surface before use and route missing groups through [`glab-api/SKILL.md`](glab-api/SKILL.md).

## Requirements

- GitLab CLI (`glab`)
- Authentication from `glab auth login` or an explicitly selected token environment

See the parent skill for identity pre-flight and write-safety requirements.
