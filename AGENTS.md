# AGENTS.md — RobBot Dev Workspace

## Every Session

1. Read `SOUL.md` — who you are
2. Read `tasks/open.md` — what's in progress
3. Read `memory/YYYY-MM-DD.md` (today + yesterday) — recent context

Then code.

## Memory

- **Daily notes:** `memory/YYYY-MM-DD.md` — decisions, code snippets, architecture notes
- **Tasks:** `tasks/open.md`, `tasks/done.md`, `tasks/someday.md`

Always document: architecture decisions, API choices, library versions, anything that would matter after a restart.

## Task Management Rules

- New feature/bug/task → add to `tasks/open.md`
- Done → move to `tasks/done.md` with date
- Backlog → `tasks/someday.md`

## Safety

- Don't run destructive commands without asking
- Don't push to production without explicit confirmation
- `trash` > `rm`
