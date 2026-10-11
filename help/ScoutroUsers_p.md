---
page: htroot/ScoutroUsers_p.html
help: help/ScoutroUsers_p.md
title: Users
package: configuration-administration
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroUsers_p.java
---

# Users

*Settings → Users* manages the people who sign in to Scoutro: their role,
their collections, their export right and their status. Agents are managed
separately under *Agents*; they are service identities with tokens, not
people. Design and permission matrix: `docs/SCOUTRO_USERS_ACCESS.md`.

## Roles

| Role | May |
|---|---|
| Research | search, chat, websites, host analysis and knowledge in the allowed collections |
| Operator | additionally crawls, discovery jobs and crawl reports in the allowed collections |
| Administrator | everything: users, agents, models, collections, knowledge-graph operations, system, YaCy's advanced tools |

Export (downloads of domain lists and graph data) is a separate right of a
Research or Operator account; administrators always have it. "All
collections" means every collection, also future ones.

## Access

| Setting | Meaning |
|---|---|
| Protected access mode | Only the listed Scoutro pages are reachable without the administrator role; native search, Solr, MCP, suggestions, chat and the YaCy pages need an administrator. Research and Operator accounts can only be created or unlocked in this mode, and the mode cannot be left while such accounts are active. Tools with the administrator password (HTTP Digest) and agents keep working. |
| Guest access | Off by default and only in protected mode: without sign-in, people may search the released collections and nothing else. |
| Built-in administrator on the login page | Whether the YaCy administrator may sign in on the login page. It can be switched off only with an active administrator account and not from the built-in administrator's own session. HTTP Digest stays available. |

Warnings: *peer-to-peer* (the peer answers searches of other YaCy peers from the
whole index; collection rights do not apply to peers), *default password* of
the built-in administrator, and *protected rules enforced* (active
restricted accounts although the setting is off).

## Accounts

- **New account:** user name (cannot be changed later), name, role,
  collections, export, and a generated temporary password. Hand the password
  over personally or through a secure channel; the person must set an own
  password at the first sign-in. No e-mail is needed.
- **Edit:** name, role, collections, export. Changing role, collections or
  export ends the account's sessions immediately.
- **Lock / Unlock:** a locked account cannot sign in; its sessions end.
- **Reset password:** a new temporary password; all sessions end.
- **End sessions**, **Remove** (with confirmation; the audit log keeps the
  entries).

The server refuses to lock, demote or remove the last administrator who can
sign in, and nobody can lock, demote or remove their own account.

## Audit log

Newest first: sign-ins (also failures), sign-outs, password changes and
resets, account and access changes, restart and shutdown, and every changing
Scoutro action of a person (route, target and result). Passwords, tokens,
cookies, document contents and search texts are never logged. Filter by a
person or target name. Stored in `DATA/SETTINGS/scoutro-user-audit.jsonl`
(newest 20,000 entries).

## API

`/scoutro/api/v1/users`, `/v1/users/{name}`, `/v1/users/{name}/password`,
`/v1/users/{name}/sessions[/revoke]`, `/v1/access`, `/v1/access/audit`
(administrator; see `docs/API.md`).
