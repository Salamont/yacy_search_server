/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.derive;

import java.sql.*;
import java.util.*;
import org.json.*;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.derive.MatchingRules.*;
import net.yacy.scoutro.knowledge.store.*;

/** Resumable provider partitions, indexed product/need joins. Limits never mean refutation. */
public final class MatchingService {
    private final KgStore store;
    private final KgConfig cfg;
    private final int budget;
    private static final int MAX_PROVIDER_FACTS=2500;
    public MatchingService(KgStore store,KgConfig cfg){this(store,cfg,Math.max(1,cfg.matchesMax));}
    public MatchingService(KgStore store,KgConfig cfg,int budget){this.store=store;this.cfg=cfg;this.budget=Math.max(1,budget);}
    private static final class Pending {
        Offer offer;Observation signal;Assessment assessment;
        Pending(Offer o,Observation s,Assessment a){offer=o;signal=s;assessment=a;}
    }
    private static final class Partition {
        String provider,token;long startRevision;String nextOffer="";long nextCandidate;
        final List<Pending> rows=new ArrayList<>();
        int checked;boolean complete,unchanged;String deferred;
    }
    public JSONObject run(long now)throws KgException {
        if(cfg.matchesMax==0) {
            JSONObject disabled=KgJson.obj("version",MatchingRules.VERSION,"checked",0,"upserts",0,"completed_partitions",0,
                    "cycle_complete",false,"deferred","configuration_disabled");
            store.write(WriteClass.SYSTEM,0,c->{KgStore.putMeta(c,"matching_last",disabled.toString());return null;});return disabled;
        }
        JSONObject state=store.read(c->{String raw=KgStore.getMeta(c,"matching_work");return raw==null?new JSONObject():net.yacy.scoutro.knowledge.extract.Values.json(raw);});
        int checked=0,inserted=0,completed=0,visited=0;String deferred=null;boolean cycleComplete=false;
        while(checked<budget&&visited<500) {
            final JSONObject previous=state;final int remaining=budget-checked;
            Partition part=store.read(c->compute(c,previous,remaining));
            if(part==null){state=new JSONObject();cycleComplete=true;break;}
            checked+=part.checked;
            JSONObject next=part.complete?KgJson.obj("after_provider",part.provider):KgJson.obj("provider",part.provider,
                    "token",part.token,"start_revision",part.startRevision,"offer",part.nextOffer,"candidate_after",part.nextCandidate);
            store.write(WriteClass.GROWTH,1024L*(part.rows.size()+1),c->{
                for(Pending row:part.rows)upsert(c,row,part.token,now);
                // Only a completed and unchanged input partition permits reconciliation.
                if(part.complete&&part.unchanged&&part.startRevision==KgStore.queryLong(c,"SELECT coalesce(max(event_rowid),0) FROM kg_observation_event")) {
                    try(PreparedStatement p=c.prepareStatement("SELECT public_id FROM kg_match_contribution WHERE provider_id=? AND seen_generation<>?")) {
                        p.setString(1,part.provider);p.setString(2,part.token);
                        List<String> remove=new ArrayList<>();try(ResultSet r=p.executeQuery()){while(r.next())remove.add(r.getString(1));}
                        for(String id:remove)delete(c,id,now);
                    }
                }
                KgStore.putMeta(c,"matching_work",next.toString());return null;
            });
            inserted+=part.rows.size();state=next;
            if(part.deferred!=null)deferred=part.deferred;
            if(part.complete){visited++;if(part.deferred==null)completed++;}else {deferred=part.deferred==null?"work_budget":part.deferred;break;}
        }
        if(cycleComplete)store.write(WriteClass.SYSTEM,0,c->{KgStore.putMeta(c,"matching_work","{}");return null;});
        JSONObject result=KgJson.obj("version",MatchingRules.VERSION,"checked",checked,"upserts",inserted,"completed_partitions",completed,
                "cycle_complete",cycleComplete&&deferred==null,"deferred",deferred==null&&!cycleComplete?"work_budget":deferred);
        store.write(WriteClass.SYSTEM,0,c->{KgStore.putMeta(c,"matching_last",result.toString());return null;});
        return result;
    }
    private Partition compute(Connection c,JSONObject state,int remaining)throws SQLException {
        Partition out=new Partition();
        out.provider=state.optString("provider",null);
        if(out.provider==null) {
            String after=state.optString("after_provider","");
            try(PreparedStatement p=c.prepareStatement("SELECT organization_id FROM kg_observation WHERE organization_id>? AND"
                    +" ((predicate='system_signal' AND json_valid(value) AND json_extract(value,'$.context')='offered_capability')"
                    +" OR (subject_type='service' AND predicate IN('name','category','description')))"
                    +" UNION SELECT provider_id FROM kg_match_contribution WHERE provider_id>? ORDER BY 1 LIMIT 1")) {
                p.setString(1,after);p.setString(2,after);try(ResultSet r=p.executeQuery()){if(!r.next())return null;out.provider=r.getString(1);}
            }
            out.token=UUID.randomUUID().toString();out.startRevision=KgStore.queryLong(c,"SELECT coalesce(max(event_rowid),0) FROM kg_observation_event");
        } else {out.token=state.optString("token");out.startRevision=state.optLong("start_revision");}
        List<Offer> offers=new ArrayList<>();List<Observation> regions=new ArrayList<>();int facts=0;
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE organization_id=? AND"
                +" (predicate='service_area' OR (predicate='system_signal' AND json_valid(value) AND json_extract(value,'$.context')='offered_capability')"
                +" OR (subject_type='service' AND predicate IN('name','category','description'))) AND assertion_status='recorded'"
                +" ORDER BY observation_rowid LIMIT ?")) {
            p.setString(1,out.provider);p.setInt(2,MAX_PROVIDER_FACTS+1);
            try(ResultSet r=p.executeQuery()){while(r.next()) {
                if(++facts>MAX_PROVIDER_FACTS){out.deferred="provider_fact_limit";out.complete=true;out.unchanged=false;return out;}
                Observation o=Observation.read(c,r);offers.addAll(MatchingRules.offers(o));if("service_area".equals(o.predicate))regions.add(o);
            }}
        }
        offers.sort(Comparator.comparing(MatchingService::offerKey));String resume=state.optString("offer","");
        for(Offer offer:offers) {
            String key=offerKey(offer);if(key.compareTo(resume)<0)continue;
            long after=key.equals(resume)?state.optLong("candidate_after"):0;
            String predicate=List.of("it-support","cad-bim").contains(offer.rule)?"system_signal":"business_need_signal";
            String field="system_signal".equals(predicate)?"product":"need";
            try(PreparedStatement p=c.prepareStatement("SELECT * FROM kg_observation WHERE predicate='"+predicate+"' AND json_valid(value)"
                    +" AND json_extract(value,'$."+field+"')=? AND observation_rowid>? ORDER BY observation_rowid LIMIT ?")) {
                p.setString(1,offer.key);p.setLong(2,after);p.setInt(3,remaining-out.checked+1);
                try(ResultSet r=p.executeQuery()){while(r.next()) {
                    if(out.checked==remaining){out.nextOffer=key;out.nextCandidate=after;return out;}
                    after=r.getLong("observation_rowid");out.checked++;
                    Observation signal=Observation.read(c,r);
                    Assessment assessment=MatchingRules.assess(c,offer,signal,regions,cfg,null);
                    if(assessment!=null)out.rows.add(new Pending(offer,signal,assessment));
                }}
            }
        }
        out.complete=true;out.unchanged=out.startRevision==KgStore.queryLong(c,"SELECT coalesce(max(event_rowid),0) FROM kg_observation_event");
        return out;
    }
    private static String offerKey(Offer offer){return offer.evidence.id+":"+offer.rule+":"+offer.key+":"+offer.service;}
    private static void upsert(Connection c,Pending row,String generation,long now)throws SQLException {
        Offer offer=row.offer;Observation signal=row.signal;
        String kind=offer.kind==3?"suggested_customer":"suggested_partner";
        String proposal=KgIds.derivedId(kind,offer.evidence.actor,signal.actor,"matching-v1","");
        String id=KgIds.derivedId(kind,offer.evidence.actor,signal.actor,"contribution:"+offerKey(offer),signal.id);
        JSONArray refs=new JSONArray().put(KgJson.obj("role","service","id",offer.evidence.id)).put(KgJson.obj("role","signal","id",signal.id));
        for(Observation extra:row.assessment.extra)refs.put(KgJson.obj("role","hiring_organization".equals(extra.predicate)?"employer":"region","id",extra.id));
        if(refs.toString().length()>4000)throw new SQLException("match references exceed their contract");
        boolean changed=true;
        try(PreparedStatement previous=c.prepareStatement("SELECT refs,rule_version,corroboration_key FROM kg_match_contribution WHERE public_id=?")) {
            previous.setString(1,id);try(ResultSet r=previous.executeQuery()){if(r.next())changed=!refs.toString().equals(r.getString(1))||!MatchingRules.VERSION.equals(r.getString(2))
                    ||!MatchingRules.corroboration(offer,signal).equals(r.getString(3));}
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_match_contribution(public_id,proposal_id,kind,provider_id,candidate_id,rule,"
                +"rule_version,service_key,signal_key,corroboration_key,refs,computed_at,seen_generation) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
                +" ON CONFLICT(public_id) DO UPDATE SET seen_generation=excluded.seen_generation,refs=excluded.refs,rule_version=excluded.rule_version,corroboration_key=excluded.corroboration_key")) {
            int n=1;p.setString(n++,id);p.setString(n++,proposal);p.setInt(n++,offer.kind);p.setString(n++,offer.evidence.actor);p.setString(n++,signal.actor);
            p.setString(n++,offer.rule);p.setString(n++,MatchingRules.VERSION);p.setString(n++,offerKey(offer));p.setString(n++,signal.id);
            p.setString(n++,MatchingRules.corroboration(offer,signal));p.setString(n++,refs.toString());p.setLong(n++,now);p.setString(n,generation);p.executeUpdate();
        }
        if(changed) {
            MatchingAccess.remember(c,id);
            try(PreparedStatement p=c.prepareStatement("DELETE FROM kg_match_ref WHERE contribution_id=?")){p.setString(1,id);p.executeUpdate();}
        }
        try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO kg_match_ref VALUES(?,?)")) {
            for(int i=0;i<refs.length();i++){p.setString(1,id);p.setString(2,refs.optJSONObject(i).optString("id"));p.executeUpdate();}
        }
        MatchingAccess.remember(c,id);
        if(changed)KgChangeLog.record(c,KgChangeLog.Kind.MATCH_CONTRIBUTION,id,KgChangeLog.Op.UPSERT,null,Set.of(),Set.of(),now);
    }
    private static void delete(Connection c,String id,long now)throws SQLException {
        MatchingAccess.remember(c,id);
        try(PreparedStatement p=c.prepareStatement("DELETE FROM kg_match_contribution WHERE public_id=?")){p.setString(1,id);p.executeUpdate();}
        KgChangeLog.record(c,KgChangeLog.Kind.MATCH_CONTRIBUTION,id,KgChangeLog.Op.DELETE,null,Set.of(),Set.of(),now);
    }
    public static void clear(Connection c,long now)throws SQLException {
        List<String> ids=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT public_id FROM kg_match_contribution")){while(r.next())ids.add(r.getString(1));}
        for(String id:ids)delete(c,id,now);
        KgStore.putMeta(c,"matching_work","{}");
    }
}
