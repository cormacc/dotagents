# nested-context

Pi loads `AGENTS.md` (or a variant) only from the working directory and its ancestors. This extension delivers the context files in directories *below* the working directory when a tool touches a path that they govern.

## Behaviour

- `read`: the extension appends each governing context file that this session has not seen to the read result.
- `edit` and `write`: if a governing context file has not been seen, the extension blocks the first call. The block reason carries the rules, and the agent then issues the call again.
- Each file is delivered once per session. After a compaction, the next touch delivers it again.
- Several nested files on the way down to a path are delivered outermost first.
- In each directory the extension takes the file that Pi would take: `AGENTS.override.md`, `AGENTS.md`, `AGENTS.MD`, `CLAUDE.md`, then `CLAUDE.MD`.

The system prompt does not change, so the provider prompt cache stays valid. Rules in a tool result can be followed less strictly than rules in the system prompt.

## Scope

A context file is delivered only when Git tracks it in the working directory's repository, or in a submodule of that repository. These files are not delivered:

- untracked or gitignored files;
- files in a nested clone or any other repository that is not a submodule;
- anything when the working directory is not in a Git repository, or when a `git` call fails.

A tracked nested file has the same source as the root `AGENTS.md`, which Pi loads without project trust, so the extension has no trust gate.

## Limits

- Only the built-in `read`, `edit` and `write` tools are covered. An edit through `bash` or another tool does not trigger delivery and is not blocked.
- A large context file is delivered whole.
- If an untracked `AGENTS.override.md` is the file that Pi would take in a directory, that directory delivers nothing.

## Tests

```sh
pi/extensions/nested-context/test.sh
```

The script type-checks the extension, runs the discovery unit tests against a fixture repository with a submodule, and runs Pi RPC integration tests with a scripted fake model.
