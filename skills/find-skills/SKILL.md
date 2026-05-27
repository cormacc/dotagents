---
name: find-skills
description: Helps users discover and install agent skills when they ask questions like "how do I do X", "find a skill for X", "is there a skill that can...", or express interest in extending capabilities. This skill should be used when the user is looking for functionality that might exist as an installable skill.
---

# Find Skills

Discover and install skills from the open agent skills ecosystem via
`npx skills` (package manager) and <https://skills.sh/> (leaderboard).

## Workflow

1. **Identify the domain + task** the user needs help with.
2. **Check the leaderboard** at <https://skills.sh/> first for popular,
   battle-tested skills in that domain.
3. **Search** if needed:
   ```bash
   npx skills find <keywords>
   ```
   Use specific keywords (e.g. `react performance`, not just `performance`).
4. **Verify quality before recommending.** A skill is only worth surfacing if:
   - Install count is meaningful (prefer 1K+; be wary of <100).
   - Source is reputable (`vercel-labs`, `anthropics`, `microsoft`, or a repo
     with substantial stars).
5. **Present** the skill with: name, what it does, install count, install
   command, link to learn more.
6. **Install on request:**
   ```bash
   npx skills add <owner/repo@skill> -g -y
   ```
   (`-g` user-level, `-y` skips confirmation.)

## When nothing fits

Tell the user no match was found, offer to help directly, and mention that
`npx skills init <name>` is the entry point for creating their own.

## Other useful commands

- `npx skills check` — list updates available
- `npx skills update` — update installed skills
