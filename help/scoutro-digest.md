---
page: htroot/scoutro-digest.html
help: help/scoutro-digest.md
title: Browser sign-in
package: configuration-administration
access: admin
kind: page
backend_java: source/net/yacy/htroot/ScoutroDigest.java
---

# Browser sign-in (HTTP Digest)

`scoutro-digest.html` is the fallback of the login page for the YaCy
administrator. Opening it shows the browser's own login dialog (HTTP Digest
with the built-in administrator's name and password). After a successful
sign-in it continues to `next` (only a path on this server, otherwise the
overview).

Use it when the browser does not keep the Scoutro sign-in cookie, for example
inside a frame of another site. The browser keeps the Digest sign-in until it
is closed; there is no sign-out for it. Research and Operator accounts cannot
use this page.

The page has no parameters other than `next` and changes nothing.
