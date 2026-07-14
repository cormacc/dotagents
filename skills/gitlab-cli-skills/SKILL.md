---
name: gitlab-cli-skills
description: GitLab CLI (glab) command reference and workflows; routes to sub-skills for auth, CI, MRs, issues, releases, repos, and 30+ other glab commands. Use for glab, GitLab CLI/terminal/automation, or MR/issue/pipeline operations via CLI.
metadata: {"openclaw": {"requires": {"bins": ["glab"], "anyBins": ["cosign"]}, "install": [{"id": "brew", "kind": "brew", "formula": "glab", "bins": ["glab"], "label": "Install glab (brew)"}, {"id": "download", "kind": "download", "url": "https://gitlab.com/gitlab-org/cli/-/releases", "label": "Download glab binary"}]}}
requirements:
  binaries:
    - glab
  binaries_optional:
    - cosign
  notes: |
    Requires GitLab authentication via 'glab auth login' (stores token in ~/.config/glab-cli/config.yml).
    Some features may access sensitive files: SSH keys (~/.ssh/id_rsa for DPoP), Docker config (~/.docker/config.json for registry auth).
    Review auth workflows and script contents before autonomous use.
openclaw:
  requires:
    credentials:
      - name: GITLAB_TOKEN
        description: >
          GitLab personal access token with 'api' scope. Used by automation
          scripts (e.g. post-inline-comment.py) to post MR comments via the
          REST API. If not set, scripts fall back to reading the token from
          glab CLI config (~/.config/glab-cli/config.yml).
        required: false
        fallback: glab config (set via glab auth login)
    network:
      - description: Outbound HTTPS to your GitLab instance (default https://gitlab.com)
        scope: authenticated API calls only; HTTPS enforced; token never sent over HTTP
    write_access:
      - description: >
          Scripts in this skill can post comments, resolve threads, and approve
          merge requests on your behalf. Review scripts/post-inline-comment.py
          before use in automated or agentic contexts.
---

# GitLab CLI Skills

Comprehensive GitLab CLI (glab) command reference and workflows.

## Quick start

```bash
# First time setup
glab auth login

# Common operations
glab mr create --fill              # Create MR from current branch
glab issue create                  # Create issue
glab ci view                       # View pipeline status
glab repo view --web              # Open repo in browser
```

## Multi-agent identity note

When you want different agents to appear as different GitLab users, give each agent its own GitLab bot/service account. Multiple personal access tokens on the same GitLab user still act as that same visible identity.

Use the **Actor identity** for actor-authored GitLab comments, replies, approvals, and other writes. Use an **agent identity** only when the GitLab action is explicitly that agent's own work product. Choose the intended visible actor **before the first GitLab write**.

Treat shell identity as sticky and unsafe by default. If another env file was sourced earlier in the same shell/session, `glab` may still write as that previously loaded identity unless you deliberately switch and verify first.

A practical pattern is one env file per actor, for example `~/.config/openclaw/env/gitlab-actor.env`, `~/.config/openclaw/env/gitlab-reviewer.env`, and `~/.config/openclaw/env/gitlab-release.env`. Keep these env files outside version control, restrict their permissions (for example `chmod 600`), be mindful of backup exposure, and use least-privilege bot/service-account tokens. In a reused shell, clear stale GitLab auth vars first or start a fresh shell. If those files use plain `KEY=value` lines, load them with exported vars before running `glab`:

```bash
unset GITLAB_TOKEN GITLAB_ACCESS_TOKEN OAUTH_TOKEN GITLAB_HOST
set -a
source ~/.config/openclaw/env/gitlab-<actor>.env
set +a
```

Plain `source` updates the current shell but may not export variables to child processes such as `glab`. If the token/host vars are not exported, `glab` may silently fall back to shared stored auth from `~/.config/glab-cli/config.yml`, which can make the wrong account appear to perform the action.

### Required pre-flight before any GitLab write

Run this immediately before any GitLab write, including `glab mr note`, review replies/approvals, and any `glab api` `POST`/`PATCH`/`PUT`/`DELETE` call:

```bash
glab auth status --hostname "$GITLAB_HOST"
glab api --hostname "$GITLAB_HOST" user
```

This assumes the target actor env file set `GITLAB_HOST` for the exact GitLab instance you intend to modify. Do not write until both commands clearly show the intended visible actor on that host.

### Wrong-identity remediation

If a comment or reply was posted under the wrong identity:

1. Stop posting.
2. Delete the mistaken comment or reply if cleanup is needed.
3. `unset GITLAB_TOKEN GITLAB_ACCESS_TOKEN OAUTH_TOKEN GITLAB_HOST` or start a fresh shell.
4. Source the correct env file with `set -a; source ...; set +a`.
5. Rerun `glab auth status --hostname "$GITLAB_HOST"` and `glab api --hostname "$GITLAB_HOST" user`.
6. Repost under the correct actor.
7. Verify the thread no longer shows the wrong visible author for the replacement message.

If the wrong-identity write changed state beyond a comment or reply, do not treat the comment cleanup steps as sufficient. Re-auth as above, then use the matching GitLab reversal for that write under the correct actor and host, such as unapproving an MR or sending the compensating `glab api --hostname "$GITLAB_HOST"` mutation for the exact resource that was changed.

## Routing to command groups

Only this parent is globally discovered. When a request names a GitLab/`glab` domain, read exactly the matching relative skill below; do not load every child. If a requested group is absent from `glab --help`, verify `glab <group> --help` and use [`glab-api/SKILL.md`](glab-api/SKILL.md) rather than inventing a command.

Selection rules:

- Authentication or CLI setup → [`glab-auth/SKILL.md`](glab-auth/SKILL.md), [`glab-config/SKILL.md`](glab-config/SKILL.md), [`glab-ssh-key/SKILL.md`](glab-ssh-key/SKILL.md), [`glab-gpg-key/SKILL.md`](glab-gpg-key/SKILL.md), [`glab-token/SKILL.md`](glab-token/SKILL.md).
- Merge requests, issues, work items, incidents, or quick actions → [`glab-mr/SKILL.md`](glab-mr/SKILL.md), [`glab-issue/SKILL.md`](glab-issue/SKILL.md), [`glab-workitems/SKILL.md`](glab-workitems/SKILL.md), [`glab-incident/SKILL.md`](glab-incident/SKILL.md), [`glab-quick-actions/SKILL.md`](glab-quick-actions/SKILL.md).
- Pipelines or jobs → start with [`glab-ci/SKILL.md`](glab-ci/SKILL.md); load [`glab-job/SKILL.md`](glab-job/SKILL.md) only for artifact download or the CI-owned job operations it routes to.
- CI configuration or infrastructure → [`glab-schedule/SKILL.md`](glab-schedule/SKILL.md), [`glab-variable/SKILL.md`](glab-variable/SKILL.md), [`glab-securefile/SKILL.md`](glab-securefile/SKILL.md), [`glab-runner/SKILL.md`](glab-runner/SKILL.md), [`glab-runner-controller/SKILL.md`](glab-runner-controller/SKILL.md), [`glab-cluster/SKILL.md`](glab-cluster/SKILL.md), [`glab-opentofu/SKILL.md`](glab-opentofu/SKILL.md).
- Repository/project operations → [`glab-repo/SKILL.md`](glab-repo/SKILL.md), [`glab-deploy-key/SKILL.md`](glab-deploy-key/SKILL.md), [`glab-label/SKILL.md`](glab-label/SKILL.md), [`glab-milestone/SKILL.md`](glab-milestone/SKILL.md), [`glab-iteration/SKILL.md`](glab-iteration/SKILL.md).
- Releases and project history → [`glab-release/SKILL.md`](glab-release/SKILL.md), [`glab-changelog/SKILL.md`](glab-changelog/SKILL.md), [`glab-attestation/SKILL.md`](glab-attestation/SKILL.md).
- User collaboration → [`glab-user/SKILL.md`](glab-user/SKILL.md), [`glab-snippet/SKILL.md`](glab-snippet/SKILL.md), [`glab-todo/SKILL.md`](glab-todo/SKILL.md).
- Advanced MR structure → [`glab-stack/SKILL.md`](glab-stack/SKILL.md).
- Direct/custom API operation → [`glab-api/SKILL.md`](glab-api/SKILL.md).
- CLI customization → [`glab-alias/SKILL.md`](glab-alias/SKILL.md), [`glab-completion/SKILL.md`](glab-completion/SKILL.md).
- GitLab AI integrations → [`glab-duo/SKILL.md`](glab-duo/SKILL.md), [`glab-mcp/SKILL.md`](glab-mcp/SKILL.md).

Parent-owned diagnostics replace the former trivial help/version/update skills:

```bash
glab --help                    # command groups
glab <command> --help          # installed syntax and subcommands
glab version                   # installed build
glab check-update              # release update check
```

Treat the installed `glab` help as authoritative when captured child references differ. These nested descriptions are routing metadata, not independently enabled global triggers; do not enable full-depth discovery.

## When to use glab vs web UI

**Use glab when:**
- Automating GitLab operations in scripts
- Working in terminal-centric workflows
- Batch operations (multiple MRs/issues)
- Integration with other CLI tools
- CI/CD pipeline workflows
- Faster navigation without browser context switching

**Use web UI when:**
- Complex diff review with inline comments
- Visual merge conflict resolution
- Configuring repo settings and permissions
- Advanced search/filtering across projects
- Reviewing security scanning results
- Managing group/instance-level settings

## Common workflows

### Daily development

```bash
# Start work on issue
glab issue view 123
git checkout -b 123-feature-name

# Create MR when ready
glab mr create --fill --draft

# Mark ready for review
glab mr update --ready

# Merge after approval
glab mr merge --when-pipeline-succeeds --remove-source-branch
```

### Code review

```bash
# List your review queue
glab mr list --reviewer=@me --state=opened

# Review an MR
glab mr checkout 456
glab mr diff
npm test

# Approve
glab mr approve 456
glab mr note 456 -m "LGTM! Nice work on the error handling."
```

### CI/CD debugging

```bash
# Check pipeline status
glab ci status

# View failed jobs
glab ci view

# Get job logs
glab ci trace <job-id>

# Retry failed job
glab ci retry <job-id>
```

## Decision Trees

### "Should I create an MR or work on an issue first?"

```
Need to track work?
├─ Yes → Create issue first (glab issue create)
│         Then: glab mr for <issue-id>
└─ No → Direct MR (glab mr create --fill)
```

**Use `glab issue create` + `glab mr for` when:**
- Work needs discussion/approval before coding
- Tracking feature requests or bugs
- Sprint planning and assignment
- Want issue to auto-close when MR merges

**Use `glab mr create` directly when:**
- Quick fixes or typos
- Working from existing issue
- Hotfixes or urgent changes

### "Which CI command should I use?"

```
What do you need?
├─ Overall pipeline status → glab ci status
├─ Visual pipeline view → glab ci view
├─ Specific job logs → glab ci trace <job-id>
├─ Download build artifacts → glab ci artifact <ref> <job-name>
├─ Validate config file → glab ci lint
├─ Trigger new run → glab ci run
└─ List all pipelines → glab ci list
```

**Quick reference:**
- Pipeline-level: `glab ci status`, `glab ci view`, `glab ci run`
- Job-level: `glab ci trace`, `glab ci retry`, `glab ci cancel job`, `glab job artifact`
- Artifacts: `glab ci artifact` (by pipeline) or job artifacts via `glab job`

### "Clone or fork?"

```
What's your relationship to the repo?
├─ You have write access → glab repo clone group/project
├─ Contributing to someone else's project:
│   ├─ One-time contribution → glab repo fork + work + MR
│   └─ Ongoing contributions → glab repo fork, then sync regularly
└─ Just reading/exploring → glab repo clone (or view --web)
```

**Fork when:**
- You don't have write access to the original repo
- Contributing to open source projects
- Experimenting without affecting the original
- Need your own copy for long-term work

**Clone when:**
- You're a project member with write access
- Working on organization/team repositories
- No need for a personal copy

### "Project vs group labels?"

```
Where should the label live?
├─ Used across multiple projects → glab label create --group <group>
└─ Specific to one project → glab label create (in project directory)
```

**Group-level labels:**
- Consistent labeling across organization
- Examples: priority::high, type::bug, status::blocked
- Managed centrally, inherited by projects

**Project-level labels:**
- Project-specific workflows
- Examples: needs-ux-review, deploy-to-staging
- Managed by project maintainers

## Related Skills

**MR and Issue workflows:**
- Start with `glab-issue` to create/track work
- Use `glab-mr` to create MR that closes issue
- Script: `scripts/create-mr-from-issue.sh` automates this

**CI/CD debugging:**
- Use `glab-ci` for pipeline-level operations
- Use `glab-job` for individual job operations
- Script: `scripts/ci-debug.sh` for quick failure diagnosis

**Repository operations:**
- Use `glab-repo` for repository management
- Use `glab-auth` for authentication setup
- Script: `scripts/sync-fork.sh` for fork synchronization

**Configuration:**
- Use `glab-auth` for initial authentication
- Use `glab-config` to set defaults and preferences
- Use `glab-alias` for custom shortcuts
