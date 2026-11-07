# Synthetic facility fixtures (knowledge graph, first quality stage)

Invented pages of an invented care provider ("Muster Altenhilfe gGmbH", `www.muster-altenhilfe.de`) with three facilities, its
imprint, an overview page and a news page, plus control pages of other fields (an IT company's contact page, a builder's
imprint). No real organisation, person, address or phone number. Read by `FacilityPageTest`, `OperatorContactRegressionTest` (tiers 1 and 2 on YaCy's own HTML
parser) and `FacilityGraphTest` (embedded Solr, the real sync, a controlled model, a temporary graph): nothing reaches a model,
a network service or a production graph.

| File | What it covers |
|---|---|
| `care-operator-imprint.html` | the operator's imprint: name, address with a `<br>` (a dot between number and postal code in YaCy's text), phone, role mailbox, register, VAT ID; a person (managing director) that must not be kept |
| `care-facility-lindenhof.html` | a facility's detail page (`/einrichtungen/haus-lindenhof/`): its own address, phone and mailbox, a contact person with a personal mailbox, office hours; the operator's address in the footer; "Impressum" only as a menu and footer link |
| `care-facility-am-markt.html` | a facility at "Am Markt 5" (a street named by its square) |
| `care-facility-am-see.html` | a facility whose address is written in one line; read under two URLs (with and without a trailing slash) |
| `care-facilities-overview.html` | the list of the provider's facilities with their addresses (the LLM tier names them) |
| `care-contact.html` | the provider's contact page listing two facilities with their addresses and phones: no address of it is the operator's |
| `care-news.html` | a news page naming a facility without its address |
| `software-contact.html`, `construction-imprint.html` | other fields: their contacts must stay as they were or improve |
| `org-home-footer-blocks.html`, `org-about-footer-breaks.html`, `org-contact-hq-branches.html` | K1 of the validation on the production graph: the operator's footer after a link "Impressum", in elements of their own, with line breaks (register, VAT ID) and in one line beside a headquarters and two branches; the operator keeps its own block and gets no branch |
| `care-news-carrier.html` | a sentence about a house the operator runs ("ist Träger des neuen Hauses …"): the operator gets its footer, not the house |
| `sun-*.html` | a second provider ("Sonnen Pflege gGmbH", `www.sonnen-pflege.de`) with two houses of one name in two towns, its imprint naming a house before the operator's address (K2), an imprint in a page's footer, a news page whose town and operator the model takes for facilities and an unclear name (K6); read by `PlaceAddressRegressionTest` and `FacilityRegressionGraphTest` |

## rule/12: the pages of the four portals' fields (section 12 of `SCOUTRO_KG_FACILITIES.md`)

Read by `PortalRegressionTest`. Invented pages of a construction company, an IT company, a coaching provider and the care provider
above, after the patterns of the validation with the full production data; no real organisation, person, address or phone number.

| File | What it covers |
|---|---|
| `construction-footer-no-name.html` | K1: a services page whose footer holds the address, phone, fax and mailbox without the name before them; the name stands in the text and the copyright line |
| `construction-service-page.html` | F3: "Rollladen und Raffstore", a service with the company's footer: no facility; the operator keeps its footer block |
| `construction-project-page.html` | F3: "Bürgerhaus Musterstadt", a project under a dated path and under `/projekte/`: no facility |
| `construction-branch-page.html` | F3: "Niederlassung Nord" under `/standorte/` with its own address, phone and mailbox: a facility; the operator keeps its own footer, nothing of the branch |
| `software-office-page.html` | F3: an office page under `/standorte/` headed by its town ("München") with its address: a facility named by its town |
| `software-team-page.html`, `software-directions-page.html` | F3: "Mitarbeiter" and "So finden Sie uns" with the company's address: no facility, no person |
| `coaching-profile.html` | K1 and F3: a coach's profile page ("Über mich", "Mein Angebot") with a footer without the name: no facility, the operator's contacts, no person |
| `coaching-location.html` | F3: "Praxis Hamburg-Altona" under `/standorte/` with its own address: a facility; the operator keeps its footer |
| `care-category-page.html` | F3: "Soziotherapeutische Einrichtungen", a category heading of the care provider: no facility |
| `care-news-dated.html` | K1 and F3: a dated article naming a house with its address: no facility, the operator gets its own footer only |
| `org-privacy-processors.html` | F4: a privacy notice with its controller and three processors (newsletter, hosting, analytics): the controller is the operator, no processor is |
| `org-privacy-link-only.html` | F4: a privacy notice without a controller, with a link to the imprint and two providers named: nobody is the operator |
| `org-imprint-towns.html` | addresses: a town of two words with a prefix ("Bad Rappenau") and the sentence after it; "St. Ingbert" |
| `software-booking-notice.html` | request 10: a booking tool's notice ("Anbieter ist die Terminwerk Service GmbH, …") on a services page names no operator; the copyright line's company is it and gets the page's only phone and mailbox |
| `org-privacy-brand-contact.html` | request 10: a privacy notice without a controller's marker; the site's own company heads the footer's contact block (address, phone, fax, mailbox), the analytics and hosting providers are none |
| `org-privacy-controller-sentence.html` | request 10: "Verantwortlicher im Sinne der Datenschutzgesetze … ist:" opens the controller's block (a town "Bad Beispielheim"); a reCAPTCHA provider after it is none |
| `org-imprint-privacy-combined.html` | request 10: a combined page `/impressum-datenschutz/` is an imprint: register, VAT ID, address and contacts are the operator's, the processors of the privacy part are none |
| `software-imprint-lowercase-brand.html` | request 10: an imprint whose operator writes its name in lower case ("uib-beispiel GmbH"), with a hosting provider's section after it |
| `construction-footer-formats.html` | request 10: a footer in YaCy's text: "Tel.:. 0441 – 2197300" (label in its own element, a dash in the number), "Ofener Str.30" (the number right after the abbreviation) |
| `construction-two-offices.html` | request 10: two offices in one block after the name: the first address and its phone are the operator's, the page's only mailbox too; the second office's values are not guessed |
| `construction-partner-page.html` | request 10: a partner page whose partners' contact blocks come first; the operator's name is the site's and stands in the footer |
| `care-directory-list.html` | request 10: a list of services with their contact blocks (a clinic, a UG, offices): none is the operator; the association of the copyright line is, named without its quotes |
| `construction-footer-mini-imprint.html` | request 10: a footer's own small imprint ("Inhaltlich verantwortlich gemäß § 6 TDG: …, HRA 535/10") names persons only; the company of the contact box, vouched for by the copyright line, is the operator |
