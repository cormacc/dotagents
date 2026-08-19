# Isolation check for the evaluation runs

Date: 2026-08-18. Auditor: the worker session for task 4d98c102.

## Harness

Each run was a fresh, non-interactive `claude -p` session (claude-opus-5 for all three runs, from each result envelope's `modelUsage`). Flags, verified against `claude --help` first: `--disable-slash-commands` (disables all skills), `--allowedTools "Read,Write"`, `--disallowedTools "Task,Bash,Glob,Grep,WebFetch,WebSearch"`, `--session-id <uuid>`, `--output-format json`, prompt on stdin. The first harness choice, `pi --print`, was abandoned: Anthropic refused every new headless pi session with a third-party extra-usage error while `pi auth check` reported `ready` and no other provider was authenticated.

The `home/AGENTS.md` § ASD-STE100 block was removed before the first run and git-restored after the last run. `~/.pi/agent/AGENTS.md` and the `~/.claude/CLAUDE.md` import both resolve to that live file, so the removal covered the sessions. Verified during the removal window: `grep -c ASD-STE100` on the resolved file returned 0 while the positive control `grep -c 'British English'` returned 2.

## Per-session tool-call audit

Source: each session's transcript JSONL under `~/.claude/projects/-home-cormacc-dotfiles-agents/`. Every `tool_use` item was extracted with `jq`. The full call lists are reproduced below the table. Probe pattern: `asd-ste100`. Positive control for the pattern: the incumbent's own transcript, where it matches 5 calls.

| Run | Session ID | Tool calls touching `skills/asd-ste100` | Candidate read present |
|---|---|---|---|
| govuk-style | 85de491b-fd9c-4889-b184-1f211c1e10a0 | 0 | yes: `candidates/govuk-style.md` |
| simple-english | b3259d08-c38f-4e8b-9cf6-a08b8a3ec638 | 0 | yes: `candidates/simple-english.md` |
| asd-ste100 (incumbent, exempt) | acf1f9b4-d376-4422-b0be-285f4475ee88 | 5 (2 reads under `skills/asd-ste100/`, 3 writes to its own outputs folder) | yes: `skills/asd-ste100/SKILL.md` |

Conclusion: no non-incumbent session read `skills/asd-ste100`. Each session read exactly its own candidate plus the three samples, and wrote exactly its three outputs.

## Full tool-call lists

### govuk-style

```
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/candidates/govuk-style.md
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/retro-skill.md
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/herdr-orch-readme.org
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/guidance-record.org
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/govuk-style/retro-skill.md
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/govuk-style/herdr-orch-readme.org
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/govuk-style/guidance-record.org
```

### simple-english

```
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/candidates/simple-english.md
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/retro-skill.md
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/herdr-orch-readme.org
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/guidance-record.org
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/simple-english/retro-skill.md
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/simple-english/herdr-orch-readme.org
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/simple-english/guidance-record.org
```

### asd-ste100

```
Read /home/cormacc/dotfiles/agents/skills/asd-ste100/SKILL.md
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/retro-skill.md
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/herdr-orch-readme.org
Read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/samples/guidance-record.org
Read /home/cormacc/dotfiles/agents/skills/asd-ste100/references/writing-rules.md
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/asd-ste100/retro-skill.md
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/asd-ste100/herdr-orch-readme.org
Write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/outputs/asd-ste100/guidance-record.org
```

