---
page: htroot/scoutro-forbidden.html
help: help/scoutro-forbidden.md
title: No permission
package: configuration-administration
access: public
kind: page
backend_java: source/net/yacy/htroot/ScoutroForbidden.java
---

# No permission

`scoutro-forbidden.html` is shown with status 403 when a signed-in identity
opens a page or function its role or collections do not allow, for example a
Research account on a YaCy administration page. It is the servlet container's
error page for 403 and shows no data. It offers the overview (or the search),
"My account" and a sign-in with another account.
