/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import java.util.*;
import java.util.regex.*;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Signals;

/** Contextual, deterministic input facts for future matching, never matching rules.
 * Sentences retain their qualifiers. Ambiguous or overlong sentences are skipped,
 * rather than turning a truncated product mention into installed software.
 * Version 3 (docs/SCOUTRO_MATCHING.md, "Context correction"): markers inside a product name
 * (SAP In-House Cash) or another word (house, cooperate) are no actor or use; a reference page
 * describes client work; a company's competence statement is no requirement; a statement that
 * names another company with an unnamed "we" leaves the actor unclear. The version is no part
 * of an extractor identity, so it re-extracts nothing by itself; matching re-reads stored quotes.
 */
public final class BusinessSignals {
    /** The patterns of the inline expressions, compiled once (0.11.8: they were compiled again on every read of a quote). */
    private static final java.util.concurrent.ConcurrentHashMap<String,Pattern> PATTERNS=new java.util.concurrent.ConcurrentHashMap<>();
    public static final String VERSION="7"; // 4: own use in the clause of its verb, questions, others' use, delivery verbs (0.12.2);
    // 5: a planned migration in the clause of its verb, its product and the company (0.13.0); 6: an own offer is no use, an alternative
    // to a product names no use of it, the reader's lines of an FAQ (0.14.0); 7: an offer with the reader or others as the user (0.14.1)
    private static final Pattern CUSTOMERS=p("Kundenprojekt|Bestandskunden|unser(?:e[nrms]?)? Kunde|bei (?:unseren? )?Kunden|für (?:unsere )?Kunden|client projects?|customer projects?");
    private static final Pattern KNOWLEDGE=p("Kenntnisse|Kenntnissen|Erfahrung|Erfahrungen|Kompetenz|beherrschen|knowledge|skills?|proficiency|experience");
    private static final Pattern DESIRED=p("wünschenswert|erwünscht|von Vorteil|idealerweise|optional|desirable|preferred|nice.to.have");
    /** 0.12.0: "setzen … ein" with the particle at the clause's end (not the article of "setzen auf ein …"), "arbeiten mit" as words. */
    private static final Pattern USE=p("nutzen|nutzt|verwenden|verwendet|betreiben|betreibt|setzen\\b[^.!?;:]{0,60}?\\bein\\b(?=\\s*(?:[.,;:!?)]|$))"
            +"|\\barbeiten\\b.{0,20}\\bmit\\b|\\buses?\\b(?![\\s-]*cases?\\b)|\\boperate\\b|\\brunning\\b");
    /**
     * 0.12.2: a use verb of the company's own use: the German verbs in lower case (the noun "Nutzen" is no use: "der
     * wirtschaftliche Nutzen"), "running" only of the company's own systems ("we are running", "running our"), never a product
     * running ("SAP is running") or an activity ("running jobs").
     */
    private static final Pattern OWN_USE_VERB=Pattern.compile("(?u)\\b(?:nutzen|nutzt|verwenden|verwendet|betreiben|betreibt|einsetzen|einsetzt)\\b"
            +"|\\bsetzen\\b[^.!?;:]{0,60}?\\bein\\b(?=\\s*(?:[.,;:!?)]|$))|\\barbeiten\\b.{0,20}\\bmit\\b"
            +"|(?iu:\\buses?\\b(?![\\s-]*cases?\\b)(?!\\s+to\\b)|\\boperate\\b|\\bwe(?:'re|’re|\\s+are)\\s+running\\b|\\brunning\\s+our\\b)");
    /**
     * 0.12.2: where a clause of a sentence ends: a colon, semicolon, question or exclamation mark, a dash, and a comma before a
     * subordinate or contrasting clause ("…, um diese Chancen zu nutzen", "Teams, die Azure nutzen", "…, was SAP bereits bietet",
     * "…, auf der Unternehmen … betreiben"). A list of names keeps its commas.
     */
    private static final Pattern CLAUSE_END=Pattern.compile("(?iu)[:;?!]|\\s[–—-]\\s|,\\s*(?=(?:um|die|der|das|den|dem|deren|dessen|welche[nmrs]?|was|wo|wie|wenn|weil|damit|dass|ob|obwohl"
            +"|aber|sondern|denn|während|sodass|so dass|desto|which|that|who|where|when|while|because|but|ensuring|to)\\b"
            +"|(?:auf|in|mit|bei|für|von|zu|an|über|unter)\\s+(?:der|dem|den|denen|die|das|welche[nmrs]?|which|whom)\\b)");
    /** 0.12.2: the company's own word in a clause: "wir", "unser", "intern", "eigene" (not the compound "Azure-eigene"). */
    private static final Pattern OWN_WORD=p("\\bintern\\b|in.house(?![\\s-]{0,2}(?:cash|bank))|\\bunser(?:e[nmrs]?)?\\b|\\bwir\\b|our internal|\\bour\\b|\\bwe\\b|(?<![-\\p{L}])eigene[nrms]?\\b");
    /**
     * 0.12.2: a provider's own services in a row of delivery verbs ("Wir entwickeln, modernisieren und betreiben … auf Microsoft
     * Azure", "Wir planen, migrieren und betreiben Cloud-Infrastrukturen …"): its offer, not its own use or plan.
     */
    private static final Pattern DELIVERY_ROW=p("\\b(?:entwickeln|modernisieren|implementieren|planen|migrieren|bauen|konzipieren|beraten|begleiten|realisieren"
            +"|integrieren|betreiben|liefern|develop|modernize|modernise|implement|plan|migrate|build|design|operate|deliver)\\b\\s*,\\s*(?:und\\s+|and\\s+)?"
            +"(?:entwickeln|modernisieren|implementieren|planen|migrieren|bauen|konzipieren|beraten|begleiten|realisieren|integrieren|betreiben|liefern"
            +"|develop|modernize|modernise|implement|plan|migrate|build|design|operate|deliver)\\b");
    /** 0.12.2: advice to the reader ("Wir empfehlen die Umstellung … einzuplanen"): no plan or use of the company. */
    /**
     * 0.14.0 (item A2): the provider's work for others in a relative clause or with the reader as object ("Systeme, die wir
     * migrieren, betreuen und absichern", "migrieren wir Ihre Daten", "wir migrieren Ihre Systeme"); "die wir migrieren
     * werden" is an own plan.
     */
    private static final Pattern MIGRATES_FOR_OTHERS=p("\\b(?:die|welche|das|den)\\s+wir\\s+(?:[\\p{L}-]+\\s+){0,3}(?:migrieren|betreuen|einführen|einfuehren"
            +"|implementieren|absichern)\\b(?!\\s+(?:werden|wollen|möchten|moechten))|\\bmigrieren\\s+wir\\b|\\bwir\\s+migrieren\\s+(?:Ihre?[nmrs]?|ihre?[nmrs]?|Kunden)\\b"
            +"|\\b(?:which|that)\\s+we\\s+(?:migrate|implement|manage)\\b|\\bwe\\s+migrate\\s+(?:your|clients?|customers?)\\b");
    /** 0.14.0 (item A2): the provider choosing products for the reader ("wir nutzen die passenden Muster-Lösungen"): its offer, no use. */
    private static final Pattern CHOOSES_FOR_READER=p("\\b(?:wir|we)\\s+(?:nutzen|verwenden|setzen|wählen|waehlen|use|choose)\\s+(?:die|den|das|the)\\s+"
            +"(?:passenden|geeigneten|richtigen|optimalen|besten|right|best|appropriate)\\b");
    /**
     * 0.14.1 (R2): the provider helping the reader to use a product ("Wir unterstützen Sie dabei, Muster-Cloud zu nutzen", "schulen
     * wir dich in der Nutzung von …"): its offer, no use of its own. The reader formally (case-sensitive) or informally.
     */
    private static final Pattern HELPS_READER=Pattern.compile("(?u)\\b(?:[Uu]nterstütz(?:en|t|e)|[Hh]elfen|[Hh]ilft|[Bb]egleit(?:en|et|e)|[Bb]eraten|[Bb]erät"
            +"|[Ss]chul(?:en|t|e)|[Bb]efähig(?:en|t)|[Cc]oach(?:en|t))\\s+(?:wir\\s+|euch\\s+)?(?:Sie|dich|euch|Ihnen|dir)\\b"
            +"|\\b(?:[Ww]ie|[Dd]ass|[Dd]amit|[Ww]enn|[Oo]b)\\s+du\\b|\\b[Dd]ein(?:e[nmrs]?)?\\b");
    /**
     * 0.14.1 (R2): others or the reader as the users of a product ("Damit können Kunden … einsetzen", "So können Sie …"): the
     * provider describes what its offer gives them, no use of its own.
     */
    private static final Pattern OTHERS_USE=Pattern.compile("(?u)\\b(?:können|kann|könnt|sollen|sollten|dürfen)\\s+(?:(?:Ihre|unsere|alle|viele)\\s+)?"
            +"(?:Unternehmen|Kunden|Firmen|Händler|Teams|Organisationen|Betriebe|Anwender|Nutzer|Mitarbeitende|Sie|du)\\b");
    /**
     * 0.14.1 (R1): the path of a privacy or cookie notice ("/datenschutz/", "/datenschutzerklaerung", "/cookie-richtlinie-eu/",
     * "/privacy-policy", "/impressum-datenschutz/"), as a whole segment or with a country's or version's mark ("-eu", "-neu"); not a
     * page offering data protection ("/datenschutzberatung/") or an article about it ("/blog/privacy-by-design").
     */
    private static final Pattern PRIVACY_PATH=Pattern.compile("(?iu)(?:^|[/_-])(?:datenschutz(?:erkl(?:ae|ä)rung(?:en)?|hinweise?|bestimmungen"
            +"|richtlinien?|informationen?|info)?|privacy(?:[-_]?(?:policy|policies|notice|statement))?|data[-_]?protection(?:[-_]?(?:policy|notice"
            +"|declaration))?|cookies?(?:[-_]?(?:policy|richtlinien?|hinweise?|erkl(?:ae|ä)rung|einstellungen|settings|notice))?|dsgvo|gdpr)"
            +"(?=(?:[-_](?:eu|de|en|ch|at|neu|alt|\\d{1,4}))?(?:[./]|$))");
    /** 0.14.0 (item A2): a product named as what an offer replaces ("Alternative zu …", "statt …", "anstelle von …", "instead of …"). */
    private static final Pattern ALTERNATIVE=p("\\bAlternativen?\\s+(?:zu|für|fuer)\\s+|\\balternatives?\\s+to\\s+|\\bstatt\\s+|\\banstatt\\s+"
            +"|\\banstelle\\s+(?:von\\s+)?|\\binstead\\s+of\\s+|\\bErsatz\\s+(?:für|fuer)\\s+|\\breplacement\\s+for\\s+");
    /** Where the phrase after {@link #ALTERNATIVE} ends: a clause's mark or its verb. */
    private static final Pattern ALTERNATIVE_END=p("[,;:.!?]|\\s(?:wir|we|nutzen|nutzt|setzen|verwenden|arbeiten|use|uses)\\b");
    /** 0.14.0 (item A4): a heading of questions and answers ("FAQ", "Häufige Fragen", "Fragen und Antworten"). */
    private static final Pattern FAQ_HEADING=p("^\\W*#+\\s*.*(?:\\bFAQs?\\b|häufige\\s+fragen|haeufige\\s+fragen|häufig\\s+gestellte|fragen\\s+(?:und|&)\\s+antworten"
            +"|frequently\\s+asked|\\bQ\\s?&\\s?A\\b)");
    /** 0.14.0 (item A4): the section of the reader's questions and lines ({@link #FAQ_HEADING}): no use, plan or need of the company. */
    public static final String FAQ="faq";
    private static final Pattern RECOMMENDATION=p("\\bwir\\s+empfehlen\\b|\\bempfehlen\\s+wir\\b|\\bunsere?\\s+Empfehlung\\b|\\bwe\\s+recommend\\b|\\bour\\s+recommendation\\b");
    /**
     * 0.12.2: a product named as the subject of a use verb ("Salesforce verwendet nach eigenen Angaben …", "SAP is running"): the
     * product acts, the company does not use it.
     */
    private static final String PRODUCT_SUBJECT="(?:verwendet|nutzt|betreibt|setzt|is\\s+running|runs|uses)\\b";
    /** "in-house", but not the SAP treasury modules In-House Cash / In-House Bank(ing): a product name is no actor. */
    private static final String IN_HOUSE="in.house(?![\\s-]{0,2}(?:cash|bank))";
    private static final Pattern OWN=p("\\bintern\\b|"+IN_HOUSE+"|\\bunser(?:e[nmrs]?)?\\b|\\bwir\\b|our internal|\\bwe\\b|\\beigene[nrms]?\\b");
    /**
     * 0.12.0 (T6): what a provider's page says to its reader is its offer, not its own use ("Nutzen Sie die Vorteile unseres SAP
     * Supports", "… mit Ihren bestehenden SAP-Systemen", "wie Sie diese … nutzen"); the formal address only, case-sensitive.
     */
    private static final Pattern READER=Pattern.compile("(?u)\\b(?:[Nn]utzen|[Vv]erwenden|[Ss]etzen|[Pp]rofitieren) Sie\\b|\\b(?:wie|dass|damit|wenn|ob) Sie\\b"
            +"|\\b(?:Ihr|Ihre|Ihren|Ihrem|Ihres|Ihrer|Ihnen)\\b|\\b(?:[Yy]our|[Yy]ou)\\b");
    /**
     * 0.12.0 (T6, round 2): the reader's need as a provider describes it ("… setzt die Teamentwicklung genau dort an, wo Sie uns
     * brauchen", "Ihren Bedarf"): no need of the company itself. The formal address only, case-sensitive.
     */
    private static final Pattern READER_NEED=Pattern.compile("(?u)\\b(?:Sie|you)\\b[^.!?]{0,30}?\\b(?:brauchen|benötigen|need)\\b"
            +"|\\b(?:Ihr|Ihre|Ihren|Ihrem|Ihres|your)\\b[^.!?]{0,20}?\\b(?:Bedarf|Bedürfnisse|needs)\\b");
    /**
     * 0.12.0 (T6, round 2): a service of a product named as one word ("seit 2010 betreiben wir SAP-Hosting", "unser SAP Support"):
     * the company provides the service, it does not use the product.
     */
    private static final Pattern SERVICE_COMPOUND=Pattern.compile("(?u)\\b[\\p{L}0-9/]+-(?:Hosting|Support|Betrieb|Beratung|Services?|Consulting"
            +"|Schulung|Training|Integration|Implementierung|Betreuung|Wartung)\\b|\\b\\p{Lu}[\\p{L}0-9/]*\\s+(?:Hosting|Support|Betrieb|Beratung|Services?"
            +"|Consulting|Schulung|Training|Betreuung|Wartung)\\b");
    /** A service word joined to a product ("SAP-Hosting"), not to a company's name ("STELLWERK Consulting AG"). */
    private static boolean serviceOfProduct(String quote,Signals vocab) {
        Matcher m=SERVICE_COMPOUND.matcher(quote);
        while(m.find())if(!vocab.products(m.group()).isEmpty())return true;
        return false;
    }
    /** A system run or supported for others ("betreiben wir SAP … für Unternehmen jeder Größe", "unterstützt Versorger mit SAP"). */
    private static final Pattern OFFER_TO=p("\\b(?:unterstütz\\w*|begleit\\w*|berät|beraten|betreu\\w*|betreiben|betreibt|hosten|implementier\\w*|realisier\\w*)\\b"
            +"[^.!?]{0,60}?\\b(?:Kunden|Unternehmen|Versorger|Stadtwerke|Kommunen|Verwaltungen|Mittelständler|Mittelstand|Organisationen|Firmen|Betriebe"
            +"|customers|clients|companies|organi[sz]ations|businesses)\\b"
            +"|\\b(?:deliver(?:s|ing)?|provid(?:e|es|ing)|offer(?:s|ing)?|build(?:s|ing)?|implement(?:s|ing)?)\\b[^.!?]{0,60}?\\b(?:solutions?|services?|integration|platforms?|consulting)\\b");
    /**
     * 0.12.0 (T6): a use verb that is no own use: a cooperation ("arbeiten wir direkt mit SAP zusammen", partners), a product in
     * a list of examples ("aus IT-Systemen wie SAP"), a sentence about others ("Industrieunternehmen betreiben …", "the
     * headquarters runs …") or the quoted words of another person ("… says Heisig").
     */
    private static final Pattern NOT_OWN_USE=p("\\bzusammen\\b|zusammenarbeit|partner(?:schaft|schaften|n|s|ship)?\\b"
            +"|\\b(?:Systemen?|systems?|Quellen|sources|Plattformen|platforms|Anwendungen|applications|Tools|Lösungen|solutions)\\s+(?:wie|such as|like|z\\.\\s?B\\.)\\s"
            +"|^\\W*(?:(?:viele|die meisten|zahlreiche|häufig|oft|many|most)\\s+)?(?:Industrieunternehmen|Unternehmen|Firmen|Mittelständler|Versorger|Stadtwerke"
            +"|Kommunen|Verwaltungen|Konzerne|Betriebe|Kunden|Organisationen|companies|organi[sz]ations|enterprises|customers|clients)\\b"
            +"|\\bthe headquarters\\b|\\bsubsidiaries\\b"
            // 0.12.0 (T6, round 2): examples of technologies, others using the provider's own product ("… nutzen unsere Reports",
            // "um das volle Potenzial unseres Tools zu nutzen"), a sentence about others ("wie Unternehmen … nutzen", "Unternehmen,
            // die SAP verwenden"), the breadth of a competence ("mit allen gängigen SAP-Systemen"), "easy to use", "for use"
            +"|\\b(?:Technologien|technologies|Werkzeugen?|tools|Produkten?|products|Anbietern?|vendors)\\s+(?:wie|such as|like|z\\.\\s?B\\.)\\s"
            +"|\\b(?:nutzen|nutzt|verwenden|verwendet|uses?)\\s+(?:bereits\\s+|heute\\s+)?(?:unsere?[nmrs]?|our)\\b|\\bunser(?:es|er|en|em|e)?\\s+\\S+\\s+zu\\s+nutzen\\b"
            +"|\\b(?:wie|dass|wenn|ob)\\s+(?:viele\\s+)?(?:Unternehmen|Firmen|Kunden|Organisationen|companies|organi[sz]ations|customers|clients)\\b"
            +"|\\b(?:Unternehmen|Firmen|Kunden|Organisationen),\\s+die\\b"
            +"|\\b(?:allen|alle|verschiedenen|diversen|unterschiedlichen)\\s+(?:gängigen\\s+|relevanten\\s+|führenden\\s+)?[\\p{L}/-]*(?:Systemen|Tools|Plattformen|Lösungen)\\b"
            +"|\\b(?:easy|simple|ready|intuitive|free)\\s+to\\s+use\\b|\\bfor\\s+use\\b"
            // 0.12.2: what can be done ("lässt sich … nutzen"), others as the subject ("Zahlreiche Energieversorger arbeiten …"),
            // a product's technology ("Unsere Lösungen basieren auf … und nutzen …"), examples of models and services, a list of
            // three or more platforms worked with ("arbeiten mit Shopify, Shopware, WooCommerce …")
            +"|\\b(?:lässt|lassen)\\s+sich\\b"
            +"|^\\W*(?:zahlreiche|viele|die\\s+meisten|mehr\\s+als\\s+\\d+|über\\s+\\d+|hunderte|tausende|numerous|hundreds\\s+of|thousands\\s+of)\\s+\\p{L}+"
            +"|^\\W*unser(?:e|er)?\\s+(?:Lösung(?:en)?|Software|Produkte?|Plattform(?:en)?|Apps?|Anwendung(?:en)?|Tools?|Module?|Services|Dienste)\\b"
            +"|^\\W*our\\s+(?:solutions?|software|products?|platforms?|apps?|applications?|tools?|modules?|services)\\b"
            +"|\\b(?:Modelle|Dienste|Services|Komponenten|models|services|components)\\s+(?:wie|such as|like)\\s"
            +"|\\b(?:mit|nutzen|verwenden|with|use)\\b[^.;:!?]*?\\p{Lu}[\\p{L}0-9]*,\\s*\\p{Lu}[\\p{L}0-9]*,\\s*\\p{Lu}[\\p{L}0-9]*"
            // the quoted words of a client ("„Als junges Unternehmen setzen wir mit SAP B1 …“"): a customer's voice
            +"|^\\W{0,3}[„“\"«‚]"
            +"|[„“\"”][^„“\"”]{10,}[“”\"]\\s*,?\\s*(?:says|said|sagt|sagte|erklärt|erklärte|so)\\b");
    /** Explicit own use, needed where a provider of the same product says it uses it (0.12.0): "intern", "für unsere Buchhaltung". */
    private static final Pattern EXPLICIT_OWN=p("\\bintern\\b|\\binterne[nmrs]?\\b|"+IN_HOUSE+"|\\binhouse\\b|im eigenen Haus"
            +"|\\b(?:für\\s+)?(?:unsere[nmrs]?|eigene[nmrs]?)\\s+(?:eigene[nmrs]?\\s+)?(?:Buchhaltung|Finanzbuchhaltung|Verwaltung|Logistik|Produktion|Personal\\w*"
            +"|Mitarbeiter\\w*|Prozesse|Controlling|Rechnungswesen|Standorte|Werke|Lager)\\b|\\binternally\\b|\\bour own\\b");

    /**
     * True if the actor is named as the provider of what is used: after "von/vom/by/from" or as the object of a use verb ("Wir
     * nutzen dab Nexus", "nutzt die AMS von prismat"). Then the speaking "wir" is a customer of the actor, not the actor.
     */
    public static boolean providerNamed(String quote,String actorName) {
        if(quote==null||actorName==null||actorName.isBlank())return false;
        String name=Pattern.quote(actorName.trim());
        return Pattern.compile("(?iu)\\b(?:von|vom|by|from)\\s+(?:der\\s+|dem\\s+|the\\s+)?"+name+"(?![\\p{L}])"
                +"|\\b(?:nutzen|nutzt|verwenden|verwendet|setzen|setzt|einsetzen|uses?|using)\\s+(?:die\\s+|das\\s+|den\\s+|the\\s+)?"+name+"(?![\\p{L}])").matcher(quote).find();
    }
    /** True if one of the actor's names stands as the provider of what the passage's speaker uses: see {@link #providerNamed}. */
    public static boolean customerVoice(String quote,java.util.Collection<String> actorNames) {
        if(quote==null)return false;
        for(String n:actorNames)if(providerNamed(quote,n))return true;
        return false;
    }
    /**
     * A provider's offer worded with the words of a building need (0.12.0, gate T6, round 2; production sample of bauteamcheck,
     * 22 October 2026: every sampled "planned" new build, rebuild or renovation was one): the reader's project ("Wenn Sie einen
     * Neubau planen", "Wir planen gemeinsam mit Ihnen den Umbau", "deine gesamte Elektrik"), a delivery ("planen und
     * realisieren wir", "Wir planen, modernisieren und realisieren Bäder", "Wir organisieren alle Gewerke"), a range of services
     * ("Ob Neubau, Sanierung oder Erweiterung", "Neubau – Sanierung – Reparatur", "von der energetischen Sanierung bis zum
     * Neubau", "Altbau wie Neubau") or a private client's words ("Meine Frau und ich planten …"). The company's own plan stays
     * ("Wir planen den Neubau unserer Firmenzentrale in Arnsberg").
     */
    private static final Pattern NEED_OFFER=Pattern.compile("(?u)\\b(?:Sie|Ihr(?:e[nmrs]?)?|Ihnen|Euch|Eu(?:er|re[nmrs]?))\\b|(?iu:"
            +"\\b(?:du|dich|dir|dein(?:e[nmrs]?)?|ich|you|your)\\b|\\bmeine?\\s+(?:Frau|Mann|Familie|Partner(?:in)?)\\b"
            +"|\\bplan(?:en|t)(?:\\s*,\\s*\\p{L}+)*\\s*(?:,|und)\\s+(?:realisier|bau|install|fertig|gestalt|modernisier|organisier|umsetz|ausführ|erricht|verleg|montier)"
            +"|\\b(?:organisieren|koordinieren|übernehmen)\\s+(?:wir\\s+)?(?:alle|sämtliche)\\b|\\bjedes\\s+(?:Projekt|Vorhaben)\\b|\\bde[mn]\\s+Kunden\\b"
            +"|\\b(?:ob|egal,?\\s+ob|sei\\s+es|ganz\\s+gleich,?\\s+ob)\\b[^.:]{0,60}\\b(?:Neu|Um|An|Alt)?bau\\w*[^.:]{0,40}\\b(?:oder|bis|wie)\\b"
            +"|\\b(?:ob|egal,?\\s+ob|sei\\s+es)\\b[^.:]{0,60}\\b\\p{L}*[Ss]anierung\\w*[^.:]{0,40}\\b(?:oder|bis)\\b"
            +"|^\\W*(?:Neubau|Umbau|Anbau|Altbau|Sanierung)\\w*\\s*(?:,|–|—|-|oder)[^.]{0,60}(?::|–|—|\\s-\\s|\\bbei\\b)"
            +"|\\b(?:Alt|Neu)bau\\s+wie\\s+(?:Alt|Neu)bau\\b|\\bvo(?:n|m)\\b[^.\\d]{2,80}?\\bbis\\s+(?:hin\\s+)?zu[mr]?\\b)");

    /** True if the passage describes the reader's need, as a provider writes about its clients: see {@link #READER_NEED}. */
    public static boolean readerNeed(String passage){return passage!=null&&READER_NEED.matcher(passage).find();}
    /** True if the passage states own use explicitly (internally, for the company's own departments): see {@link #EXPLICIT_OWN}. */
    public static boolean explicitOwn(String passage){return passage!=null&&EXPLICIT_OWN.matcher(passage).find();}
    private static final Pattern PLAN=p("planen|plant|geplant|beabsichtigen|beabsichtigt|werden.{0,60}migrieren|planning|planned|intend");
    private static final Pattern MIGRATION=p("Migration|migrieren|Umstellung|migrate|migration");
    private static final Pattern COMPLETED=p("abgeschlossen|vollzogen|completed|finished");
    /**
     * 0.12.0 (B2): support sought for an own migration ("Für unsere S/4HANA-Migration suchen wir externe Unterstützung"), a
     * need like a planned one; a consultant or other job title is recruiting, never such a need.
     */
    private static final Pattern SOUGHT=p("\\bsuch(?:en|t)\\b.{0,40}(?:Unterstützung|Partner|Dienstleister)|(?:Unterstützung|Partner|Dienstleister).{0,60}\\bgesucht"
            +"|\\b(?:seeking|looking for)\\b.{0,40}(?:support|partner|service provider)");
    /** Recruiting words: a sentence seeking staff ("SAP-Berater (m/w/d)", "Verstärkung") is no sought support (0.12.0). */
    private static final Pattern STAFF=p("\\(\\s*[mwfdx]\\s*/\\s*[mwfdx]|Berater|Consultant|Verstärkung|Kolleg|Mitarbeiter|Bewerb|Stelle|hiring|join us");
    private static final Pattern SHUTDOWN=p("abgeschaltet|außer Betrieb|ausser Betrieb|stillgelegt|decommissioned|switched off");
    private static final Pattern NEGATION=p("\\bnicht\\b|\\bkeine?[nrs]?\\b|\\bnot\\b|\\bwithout\\b|n['’]t\\b");
    private static final Pattern OFFER=p("wir bieten|wir erbringen|wir unterstützen|unsere Leistungen|we offer|we provide|we support");
    private static final Pattern NEED=p("Bedarf|benötigen|benötigt|brauchen|suchen.{0,40}(?:Unterstützung|Partner)|need|require|seeking.{0,40}support");
    private static final Pattern OWN_CARE=p("unser(?:e[nmrs]?)?.{0,80}(?:Entlassmanagement|Überleitungsmanagement|Pflegeüberleitung)|wir.{0,80}(?:organisieren|koordinieren).{0,80}(?:Entlass|Pflegeüber)|our.{0,80}discharge management");
    private static final Pattern CLOSED=p("Stelle (?:ist )?(?:besetzt|geschlossen)|Position (?:ist )?besetzt|Bewerbungsverfahren (?:ist )?beendet|vacancy (?:is )?(?:filled|closed)");
    /** An intermediary advertises for its client, also a recruiter for its "Mandant" (0.10.6). */
    public static final Pattern INTERMEDIARY=p("im Auftrag (?:unseres|eines) (?:Kunden|Mandanten)|für unseren (?:Kunden|Mandanten)"
            +"|für einen unserer (?:Kunden|Mandanten)|on behalf of (?:our|a) client|for our client");
    /** Reference, case-study and client pages: what they describe is work for clients, not the operator's own use or need.
     * Deliberately narrower than a general "projects" page, which can describe the operator's own projects. */
    private static final Pattern REFERENCE_PATH=Pattern.compile("(?iu)(?:^|/)(?:referenzen|referenz|references?|case-stud(?:y|ies)|fallstudien"
            +"|kundenprojekte|projekt-?beispiele|success-stor(?:y|ies)|erfolgsgeschichten|kunden|customers|clients"
            // 0.12.0 (T6, round 2): an article reports a project, mostly a client's (the plan: "Fachartikel" prove neither need nor use)
            +"|blog|magazin|fachartikel)(?:[./_\\-]|$)");
    /** A named legal entity (Muster Energie AG); used to tell an unnamed "we" from the company the sentence is about. */
    private static final Pattern LEGAL_ENTITY=Pattern.compile("(?u)\\b(?:[\\p{Lu}][\\p{L}\\p{N}&'.-]*\\s+){1,4}"
            +"(?:gGmbH|GmbH|AG|SE|KGaA|KG|OHG|GbR|UG|mbH|e\\.\\s?V\\.|Ltd\\.?|Inc\\.?|LLC|plc|S\\.A\\.|B\\.V\\.|N\\.V\\.)(?![\\p{L}])");
    /**
     * Prefix of the sections of signals read from a job advertisement for its declared employer without a job object
     * (0.10.2, {@code job_ad_description}, {@code job_ad_skills}, {@code job_ad_qualifications}, {@code job_ad_text}): they are
     * read as on a posting (a skill is a requirement, the posting resolves its employer) and only as system signals.
     */
    public static final String JOB_AD="job_ad_";
    /** True for a section of a job advertisement read for its employer ({@link #JOB_AD}). */
    public static boolean jobAd(String section){return section!=null&&section.startsWith(JOB_AD);}
    /** Section of signals read on a reference page ({@link #referencePage}). */
    public static final String REFERENCES="references";
    private BusinessSignals() { }
    private static Pattern p(String regex) {return PATTERNS.computeIfAbsent(regex,r->Pattern.compile("(?iu)"+r));}

    public static void extract(String text,Mention actor,Extraction out,int tier,int kind,String section,String locator) {
        if(text==null||actor==null)return;
        // JSON-LD descriptions may contain HTML; preserve paragraph boundaries.
        String plain=text.replaceAll("(?i)</?(?:p|li|br|h[1-6])[^>]*>","\n").replaceAll("<[^>]+>"," ");
        // The old repeated regex alternation recursed once per character,
        // overflowing BEFORE the quote-length check. Iterate once over the
        // text instead, also avoiding quadratic find() retries on long lines
        // without terminating punctuation. Preserve its offsets/date dots.
        int start=0,lastNumericDot=-1;
        for(int at=0;at<=plain.length();at++) {
            boolean end=at==plain.length();char ch=end?'\0':plain.charAt(at);
            if(ch=='.'&&at>0&&at+1<plain.length()&&asciiDigit(plain.charAt(at-1))&&asciiDigit(plain.charAt(at+1))) {
                lastNumericDot=at;continue;
            }
            if(!end&&ch!='\n'&&ch!='.'&&ch!='!'&&ch!='?'&&ch!=';')continue;
            // An interior newline was not a regex terminator. Its greedy repetition
            // could backtrack to the last numeric dot, otherwise skip this
            // unterminated line. Keep that behavior; do not invent evidence.
            // Java's $ also matched immediately before the final newline.
            int until=ch=='\n'?(at==plain.length()-1?at:lastNumericDot+1):end?at:at+1;
            int offset=start,locatorOffset=start;start=at+1;lastNumericDot=-1;
            if(until<=offset||(!end&&ch!='\n'&&at==offset))continue;
            if(until-offset>900) {
                // Length is checked after trim in the original contract.
                while(offset<until&&plain.charAt(offset)<=' ')offset++;
                while(until>offset&&plain.charAt(until-1)<=' ')until--;
                if(until-offset>900)continue;
            }
            String quote=plain.substring(offset,until).trim();if(quote.isEmpty())continue;
            // Locator starts before trim, as with Matcher.start().
            for(String predicate:List.of(Vocabulary.SYSTEM_SIGNAL,Vocabulary.BUSINESS_NEED_SIGNAL,Vocabulary.BUSINESS_ROLE_EVIDENCE,Vocabulary.JOB_STATUS)) {
                if(Vocabulary.JOB_STATUS.equals(predicate)&&!Vocabulary.JOB.equals(actor.type))continue;
                // an advertisement read for its employer gives system hints only, never a need or a role of the company
                if(jobAd(section)&&!Vocabulary.SYSTEM_SIGNAL.equals(predicate))continue;
                if((Vocabulary.BUSINESS_NEED_SIGNAL.equals(predicate)||Vocabulary.BUSINESS_ROLE_EVIDENCE.equals(predicate))
                        && !Vocabulary.ORGANIZATION.equals(actor.type)&&!Vocabulary.FACILITY.equals(actor.type))continue;
                // 0.12.0 (T6, round 2): under a heading of customers' voices a "wir" is the client speaking about its use; 0.14.0 (item
                // A4): under a heading of questions and answers it is the reader
                String in=REFERENCES.equals(section)||!customerVoiceSection(plain,offset)?section:REFERENCES;
                if("text".equals(in)&&faqSection(plain,offset))in=FAQ;
                for(String value:read(predicate,quote,in,actor.type,actor.name))
                    out.add(new Claim(actor.ref,predicate,null,located(value,locator+":"+locatorOffset),tier,kind,DESIRED.matcher(quote).find(),
                            locator+":"+locatorOffset,quote));
            }
        }
    }

    private static boolean asciiDigit(char ch){return ch>='0'&&ch<='9';}

    /** A heading of customers' voices or of a client list ("Kunden, die unsere Lösungen nutzen", "Kundenstimmen", "What our clients say"). */
    private static final Pattern CUSTOMER_VOICES=p("kunden,? die unsere|kundenstimmen|stimmen unserer kunden|das sagen unsere kunden|was unsere kunden sagen"
            +"|erfahrungsberichte|testimonials?|customers using our|what our (?:customers|clients) say|our (?:customers|clients) say|our clients\\b"
            +"|unsere referenzen|referenzen sprechen|our references|references speak|^\\W*#+\\s*(?:kunden)?referenzen\\W*$|^\\W*#+\\s*references\\W*$");
    /** How far a heading of customers' voices reaches at most (the voices below it, up to the next heading). */
    private static final int VOICES_REACH=4000;

    /**
     * True if the position stands below a heading of customers' voices (the last heading line before it, at most
     * {@link #VOICES_REACH} characters back): what is said there is said by clients about their own use.
     */
    static boolean customerVoiceSection(String text,int at) {
        int from=Math.max(0,at-VOICES_REACH);
        int line=at;
        while(line>from) {
            int nl=text.lastIndexOf('\n',line-1);
            int start=nl<from?from:nl+1;
            String l=text.substring(start,Math.min(text.length(),text.indexOf('\n',start)<0?text.length():text.indexOf('\n',start)));
            if(l.trim().startsWith("#"))return CUSTOMER_VOICES.matcher(l).find();
            if(nl<from)break;
            line=nl;
        }
        return false;
    }

    /** 0.14.0 (item A4): true if the position stands below a heading of questions and answers (the last heading line before it). */
    static boolean faqSection(String text,int at) {
        int from=Math.max(0,at-VOICES_REACH);
        int line=at;
        while(line>from) {
            int nl=text.lastIndexOf('\n',line-1);
            int start=nl<from?from:nl+1;
            String l=text.substring(start,Math.min(text.length(),text.indexOf('\n',start)<0?text.length():text.indexOf('\n',start)));
            if(l.trim().startsWith("#")&&start<at)return FAQ_HEADING.matcher(l).find();
            if(nl<from)break;
            line=nl;
        }
        return false;
    }

    /** 0.14.0 (item A2): the products the quote names as what an offer replaces ({@link #ALTERNATIVE}): no use, no offer of them. */
    static Set<String> replaced(String quote,Signals vocab) {
        Set<String> out=new LinkedHashSet<>();
        Matcher m=ALTERNATIVE.matcher(quote);
        while(m.find()) {
            Matcher end=ALTERNATIVE_END.matcher(quote).region(m.end(),Math.min(quote.length(),m.end()+80));
            out.addAll(vocab.products(quote.substring(m.end(),end.find()?end.start():Math.min(quote.length(),m.end()+80))));
        }
        return out;
    }

    /**
     * 0.14.1 (R3): what keeps a company offering a product a customer of another provider of it (the owner's decision of 22 October
     * 2026: a provider of the product itself can be a customer): support it seeks for its own migration, or its use stated as
     * explicitly its own ("intern", "im eigenen Haus", "für unsere Buchhaltung").
     */
    public static boolean ownNeedOrUse(String quote) {
        return quote!=null&&(SOUGHT.matcher(quote).find()&&!STAFF.matcher(quote).find()||EXPLICIT_OWN.matcher(quote).find());
    }

    /**
     * 0.14.1 (R1): whether the page at {@code url} is a privacy or cookie notice (by its path, {@link #PRIVACY_PATH}): a notice of
     * the services a website embeds, written for the law; the matching reads no use or offer of website technology from it.
     */
    public static boolean privacyNotice(String url) {
        if(url==null)return false;
        String path;
        try{path=java.net.URI.create(url.trim().replace(" ","%20")).getRawPath();}catch(IllegalArgumentException e){return false;}
        if(path==null||path.isEmpty())return false;
        try{path=java.net.URLDecoder.decode(path,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException e){/* keep the raw path */}
        return PRIVACY_PATH.matcher(path).find();
    }

    /** Whether the page at {@code url} is a reference, case-study or client page (by its path). */
    public static boolean referencePage(String url) {
        if(url==null)return false;
        String path;
        try{path=java.net.URI.create(url.trim()).getRawPath();}catch(IllegalArgumentException e){return false;}
        if(path==null||path.isEmpty())return false;
        try{path=java.net.URLDecoder.decode(path,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException e){/* keep the raw path */}
        return REFERENCE_PATH.matcher(path).find();
    }

    /** The quote names a company other than the actor (a product vendor such as SAP SE is no actor of the statement). */
    private static boolean otherCompanyNamed(String quote,String actorName,Signals vocab) {
        Matcher m=LEGAL_ENTITY.matcher(quote);String own=actorName==null?null:actorName.toLowerCase(Locale.ROOT);
        while(m.find()) {
            String name=m.group().trim(),lower=name.toLowerCase(Locale.ROOT);
            if(own!=null&&!own.isBlank()&&(lower.contains(own)||own.contains(lower)))continue;
            if(!vocab.products(name).isEmpty())continue;
            return true;
        }return false;
    }

    /** Distinct passages/postings must not collapse into the live Evidence PK and lose a quotation. */
    public static String located(String value,String locator) {
        if(!value.startsWith("{"))return value;
        try {org.json.JSONObject fields=new org.json.JSONObject(value);fields.put("statement_context",locator);return fields.toString();}
        catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
    }

    private static void assertionDate(Map<String,Object> fields,String quote) {
        Matcher date=p("\\b(?:am|seit|zum|on|since)\\s+(20[0-9]{2}(?:-[0-9]{2}(?:-[0-9]{2})?)?|[0-9]{1,2}\\.[0-9]{1,2}\\.20[0-9]{2})\\b").matcher(quote);
        if(date.find()){String normalized=Values.date(date.group(1));if(normalized!=null) {
            fields.put("asserted_date",normalized);String marker=date.group().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
            fields.put("date_kind",List.of("seit","since").contains(marker)?"since":"event_date");
        }}
    }

    /** Shared by rules, structured data and the LLM validator/application. */
    /**
     * 0.12.2: the products the company itself uses by a clause of the quote ({@link #CLAUSE_END}): one with a use verb of its own
     * ({@link #OWN_USE_VERB}), the product and its own word or name, where the product is not the subject of the verb.
     */
    static Set<String> ownUseProducts(String quote,Signals vocab,String actorName) {
        Set<String> out=new LinkedHashSet<>();
        // a relative clause of the company's own ("… auf unsere SAP-Daten zu, die wir intern nutzen") belongs to its antecedent
        List<String> clauses=new ArrayList<>();
        for(String part:CLAUSE_END.split(quote)) {
            if(!clauses.isEmpty()&&OWN_RELATIVE.matcher(part).find())clauses.set(clauses.size()-1,clauses.get(clauses.size()-1)+", "+part);
            else clauses.add(part);
        }
        for(String clause:clauses) {
            // the company's own systems or data of the product ("auf unsere SAP-Daten zuzugreifen", "unser SAP-System") are its use
            if(OWN_POSSESSION.matcher(clause).find()&&!productSubject(clause,vocab)){out.addAll(vocab.products(clause));continue;}
            if(!OWN_USE_VERB.matcher(clause).find())continue;
            Set<String> products=vocab.products(clause);
            if(products.isEmpty())continue;
            boolean self=OWN_WORD.matcher(clause).find()||actorName!=null&&clause.toLowerCase(Locale.ROOT).contains(actorName.toLowerCase(Locale.ROOT));
            if(!self||productSubject(clause,vocab))continue;
            out.addAll(products);
        }
        return out;
    }
    /**
     * 0.13.0: the products of a planned migration by a clause of the quote ({@link #CLAUSE_END}), as own use since 0.12.2: the
     * migration ("Migration", "Umstellung", "migrieren"), the plan or the sought support ({@link #PLAN}, {@link #SOUGHT}), the product
     * and "wir"/"unser" or the company's name stand in one clause ("Wir planen die Migration auf S/4HANA"). A plan of something else
     * next to another party's migration ("Wir planen Workshops; die Migration übernehmen Partner") and a product of another clause
     * are none.
     */
    static Set<String> plannedMigrationProducts(String quote,Signals vocab,String actorName) {
        Set<String> out=new LinkedHashSet<>();
        List<String> clauses=new ArrayList<>();
        for(String part:CLAUSE_END.split(quote)) {
            if(!clauses.isEmpty()&&OWN_RELATIVE.matcher(part).find())clauses.set(clauses.size()-1,clauses.get(clauses.size()-1)+", "+part);
            else clauses.add(part);
        }
        for(String clause:clauses) {
            if(!MIGRATION.matcher(clause).find()||!(PLAN.matcher(clause).find()||SOUGHT.matcher(clause).find()))continue;
            boolean self=OWN_WORD.matcher(clause).find()||actorName!=null&&clause.toLowerCase(Locale.ROOT).contains(actorName.toLowerCase(Locale.ROOT));
            if(self)out.addAll(vocab.products(clause));
        }
        return out;
    }
    /** 0.12.2: the company's own systems or data of a product ("unsere SAP-Daten", "unser SAP-System", "our SAP landscape"). */
    private static final Pattern OWN_POSSESSION=p("\\bunser(?:e[nmrs]?)?\\s+(?:eigene[nmrs]?\\s+)?[\\p{L}0-9/®]+-?(?:Daten|Systems?|Systeme[nm]?|Systemlandschaft|Landschaft|Umgebung|Instanz|Installation)\\b"
            +"|\\bour\\s+(?:own\\s+)?[\\p{L}0-9/®]+\\s+(?:data|systems?|landscape|environment|instance|installation)\\b");
    /** 0.12.2: a relative clause with the company as its subject ("die wir intern nutzen", "which we use"). */
    private static final Pattern OWN_RELATIVE=p("^\\s*(?:die|der|das|den|dem|welche[nmrs]?|which|that)\\s+(?:wir|we)\\b");
    /** 0.12.2: a product named right before a use verb of which it is the subject ("Salesforce verwendet …", "SAP is running"). */
    private static boolean productSubject(String clause,Signals vocab) {
        Matcher m=p("([\\p{L}0-9/®-]+(?:\\s+[\\p{L}0-9/®-]+)?)\\s+"+PRODUCT_SUBJECT).matcher(clause);
        while(m.find())if(!vocab.products(m.group(1)).isEmpty()&&!p("\\b(?:wir|we)\\b").matcher(m.group(1)).find())return true;
        return false;
    }
    public static List<String> read(String predicate,String quote,String section,String actorType,String actorName) {
        if(quote==null||quote.length()>900)return List.of();
        Signals vocab=KgVocabularies.get().signals;
        List<String> out=new ArrayList<>();boolean own=OWN.matcher(quote).find();
        boolean named=actorName!=null&&quote.toLowerCase(Locale.ROOT).contains(actorName.toLowerCase(Locale.ROOT));
        // 0.12.0 (T6, round 2): the actor named as the provider of what another uses ("Bedford nutzt die AMS von prismat") or as the
        // product a speaking "wir" uses ("Wir nutzen dab Nexus" on dab's page: a customer's voice) is no subject of own use
        if(named&&providerNamed(quote,actorName)){named=false;own=false;}
        boolean references=REFERENCES.equals(section);
        // 0.14.0 (item A4): the reader's lines and questions of an FAQ state no use, plan, migration or need of the company
        boolean faq=FAQ.equals(section);
        // a job advertisement read for its employer is read as its posting: its field decides skills and qualifications
        boolean posting=Vocabulary.JOB.equals(actorType)||jobAd(section);
        String field=jobAd(section)?section.substring(JOB_AD.length()):section;
        if(Vocabulary.SYSTEM_SIGNAL.equals(predicate)) {
            String context=null;
            boolean customer=CUSTOMERS.matcher(quote).find();
            if(NEGATION.matcher(quote).find())return out;
            // An unnamed "we" next to another named company: whose system it is stays unclear (postings resolve their employer).
            if(own&&!named&&!posting&&otherCompanyNamed(quote,actorName,vocab))own=false;
            // 0.12.2: a question (an FAQ, a rhetorical question to the reader) states no use, plan, migration or shutdown
            boolean question=quote.trim().endsWith("?");
            if(OFFER.matcher(quote).find()&&p("Beratung|Integration|Implementierung|Support|Unterstützung|Schulung|Training|consulting|implementation").matcher(quote).find()
                    // 0.12.2: a row of delivery verbs is the provider's offer ("Wir entwickeln, modernisieren und betreiben …")
                    ||!posting&&(own||named)&&DELIVERY_ROW.matcher(quote).find()
                    // 0.12.0 (T6): the provider speaking to its reader, or running and supporting systems for others, offers them
                    ||!posting&&(own||named)&&(READER.matcher(quote).find()||OFFER_TO.matcher(quote).find()||serviceOfProduct(quote,vocab))
                    // 0.14.0 (item A2): the provider migrating or running systems for others, or choosing them for the reader
                    ||!posting&&(MIGRATES_FOR_OTHERS.matcher(quote).find()||(own||named)&&CHOOSES_FOR_READER.matcher(quote).find())
                    // 0.14.1 (R2): the provider helping the reader to use it, or others and the reader as its users
                    ||!posting&&(own||named)&&(HELPS_READER.matcher(quote).find()||OTHERS_USE.matcher(quote).find()))context="offered_capability";
            else if(customer)context="customer_projects";
            else if(question||RECOMMENDATION.matcher(quote).find()) context=null;
            else if(SHUTDOWN.matcher(quote).find()&&(own||named)) context="shutdown";
            else if(MIGRATION.matcher(quote).find()&&COMPLETED.matcher(quote).find()&&(own||named))context="completed_migration";
            // 0.13.0: the migration, the plan (or the support sought), the product and the company in one clause
            else if(MIGRATION.matcher(quote).find()&&(PLAN.matcher(quote).find()||SOUGHT.matcher(quote).find()&&!OFFER.matcher(quote).find()&&!STAFF.matcher(quote).find())&&(own||named)
                    &&!plannedMigrationProducts(quote,vocab,named?actorName:null).isEmpty())context="planned_migration";
            // no own use: a cooperation, examples, others' systems, quoted words; experience "used" is a competence (0.12.0, T6)
            // 0.12.2: the company's own use in one clause (its verb, the product and "wir"/"unser" or its name), not anywhere in the
            // sentence ("…, um diese Chancen zu nutzen", "Teams, die Azure … nutzen")
            else if(USE.matcher(quote).find()&&(own||named)&&!NOT_OWN_USE.matcher(quote).find()&&!ownUseProducts(quote,vocab,named?actorName:null).isEmpty()
                    &&(!KNOWLEDGE.matcher(quote).find()||EXPLICIT_OWN.matcher(quote).find()))context="internal_use";
            else if(KNOWLEDGE.matcher(quote).find()||"skills".equals(field)||"qualifications".equals(field))
                // A requirement belongs to a posting; a company's own competence statement (Kernkompetenz) is no need.
                context=!posting&&!"skills".equals(field)&&!"qualifications".equals(field)?"stated_competence"
                        :DESIRED.matcher(quote).find()?"desirable_competence":"required_competence";
            if(context==null)return out;
            if(faq&&List.of("internal_use","planned_migration","completed_migration","shutdown").contains(context))return out;
            // Simultaneous internal/customer claims need finer context; never assert internal use from them.
            if(customer&&p("\\bintern\\b|"+IN_HOUSE).matcher(quote).find())return out;
            // A reference page reports client work: a system used, migrated or shut down there is the client's.
            if(references&&List.of("internal_use","planned_migration","completed_migration","shutdown").contains(context))context="customer_projects";
            // 0.12.2: own use is the use of the products of its clauses; 0.14.0 (item A2): a product an offer replaces is none
            Set<String> replaced=replaced(quote,vocab);
            for(String product:"internal_use".equals(context)?ownUseProducts(quote,vocab,named?actorName:null)
                    :"planned_migration".equals(context)?plannedMigrationProducts(quote,vocab,named?actorName:null):vocab.products(quote)) {
                if(replaced.contains(product))continue;
                Map<String,Object> fields=new TreeMap<>();fields.put("product",product);fields.put("context",context);
                fields.put("section",section);fields.put("scope",p("unternehmensweit|vollständig|organisation.wide|throughout our company").matcher(quote).find()?"organization":"unspecified");
                String role="unspecified";
                if("shutdown".equals(context))role="source";
                else if(context.endsWith("migration")) {
                    Matcher fromWord=p("\\b(?:von|from) ").matcher(quote),toWord=p("\\b(?:zu|auf|nach|to) ").matcher(quote);
                    int from=fromWord.find()?fromWord.start():-1,to=toWord.find()?toWord.start():-1;
                    if(from>=0&&vocab.products(quote.substring(from,to>from?to:quote.length())).contains(product))role="source";
                    else if(to>=0&&vocab.products(quote.substring(to)).contains(product))role="target";
                }
                fields.put("system_role",role);assertionDate(fields,quote);out.add(Values.canonical(fields));
            }
        } else if(Vocabulary.BUSINESS_NEED_SIGNAL.equals(predicate)) {
            if(references||faq||!(own||named)||CUSTOMERS.matcher(quote).find()||INTERMEDIARY.matcher(quote).find()||READER_NEED.matcher(quote).find())return out;
            for(String need:vocab.needs(quote)) {
                boolean cancelled=p("abgesagt|eingestellt|aufgegeben|cancelled|canceled").matcher(quote).find();
                boolean completed=p("fertiggestellt|abgeschlossen|completed|finished").matcher(quote).find();
                boolean ownProgram=List.of("leadership-development","team-development").contains(need)
                        &&p("Programm|program").matcher(quote).find()
                        &&(p("unser(?:e[nmrs]?)? (?:Führungskräfte|Mitarbeitende|Teams|Belegschaft)|our (?:leaders|employees|teams)").matcher(quote).find()
                            ||p("intern(?:e[nmrs]?)? |internal ").matcher(quote).find()&&p("starten|beginnen|initiieren|führen|start|launch").matcher(quote).find());
                if(NEGATION.matcher(quote).find()&&!cancelled)continue;
                String context=cancelled?"cancelled_need":completed?"completed_need"
                        :"care-transition".equals(need)&&OWN_CARE.matcher(quote).find()?"organizational_transition"
                        : PLAN.matcher(quote).find()?"planned_need":NEED.matcher(quote).find()||ownProgram?"explicit_need":null;
                if(context==null||OFFER.matcher(quote).find())continue;
                // 0.12.0 (T6, round 2, production sample of bauteamcheck): a provider's offer in the words of a building need is none
                // (the care transition of a hospital is worded for its patients: its own process, read by the matching rule)
                if(!"care-transition".equals(need)&&NEED_OFFER.matcher(quote).find())continue;
                Map<String,Object> fields=new TreeMap<>(Map.of("need",need,"context",context,"section",section,"scope","unspecified"));
                needDetails(fields,quote,need);
                assertionDate(fields,quote);out.add(Values.canonical(fields));
            }
        } else if(Vocabulary.BUSINESS_ROLE_EVIDENCE.equals(predicate)) {
            if(!OFFER.matcher(quote).find()||NEGATION.matcher(quote).find())return out;
            Map<String,String> roles=Map.of("it_consultancy","IT.Beratung|SAP.Beratung|IT.consulting|SAP.consulting",
                    "system_integrator","Systemintegration|system integration","recruiter","Personalvermittlung|personnel recruitment",
                    "job_portal","Stellenportal|job portal");
            for(Map.Entry<String,String> r:roles.entrySet())if(p(r.getValue()).matcher(quote).find())
                out.add(Values.canonical(new TreeMap<>(Map.of("role",r.getKey(),"context","own_offered_services","section",section))));
        } else if(Vocabulary.JOB_STATUS.equals(predicate)&&CLOSED.matcher(quote).find())out.add("ended");
        return out;
    }

    /** Only explicit project responsibility/region/process, never inferred from a company name or collection. */
    private static void needDetails(Map<String,Object> fields,String quote,String need) {
        Matcher location=Pattern.compile("(?:\\bin |am Standort |Standort |\\bat )([\\p{Lu}][\\p{L}-]{2,40}(?: [\\p{Lu}][\\p{L}-]{2,40})?)").matcher(quote);
        if(location.find())fields.put("location",location.group(1));
        Matcher project=Pattern.compile("(?iu)\\bProjekt\\s+[\"„]?([\\p{L}\\p{N}_-]{2,50})").matcher(quote);
        if(project.find())fields.put("project",project.group(1).toLowerCase(Locale.ROOT));
        String responsibility=p("als (?:Bauherr|Eigentümer|Gebäudebetreiber)|unser(?:e[nmrs]?)? (?:eigene[nmrs]? )?(?:Werk|Gebäude|Standort|Betrieb|Klinik|Krankenhaus)|our (?:own )?(?:building|site|factory)").matcher(quote).find()?"own_responsibility":"unknown";
        fields.put("responsibility",responsibility);
        String phase=p("abgesagt|aufgegeben|eingestellt|cancelled|canceled").matcher(quote).find()?"cancelled"
                :p("fertiggestellt|abgeschlossen|completed|finished").matcher(quote).find()?"completed"
                :p("Bauarbeiten|im Bau|construction started|under construction").matcher(quote).find()?"construction"
                :p("genehmigt|approved").matcher(quote).find()?"approved"
                :PLAN.matcher(quote).find()?"planned":"unknown";
        fields.put("phase",phase);
        if("care-transition".equals(need)) {
            fields.put("hospital_process",p("unser(?:e[nmrs]?)? (?:Klinik|Krankenhaus)|our hospital").matcher(quote).find()
                    &&p("organisiert|organisieren|koordiniert|koordinieren|Übergang|Überleitung|coordinate|transition").matcher(quote).find());
            String destination=p("ambulant(?:e[nrms]?)? (?:Versorgung|Pflege)|outpatient care").matcher(quote).find()?"outpatient"
                    :p("Kurzzeitpflege|short.term care").matcher(quote).find()?"short_term":null;
            if(destination!=null)fields.put("destination",destination);
        }
    }
}
