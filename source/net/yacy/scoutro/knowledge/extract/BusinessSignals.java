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
    public static final String VERSION="1";
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
        Matcher clauses=Pattern.compile("[^.!?;\\n]+(?:[.!?;]|$)").matcher(plain);
        while(clauses.find()) {
            String quote=clauses.group().trim();if(quote.isEmpty()||quote.length()>900)continue;
            for(String predicate:List.of(Vocabulary.SYSTEM_SIGNAL,Vocabulary.BUSINESS_NEED_SIGNAL,Vocabulary.BUSINESS_ROLE_EVIDENCE,Vocabulary.JOB_STATUS)) {
                if(Vocabulary.JOB_STATUS.equals(predicate)&&!Vocabulary.JOB.equals(actor.type))continue;
                if((Vocabulary.BUSINESS_NEED_SIGNAL.equals(predicate)||Vocabulary.BUSINESS_ROLE_EVIDENCE.equals(predicate))
                        && !Vocabulary.ORGANIZATION.equals(actor.type)&&!Vocabulary.FACILITY.equals(actor.type))continue;
                for(String value:read(predicate,quote,section,actor.type,actor.name))
                    out.add(new Claim(actor.ref,predicate,null,value,tier,kind,DESIRED.matcher(quote).find(),
                            locator+":"+clauses.start(),quote));
            }
        }
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
            if(SHUTDOWN.matcher(quote).find()&&(own||named)) context="shutdown";
            else if(MIGRATION.matcher(quote).find()&&COMPLETED.matcher(quote).find()&&(own||named))context="completed_migration";
            else if(MIGRATION.matcher(quote).find()&&PLAN.matcher(quote).find()&&(own||named))context="planned_migration";
            else if(customer)context="customer_projects";
            else if(NEGATION.matcher(quote).find())return out;
            else if(USE.matcher(quote).find()&&(own||named))context="internal_use";
            else if(KNOWLEDGE.matcher(quote).find()||"skills".equals(section)||"qualifications".equals(section))
                context=DESIRED.matcher(quote).find()?"desirable_competence":"required_competence";
            else if(OFFER.matcher(quote).find()&&p("Beratung|Integration|Implementierung|Support|consulting|implementation").matcher(quote).find())context="offered_capability";
            if(context==null)return out;
            // Simultaneous internal/customer claims need finer context; never assert internal use from them.
            if(customer&&p("\\bintern\\b|in.house").matcher(quote).find())return out;
            for(String product:vocab.products(quote)) {
                Map<String,Object> fields=new TreeMap<>();fields.put("product",product);fields.put("context",context);
                fields.put("section",section);fields.put("scope",p("unternehmensweit|vollständig|organisation.wide|throughout our company").matcher(quote).find()?"organization":"unspecified");
                String role="unspecified";
                if("shutdown".equals(context))role="source";
                else if(context.endsWith("migration")) {
                    int from=quote.toLowerCase(Locale.ROOT).indexOf("von "),to=quote.toLowerCase(Locale.ROOT).indexOf(" zu ");
                    if(from>=0&&to>from) {
                        if(vocab.products(quote.substring(from,to)).contains(product))role="source";
                        else if(vocab.products(quote.substring(to)).contains(product))role="target";
                    }
                }
                fields.put("system_role",role);out.add(Values.canonical(fields));
            }
        } else if(Vocabulary.BUSINESS_NEED_SIGNAL.equals(predicate)) {
            if(!(own||named)||CUSTOMERS.matcher(quote).find()||INTERMEDIARY.matcher(quote).find()||NEGATION.matcher(quote).find())return out;
            for(String need:vocab.needs(quote)) {
                String context="care-transition".equals(need)&&OWN_CARE.matcher(quote).find()?"organizational_transition"
                        : PLAN.matcher(quote).find()?"planned_need":NEED.matcher(quote).find()?"explicit_need":null;
                if(context==null||OFFER.matcher(quote).find())continue;
                out.add(Values.canonical(new TreeMap<>(Map.of("need",need,"context",context,"section",section,"scope","unspecified"))));
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
}
