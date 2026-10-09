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
    public static final String VERSION="1";
    private MatchingRules() { }

    public static final class Observation {
        public String id,actor,originalActor,subject,type,predicate,text,source,revision,status,assertedAt;
        public JSONObject value,identity;
        public Long observedAt;
        public final Map<Integer,String> collections=new TreeMap<>();
        public static Observation read(Connection c,String id)throws SQLException {
            try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE public_id=?")) {
                p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?read(c,r):null;}
            }
        }
        public static Observation read(Connection c,ResultSet r)throws SQLException {
            Observation o=new Observation();o.id=r.getString("public_id");o.actor=r.getString("organization_id");
            o.originalActor=r.getString("original_organization_id");
            o.subject=r.getString("subject_id");o.type=r.getString("subject_type");o.predicate=r.getString("predicate");
            o.text=r.getString("quote");o.source=r.getString("source_id");o.status=r.getString("assertion_status");
            o.revision=r.getString("content_revision");
            o.assertedAt=r.getString("asserted_at");o.observedAt=r.getObject("observed_at")==null?null:r.getLong("observed_at");
            try {o.identity=new JSONObject(r.getString("identity_context"));String raw=r.getString("value");
                o.value=raw.startsWith("{")?new JSONObject(raw):KgJson.obj("literal",raw);
            }catch(JSONException e){throw new SQLException("invalid observation",e);}
            try(PreparedStatement s=c.prepareStatement("SELECT k.coll_id,k.name FROM kg_observation_scope os"
                    +" JOIN kg_observation b USING(observation_rowid) JOIN kg_collection k USING(coll_id) WHERE b.public_id=? ORDER BY k.name")) {
                s.setString(1,o.id);try(ResultSet names=s.executeQuery()){while(names.next())o.collections.put(names.getInt(1),names.getString(2));}
            }return o;
        }
        public boolean accessible(Viewer v){return v==null?!collections.isEmpty():v.all()?!collections.isEmpty():collections.keySet().stream().anyMatch(v.collections()::contains);}
        public boolean usable(){return actor!=null&&"recorded".equals(status)&&!text.isBlank();}
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
    private static boolean has(String text,String regex){return Pattern.compile("(?iu)"+regex).matcher(text).find();}
    private static String compact(String text){return text.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();}

    public static List<Offer> offers(Observation o) {
        if(!o.usable()||"job".equals(o.type))return List.of();
        boolean service="service".equals(o.type)&&List.of("name","category","description").contains(o.predicate)
                &&o.identity.optJSONArray("assignment")!=null&&o.identity.optJSONArray("assignment").length()>0;
        boolean offered=Set.of("organization","facility").contains(o.type)&&"system_signal".equals(o.predicate)&&"offered_capability".equals(o.value.optString("context"));
        if(!service&&!offered)return List.of();
        String text=o.value.optString("literal",o.text)+" "+o.text;
        List<Offer> out=new ArrayList<>();
        if(has(text,"Beratung|consulting|Support|Unterstützung|Schulung|Training|Einführung|Integration|Implementierung|Betreuung|Migration")) {
            for(String product:KgVocabularies.get().signals.products(text)) {
                String rule=Set.of("revit","archicad").contains(product)?"cad-bim":"it-support";
                out.add(new Offer(o,rule,product,product+" support",3));
            }
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
        Assessment result=new Assessment();result.fit="explicit_rule";result.score=60;result.strength="qualified";
        boolean jobBased="job".equals(signal.type);
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
                    ||!List.of("internal_use","required_competence","desirable_competence","planned_migration").contains(context))return null;
            if(List.of("internal_use","planned_migration").contains(context)&&systemRetired(c,signal))return null;
            if(jobBased) {
                if(itConsultancy(c,signal.actor)||ownCapacity(signal))return null;
                result.uncertainties.add("company_role_unknown");result.score-=15;
            }
            result.strength="internal_use".equals(context)?"stated":"weak";
            if(jobBased&&"stated".equals(result.strength))result.strength="qualified";
            result.score+= "internal_use".equals(context)?15:"planned_migration".equals(context)?5:"desirable_competence".equals(context)?-15:-10;
            result.temporal="planned_migration".equals(context)?"planned_not_reconfirmed":"historical_not_reconfirmed";
            result.reason=offer.service+" meets "+context+"; present use not reconfirmed";
        } else {
            if(!"business_need_signal".equals(signal.predicate)||!offer.key.equals(signal.value.optString("need"))||!Set.of("organization","facility").contains(signal.type)
                    ||!List.of("planned_need","explicit_need","organizational_transition").contains(context))return null;
            if(jobBased&&List.of("leadership-development","team-development").contains(offer.rule))return null;
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
    private static boolean ownCapacity(Observation signal) {
        JSONArray names=signal.identity.optJSONArray("subject");String title="";
        if(names!=null)for(int i=0;i<names.length();i++){JSONObject n=names.optJSONObject(i);if(n!=null&&"name".equals(n.optString("predicate")))title+=" "+n.optString("value");}
        return has(title,"SAP.Berater|IT.Berater|SAP.Consultant|Revit.Trainer|Archicad.Trainer|BIM.Berater|CAD.Berater|Coach");
    }
    private static String regionName(Observation evidence) {
        String kind=evidence.value.optString("kind");
        if(List.of("place","region","state").contains(kind))return compact(evidence.value.optString("name"));
        // Legacy literal regions are also explicit; national/radius scopes need a future geographic rule.
        return kind.isEmpty()?compact(evidence.value.optString("literal")):"";
    }
    private static boolean hasPosting(Connection c,Observation signal)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM kg_observation WHERE source_id=? AND content_revision=? AND subject_type='job' LIMIT 1")) {
            p.setString(1,signal.source);p.setString(2,signal.revision);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    /** A company passage on a posting page cannot bypass job policy or the employer exclusion.
     * Only one source-declared employer for that revision grounds the passage; mixed postings defer it. */
    private static Observation postingEmployer(Connection c,Observation signal,List<Observation> supplied,Viewer v)throws SQLException {
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
        try(PreparedStatement p=c.prepareStatement("SELECT value FROM kg_observation WHERE organization_id=? AND predicate='business_role_evidence'"
                +" AND assertion_status='recorded' AND json_valid(value)")) {
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
        return latestSystemChange(c,signal,null)!=null;
    }
    public static Observation latestSystemChange(Connection c,Observation signal,Viewer permitted)throws SQLException {
        Long floor=effectiveTime(signal,true);if(floor==null)return null;
        Observation latest=null;long latestTime=Long.MIN_VALUE;
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE predicate='system_signal' AND json_valid(value)"
                +" AND json_extract(value,'$.product')=? AND organization_id=? AND assertion_status='recorded'"
                +" AND json_extract(value,'$.context') IN('shutdown','completed_migration')")) {
            p.setString(1,signal.value.optString("product"));p.setString(2,signal.actor);
            try(ResultSet r=p.executeQuery()){while(r.next()) {
                Observation later=Observation.read(c,r);Long time=effectiveTime(later,false);if(time==null||time<=floor||!later.accessible(permitted))continue;
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
            }}
        }return latest;
    }
    private static boolean needRetired(Connection c,Observation signal)throws SQLException {
        String project=signal.value.optString("project");Long floor=effectiveTime(signal,true);
        if(project.isBlank()||floor==null)return false;
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE predicate='business_need_signal' AND json_valid(value)"
                +" AND json_extract(value,'$.need')=? AND organization_id=? AND assertion_status='recorded'"
                +" AND json_extract(value,'$.project')=? AND json_extract(value,'$.context') IN('completed_need','cancelled_need')")) {
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
