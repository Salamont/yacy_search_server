---
page: htroot/scoutro-login.html
help: help/scoutro-login.md
title: Sign in
package: configuration-administration
access: public
kind: page
backend_java: source/net/yacy/htroot/ScoutroLogin.java
---

# Sign in

`scoutro-login.html` is Scoutro's own sign-in page. It replaces the browser's
login dialog for people; tools and scripts keep using HTTP Digest
(see `docs/SCOUTRO_USERS_ACCESS.md`).

## When the page appears

- A browser opens a page that needs a sign-in without being signed in: the
  page is redirected here with `next=<the requested page>`. Only a path on this
  server is accepted as return target; anything else (another host, `//…`,
  `javascript:`) returns to the overview.
- `?signedout=1` after signing out, `?expired=1` after the session ended,
  `?change=1` when a new password is required first.
- API calls, `curl --digest`, `scoutroctl` and other tools never see this page:
  they get the Digest challenge (401) as before. The redirect can be switched
  off with `scoutro.auth.loginPage=false`.

## What you can do here

1. Sign in with a Scoutro account (user name and password).
2. Administrators can also sign in with the name and password of the built-in
   YaCy administrator (as long as *Settings → Users* allows it).
3. If the account must set a new password (new account or reset by an
   administrator), the page asks for it before anything else works: at least
   10 characters, not the user name.
4. **Browser sign-in for administrators (HTTP Digest)** opens the browser's
   login dialog instead (`scoutro-digest.html`), e.g. when the browser does not
   keep the sign-in cookie.

After signing in the page checks that the browser sent the cookie back and
then continues to the requested page.

## Messages

| Message | Meaning |
|---|---|
| User name or password is wrong. | Same answer for an unknown name and a wrong password. |
| Too many failed sign-ins … | 5 failures for a name or 30 from one address within 15 minutes; wait and try again. The account is not locked. |
| This account is locked. | An administrator locked it (shown only after the right password). |
| Your browser did not keep the sign-in. | Cookies are blocked for this site; allow them or use the browser sign-in. |
| The built-in administrator still uses the default password. | Change it on the *Accounts* page (`ConfigAccounts_p.html`). |

## Security

- The session cookie `scoutro_session` is `HttpOnly`, `SameSite=Strict` and
  `Secure` behind HTTPS. Only its hash is kept on the server; sessions end
  after 30 minutes without use, after 12 hours at the latest, with sign-out,
  and when the password, role, collections, export right or status change.
- Passwords are stored as Argon2id hashes; nothing is stored in the browser's
  local storage.
- Every sign-in and failure is written to the audit log (never the password).

## API

`POST /scoutro/api/v1/auth/login` with `{"username", "password", "next"}`,
`GET /scoutro/api/v1/auth/session`, `POST /scoutro/api/v1/auth/logout`,
`POST /scoutro/api/v1/auth/password`. See `docs/API.md`, section "People:
sign-in and accounts".
