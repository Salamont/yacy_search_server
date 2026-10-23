/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.derive;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.extract.*;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/** Six explicit rule groups. No target industry, company-name classification or LLM matching. */
public final class MatchingRules {
    /** The contexts of a client documented by a provider's reference (BusinessRules.references): a purchased service or program. */
    static final Set<String> PURCHASED=Set.of("purchased_service","purchased_need");
    /** True if both pages belong to one site (the host without "www."). */
    static boolean sameSite(String a,String b) {
        String x=host(a),y=host(b);return x!=null&&x.equals(y);
    }
    private static String host(String url) {
        if(url==null)return null;
        try{String h=java.net.URI.create(url.trim().replace(" ","%20")).getHost();return h==null?null:h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");}
        catch(IllegalArgumentException e){return null;}
    }
    /** The rules' patterns, compiled once (0.11.8: they were compiled again for every contribution read). */
    private static final java.util.concurrent.ConcurrentHashMap<String,Pattern> PATTERNS=new java.util.concurrent.ConcurrentHashMap<>();
    public static final String VERSION="1";
    /**
     * 0.12.1: the current reading of an observation's page, as an SQL condition on the observation {@code o}. Observations are
     * history: a page read again by a newer extractor keeps its earlier readings (SCOUTRO_DURABLE_OBSERVATIONS.md), so readings a
     * later rule version no longer makes (a product name in a menu read as a system in use) fed the matching beside the new ones.
     * An observation is current unless its page is still active, was processed after the observation was recorded and carries no
     * evidence of its tier from its extractor any more. A page that left the index keeps its observations (an advertisement that
     * is gone is still the evidence of its job).
     */
    public static String current(String o) {
        return "(NOT EXISTS(SELECT 1 FROM kg_doc cd WHERE cd.doc_id="+o+".source_id AND cd.state=1 AND cd.processed_at>"+o+".recorded_at)"
                +" OR EXISTS(SELECT 1 FROM kg_doc cd JOIN kg_evidence ce ON ce.doc_rowid=cd.doc_rowid AND ce.tier="+o+".tier"
                +" JOIN kg_extractor cx ON cx.ext_id=ce.ext_id WHERE cd.doc_id="+o+".source_id AND cx.name||'/'||cx.version="+o+".extractor))";
    }
    /**
     * The checks of one read (0.11.8), by signal, actor and offer: within one read transaction they give the same answer for
     * every contribution that shares them, and a projection of 10,000 contributions asked them again for each (read_timeout).
     * Off unless a reader opens it with {@link #openMemo()}; the matching run never uses it.
     */
    private static final ThreadLocal<Map<String,Object>> MEMO=new ThreadLocal<>();
    @FunctionalInterface private interface Check<T>{T get()throws SQLException;}
    /** Starts the memo of this thread's read; {@link #closeMemo()} ends it. Returns false if one was already open (nested read). */
    public static boolean openMemo(){if(MEMO.get()!=null)return false;MEMO.set(new HashMap<>());return true;}
    public static void closeMemo(){MEMO.remove();}
    @SuppressWarnings("unchecked")
    private static <T> T memo(String key,Check<T> check)throws SQLException {
        Map<String,Object> m=MEMO.get();if(m==null)return check.get();
        if(m.containsKey(key))return (T)m.get(key);
        T value=check.get();m.put(key,value);return value;
    }
    private MatchingRules() { }
    /**
     * Within an open memo: the answers of {@link #hasPosting} and the retirement candidates of {@link #systemRetired} for the
     * observations the contributions of {@code entity} refer to, in two queries instead of two per observation (0.11.8). An
     * observation without a source revision or an actor is left to the single queries.
     */
    public static void preload(Connection c,String entity,Collection<Observation> observations)throws SQLException {
        Map<String,Object> m=MEMO.get();if(m==null)return;
        String ids="SELECT r.observation_id FROM kg_match_ref r JOIN kg_match_contribution mc ON mc.public_id=r.contribution_id"
                +" WHERE mc.provider_id=? OR mc.candidate_id=?";
        Set<String> postings=new HashSet<>();
        try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT o.source_id,o.content_revision FROM kg_observation o WHERE o.public_id IN ("+ids+")"
                +" AND EXISTS(SELECT 1 FROM kg_observation j WHERE j.source_id=o.source_id AND j.content_revision=o.content_revision AND j.subject_type='job')")) {
            p.setString(1,entity);p.setString(2,entity);
            try(ResultSet r=p.executeQuery()){while(r.next())postings.add(r.getString(1)+"\0"+r.getString(2));}
        }
        Map<String,Map<String,List<Observation>>> retirements=new HashMap<>();
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE predicate='system_signal' AND json_valid(value)"
                +" AND organization_id IN (SELECT o.organization_id FROM kg_observation o WHERE o.public_id IN ("+ids+"))"
                +" AND assertion_status='recorded' AND json_extract(value,'$.context') IN('shutdown','completed_migration') AND "+current("kg_observation")
                +" ORDER BY observation_rowid")) {
            p.setString(1,entity);p.setString(2,entity);
            try(ResultSet r=p.executeQuery()){while(r.next()) {
                Observation later=Observation.read(c,r);
                if(later.value.opt("product") instanceof String product)
                    retirements.computeIfAbsent(later.actor,k->new HashMap<>()).computeIfAbsent(product,k->new ArrayList<>()).add(later);
            }}
        }
        for(Observation o:observations) {
            if(o.source!=null&&o.revision!=null)m.putIfAbsent("hasPosting\0"+o.source+"\0"+o.revision,postings.contains(o.source+"\0"+o.revision));
            if(o.actor!=null)m.putIfAbsent("retirements\0"+o.actor,retirements.getOrDefault(o.actor,Map.of()));
        }
    }

    public static final class Observation {
        public String id,actor,originalActor,subject,type,predicate,text,source,url,revision,status,assertedAt;
        public JSONObject value,identity;
        public Long observedAt;
        /** False for a reading its page no longer carries ({@link MatchingRules#current}); read with {@link #read(Connection,ResultSet)}. */
        public boolean current=true;
        public final Map<Integer,String> collections=new TreeMap<>();
        public static Observation read(Connection c,String id)throws SQLException {
            try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE public_id=?")) {
                p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?read(c,r):null;}
            }
        }
        public static Observation read(Connection c,ResultSet r)throws SQLException {
            Observation o=fields(r);
            try(PreparedStatement s=c.prepareStatement("SELECT k.coll_id,k.name FROM kg_observation_scope os"
                    +" JOIN kg_observation b USING(observation_rowid) JOIN kg_collection k USING(coll_id) WHERE b.public_id=? ORDER BY k.name")) {
                s.setString(1,o.id);try(ResultSet names=s.executeQuery()){while(names.next())o.collections.put(names.getInt(1),names.getString(2));}
            }
            try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM kg_observation o WHERE o.observation_rowid=? AND NOT "+current("o"))) {
                s.setLong(1,r.getLong("observation_rowid"));try(ResultSet old=s.executeQuery()){o.current=!old.next();}
            }return o;
        }
        /**
         * The observations the matching contributions of {@code entity} refer to, with their collections, in two queries
         * (0.11.8: one read of an entity with 40,000 contributions read 80,000 times one observation).
         */
        public static Map<String,Observation> referencedBy(Connection c,String entity)throws SQLException {
            String ids="SELECT r.observation_id FROM kg_match_ref r JOIN kg_match_contribution m ON m.public_id=r.contribution_id"
                    +" WHERE m.provider_id=? OR m.candidate_id=?";
            Map<String,Observation> out=new HashMap<>();
            try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE public_id IN ("+ids+")")) {
                p.setString(1,entity);p.setString(2,entity);
                try(ResultSet r=p.executeQuery()){while(r.next()){Observation o=fields(r);out.put(o.id,o);}}
            }
            try(PreparedStatement s=c.prepareStatement("SELECT b.public_id,k.coll_id,k.name FROM kg_observation_scope os"
                    +" JOIN kg_observation b USING(observation_rowid) JOIN kg_collection k USING(coll_id) WHERE b.public_id IN ("+ids+")")) {
                s.setString(1,entity);s.setString(2,entity);
                try(ResultSet names=s.executeQuery()){while(names.next()){Observation o=out.get(names.getString(1));if(o!=null)o.collections.put(names.getInt(2),names.getString(3));}}
            }
            try(PreparedStatement s=c.prepareStatement("SELECT o.public_id FROM kg_observation o WHERE o.public_id IN ("+ids+") AND NOT "+current("o"))) {
                s.setString(1,entity);s.setString(2,entity);
                try(ResultSet old=s.executeQuery()){while(old.next()){Observation o=out.get(old.getString(1));if(o!=null)o.current=false;}}
            }return out;
        }
        /** The row without its collections (enough for {@link #offers}, which never authorizes anything). */
        static Observation fields(ResultSet r)throws SQLException {
            Observation o=new Observation();o.id=r.getString("public_id");o.actor=r.getString("organization_id");
            o.originalActor=r.getString("original_organization_id");
            o.subject=r.getString("subject_id");o.type=r.getString("subject_type");o.predicate=r.getString("predicate");
            o.text=r.getString("quote");o.source=r.getString("source_id");o.url=r.getString("source_url");o.status=r.getString("assertion_status");
            o.revision=r.getString("content_revision");
            o.assertedAt=r.getString("asserted_at");o.observedAt=r.getObject("observed_at")==null?null:r.getLong("observed_at");
            try {o.identity=new JSONObject(r.getString("identity_context"));String raw=r.getString("value");
                o.value=raw.startsWith("{")?new JSONObject(raw):KgJson.obj("literal",raw);
            }catch(JSONException e){throw new SQLException("invalid observation",e);}
            return o;
        }
        public boolean accessible(Viewer v){return v==null?!collections.isEmpty():v.all()?!collections.isEmpty():collections.keySet().stream().anyMatch(v.collections()::contains);}
        public boolean usable(){return actor!=null&&"recorded".equals(status)&&current&&!text.isBlank();}
        public String name() {
            if(!Objects.equals(actor,originalActor))return null;
            JSONArray names=identity.optJSONArray("organization");
            if(names!=null)for(int i=0;i<names.length();i++) {
                JSONObject n=names.optJSONObject(i);if(n!=null&&List.of("name","legal_name").contains(n.optString("predicate")))return n.optString("value");
            }return null;
        }
    }
    public static final class Offer {
        public final Observation evidence;
        public final String rule,key,service;
        public final int kind;
        Offer(Observation o,String rule,String key,String service,int kind){this.evidence=o;this.rule=rule;this.key=key;this.service=service;this.kind=kind;}
    }
    public static final class Assessment {
        public String strength,temporal,fit,reason;
        public int score;
        public final List<String> uncertainties=new ArrayList<>();
        public final List<Observation> extra=new ArrayList<>();
    }
    /**
     * The words of a provider's support of a product: the nouns and, since 0.12.1, the verbs a provider writes them with ("wir beraten
     * Sie", "führen SAP DRC in Ihrem System ein", "begleiten Ihre Migration"); the noun alone missed most offers written as sentences.
     */
    static final String SUPPORT="Beratung|berat(?:en|e|end)\\b|consulting|Support|Unterstützung|unterstütz(?:en|t|e)\\b|Schulung|schul(?:en|t)\\b"
            +"|Training|Workshop|Einführung|einführ\\p{L}*|führ(?:en|t|e)\\s+(?:\\S+\\s+){1,8}?ein\\b|Integration|integrier\\p{L}*"
            +"|Implementierung|implementier\\p{L}*|Betreuung|betreu(?:en|t|e)\\b|Migration|migrier\\p{L}*|begleit(?:en|et|e|ung)\\b"
            +"|Umsetzung|(?:setzen|setzt)\\s+(?:\\S+\\s+){1,8}?um\\b";
    private static boolean has(String text,String regex){return PATTERNS.computeIfAbsent(regex,r->Pattern.compile("(?iu)"+r)).matcher(text).find();}
    private static String compact(String text){return text.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();}

    public static List<Offer> offers(Observation o) {
        if(MEMO.get()==null)return offersOf(o);
        try{return memo("offers\0"+o.id+"\0"+o.status,()->offersOf(o));}catch(SQLException e){throw new IllegalStateException(e);}
    }
    private static List<Offer> offersOf(Observation o) {
        if(!o.usable()||"job".equals(o.type))return List.of();
        boolean service="service".equals(o.type)&&List.of("name","category","description").contains(o.predicate)
                &&o.identity.optJSONArray("assignment")!=null&&o.identity.optJSONArray("assignment").length()>0;
        boolean offered=Set.of("organization","facility").contains(o.type)&&"system_signal".equals(o.predicate)&&"offered_capability".equals(o.value.optString("context"));
        if(!service&&!offered)return List.of();
        String text=o.value.optString("literal",o.text)+" "+o.text;
        List<Offer> out=new ArrayList<>();
        Set<String> products=new LinkedHashSet<>();
        if(has(text,SUPPORT))products.addAll(KgVocabularies.get().signals.products(text));
        // 0.12.1: a service named or categorized as the product itself ("SAP", software/sap) is a service of it, also without a
        // word of support (as for an employer's own postings, offersItself); the product of its name only, never of its quote
        if(service&&List.of("name","category").contains(o.predicate))products.addAll(KgVocabularies.get().signals.products(o.value.optString("literal","")));
        for(String product:products) {
            String rule=Set.of("revit","archicad").contains(product)?"cad-bim":"it-support";
            out.add(new Offer(o,rule,product,product+" support",3));
        }
        if(service) {
            if(has(text,"Architektur|architectur|Entwurfsplanung|Genehmigungsplanung|Generalplanung|Bauplanung"))
                for(String need:List.of("new-build","rebuild","site-expansion"))out.add(new Offer(o,"construction",need,"architecture/planning",3));
            if(has(text,"Hochbau|Bauausführung|Bauleistung|building construction"))
                for(String need:List.of("new-build","rebuild","site-expansion"))out.add(new Offer(o,"construction",need,"construction execution",3));
            if(has(text,"Energieberatung|energy consult|energetische.{0,15}Planung|Gebäudetechnik|Fachplanung"))
                out.add(new Offer(o,"energy-renovation","energy-renovation","energy/facility planning",3));
            if(has(text,"Führungscoaching|Führungskräftecoaching|Executive.Coaching|Leadership.Coaching|Führungsentwicklung|Organisationsentwicklung"))
                out.add(new Offer(o,"leadership-development","leadership-development","leadership/organization development",3));
            if(has(text,"Teamcoaching|Team.Coaching|Teamentwicklung|Organisationsentwicklung|team development"))
                out.add(new Offer(o,"team-development","team-development","team/organization development",3));
            if(has(text,"ambulante[_ ]Pflege|häusliche Krankenpflege|outpatient care"))
                out.add(new Offer(o,"care-transition","care-transition","outpatient",4));
            if(has(text,"Kurzzeitpflege|short.term care"))
                out.add(new Offer(o,"care-transition","care-transition","short_term",4));
        }
        return out;
    }

    /** Read-time validation uses current assignments, policy and withdrawals as well as current scopes. */
    public static Assessment assess(Connection c,Offer offer,Observation signal,List<Observation> extras,KgConfig cfg,Viewer permitted)throws SQLException {
        if(!offer.evidence.usable()||!signal.usable()||offer.evidence.actor.equals(signal.actor)
                ||!offer.evidence.accessible(permitted)||!signal.accessible(permitted))return null;
        String context=signal.value.optString("context");
        // 0.12.0 (T6, round 2): a client documented by a provider's reference bought that provider's service; its own reference
        // names an existing client (no suggestion), every other provider of the service may get one
        boolean purchased=PURCHASED.contains(context);
        if(purchased&&sameSite(offer.evidence.url,signal.url))return null;
        // A reference/case-study page describes client work: neither the operator's own system nor its own need.
        if(!purchased&&BusinessSignals.referencePage(signal.url))return null;
        Assessment result=new Assessment();result.fit="explicit_rule";result.score=60;result.strength="qualified";
        // a hint read from a job advertisement for its employer (0.10.2) is job-based like a posting's own signal
        boolean jobAd=BusinessSignals.jobAd(signal.value.optString("section"));
        boolean jobBased="job".equals(signal.type)||jobAd;
        if(!jobBased&&hasPosting(c,signal)) {
            Observation employer=postingEmployer(c,signal,extras,permitted);if(employer==null)return null;
            result.extra.add(employer);jobBased=true;
        }
        if(jobBased) {
            if(("job".equals(signal.type)&&!"source_declared".equals(signal.identity.optString("employer_assignment")))
                    ||!Objects.equals(signal.actor,signal.originalActor))return null;
            if(signal.collections.entrySet().stream().noneMatch(e->(permitted==null||permitted.all()||permitted.collections().contains(e.getKey()))
                    &&cfg.jobSignalsMatching(e.getValue())))return null;
        }
        if(List.of("it-support","cad-bim").contains(offer.rule)) {
            if(!Set.of("organization","facility","job").contains(signal.type))return null;
            if(!"system_signal".equals(signal.predicate)||!offer.key.equals(signal.value.optString("product"))
                    ||!List.of("internal_use","required_competence","desirable_competence","planned_migration","purchased_service").contains(context))return null;
            // A requirement belongs to a posting; a company's own competence statement is no need (often its offer).
            if(context.endsWith("_competence")&&!"job".equals(signal.type)&&!jobAd)return null;
            // The stored context must still be what the shared parser reads in the stored quote (veto only, never an upgrade);
            // a documented purchase comes from the reader of references, not from the sentence reader
            if(!purchased&&!contextConfirmed(signal,context))return null;
            if(List.of("internal_use","planned_migration").contains(context)&&systemRetired(c,signal))return null;
            // 0.12.0 (T6, round 2): a client speaking on the actor's page ("Wir nutzen dab Nexus …" on dab's page) or the actor named
            // as the provider of what another uses is the client's use, not the actor's (veto at reading, as the context check).
            if(!jobBased&&BusinessSignals.customerVoice(signal.text,actorNames(signal)))return null;
            if(jobBased) {
                // A delivery role of an employer that offers the product itself (its consultant, a role building its business
                // with clients) is capacity, not a customer need; a title alone excludes nothing (owner decision, 22 Oct 2026).
                if((itConsultancy(c,signal.actor)||offersItself(c,signal.actor,offer)||siteOffersItself(c,signal,offer))
                        &&(ownCapacity(signal)||postingDeliveryRole(c,signal)))return null;
                result.uncertainties.add("company_role_unknown");result.score-=15;
            }
            result.strength="internal_use".equals(context)||purchased?"stated":"weak";
            if(jobBased&&"stated".equals(result.strength))result.strength="qualified";
            result.score+= "internal_use".equals(context)||purchased?15:"planned_migration".equals(context)?5:"desirable_competence".equals(context)?-15:-10;
            result.temporal="planned_migration".equals(context)?"planned_not_reconfirmed":"historical_not_reconfirmed";
            result.reason=purchased?offer.service+" meets a documented purchase of "+offer.key+" (a reference of another provider); present need not reconfirmed"
                    :offer.service+" meets "+context+"; present use not reconfirmed";
        } else {
            if(!"business_need_signal".equals(signal.predicate)||!offer.key.equals(signal.value.optString("need"))||!Set.of("organization","facility").contains(signal.type)
                    ||!List.of("planned_need","explicit_need","organizational_transition","purchased_need").contains(context))return null;
            // a documented purchase of a program (team or leadership development) only; a past building or care project is no need
            if(purchased&&!List.of("leadership-development","team-development").contains(offer.rule))return null;
            if(jobBased&&List.of("leadership-development","team-development").contains(offer.rule))return null;
            // 0.12.0 (T6, round 2): the reader's need on a provider's page ("wo Sie uns brauchen") is its offer (veto at reading).
            if(BusinessSignals.readerNeed(signal.text))return null;
            if(needRetired(c,signal))return null;
            String phase=signal.value.optString("phase","unknown");
            if("construction".equals(offer.rule)||"energy-renovation".equals(offer.rule)) {
                if(!"own_responsibility".equals(signal.value.optString("responsibility"))||signal.value.optString("location").isBlank()
                        ||!List.of("planned","approved","construction").contains(phase))return null;
                if("construction".equals(offer.rule)&&"architecture/planning".equals(offer.service)&&"construction".equals(phase))return null;
                if("construction execution".equals(offer.service)&&"planned".equals(phase)) {
                    result.uncertainties.add("procurement_and_execution_phase_unconfirmed");result.score-=10;
                }
            }
            if("care-transition".equals(offer.rule)) {
                if(has(signal.text,"für (?:Frau|Herr[n]?)\\b|Patient(?:in)? (?:Frau|Herr[n]?)\\b|eine? Patient(?:in)?\\b|a patient\\b|the patient with\\b"))return null;
                if(!signal.value.optBoolean("hospital_process")||!offer.service.equals(signal.value.optString("destination"))
                        ||signal.value.optString("location").isBlank())return null;
                Observation region=extras.stream().filter(o->o.usable()&&offer.evidence.actor.equals(o.actor)
                        &&"service_area".equals(o.predicate)&&o.accessible(permitted)
                        &&regionName(o).equals(compact(signal.value.optString("location")))).findFirst().orElse(null);
                if(region==null)return null;
                result.extra.add(region);result.fit="explicit_rule_and_region";result.uncertainties.add("organizational_capacity_unconfirmed");
            }
            result.temporal="planned_need".equals(context)?"planned_not_reconfirmed":"observed_need_not_reconfirmed";
            result.reason=offer.service+" meets "+offer.key+"; present project/program/process status not reconfirmed";
        }
        if(signal.observedAt==null){result.uncertainties.add("observation_date_unknown");result.score-=5;}
        result.uncertainties.add("external_purchase_or_cooperation_unconfirmed");
        return result;
    }
    /** The names the extraction knew the actor by: the stated names of the subject and of its organization. */
    private static List<String> actorNames(Observation o) {
        List<String> out=new ArrayList<>();
        for(String part:List.of("subject","organization")) {
            JSONArray names=o.identity.optJSONArray(part);
            if(names!=null)for(int i=0;i<names.length();i++) {
                JSONObject n=names.optJSONObject(i);
                if(n!=null&&List.of("name","legal_name").contains(n.optString("predicate"))&&!n.optString("value").isBlank())out.add(n.optString("value"));
            }
        }return out;
    }
    /** Re-reads the stored quote with the current shared parser; an unconfirmed product/context pair no longer qualifies. */
    static boolean contextConfirmed(Observation signal,String context) {
        if(MEMO.get()==null)return contextConfirmedOf(signal,context);
        try{return memo("context\0"+signal.id+"\0"+context,()->contextConfirmedOf(signal,context));}catch(SQLException e){throw new IllegalStateException(e);}
    }
    private static boolean contextConfirmedOf(Observation signal,String context) {
        String product=signal.value.optString("product"),section=signal.value.optString("section","text");
        List<String> names=new ArrayList<>();names.add(null);names.addAll(actorNames(signal));
        for(String name:names)for(String value:BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,signal.text,section,signal.type,name)) {
            JSONObject v=Values.json(value);
            if(v!=null&&product.equals(v.optString("product"))&&context.equals(v.optString("context")))return true;
        }return false;
    }
    /** The employer offers the same product support itself (an offered capability or an assigned service, as for providers). */
    private static boolean offersItself(Connection c,String actor,Offer offer)throws SQLException {
        return memo("offersItself\0"+actor+"\0"+offer.rule+"\0"+offer.key,()->offersItselfOf(c,actor,offer));
    }
    private static boolean offersItselfOf(Connection c,String actor,Offer offer)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE organization_id=? AND assertion_status='recorded' AND"
                +" ((predicate='system_signal' AND json_valid(value) AND json_extract(value,'$.context')='offered_capability'"
                +" AND json_extract(value,'$.product')=?) OR (subject_type='service' AND predicate IN('name','category','description')))"
                +" AND "+current("kg_observation")+" ORDER BY observation_rowid LIMIT 2501")) {
            p.setString(1,actor);p.setString(2,offer.key);try(ResultSet r=p.executeQuery()){while(r.next()) {
                Observation o=Observation.fields(r);
                for(Offer own:offers(o))if(own.rule.equals(offer.rule)&&own.key.equals(offer.key))return true;
                // 0.12.0 (T6, round 2): a service named or categorized as the product itself ("SAP", software/sap) is a service of it,
                // also without a word of support (the employer's own consultant posting is then its delivery capacity)
                if(List.of("it-support","cad-bim").contains(offer.rule)&&o.usable()&&"service".equals(o.type)&&List.of("name","category").contains(o.predicate)
                        &&KgVocabularies.get().signals.products(o.value.optString("literal",o.text)).contains(offer.key))return true;
            }}
        }return false;
    }
    /**
     * 0.12.0 (T6, round 2): an employer named as the site of its posting ("QUANTO Solutions" on quanto-solutions.de) is the
     * site's operator, also where the graph keeps the two apart (the posting's employer and the site's unnamed operator); the
     * operator's own offer of the product or its role as an IT consultancy then counts as the employer's. A recruiter or a
     * portal advertising for another company is no such employer: its name is not the site's.
     */
    private static boolean siteOffersItself(Connection c,Observation signal,Offer offer)throws SQLException {
        String host=host(signal.url);if(host==null)return false;
        String domain=net.yacy.scoutro.knowledge.resolve.Normalizers.registrableDomain(host);if(domain==null||domain.indexOf('.')<0)return false;
        String site=bare(domain.substring(0,domain.lastIndexOf('.')));
        boolean named=false;
        JSONArray names=signal.identity.optJSONArray("organization");
        if(names!=null)for(int i=0;i<names.length();i++){JSONObject n=names.optJSONObject(i);
            if(n!=null&&List.of("name","legal_name").contains(n.optString("predicate"))&&site.length()>=3&&site.equals(bare(n.optString("value"))))named=true;}
        if(!named)return false;
        return memo("siteOffers\0"+domain+"\0"+signal.actor+"\0"+offer.rule+"\0"+offer.key,()->{
            try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT e.public_id FROM kg_entity_key k JOIN kg_vocab v ON v.term_id=k.scheme"
                    +" JOIN kg_entity e ON e.ent_rowid=k.ent_rowid WHERE v.kind=3 AND v.name IN('site_operator','domain_operator') AND k.scope=?"
                    +" AND e.status=1 LIMIT 3")) {
                p.setString(1,"organization@"+domain);
                List<String> operators=new ArrayList<>();
                try(ResultSet r=p.executeQuery()){while(r.next())operators.add(r.getString(1));}
                for(String operator:operators)if(!operator.equals(signal.actor)&&(itConsultancy(c,operator)||offersItself(c,operator,offer)))return true;
            }return false;
        });
    }
    /** A name without legal form, spaces and punctuation, for comparing it with a site's name ("QUANTO Solutions" ~ quanto-solutions). */
    private static String bare(String name) {
        return name==null?"":name.toLowerCase(Locale.ROOT).replaceAll("(?<![\\p{L}\\p{N}])(?:gmbh(?:\\s*&\\s*co\\.?\\s*kg)?|ag|se|kg|ug|e\\.?\\s?v\\.?|ltd\\.?|inc\\.?|llc)(?![\\p{L}\\p{N}])","")
                .replaceAll("[^\\p{L}\\p{N}]","");
    }
    private static boolean ownCapacity(Observation signal) {
        JSONArray names=signal.identity.optJSONArray("subject");String title="";
        if(names!=null)for(int i=0;i<names.length();i++){JSONObject n=names.optJSONObject(i);if(n!=null&&"name".equals(n.optString("predicate")))title+=" "+n.optString("value");}
        // In-house and key-user roles serve the employer's own systems: a customer need, not delivery capacity (0.11.8).
        if(has(title,"In.?house|Key.?User"))return false;
        // Words may also stand between product and role: "SAP Senior Berater", "Freiberuflicher SAP Consultant" (0.11.8).
        // 0.12.0 (T6, round 2): a consultant without the product in the title too ("Senior Business Consultant Transformation"):
        // it counts only together with an employer offering the product itself, never alone
        return has(title,"SAP.Berater|IT.Berater|SAP.Consultant|Revit.Trainer|Archicad.Trainer|BIM.Berater|CAD.Berater|Coach"
                +"|\\b(?:SAP|IT|BIM|CAD)\\b[^,;|]{0,40}?\\b(?:Berater|Consultant)|\\b(?:Revit|Archicad)\\b[^,;|]{0,40}?\\bTrainer"
                +"|\\b(?:Consultant|Berater(?:in)?)\\b");
    }
    /** A posting's words that build the employer's business with clients ("… im Kundenprojekt", "… am Markt etabliert"). */
    private static final String DELIVERY_ROLE="\\b(?:beim|bei unseren|bei Ihren|für unsere|unsere[nr]?) Kunden\\b|Kundenprojekt|Projekte(?:n)? (?:bei|für) Kunden"
            +"|am Markt (?:etabl|aufbau)|Neukunden|client projects?|at (?:our )?clients|for our clients";
    /**
     * The delivery role of a posting: in the signal's own quote or (0.12.0, T6 round 2, production: Rewion) in another sentence
     * of the same posting ("… sorgst dafür, dass er sich erfolgreich am Markt etabliert" in its description makes "Erfahrung in
     * SAP-Projekten" of its qualifications the same posting's). A job ad read on an organisation's page has only its quote.
     */
    private static boolean postingDeliveryRole(Connection c,Observation signal)throws SQLException {
        if(has(signal.text,DELIVERY_ROLE))return true;
        if(!"job".equals(signal.type)||signal.subject==null)return false;
        return memo("postingRole\0"+signal.subject,()->{
            try(PreparedStatement p=c.prepareStatement("SELECT quote FROM kg_observation WHERE subject_id=? AND assertion_status='recorded'"
                    +" ORDER BY observation_rowid LIMIT 200")) {
                p.setString(1,signal.subject);try(ResultSet r=p.executeQuery()){while(r.next())if(has(r.getString(1),DELIVERY_ROLE))return true;}
            }return false;
        });
    }
    private static String regionName(Observation evidence) {
        String kind=evidence.value.optString("kind");
        if(List.of("place","region","state").contains(kind))return compact(evidence.value.optString("name"));
        // Legacy literal regions are also explicit; national/radius scopes need a future geographic rule.
        return kind.isEmpty()?compact(evidence.value.optString("literal")):"";
    }
    private static boolean hasPosting(Connection c,Observation signal)throws SQLException {
        return memo("hasPosting\0"+signal.source+"\0"+signal.revision,()->hasPostingOf(c,signal));
    }
    private static boolean hasPostingOf(Connection c,Observation signal)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM kg_observation WHERE source_id=? AND content_revision=? AND subject_type='job' LIMIT 1")) {
            p.setString(1,signal.source);p.setString(2,signal.revision);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    /** A company passage on a posting page cannot bypass job policy or the employer exclusion.
     * Only one source-declared employer for that revision grounds the passage; mixed postings defer it. */
    private static Observation postingEmployer(Connection c,Observation signal,List<Observation> supplied,Viewer v)throws SQLException {
        StringBuilder key=new StringBuilder("postingEmployer\0").append(signal.id);for(Observation o:supplied)key.append('\0').append(o.id);
        return memo(key.toString(),()->postingEmployerOf(c,signal,supplied,v));
    }
    private static Observation postingEmployerOf(Connection c,Observation signal,List<Observation> supplied,Viewer v)throws SQLException {
        List<Observation> assignments=new ArrayList<>();Set<String> actors=new HashSet<>();
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM kg_observation WHERE source_id=? AND content_revision=? AND subject_type='job'"
                +" AND (organization_id IS NULL OR original_organization_id IS NULL OR organization_id<>original_organization_id) LIMIT 1")) {
            p.setString(1,signal.source);p.setString(2,signal.revision);try(ResultSet r=p.executeQuery()){if(r.next())return null;}
        }
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE source_id=? AND content_revision=?"
                +" AND subject_type='job' AND predicate='hiring_organization' ORDER BY public_id")) {
            p.setString(1,signal.source);p.setString(2,signal.revision);try(ResultSet r=p.executeQuery()){while(r.next()) {
                Observation o=Observation.read(c,r);if(!o.usable()||!Objects.equals(o.actor,o.originalActor))continue;
                actors.add(o.actor);if(o.accessible(v))assignments.add(o);
            }}
        }
        if(actors.size()!=1||!actors.contains(signal.actor))return null;
        for(Observation o:supplied)if(assignments.stream().anyMatch(a->a.id.equals(o.id)))return o;
        return assignments.isEmpty()?null:assignments.get(0);
    }
    private static boolean itConsultancy(Connection c,String actor)throws SQLException {
        return memo("itConsultancy\0"+actor,()->itConsultancyOf(c,actor));
    }
    private static boolean itConsultancyOf(Connection c,String actor)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT value FROM kg_observation WHERE organization_id=? AND predicate='business_role_evidence'"
                +" AND assertion_status='recorded' AND json_valid(value) AND "+current("kg_observation"))) {
            p.setString(1,actor);try(ResultSet r=p.executeQuery()){while(r.next()) {
                JSONObject v=Values.json(r.getString(1));if(v==null)continue;
                if("own_offered_services".equals(v.optString("context"))&&List.of("it_consultancy","system_integrator").contains(v.optString("role")))return true;
            }}
        }return false;
    }
    private static Long effectiveTime(Observation o,boolean upper) {
        if("event_date".equals(o.value.optString("date_kind"))&&o.assertedAt!=null) {
            LocalDate last=Values.lastDay(o.assertedAt);
            if(last!=null) {
                String date=o.assertedAt.length()==4?o.assertedAt+"-01-01":o.assertedAt.length()==7?o.assertedAt+"-01":o.assertedAt;
                long event=upper?last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()-1
                        :LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                // A scheduled execution date is not the date the plan was first evidenced.
                if(o.value.optString("context").startsWith("planned_")&&o.observedAt!=null)return Math.min(event,o.observedAt);
                return event;
            }
        }return o.observedAt;
    }
    private static boolean systemRetired(Connection c,Observation signal)throws SQLException {
        return memo("systemRetired\0"+signal.id,()->latestSystemChange(c,signal,null)!=null);
    }
    public static Observation latestSystemChange(Connection c,Observation signal,Viewer permitted)throws SQLException {
        Long floor=effectiveTime(signal,true);if(floor==null)return null;
        Observation latest=null;long latestTime=Long.MIN_VALUE;
        for(Observation later:retirementsOf(c,signal)) {
            Long time=effectiveTime(later,false);if(time==null||time<=floor||!later.accessible(permitted))continue;
            String scope=signal.value.optString("scope","unspecified"),other=later.value.optString("scope","unspecified");
            if(!"organization".equals(other)&&("unspecified".equals(scope)||!scope.equals(other)))continue;
            String context=later.value.optString("context"),role=later.value.optString("system_role");
            boolean retires=false;
            if("planned_migration".equals(signal.value.optString("context"))) {
                String plannedRole=signal.value.optString("system_role");
                if("source".equals(plannedRole)&&("shutdown".equals(context)||"source".equals(role)))retires=true;
                if("target".equals(plannedRole)&&"completed_migration".equals(context)&&"target".equals(role))retires=true;
            } else if("shutdown".equals(context)||"source".equals(role))retires=true;
            if(retires&&time>latestTime){latest=later;latestTime=time;}
        }return latest;
    }
    /** The recorded shutdowns and completed migrations of the signal's product at its actor, preloaded or read. */
    @SuppressWarnings("unchecked")
    private static List<Observation> retirementsOf(Connection c,Observation signal)throws SQLException {
        String product=signal.value.optString("product");Map<String,Object> m=MEMO.get();
        Object preloaded=m==null||product.isEmpty()?null:m.get("retirements\0"+signal.actor);
        if(preloaded!=null)return ((Map<String,List<Observation>>)preloaded).getOrDefault(product,List.of());
        List<Observation> out=new ArrayList<>();
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE predicate='system_signal' AND json_valid(value)"
                +" AND json_extract(value,'$.product')=? AND organization_id=? AND assertion_status='recorded'"
                +" AND json_extract(value,'$.context') IN('shutdown','completed_migration') AND "+current("kg_observation"))) {
            p.setString(1,product);p.setString(2,signal.actor);
            try(ResultSet r=p.executeQuery()){while(r.next())out.add(Observation.read(c,r));}
        }return out;
    }
    private static boolean needRetired(Connection c,Observation signal)throws SQLException {
        return memo("needRetired\0"+signal.id,()->needRetiredOf(c,signal));
    }
    private static boolean needRetiredOf(Connection c,Observation signal)throws SQLException {
        String project=signal.value.optString("project");Long floor=effectiveTime(signal,true);
        if(project.isBlank()||floor==null)return false;
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE predicate='business_need_signal' AND json_valid(value)"
                +" AND json_extract(value,'$.need')=? AND organization_id=? AND assertion_status='recorded'"
                +" AND json_extract(value,'$.project')=? AND json_extract(value,'$.context') IN('completed_need','cancelled_need')"
                +" AND "+current("kg_observation"))) {
            p.setString(1,signal.value.optString("need"));p.setString(2,signal.actor);p.setString(3,project);
            try(ResultSet r=p.executeQuery()){while(r.next()){Observation other=Observation.read(c,r);Long time=effectiveTime(other,false);if(time!=null&&time>floor)return true;}}
        }return false;
    }
    public static String corroboration(Offer offer,Observation signal) {
        // Same quote on portals, tiers or revisions is one reason, never independent confirmation.
        return KgIds.statementId(offer.evidence.actor,offer.rule,offer.key+"\0"+offer.evidence.subject+"\0"+offer.service+"\0"+signal.actor
                +"\0"+compact(signal.text)+"\0"+signal.value.optString("context")+"\0"+signal.value.optString("scope")+"\0"+signal.value.optString("project"));
    }
    public static String sourceCorroboration(Offer offer,Observation signal) {
        String fields="";for(String key:List.of("context","scope","system_role","project","location","phase","destination"))fields+="\0"+signal.value.optString(key);
        return KgIds.statementId(offer.evidence.actor,offer.rule,offer.key+"\0"+offer.evidence.subject+"\0"+offer.service+"\0"+signal.actor+"\0"+signal.source+fields);
    }
}
