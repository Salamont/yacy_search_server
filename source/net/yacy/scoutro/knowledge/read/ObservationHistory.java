/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.read;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.store.*;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/** Authorized archival reads, independent of live statements/entities. */
public final class ObservationHistory {
    public static final String SCHEMA="scoutro.kg.history.v1";
    public static final Pattern ID=Pattern.compile("^kgo_[0-9a-f]{20}$");
    private final KgReader reader;
    public ObservationHistory(KgReader reader){this.reader=reader;}
    private static String scope(Viewer v,String alias) {
        return "EXISTS(SELECT 1 FROM kg_observation_scope os WHERE os.observation_rowid="+alias+".observation_rowid"
                +(v.all()?"":v.collections().isEmpty()?" AND 0":" AND os.coll_id IN("+join(v.collections())+")")+")";
    }
    private static String join(Collection<Integer> ids){return ids.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));}

    public JSONObject detail(String id,Viewer v)throws KgException,KgReader.NotFound {
        JSONObject result=reader.store().read(c->record(c,id,v));
        if(result==null)throw new KgReader.NotFound("observation");return result;
    }
    public JSONObject page(String entity,String source,long after,int limit,Viewer v)throws KgException {
        return reader.store().read(c->page(c,entity,source,after,limit,v));
    }
    private static JSONObject page(Connection c,String entity,String source,long after,int limit,Viewer v)throws SQLException {
        int want=Math.max(1,Math.min(100,limit));JSONArray items=new JSONArray();long last=after;boolean more=false;
        String filter=entity==null?"":" AND (o.subject_id=? OR o.organization_id=? OR o.original_organization_id=?)";
        filter+=source==null?"":" AND o.source_id=?";
        try(PreparedStatement p=c.prepareStatement("SELECT o.public_id,o.observation_rowid FROM kg_observation o WHERE "
                +scope(v,"o")+" AND o.observation_rowid>?"+filter+" ORDER BY o.observation_rowid LIMIT ?")) {
            int arg=1;p.setLong(arg++,after);if(entity!=null){for(int i=0;i<3;i++)p.setString(arg++,entity);}if(source!=null)p.setString(arg++,source);
            p.setInt(arg,want+1);try(ResultSet r=p.executeQuery()){while(r.next()){
                if(items.length()==want){more=true;break;}last=r.getLong(2);items.put(record(c,r.getString(1),v));
            }}
        }
        return KgJson.obj("schema",SCHEMA,"items",items,"next_after",more?last:null,"has_more",more);
    }
    public JSONObject events(String id,long after,int limit,Viewer v)throws KgException,KgReader.NotFound {
        JSONObject result=reader.store().read(c->record(c,id,v)==null?null:eventPage(c,id,after,limit,v));
        if(result==null)throw new KgReader.NotFound("observation");return result;
    }
    private static JSONObject eventPage(Connection c,String id,long after,int limit,Viewer v)throws SQLException {
        int want=Math.max(1,Math.min(100,limit));JSONArray items=new JSONArray();long last=after;boolean more=false;
        try(PreparedStatement p=c.prepareStatement("SELECT e.* FROM kg_observation_event e JOIN kg_observation o ON o.public_id=e.observation_id WHERE "
                +scope(v,"o")+(v.all()?"":" AND (e.kind NOT IN('scope_insert','scope_delete') OR e.after_value IN ("
                    +(v.collections().isEmpty()?"SELECT NULL":join(v.collections()))+"))")
                +" AND e.event_rowid>?"+(id==null?"":" AND e.observation_id=?")+" ORDER BY e.event_rowid LIMIT ?")) {
            p.setLong(1,after);int n=2;if(id!=null)p.setString(n++,id);p.setInt(n,want+1);
            try(ResultSet r=p.executeQuery()){while(r.next()){
                if(items.length()==want){more=true;break;}last=r.getLong("event_rowid");items.put(event(c,r,v));
            }}
        }
        return KgJson.obj("schema",SCHEMA,"items",items,"next_after",more?last:null,"has_more",more);
    }
    /** Versioned full history export: observations followed by ALL authorized audit events, no per-record cap. */
    public JSONObject export(String cursor,int limit,Viewer v)throws KgException {
        return reader.store().read(c->{String epoch=KgStore.getMeta(c,KgSchema.META_EPOCH);char phase='o';long after=0,asOf=KgStore.queryLong(c,
                    "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='kg_change'),0)");
            if(cursor!=null) {
                if(!cursor.matches("[0-9a-f]{16}:[0-9]{1,18}:[oe][0-9]{1,18}"))throw new KgException(KgException.INVALID_CURSOR,"invalid history cursor");
                String[] bits=cursor.split(":");if(!epoch.equals(bits[0]))throw new KgException(KgException.EPOCH_CHANGED,"history epoch changed");
                asOf=Long.parseLong(bits[1]);phase=bits[2].charAt(0);after=Long.parseLong(bits[2].substring(1));
                if(asOf>KgStore.queryLong(c,"SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='kg_change'),0)"))
                    throw new KgException(KgException.INVALID_CURSOR,"history cursor ahead of feed");
            }
            JSONObject page=phase=='o'?page(c,null,null,after,limit,v):eventPage(c,null,after,limit,v);
            JSONArray records=page.optJSONArray("items");for(int i=0;i<records.length();i++)KgJson.put(records.optJSONObject(i),"record",phase=='o'?"observation":"observation_event");
            String next=page.optBoolean("has_more")?epoch+":"+asOf+":"+phase+page.optLong("next_after"):phase=='o'?epoch+":"+asOf+":e0":null;
            return KgJson.obj("schema",SCHEMA,"items",records,"next",next,"has_more",next!=null,"complete",next==null,"snapshot",false,
                    "next_changes",KgChangeLog.cursor(epoch,asOf));
        });
    }
    /** Streaming administrator download; failures after the header have an explicit incomplete trailer. */
    public void stream(Viewer viewer,String collection,int limit,KgExport.Sink sink)throws KgException,java.io.IOException {
        JSONObject first=export(null,limit,viewer);sink.begin(KgJson.obj("record","header","schema",SCHEMA,"collection",collection,"snapshot",false));
        JSONObject page=first;long count=0;
        try {
            while(true){JSONArray records=page.optJSONArray("items");for(int i=0;i<records.length();i++){sink.record(records.optJSONObject(i));count++;}
                if(!page.optBoolean("has_more"))break;page=export(page.optString("next"),limit,viewer);}
            sink.end(KgJson.obj("record","trailer","schema",SCHEMA,"complete",true,"records",count,"next_changes",page.opt("next_changes")));
        }catch(KgException e){sink.end(KgJson.obj("record","trailer","schema",SCHEMA,"complete",false,"records",count,"error",e.code()));}
    }
    public static JSONObject record(Connection c,String id,Viewer v)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT o.* FROM kg_observation o WHERE o.public_id=? AND "+scope(v,"o"))) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())return null;
                Object value=r.getString("value");try{if(((String)value).startsWith("{"))value=new JSONObject((String)value);}catch(JSONException ignored){ }
                JSONObject result=KgJson.obj("schema",SCHEMA,"id",id,"subject",r.getString("subject_id"),"subject_type",r.getString("subject_type"),
                        "organization",r.getString("organization_id"),"original_organization",r.getString("original_organization_id"),
                        "identity_context",json(r.getString("identity_context")),"predicate",r.getString("predicate"),"value",value,
                        "object",r.getString("object_id"),"quote",r.getString("quote"),"locator",r.getString("locator"),
                        "source",KgJson.obj("id",r.getString("source_id"),"url",r.getString("source_url"),"status",r.getString("source_status"),
                                "revision",r.getString("content_revision")),"extractor",r.getString("extractor"),"vocabulary_version",r.getString("vocabulary_version"),
                        "asserted_at",r.getString("asserted_at"),"observed_at",iso(r,"observed_at"),"recorded_at",iso(r,"recorded_at"),
                        "assertion_status",r.getString("assertion_status"),"correction_of",visibleReference(c,r.getString("correction_of"),v),
                        "certainty",r.getInt("certainty")==1?"stated":"qualified","assessment_kind","rule_or_extractor_assessment",
                        "organization_assignment",r.getString("organization_id")==null?"unresolved":"source_grounded",
                        "current_use","not_reconfirmed");
                JSONArray collections=new JSONArray(),origin=new JSONArray();
                Set<String> allowedNames=new HashSet<>();
                try(Statement s=c.createStatement();ResultSet names=s.executeQuery("SELECT name FROM kg_collection"+(v.all()?"":v.collections().isEmpty()?" WHERE 0":" WHERE coll_id IN("+join(v.collections())+")"))) {
                    while(names.next())allowedNames.add(names.getString(1));
                }
                try(PreparedStatement s=c.prepareStatement("SELECT c.name FROM kg_observation_scope os JOIN kg_collection c USING(coll_id) WHERE os.observation_rowid=? ORDER BY c.name")) {
                    s.setLong(1,r.getLong("observation_rowid"));try(ResultSet names=s.executeQuery()){while(names.next())if(allowedNames.contains(names.getString(1)))collections.put(names.getString(1));}
                }
                for(String name:r.getString("origin_scopes").split(","))if(allowedNames.contains(name))origin.put(name);
                KgJson.put(result,"collections",collections);KgJson.put(result,"origin_collections",origin);
                String statement=r.getString("statement_id");
                KgJson.put(result,"live_statement",statement!=null&&KgReader.statementRow(c,statement,v)!=null?statement:null);
                if("job".equals(r.getString("subject_type")))jobStatus(c,r,v,result);
                if(value instanceof JSONObject && "system_signal".equals(r.getString("predicate")))timeline(c,r,(JSONObject)value,v,result);
                return result;
            }
        }
    }
    /** A correction pointer must not disclose an observation outside today's grants. */
    private static String visibleReference(Connection c,String id,Viewer v)throws SQLException {
        if(id==null)return null;
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM kg_observation o WHERE o.public_id=? AND "+scope(v,"o"))) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?id:null;}
        }
    }
    private static void timeline(Connection c,ResultSet observation,JSONObject value,Viewer v,JSONObject out)throws SQLException {
        if(observation.getString("organization_id")==null)return;
        if(!List.of("internal_use","planned_migration").contains(value.optString("context")))return;
        net.yacy.scoutro.knowledge.derive.MatchingRules.Observation signal=
                net.yacy.scoutro.knowledge.derive.MatchingRules.Observation.read(c,observation);
        net.yacy.scoutro.knowledge.derive.MatchingRules.Observation later=
                net.yacy.scoutro.knowledge.derive.MatchingRules.latestSystemChange(c,signal,v);
        if(later!=null) {
            String context=later.collections.entrySet().stream().filter(e->v.all()||v.collections().contains(e.getKey())).map(java.util.Map.Entry::getValue).findFirst().orElse(null);
            KgJson.put(out,"later_system_change",KgJson.obj("observation",later.id,"observed_at",KgReader.iso(later.observedAt),"value",later.value,"collection",context));
        }
    }

    private static void jobStatus(Connection c,ResultSet observation,Viewer v,JSONObject out)throws SQLException {
        String status="unknown",reason="not_reconfirmed";String deadline=null;
        try(PreparedStatement p=c.prepareStatement("SELECT o.predicate,o.value,o.observed_at FROM kg_observation o WHERE " +scope(v,"o")
                +" AND o.subject_id=? AND o.source_id=? AND o.assertion_status='recorded' AND o.predicate IN('job_status','valid_through')"
                +" ORDER BY o.observed_at DESC,o.observation_rowid DESC")) {
            p.setString(1,observation.getString("subject_id"));p.setString(2,observation.getString("source_id"));
            try(ResultSet r=p.executeQuery()){while(r.next()) {
                if("job_status".equals(r.getString(1))&&"ended".equals(r.getString(2))){status="ended";reason="explicit_end";break;}
                if(deadline==null&&"valid_through".equals(r.getString(1)))deadline=r.getString(2);
            }}
        }
        java.time.LocalDate until=net.yacy.scoutro.knowledge.extract.Values.lastDay(deadline);
        if(!"ended".equals(status)&&until!=null&&until.isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC))) {
            status="deadline_passed";reason="application_deadline";
        }
        KgJson.put(out,"job_search",KgJson.obj("status",status,"reason",reason,"valid_through",deadline,"position_filled","unknown"));
    }
    private static JSONObject event(Connection c,ResultSet r,Viewer v)throws SQLException {
        String kind=r.getString("kind"),before=r.getString("before_value"),after=r.getString("after_value");
        if(kind.startsWith("scope_")) {
            // Audit class changes without leaking historical collection names outside the caller's grants.
            String coll=null;try(PreparedStatement p=c.prepareStatement("SELECT name FROM kg_collection WHERE coll_id=?"+(v.all()?"":v.collections().isEmpty()?" AND 0":" AND coll_id IN("+join(v.collections())+")"))) {
                p.setString(1,after);try(ResultSet n=p.executeQuery()){if(n.next())coll=n.getString(1);}
            }before=null;after=coll;
        }
        return KgJson.obj("id",r.getString("public_id"),"sequence",r.getLong("event_rowid"),"observation",r.getString("observation_id"),"kind",kind,"at",iso(r,"at"),"before",before,"after",after);
    }
    private static String iso(ResultSet r,String column)throws SQLException {return r.getObject(column)==null?null:KgReader.iso(r.getLong(column));}
    private static JSONObject json(String value)throws SQLException {try{return new JSONObject(value);}catch(JSONException e){throw new SQLException("invalid observation JSON",e);}}
}
