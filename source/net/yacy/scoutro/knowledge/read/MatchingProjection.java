/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.read;

import java.sql.*;
import java.util.*;
import org.json.*;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.derive.*;
import net.yacy.scoutro.knowledge.derive.MatchingRules.*;
import net.yacy.scoutro.knowledge.store.*;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * Every cached contribution is revalidated, then authorized, before grouping or ranking.
 *
 * <p>0.11.8: a read validates and ranks every contribution first ({@link Light}: the same checks as before, without the
 * evidence JSON and the actor references) and builds the full record only for what it shows ({@link #expand}); the checks
 * of one read share a memo ({@link Memo}, {@link MatchingRules#openMemo}). The records are the same as before; an entity with
 * 10,000 contributions ended in read_timeout because every one was built in full and queried about thirty times.
 */
public final class MatchingProjection {
    private MatchingProjection() { }

    /** What one read has looked up: observations, their archive records and actor references, by observation id. */
    static final class Memo {
        final Map<String,Observation> observations=new HashMap<>();
        final Map<String,JSONObject> history=new HashMap<>();
        final Map<String,JSONObject> actors=new HashMap<>();
    }

    /** A contribution validated for one read, before its evidence and actors are built. */
    public static final class Light {
        final String id,proposal,provider,candidate,corroboration;final int kind;final long computedAt;
        final JSONArray refs;final Observation service,signal;final Offer offer;final Assessment assessment;
        Light(ResultSet r,JSONArray refs,Observation service,Observation signal,Offer offer,Assessment assessment)throws SQLException {
            this.id=r.getString("public_id");this.proposal=r.getString("proposal_id");this.provider=r.getString("provider_id");
            this.candidate=r.getString("candidate_id");this.corroboration=r.getString("corroboration_key");this.kind=r.getInt("kind");
            this.computedAt=r.getLong("computed_at");this.refs=refs;this.service=service;this.signal=signal;this.offer=offer;this.assessment=assessment;
        }
    }

    public static JSONObject record(Connection c,String id,Viewer permitted,KgConfig cfg)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_match_contribution WHERE public_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?record(c,r,permitted,cfg):null;}
        }
    }
    private static JSONObject record(Connection c,ResultSet r,Viewer permitted,KgConfig cfg)throws SQLException {
        Light light=light(c,r,MatchingAccess.chain(c,r.getString("public_id")),permitted,cfg,null);
        return light==null?null:full(c,light,permitted,null);
    }
    /** The checks of {@link #record}: null if the contribution is stale or not authorized for {@code permitted}. */
    static Light light(Connection c,ResultSet r,String chain,Viewer permitted,KgConfig cfg,Memo memo)throws SQLException {
        if(cfg.matchesMax==0||!cfg.derivedEnabled)return null;
        if(!MatchingRules.VERSION.equals(r.getString("rule_version"))||!MatchingAccess.allowed(chain,permitted))return null;
        JSONArray refs=array(r.getString("refs"));Observation service=null,signal=null;List<Observation> extras=new ArrayList<>();
        for(int i=0;i<refs.length();i++) {
            JSONObject ref=refs.optJSONObject(i);Observation o=observation(c,ref.optString("id"),memo);
            if(o==null||!o.accessible(permitted))return null;
            switch(ref.optString("role")){case "service":service=o;break;case "signal":signal=o;break;default:extras.add(o);}
        }
        if(service==null||signal==null||!r.getString("provider_id").equals(service.actor)||!r.getString("candidate_id").equals(signal.actor))return null;
        Offer offer=MatchingRules.offers(service).stream().filter(o->o.rule.equals(rSafe(r,"rule"))
                &&(o.evidence.id+":"+o.rule+":"+o.key+":"+o.service).equals(rSafe(r,"service_key"))).findFirst().orElse(null);
        if(offer==null||offer.kind!=r.getInt("kind"))return null;
        Assessment a=MatchingRules.assess(c,offer,signal,extras,cfg,permitted);if(a==null)return null;
        for(Observation required:a.extra)if(extras.stream().noneMatch(o->o.id.equals(required.id)))return null;
        return new Light(r,refs,service,signal,offer,a);
    }
    /** The full record of a validated contribution, as {@link #record} returns it. */
    static JSONObject full(Connection c,Light l,Viewer permitted,Memo memo)throws SQLException {
        Observation service=l.service,signal=l.signal;Offer offer=l.offer;Assessment a=l.assessment;JSONArray refs=l.refs;
        JSONArray evidence=new JSONArray(),collections=new JSONArray();Set<String> names=new TreeSet<>();
        for(int i=0;i<refs.length();i++) {
            JSONObject ref=refs.optJSONObject(i),o=history(c,ref.optString("id"),permitted,memo);
            if(o==null)return null;
            KgJson.put(o,"role",ref.optString("role"));evidence.put(o);
            JSONArray scopes=o.optJSONArray("collections");for(int j=0;j<scopes.length();j++)names.add(scopes.optString(j));
        }
        names.forEach(collections::put);
        String ac=selectedCollection(service,permitted),bc=selectedCollection(signal,permitted);
        JSONObject result=KgJson.obj("id",l.id,"proposal_id",l.proposal,"kind",
                l.kind==3?"suggested_customer":"suggested_partner","provider",actorRef(c,service,permitted,memo),
                "candidate",actorRef(c,signal,permitted,memo),"rule",offer.rule,"rule_version",MatchingRules.VERSION,
                "service",offer.service,"product",signal.value.optString("product",null),"need",signal.value.optString("need",null),
                "collection_a",ac,"collection_b",bc,"required_collections",collections,"reason",a.reason,"score",a.score/100.0,
                "evidence_strength",a.strength,"fit",a.fit,"temporal_status",a.temporal,"uncertainties",new JSONArray(a.uncertainties),
                "observed_at",KgReader.iso(signal.observedAt),"asserted_at",signal.assertedAt,"computed_at",KgReader.iso(l.computedAt),
                "evidence",evidence,"evidence_complete",true,"fact",false,"corroboration_key",l.corroboration);
        KgJson.put(result,"context",signal.value.optString("context"));
        KgJson.put(result,"evidence_kind",evidenceKind(signal.value.optString("context")));
        KgJson.put(result,"source_corroboration_key",MatchingRules.sourceCorroboration(offer,signal));
        String serviceName=service.text;JSONArray stated=service.identity.optJSONArray("subject");
        if("service".equals(service.type)&&stated!=null)for(int i=0;i<stated.length();i++)if("name".equals(stated.optJSONObject(i).optString("predicate")))serviceName=stated.optJSONObject(i).optString("value");
        KgJson.put(result,"service_name",serviceName);
        KgJson.put(result,"location",signal.value.optString("location",null));KgJson.put(result,"project",signal.value.optString("project",null));
        KgJson.put(result,"phase",signal.value.optString("phase",null));
        return result;
    }
    private static Observation observation(Connection c,String id,Memo memo)throws SQLException {
        if(memo==null)return Observation.read(c,id);
        if(memo.observations.containsKey(id))return memo.observations.get(id);
        Observation o=Observation.read(c,id);memo.observations.put(id,o);return o;
    }
    private static JSONObject history(Connection c,String id,Viewer permitted,Memo memo)throws SQLException {
        if(memo==null)return ObservationHistory.record(c,id,permitted);
        if(!memo.history.containsKey(id))memo.history.put(id,ObservationHistory.record(c,id,permitted));
        return copy(memo.history.get(id));
    }
    /** A shallow copy in the same key order: a shared record must not take the role of another contribution. */
    private static JSONObject copy(JSONObject o) {
        if(o==null)return null;
        JSONObject out=new JSONObject();for(String key:o.keySet())KgJson.put(out,key,o.opt(key));return out;
    }

    /** One read's view of an entity's contributions: what it validated, with the shared lookups for {@link #expand}. */
    public static final class Reads {
        final Memo memo=new Memo();final Viewer permitted;
        private final Map<JSONObject,Light> lights=new IdentityHashMap<>();
        Reads(Viewer permitted){this.permitted=permitted;}
        /** True if {@code reason} is a projection of {@link #lights} that {@link #expand} has not built yet. */
        boolean light(JSONObject reason){return reason!=null&&this.lights.containsKey(reason);}
        /** The full reason (the item of {@link #visible}) for a light one; any other reason as it is. */
        JSONObject expand(Connection c,JSONObject reason)throws SQLException {
            Light l=reason==null?null:this.lights.get(reason);if(l==null)return reason;
            JSONObject full=full(c,l,this.permitted,this.memo);
            if(full==null)throw new SQLException("a validated contribution has no archive record: "+l.id);
            boolean mine=reason.optBoolean("_mine");
            JSONObject self=full.optJSONObject(mine?"provider":"candidate");
            KgJson.put(full,"direction",mine?"out":"in");KgJson.put(full,"other",full.optJSONObject(mine?"candidate":"provider"));
            KgJson.put(full,"origin_collection",self.optString("target_collection"));
            KgJson.put(full,"target_collection",full.optJSONObject("other").optString("target_collection"));
            return full;
        }
    }

    /**
     * The entity's visible contributions, each built in full row by row as before 0.11.8 (no memo, no preload): the reference
     * of {@link #lights} in the tests and the read of an archived origin, which has few.
     */
    public static List<JSONObject> visible(Connection c,String entity,Viewer origin,Viewer permitted,KgConfig cfg)throws SQLException {
        List<JSONObject> out=new ArrayList<>();
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_match_contribution WHERE provider_id=? OR candidate_id=? ORDER BY public_id")) {
            p.setString(1,entity);p.setString(2,entity);try(ResultSet r=p.executeQuery()){while(r.next()) {
                JSONObject item=record(c,r,permitted,cfg);if(item==null)continue;
                boolean mine=entity.equals(r.getString("provider_id"));
                JSONObject self=item.optJSONObject(mine?"provider":"candidate");
                JSONObject assertion=null;JSONArray evidence=item.optJSONArray("evidence");
                for(int i=0;i<evidence.length();i++)if((mine?"service":"signal").equals(evidence.optJSONObject(i).optString("role")))assertion=evidence.optJSONObject(i);
                if(assertion==null)continue;
                JSONArray cols=assertion.optJSONArray("collections");boolean selected=origin.all();
                for(int i=0;i<cols.length()&&!selected;i++) {
                    try(PreparedStatement cp=c.prepareStatement("SELECT coll_id FROM kg_collection WHERE name=?")){
                        cp.setString(1,cols.optString(i));try(ResultSet cr=cp.executeQuery()){if(cr.next()&&origin.collections().contains(cr.getInt(1)))selected=true;}
                    }
                }
                if(!selected)continue;
                KgJson.put(item,"direction",mine?"out":"in");KgJson.put(item,"other",item.optJSONObject(mine?"candidate":"provider"));
                KgJson.put(item,"origin_collection",self.optString("target_collection"));
                KgJson.put(item,"target_collection",item.optJSONObject("other").optString("target_collection"));
                out.add(item);
            }}
        }return out;
    }
    /**
     * The entity's visible contributions as light reasons (the fields the grouping, deduplication and order read: id,
     * proposal_id, kind, direction, other id and target_collection, origin_collection, target_collection, score, reason,
     * computed_at, observed_at, corroboration keys), in {@link #visible}'s order; {@link Reads#expand} builds each in full.
     */
    static List<JSONObject> lights(Connection c,String entity,Viewer origin,KgConfig cfg,Reads reads)throws SQLException {
        List<JSONObject> out=new ArrayList<>();Viewer permitted=reads.permitted;
        boolean opened=MatchingRules.openMemo();
        reads.memo.observations.putAll(Observation.referencedBy(c,entity));
        MatchingRules.preload(c,entity,reads.memo.observations.values());
        try(PreparedStatement p=c.prepareStatement("SELECT m.*,"+MatchingAccess.CHAIN_COLUMN+" AS chain FROM kg_match_contribution m"
                +" WHERE m.provider_id=? OR m.candidate_id=? ORDER BY m.public_id")) {
            p.setString(1,entity);p.setString(2,entity);try(ResultSet r=p.executeQuery()){while(r.next()) {
                Light l=light(c,r,r.getString("chain"),permitted,cfg,reads.memo);if(l==null)continue;
                boolean mine=entity.equals(l.provider);
                // the assertion of this side (its service, or its signal): its permitted collections select the origin
                Observation self=mine?l.service:l.signal;boolean selected=origin.all();
                for(Map.Entry<Integer,String> e:self.collections.entrySet()) {
                    if(selected)break;
                    if((permitted.all()||permitted.collections().contains(e.getKey()))&&origin.collections().contains(e.getKey()))selected=true;
                }
                if(!selected)continue;
                String own=selectedCollection(self,permitted),target=selectedCollection(mine?l.signal:l.service,permitted);
                JSONObject reason=KgJson.obj("id",l.id,"proposal_id",l.proposal,"kind",l.kind==3?"suggested_customer":"suggested_partner",
                        "direction",mine?"out":"in","other",KgJson.obj("id",mine?l.candidate:l.provider,"target_collection",target),
                        "origin_collection",own,"target_collection",target,"score",l.assessment.score/100.0,"reason",l.assessment.reason,
                        "computed_at",KgReader.iso(l.computedAt),"observed_at",KgReader.iso(l.signal.observedAt),
                        "corroboration_key",l.corroboration,"source_corroboration_key",MatchingRules.sourceCorroboration(l.offer,l.signal),
                        "_mine",mine);
                reads.lights.put(reason,l);out.add(reason);
            }}
        } finally {
            if(opened)MatchingRules.closeMemo();
        }return out;
    }
    /** Equivalence by same contextual quote OR same source/semantic assertion. Aliases stay internal. */
    public static final class Deduplicator {
        private final List<JSONObject> reasons;
        private final Map<String,JSONObject> aliases=new HashMap<>();
        public Deduplicator(List<JSONObject> reasons){this.reasons=reasons;}
        public void add(JSONObject incoming) {
            List<String> keys=List.of(incoming.optString("corroboration_key"),incoming.optString("source_corroboration_key"));
            Set<JSONObject> same=new HashSet<>();for(String key:keys)if(aliases.containsKey(key))same.add(aliases.get(key));
            List<JSONObject> candidates=new ArrayList<>(same);candidates.add(incoming);
            candidates.sort(Comparator.comparingDouble((JSONObject o)->o.optDouble("score")).reversed()
                    .thenComparing((JSONObject o)->o.optString("observed_at",""),Comparator.reverseOrder()).thenComparing(o->o.optString("id")));
            JSONObject best=candidates.get(0);
            for(Map.Entry<String,JSONObject> e:aliases.entrySet())if(same.contains(e.getValue()))e.setValue(best);
            for(String key:keys)aliases.put(key,best);
            reasons.removeAll(same);reasons.add(best);
        }
    }
    private static JSONArray array(String raw)throws SQLException {try{return new JSONArray(raw);}catch(JSONException e){throw new SQLException("invalid match references",e);}}
    private static String rSafe(ResultSet r,String key){try{return r.getString(key);}catch(SQLException e){throw new IllegalStateException(e);}}
    /** The projection of the current row of a {@code SELECT * FROM kg_match_contribution}; null if invisible or stale. */
    public static JSONObject recordRow(Connection c,ResultSet r,Viewer permitted,KgConfig cfg)throws SQLException {
        return record(c,r,permitted,cfg);
    }
    public static List<JSONObject> all(Connection c,Viewer permitted,KgConfig cfg)throws SQLException {
        List<JSONObject> out=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM kg_match_contribution ORDER BY public_id")) {
            while(r.next()){JSONObject item=record(c,r,permitted,cfg);if(item!=null)out.add(item);}
        }return out;
    }
    private static String selectedCollection(Observation o,Viewer v) {
        return o.collections.entrySet().stream().filter(e->v.all()||v.collections().contains(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
    }
    /** 0.12.0 (B2): what a reason rests on: a stated need, a stated use, or a job's requirement; null for another context. */
    public static String evidenceKind(String context) {
        switch(context==null?"":context) {
            case "required_competence": case "desirable_competence": return "job_requirement";
            case "internal_use": return "use";
            case "planned_migration": case "planned_need": case "explicit_need": case "organizational_transition": return "need";
            default: return null;
        }
    }
    /** A deleted live employer remains navigable through B's object-to-history fallback. */
    private static JSONObject actorRef(Connection c,Observation o,Viewer v,Memo memo)throws SQLException {
        if(memo==null)return actorRef(c,o,v);
        if(!memo.actors.containsKey(o.id))memo.actors.put(o.id,actorRef(c,o,v));
        return copy(memo.actors.get(o.id));
    }
    private static JSONObject actorRef(Connection c,Observation o,Viewer v)throws SQLException {
        Set<String> names=new TreeSet<>();for(Map.Entry<Integer,String> e:o.collections.entrySet())if(v.all()||v.collections().contains(e.getKey()))names.add(e.getValue());
        try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT k.coll_id,k.name FROM kg_observation other"
                +" JOIN kg_observation_scope os ON os.observation_rowid=other.observation_rowid JOIN kg_collection k USING(coll_id)"
                +" WHERE other.organization_id=?")) {
            p.setString(1,o.actor);try(ResultSet r=p.executeQuery()){while(r.next())if(v.all()||v.collections().contains(r.getInt(1)))names.add(r.getString(2));}
        }
        String name=o.name();String type="facility".equals(o.type)&&o.actor.equals(o.subject)?"facility":"organization";
        long[] live=KgReader.entityRow(c,o.actor,v);
        if(live!=null&&live[1]==0) {
            String current=KgReader.visibleName(c,live[0],v);if(current!=null)name=current;
            try(PreparedStatement p=c.prepareStatement("SELECT t.name FROM kg_entity e JOIN kg_vocab t ON t.term_id=e.type WHERE e.ent_rowid=?")) {
                p.setLong(1,live[0]);try(ResultSet r=p.executeQuery()){if(r.next())type=r.getString(1);}
            }
        }
        return KgJson.obj("id",o.actor,"name",name,"display_name",name,"type",type,"collections",new JSONArray(names),
                "target_collection",selectedCollection(o,v),"archived",live==null,"observation",o.id);
    }
}
