---
page: htroot/scoutro-account.html
help: help/scoutro-account.md
title: My account
package: configuration-administration
access: signed-in
kind: page
backend_java: source/net/yacy/htroot/ScoutroAccount.java
---

# My account

`scoutro-account.html` shows the signed-in identity: user name, name, role,
collections, export right and how you signed in. Every signed-in identity can
open it; it shows no index data.

## What you can do here

| Section | What it does |
|---|---|
| Profile | Your role decides what you may do, your collections which data you see. Only an administrator changes them (*Settings → Users*). |
| Change password | Current password, new password (at least 10 characters, not the user name) and repetition. All other sessions of your account end; this session continues with a new sign-in cookie. |
| Sessions | Your open sessions with start, last use, address and browser. End one, end all others, or sign out. |

The built-in YaCy administrator changes its password on *Accounts*
(`ConfigAccounts_p.html`); tools with HTTP Digest then need the new password.
A browser sign-in with HTTP Digest has no sessions here and ends when the
browser is closed.

## API

`GET /scoutro/api/v1/auth/session`, `POST /scoutro/api/v1/auth/password`,
`GET /scoutro/api/v1/auth/sessions`, `POST /scoutro/api/v1/auth/sessions/revoke`,
`POST /scoutro/api/v1/auth/logout` (changing calls with `X-Scoutro-CSRF`).
See `docs/API.md`, "People: sign-in and accounts".
