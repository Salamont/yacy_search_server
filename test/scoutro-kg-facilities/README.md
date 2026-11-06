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
