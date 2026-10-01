# Administration navigation

The shared `htroot/env/templates/header.template` contains the existing YaCy
administration sidebar. Its first group is **Scoutro → Dashboard**, linking to
`scoutro-dashboard.html`, a protected overview of the local index, collections,
crawler and JVM. See [Scoutro Dashboard](../../scoutro-dashboard.md) for metric
definitions and standard detail destinations.

On mobile, the existing hamburger (`scoutro-nav-toggle`) opens the same
`scoutro-adminnav` panel, including Dashboard. The existing navigation groups,
visibility rules and administrator lock indicators continue to apply. No
configuration or system action is performed by opening the dashboard.
