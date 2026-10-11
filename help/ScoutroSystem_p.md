---
page: htroot/ScoutroSystem_p.html
help: help/ScoutroSystem_p.md
title: System
package: configuration-administration
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroSystem_p.java
---

# System

*Settings → System* shows the Scoutro and YaCy versions, the peer name, the
running time and the memory in use, with links to YaCy's status, memory and
system administration pages, the built-in administrator account and the
language.

**Re-Start** and **Shutdown** moved here from the header. Both ask for
confirmation, use YaCy's `Steering.html` with its transaction token and end the
sessions of all users. A restart needs a process supervisor (container or
service) to start Scoutro again. Both actions are written to the audit log of
people (`system.restart`, `system.shutdown`).

The page is read-only otherwise and needs the administrator.
