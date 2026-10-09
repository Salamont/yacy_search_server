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
 */
public final class BusinessSignals {
    public static final String VERSION="2";
    private static final Pattern CUSTOMERS=p("Kundenprojekt|unser(?:e[nrms]?)? Kunde|bei (?:unseren? )?Kunden|für (?:unsere )?Kunden|client projects?|customer projects?");
    private static final Pattern KNOWLEDGE=p("Kenntnisse|Kenntnissen|Erfahrung|Erfahrungen|Kompetenz|beherrschen|knowledge|skills?|proficiency|experience");
    private static final Pattern DESIRED=p("wünschenswert|erwünscht|von Vorteil|idealerweise|optional|desirable|preferred|nice.to.have");
    private static final Pattern USE=p("nutzen|nutzt|verwenden|verwendet|betreiben|betreibt|setzen.{0,60}ein|arbeiten.{0,20}mit|uses?|operate|running");
    private static final Pattern OWN=p("\\bintern\\b|in.house|\\bunser(?:e[nmrs]?)?\\b|\\bwir\\b|our internal|\\bwe\\b|eigene[nrms]?");
    private static final Pattern PLAN=p("planen|plant|geplant|beabsichtigen|beabsichtigt|werden.{0,60}migrieren|planning|planned|intend");
    private static final Pattern MIGRATION=p("Migration|migrieren|Umstellung|migrate|migration");
    private static final Pattern COMPLETED=p("abgeschlossen|vollzogen|completed|finished");
    private static final Pattern SHUTDOWN=p("abgeschaltet|außer Betrieb|ausser Betrieb|stillgelegt|decommissioned|switched off");
    private static final Pattern NEGATION=p("\\bnicht\\b|\\bkeine?[nrs]?\\b|\\bnot\\b|\\bwithout\\b");
    private static final Pattern OFFER=p("wir bieten|wir erbringen|wir unterstützen|unsere Leistungen|we offer|we provide|we support");
    private static final Pattern NEED=p("Bedarf|benötigen|benötigt|brauchen|suchen.{0,40}(?:Unterstützung|Partner)|need|require|seeking.{0,40}support");
    private static final Pattern OWN_CARE=p("unser(?:e[nmrs]?)?.{0,80}(?:Entlassmanagement|Überleitungsmanagement|Pflegeüberleitung)|wir.{0,80}(?:organisieren|koordinieren).{0,80}(?:Entlass|Pflegeüber)|our.{0,80}discharge management");
    private static final Pattern CLOSED=p("Stelle (?:ist )?(?:besetzt|geschlossen)|Position (?:ist )?besetzt|Bewerbungsverfahren (?:ist )?beendet|vacancy (?:is )?(?:filled|closed)");
    public static final Pattern INTERMEDIARY=p("im Auftrag (?:unseres|eines) Kunden|für unseren Kunden|für einen unserer Kunden|on behalf of (?:our|a) client|for our client");
    private BusinessSignals() { }
    private static Pattern p(String regex) {return Pattern.compile("(?iu)"+regex);}

    public static void extract(String text,Mention actor,Extraction out,int tier,int kind,String section,String locator) {
        if(text==null||actor==null)return;
        // JSON-LD descriptions may contain HTML; preserve paragraph boundaries.
        String plain=text.replaceAll("(?i)</?(?:p|li|br|h[1-6])[^>]*>","\n").replaceAll("<[^>]+>"," ");
        Matcher clauses=Pattern.compile("(?:[^.!?;\\n]|(?<=[0-9])\\.(?=[0-9]))+(?:[.!?;]|$)").matcher(plain);
        while(clauses.find()) {
            String quote=clauses.group().trim();if(quote.isEmpty()||quote.length()>900)continue;
            for(String predicate:List.of(Vocabulary.SYSTEM_SIGNAL,Vocabulary.BUSINESS_NEED_SIGNAL,Vocabulary.BUSINESS_ROLE_EVIDENCE,Vocabulary.JOB_STATUS)) {
                if(Vocabulary.JOB_STATUS.equals(predicate)&&!Vocabulary.JOB.equals(actor.type))continue;
                if((Vocabulary.BUSINESS_NEED_SIGNAL.equals(predicate)||Vocabulary.BUSINESS_ROLE_EVIDENCE.equals(predicate))
                        && !Vocabulary.ORGANIZATION.equals(actor.type)&&!Vocabulary.FACILITY.equals(actor.type))continue;
                for(String value:read(predicate,quote,section,actor.type,actor.name))
                    out.add(new Claim(actor.ref,predicate,null,located(value,locator+":"+clauses.start()),tier,kind,DESIRED.matcher(quote).find(),
                            locator+":"+clauses.start(),quote));
            }
        }
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
        if(Vocabulary.SYSTEM_SIGNAL.equals(predicate)) {
            String context=null;
            boolean customer=CUSTOMERS.matcher(quote).find();
            if(NEGATION.matcher(quote).find())return out;
            if(OFFER.matcher(quote).find()&&p("Beratung|Integration|Implementierung|Support|Unterstützung|Schulung|Training|consulting|implementation").matcher(quote).find())context="offered_capability";
            else if(customer)context="customer_projects";
            else if(SHUTDOWN.matcher(quote).find()&&(own||named)) context="shutdown";
            else if(MIGRATION.matcher(quote).find()&&COMPLETED.matcher(quote).find()&&(own||named))context="completed_migration";
            else if(MIGRATION.matcher(quote).find()&&PLAN.matcher(quote).find()&&(own||named))context="planned_migration";
            else if(USE.matcher(quote).find()&&(own||named))context="internal_use";
            else if(KNOWLEDGE.matcher(quote).find()||"skills".equals(section)||"qualifications".equals(section))
                context=DESIRED.matcher(quote).find()?"desirable_competence":"required_competence";
            if(context==null)return out;
            // Simultaneous internal/customer claims need finer context; never assert internal use from them.
            if(customer&&p("\\bintern\\b|in.house").matcher(quote).find())return out;
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
            if(!(own||named)||CUSTOMERS.matcher(quote).find()||INTERMEDIARY.matcher(quote).find())return out;
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
