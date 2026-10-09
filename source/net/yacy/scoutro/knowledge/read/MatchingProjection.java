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

/** Every cached contribution is revalidated, then authorized, before grouping or ranking. */
public final class MatchingProjection {
    private MatchingProjection() { }
    public static JSONObject record(Connection c,String id,Viewer permitted,KgConfig cfg)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_match_contribution WHERE public_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?record(c,r,permitted,cfg):null;}
        }
    }
    private static JSONObject record(Connection c,ResultSet r,Viewer permitted,KgConfig cfg)throws SQLException {
        if(!MatchingRules.VERSION.equals(r.getString("rule_version"))||!MatchingAccess.allowed(MatchingAccess.chain(c,r.getString("public_id")),permitted))return null;
        JSONArray refs=array(r.getString("refs"));Observation service=null,signal=null;List<Observation> extras=new ArrayList<>();
        for(int i=0;i<refs.length();i++) {
            JSONObject ref=refs.optJSONObject(i);Observation o=Observation.read(c,ref.optString("id"));
            if(o==null||!o.accessible(permitted))return null;
            switch(ref.optString("role")){case "service":service=o;break;case "signal":signal=o;break;default:extras.add(o);}
        }
        if(service==null||signal==null||!r.getString("provider_id").equals(service.actor)||!r.getString("candidate_id").equals(signal.actor))return null;
        Offer offer=MatchingRules.offers(service).stream().filter(o->o.rule.equals(rSafe(r,"rule"))
                &&(o.evidence.id+":"+o.rule+":"+o.key+":"+o.service).equals(rSafe(r,"service_key"))).findFirst().orElse(null);
        if(offer==null)return null;
        Assessment a=MatchingRules.assess(c,offer,signal,extras,cfg,permitted);if(a==null)return null;
        JSONArray evidence=new JSONArray(),collections=new JSONArray();Set<String> names=new TreeSet<>();
        for(int i=0;i<refs.length();i++) {
            JSONObject ref=refs.optJSONObject(i),o=ObservationHistory.record(c,ref.optString("id"),permitted);
            if(o==null)return null;
            KgJson.put(o,"role",ref.optString("role"));evidence.put(o);
            JSONArray scopes=o.optJSONArray("collections");for(int j=0;j<scopes.length();j++)names.add(scopes.optString(j));
        }
        names.forEach(collections::put);
        String ac=selectedCollection(service,permitted),bc=selectedCollection(signal,permitted);
        JSONObject result=KgJson.obj("id",r.getString("public_id"),"proposal_id",r.getString("proposal_id"),"kind",
                r.getInt("kind")==3?"suggested_customer":"suggested_partner","provider",actorRef(c,service,permitted),
                "candidate",actorRef(c,signal,permitted),"rule",offer.rule,"rule_version",MatchingRules.VERSION,
                "service",offer.service,"product",signal.value.optString("product",null),"need",signal.value.optString("need",null),
                "collection_a",ac,"collection_b",bc,"required_collections",collections,"reason",a.reason,"score",a.score/100.0,
                "evidence_strength",a.strength,"fit",a.fit,"temporal_status",a.temporal,"uncertainties",new JSONArray(a.uncertainties),
                "observed_at",KgReader.iso(signal.observedAt),"asserted_at",signal.assertedAt,"computed_at",KgReader.iso(r.getLong("computed_at")),
                "evidence",evidence,"evidence_complete",true,"fact",false,"corroboration_key",r.getString("corroboration_key"));
        KgJson.put(result,"context",signal.value.optString("context"));
        KgJson.put(result,"location",signal.value.optString("location",null));KgJson.put(result,"project",signal.value.optString("project",null));
        KgJson.put(result,"phase",signal.value.optString("phase",null));
        return result;
    }
    private static JSONArray array(String raw)throws SQLException {try{return new JSONArray(raw);}catch(JSONException e){throw new SQLException("invalid match references",e);}}
    private static String rSafe(ResultSet r,String key){try{return r.getString(key);}catch(SQLException e){throw new IllegalStateException(e);}}
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
    public static List<JSONObject> all(Connection c,Viewer permitted,KgConfig cfg)throws SQLException {
        List<JSONObject> out=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM kg_match_contribution ORDER BY public_id")) {
            while(r.next()){JSONObject item=record(c,r,permitted,cfg);if(item!=null)out.add(item);}
        }return out;
    }
    private static String selectedCollection(Observation o,Viewer v) {
        return o.collections.entrySet().stream().filter(e->v.all()||v.collections().contains(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
    }
    /** A deleted live employer remains navigable through B's object-to-history fallback. */
    private static JSONObject actorRef(Connection c,Observation o,Viewer v)throws SQLException {
        Set<String> names=new TreeSet<>();for(Map.Entry<Integer,String> e:o.collections.entrySet())if(v.all()||v.collections().contains(e.getKey()))names.add(e.getValue());
        try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT k.coll_id,k.name FROM kg_observation other"
                +" JOIN kg_observation_scope os ON os.observation_rowid=other.observation_rowid JOIN kg_collection k USING(coll_id)"
                +" WHERE other.organization_id=?")) {
            p.setString(1,o.actor);try(ResultSet r=p.executeQuery()){while(r.next())if(v.all()||v.collections().contains(r.getInt(1)))names.add(r.getString(2));}
        }
        String name=o.name();String type="organization";
        long[] live=KgReader.entityRow(c,o.actor,v);
        if(live!=null&&live[1]==0) {
            String current=KgReader.visibleName(c,live[0],v);if(current!=null)name=current;
        }
        return KgJson.obj("id",o.actor,"name",name,"display_name",name,"type",type,"collections",new JSONArray(names),
                "target_collection",selectedCollection(o,v),"archived",live==null,"observation",o.id);
    }
}
