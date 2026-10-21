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
    public static final String VERSION="3";
    private static final Pattern CUSTOMERS=p("Kundenprojekt|unser(?:e[nrms]?)? Kunde|bei (?:unseren? )?Kunden|für (?:unsere )?Kunden|client projects?|customer projects?");
    private static final Pattern KNOWLEDGE=p("Kenntnisse|Kenntnissen|Erfahrung|Erfahrungen|Kompetenz|beherrschen|knowledge|skills?|proficiency|experience");
    private static final Pattern DESIRED=p("wünschenswert|erwünscht|von Vorteil|idealerweise|optional|desirable|preferred|nice.to.have");
    private static final Pattern USE=p("nutzen|nutzt|verwenden|verwendet|betreiben|betreibt|setzen.{0,60}ein|arbeiten.{0,20}mit|\\buses?\\b|\\boperate\\b|\\brunning\\b");
    /** "in-house", but not the SAP treasury modules In-House Cash / In-House Bank(ing): a product name is no actor. */
    private static final String IN_HOUSE="in.house(?![\\s-]{0,2}(?:cash|bank))";
    private static final Pattern OWN=p("\\bintern\\b|"+IN_HOUSE+"|\\bunser(?:e[nmrs]?)?\\b|\\bwir\\b|our internal|\\bwe\\b|eigene[nrms]?");
    private static final Pattern PLAN=p("planen|plant|geplant|beabsichtigen|beabsichtigt|werden.{0,60}migrieren|planning|planned|intend");
    private static final Pattern MIGRATION=p("Migration|migrieren|Umstellung|migrate|migration");
    private static final Pattern COMPLETED=p("abgeschlossen|vollzogen|completed|finished");
    /**
     * 0.12.0 (B2): support sought for an own migration ("Für unsere S/4HANA-Migration suchen wir externe Unterstützung"), a
     * need like a planned one; a consultant or other job title is recruiting, never such a need.
     */
    private static final Pattern SOUGHT=p("such(?:en|t).{0,40}(?:Unterstützung|Partner|Dienstleister)|(?:Unterstützung|Partner|Dienstleister).{0,60}gesucht"
            +"|(?:seeking|looking for).{0,40}(?:support|partner|service provider)");
    private static final Pattern SHUTDOWN=p("abgeschaltet|außer Betrieb|ausser Betrieb|stillgelegt|decommissioned|switched off");
    private static final Pattern NEGATION=p("\\bnicht\\b|\\bkeine?[nrs]?\\b|\\bnot\\b|\\bwithout\\b");
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
            +"|kundenprojekte|projekt-?beispiele|success-stor(?:y|ies)|erfolgsgeschichten|kunden|customers|clients)(?:[./_\\-]|$)");
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
                for(String value:read(predicate,quote,section,actor.type,actor.name))
                    out.add(new Claim(actor.ref,predicate,null,located(value,locator+":"+locatorOffset),tier,kind,DESIRED.matcher(quote).find(),
                            locator+":"+locatorOffset,quote));
            }
        }
    }

    private static boolean asciiDigit(char ch){return ch>='0'&&ch<='9';}

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
    public static List<String> read(String predicate,String quote,String section,String actorType,String actorName) {
        if(quote==null||quote.length()>900)return List.of();
        Signals vocab=KgVocabularies.get().signals;
        List<String> out=new ArrayList<>();boolean own=OWN.matcher(quote).find();
        boolean named=actorName!=null&&quote.toLowerCase(Locale.ROOT).contains(actorName.toLowerCase(Locale.ROOT));
        boolean references=REFERENCES.equals(section);
        // a job advertisement read for its employer is read as its posting: its field decides skills and qualifications
        boolean posting=Vocabulary.JOB.equals(actorType)||jobAd(section);
        String field=jobAd(section)?section.substring(JOB_AD.length()):section;
        if(Vocabulary.SYSTEM_SIGNAL.equals(predicate)) {
            String context=null;
            boolean customer=CUSTOMERS.matcher(quote).find();
            if(NEGATION.matcher(quote).find())return out;
            // An unnamed "we" next to another named company: whose system it is stays unclear (postings resolve their employer).
            if(own&&!named&&!posting&&otherCompanyNamed(quote,actorName,vocab))own=false;
            if(OFFER.matcher(quote).find()&&p("Beratung|Integration|Implementierung|Support|Unterstützung|Schulung|Training|consulting|implementation").matcher(quote).find())context="offered_capability";
            else if(customer)context="customer_projects";
            else if(SHUTDOWN.matcher(quote).find()&&(own||named)) context="shutdown";
            else if(MIGRATION.matcher(quote).find()&&COMPLETED.matcher(quote).find()&&(own||named))context="completed_migration";
            else if(MIGRATION.matcher(quote).find()&&(PLAN.matcher(quote).find()||SOUGHT.matcher(quote).find()&&!OFFER.matcher(quote).find())&&(own||named))context="planned_migration";
            else if(USE.matcher(quote).find()&&(own||named))context="internal_use";
            else if(KNOWLEDGE.matcher(quote).find()||"skills".equals(field)||"qualifications".equals(field))
                // A requirement belongs to a posting; a company's own competence statement (Kernkompetenz) is no need.
                context=!posting&&!"skills".equals(field)&&!"qualifications".equals(field)?"stated_competence"
                        :DESIRED.matcher(quote).find()?"desirable_competence":"required_competence";
            if(context==null)return out;
            // Simultaneous internal/customer claims need finer context; never assert internal use from them.
            if(customer&&p("\\bintern\\b|"+IN_HOUSE).matcher(quote).find())return out;
            // A reference page reports client work: a system used, migrated or shut down there is the client's.
            if(references&&List.of("internal_use","planned_migration","completed_migration","shutdown").contains(context))context="customer_projects";
            for(String product:vocab.products(quote)) {
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
            if(references||!(own||named)||CUSTOMERS.matcher(quote).find()||INTERMEDIARY.matcher(quote).find())return out;
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
