/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Values;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/** Read-only projection of existing customer/partner derivations. The origin
 * view selects the origin's contribution, never the candidate permission scope.
 * Other facts, exports and weak/structural derivations keep their strict viewer. */
public final class Suggestions {
    private final KgReader reader;

    public Suggestions(final KgReader reader) {
        this.reader = reader;
    }

    static final class Group {
        final int kind;
        final long other;
        double score;
        final boolean outgoing;
        String otherId;
        JSONObject otherRef;
        final List<JSONObject> contributions=new ArrayList<>();
        final MatchingProjection.Deduplicator deduplicator=new MatchingProjection.Deduplicator(contributions);

        Group(final int kind, final long other, final double score, final boolean outgoing) {
            this.kind = kind;
            this.other = other;
            this.score = score;
            this.outgoing = outgoing;
        }
    }

    /** Authorization, endpoint membership and origin filtering precede grouping and pagination. */
    private static String where(final long ent, final Viewer selectedOrigin, final Viewer permitted) {
        final Viewer origin = KgReader.within(selectedOrigin, permitted);
        final String selected = origin.all() ? "1" : "(CASE WHEN d.a_ent = " + ent
                + " THEN d.coll_a ELSE d.coll_b END) IN (" + BusinessView.ids(origin) + ")";
        return "d.kind IN (3,4) AND (d.a_ent = " + ent + " OR d.b_ent = " + ent + ") AND "
                + BusinessView.visibleDerived(permitted, "d") + " AND " + selected
                + " AND a.status = 1 AND b.status = 1"
                + " AND EXISTS (SELECT 1 FROM kg_entity_scope sa WHERE sa.ent_rowid = d.a_ent AND sa.coll_id = d.coll_a)"
                + " AND EXISTS (SELECT 1 FROM kg_entity_scope sb WHERE sb.ent_rowid = d.b_ent AND sb.coll_id = d.coll_b)";
    }

    private static String from(final long ent, final Viewer origin, final Viewer permitted) {
        return " FROM kg_derived d JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b ON b.ent_rowid = d.b_ent"
                + " JOIN kg_collection ca ON ca.coll_id = d.coll_a JOIN kg_collection cb ON cb.coll_id = d.coll_b WHERE "
                + where(ent, origin, permitted);
    }

    /** Lightweight unique neighbours for graph pagination; no per-row JSON/evidence expansion here. */
    static List<Group> groups(final Connection c, final long ent, final Viewer origin, final Viewer permitted,final KgReader reader) throws SQLException {
        final List<Group> groups = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.kind, CASE WHEN d.a_ent = " + ent
                + " THEN d.b_ent ELSE d.a_ent END AS other, max(d.confidence) AS score, CASE WHEN d.kind = 4 OR d.a_ent = "
                + ent + " THEN 1 ELSE 0 END AS outgoing" + from(ent, origin, permitted)
                + " GROUP BY d.kind, other, outgoing ORDER BY score DESC, other, d.kind, outgoing DESC"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Group g=new Group(rs.getInt(1),rs.getLong(2),0,rs.getInt(4)!=0);g.otherId=KgReader.publicId(c,g.other);groups.add(g);
            }
        }
        Suggestions projection=new Suggestions(reader);
        for(Group g:groups) {
            JSONObject legacy=projection.legacyItem(c,ent,g,origin,permitted);g.otherRef=legacy.optJSONObject("other");
            JSONArray reasons=legacy.optJSONArray("contributions");
            for(int i=0;i<reasons.length();i++) {
                JSONObject reason=reasons.optJSONObject(i);
                if(!reason.optBoolean("evidence_complete"))continue;
                KgJson.put(reason,"rule","legacy_industry_or_partner");KgJson.put(reason,"rule_version","legacy-1");
                KgJson.put(reason,"evidence_strength","qualified");KgJson.put(reason,"temporal_status","legacy_not_reconfirmed");
                g.contributions.add(reason);g.score=Math.max(g.score,reason.optDouble("score"));
            }
        }
        groups.removeIf(g->g.contributions.isEmpty());
        for(JSONObject reason:MatchingProjection.visible(c,KgReader.publicId(c,ent),origin,permitted,reader.config())) {
            int kind="suggested_customer".equals(reason.optString("kind"))?3:4;
            boolean outgoing=kind==4||"out".equals(reason.optString("direction"));
            String other=reason.optJSONObject("other").optString("id");
            Group g=groups.stream().filter(x->x.kind==kind&&x.outgoing==outgoing&&other.equals(x.otherId)).findFirst().orElse(null);
            if(g==null) {
                long[] row=KgReader.entityRow(c,other,permitted);g=new Group(kind,row==null?-1:row[0],0,outgoing);
                g.otherId=other;g.otherRef=reason.optJSONObject("other");groups.add(g);
            }
            g.deduplicator.add(reason);
            g.score=Math.max(g.score,reason.optDouble("score"));
        }
        groups.sort(java.util.Comparator.comparingDouble((Group g)->g.score).reversed().thenComparing(g->g.otherId)
                .thenComparingInt(g->g.kind).thenComparing(g->!g.outgoing));
        return groups;
    }

    public JSONObject page(final String id, final int offset, final int limit, final Viewer selected, final Viewer permitted)
            throws KgException, KgReader.NotFound {
        final Viewer origin = KgReader.within(selected, permitted);
        final Object result = this.reader.store().read(c -> {
            final long[] ent = KgReader.entityRow(c, id, origin);
            if (ent == null) return null;
            if (ent[1] != 0L) return KgJson.obj("schema", BusinessView.SCHEMA, "redirect", KgReader.publicId(c, ent[1]));
            return page(c, ent[0], offset, limit, origin, permitted);
        });
        if (result == null) throw new KgReader.NotFound("entity " + id);
        return (JSONObject) result;
    }

    JSONObject page(final Connection c, final long ent, final int offset, final int limit, final Viewer origin, final Viewer permitted)
            throws SQLException {
        final List<Group> groups = groups(c, ent, origin, permitted,this.reader);
        final JSONArray items = new JSONArray();
        final int end = Math.min(groups.size(), offset + limit);
        for (int i = Math.min(offset, groups.size()); i < end; i++) items.put(item(c, ent, groups.get(i), origin, permitted));
        return KgJson.obj("schema", BusinessView.SCHEMA, "origin", KgReader.publicId(c, ent), "offset", offset, "limit", limit,
                "total", groups.size(), "items", items, "next_offset", end < groups.size() ? end : null,
                "note", "Existing derivations, not facts. Scores are sorting values, not measured probabilities.");
    }

    /** Expand only a shown group. Keep all its authorized collection-pair contributions. */
    private JSONObject legacyItem(final Connection c, final long ent, final Group group, final Viewer origin, final Viewer permitted) throws SQLException {
        final JSONArray contributions = new JSONArray();
        JSONObject item = null;
        final Set<String> origins = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.public_id, d.a_ent, d.b_ent, d.coll_a, d.coll_b, d.confidence,"
                + " d.reason, d.basis, d.computed_at, ca.name, cb.name" + from(ent, origin, permitted)
                + " AND d.kind = ? AND (CASE WHEN d.a_ent = ? THEN d.b_ent ELSE d.a_ent END) = ?"
                + (group.kind == 3 ? " AND d.a_ent " + (group.outgoing ? "=" : "<>") + " " + ent : "")
                + " ORDER BY d.confidence DESC, d.public_id")) {
            ps.setInt(1, group.kind);
            ps.setLong(2, ent);
            ps.setLong(3, group.other);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final boolean mine = rs.getLong(2) == ent;
                    final Viewer av = Viewer.of(Set.of(rs.getInt(4))), bv = Viewer.of(Set.of(rs.getInt(5)));
                    final String originName = rs.getString(mine ? 10 : 11), targetName = rs.getString(mine ? 11 : 10);
                    origins.add(originName);
                    final List<JSONObject> bases=new ArrayList<>();
                    try(PreparedStatement p=c.prepareStatement("SELECT reason_key,score,reason,basis FROM kg_derived_reason WHERE derived_id=? ORDER BY score DESC,reason_key")) {
                        p.setString(1,rs.getString(1));try(ResultSet extra=p.executeQuery()){while(extra.next())bases.add(KgJson.obj("id",extra.getString(1),"score",extra.getDouble(2),"reason",extra.getString(3),"basis",Values.json(extra.getString(4))));}
                    }
                    if(bases.isEmpty())bases.add(KgJson.obj("id",rs.getString(1),"score",rs.getDouble(6),"reason",rs.getString(7),"basis",Values.json(rs.getString(8))));
                    for(JSONObject saved:bases) {
                    final JSONObject basis = saved.optJSONObject("basis");
                    final JSONArray evidence = new JSONArray();
                    final boolean completeA = references(c, basis == null ? null : basis.optJSONArray("a"), av, rs.getString(10), evidence);
                    final boolean completeB = references(c, basis == null ? null : basis.optJSONArray("b"), bv, rs.getString(11), evidence);
                    final String reason = completeA && completeB ? saved.optString("reason") : "Supporting statements are no longer fully available.";
                    final JSONObject contribution = KgJson.obj("id", rs.getString(1),"reason_id",saved.optString("id"), "direction", mine ? "out" : "in", "collection_a", rs.getString(10), "collection_b",
                            rs.getString(11), "origin_collection", originName, "target_collection", targetName, "reason", reason,
                            "score", BusinessView.round(saved.optDouble("score")), "computed_at", KgReader.iso(rs.getLong(9)), "evidence", evidence,
                            "evidence_complete", completeA && completeB);
                    contributions.put(contribution);
                    if (item == null) {
                        final JSONObject other = BusinessView.entityRef(c, group.other, mine ? bv : av);
                        final Set<String> memberships = memberships(c, group.other, permitted);
                        KgJson.put(other, "collections", new JSONArray(memberships));
                        KgJson.put(other, "target_collection", targetName);
                        item = KgJson.obj("id", rs.getString(1), "kind", Vocabulary.DERIVED_KINDS.get(group.kind), "direction", mine ? "out" : "in",
                                "other", other, "confidence", BusinessView.round(group.score), "score", BusinessView.round(group.score),
                                "reason", reason, "computed_at", KgReader.iso(rs.getLong(9)), "fact", false, "label", "suggestion",
                                "target_collection", targetName);
                    }
                    }
                }
            }
        }
        final Set<String> external = memberships(c, group.other, permitted);
        external.removeAll(memberships(c, ent, origin));
        KgJson.put(item.optJSONObject("other"), "other_collections", new JSONArray(external));
        KgJson.put(item, "origin_collections", new JSONArray(origins));
        KgJson.put(item, "contributions", contributions);
        return item;
    }

    JSONObject item(Connection c,long ent,Group group,Viewer origin,Viewer permitted)throws SQLException {
        List<JSONObject> reasons=new ArrayList<>(group.contributions);
        reasons.sort(java.util.Comparator.comparingDouble((JSONObject x)->x.optDouble("score")).reversed().thenComparing(x->x.optString("id")));
        JSONObject best=reasons.get(0);JSONObject other=Values.json(group.otherRef.toString());
        Set<String> external=new TreeSet<>(),origins=new TreeSet<>();
        for(JSONObject reason:reasons) {
            String name=reason.optString("target_collection");if(!name.isEmpty())external.add(name);
            String self=reason.optString("origin_collection");if(!self.isEmpty())origins.add(self);
        }
        external.removeAll(memberships(c,ent,origin));
        KgJson.put(other,"other_collections",new JSONArray(external));
        String target=best.optString("target_collection",other.optString("target_collection",null));
        KgJson.put(other,"target_collection",target);
        String id=reasons.stream().filter(x->x.optString("rule").startsWith("legacy")).map(x->x.optString("id")).sorted().findFirst()
                .orElse(best.optString("proposal_id",best.optString("id")));
        JSONObject out=KgJson.obj("id",id,"kind",Vocabulary.DERIVED_KINDS.get(group.kind),"direction",best.optString("direction"),
                "other",other,"confidence",group.score,"score",group.score,"reason",best.optString("reason"),
                "computed_at",best.opt("computed_at"),"fact",false,"label","suggestion","target_collection",target,
                "origin_collections",new JSONArray(origins),"contributions",new JSONArray(reasons.subList(0,Math.min(25,reasons.size()))),
                "contributions_total",reasons.size(),"next_contribution_offset",reasons.size()>25?25:null);
        KgJson.put(out,"contributions_path","entities/"+KgReader.publicId(c,ent)+"/suggestions/"+id+"/contributions");
        return out;
    }

    public JSONObject contributions(String entity,String proposal,int offset,int limit,Viewer selected,Viewer permitted)throws KgException,KgReader.NotFound {
        JSONObject result=reader.store().read(c->{
            Viewer origin=KgReader.within(selected,permitted);long[] row=KgReader.entityRow(c,entity,origin);
            if(row==null) {
                // Details remain reachable from export/global suggestions after live origin cleanup.
                // The origin's archived service/signal must still be in the selected, permitted view.
                List<JSONObject> visible=MatchingProjection.visible(c,entity,origin,permitted,reader.config());
                JSONObject seed=visible.stream().filter(x->proposal.equals(x.optString("proposal_id"))||proposal.equals(x.optString("id"))).findFirst().orElse(null);
                if(seed==null)return null;
                List<JSONObject> reasons=new ArrayList<>();MatchingProjection.Deduplicator duplicate=new MatchingProjection.Deduplicator(reasons);
                for(JSONObject x:visible)if(seed.optString("kind").equals(x.optString("kind"))
                        &&seed.optJSONObject("other").optString("id").equals(x.optJSONObject("other").optString("id"))
                        &&("suggested_partner".equals(seed.optString("kind"))||seed.optString("direction").equals(x.optString("direction"))))duplicate.add(x);
                return contributionPage(reasons,offset,limit);
            }
            for(Group g:groups(c,row[0],origin,permitted,reader)) {
                if(g.contributions.stream().noneMatch(x->proposal.equals(x.optString("proposal_id"))||proposal.equals(x.optString("id"))))continue;
                return contributionPage(g.contributions,offset,limit);
            }return null;
        });
        if(result==null)throw new KgReader.NotFound("suggestion");return result;
    }

    private static JSONObject contributionPage(List<JSONObject> reasons,int offset,int limit) {
        List<JSONObject> all=new ArrayList<>(reasons);all.sort(java.util.Comparator.comparingDouble((JSONObject x)->x.optDouble("score")).reversed().thenComparing(x->x.optString("id")));
        int end=Math.min(all.size(),offset+limit);
        return KgJson.obj("schema",BusinessView.SCHEMA,"items",new JSONArray(all.subList(Math.min(offset,all.size()),end)),
                "total",all.size(),"offset",offset,"limit",limit,"next_offset",end<all.size()?end:null);
    }

    private boolean references(final Connection c, final JSONArray ids, final Viewer v, final String collection, final JSONArray out)
            throws SQLException {
        if (ids == null || ids.length() == 0) return false;
        boolean complete = true;
        final Set<Long> seen = new TreeSet<>();
        for (int i = 0; i < ids.length(); i++) {
            final String id = ids.optString(i);
            final KgReader.Stat s = KgReader.statementRow(c, id, v);
            if (s == null || !BusinessView.visible(c, s.subj, v) || s.objEnt != null && !BusinessView.visible(c, s.objEnt, v)) {
                complete = false;
                continue;
            }
            if (!seen.add(s.rowid)) continue;
            this.reader.compute(c, List.of(s), v, this.reader.now());
            final JSONObject statement = this.reader.statementJson(c, s, v);
            if (s.publicId == null) KgJson.put(statement, "id", KgReader.statementPublicId(c, s.rowid));
            KgJson.put(statement, "collection", collection);
            out.put(statement);
        }
        return complete;
    }

    private static Set<String> memberships(final Connection c, final long ent, final Viewer v) throws SQLException {
        final Set<String> names = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT k.name FROM kg_entity_scope es JOIN kg_collection k ON k.coll_id = es.coll_id"
                + " WHERE es.ent_rowid = ?" + (v.all() ? "" : " AND es.coll_id IN (" + BusinessView.ids(v) + ")") + " ORDER BY k.name")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) names.add(rs.getString(1)); }
        }
        return names;
    }

    static JSONObject legacyRecord(Connection c,String id,Viewer v,KgReader reader)throws SQLException {
        return legacyRecord(c,id,v,reader,true);
    }
    static JSONObject legacyRecord(Connection c,String id,Viewer v,KgReader reader,boolean preview)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT a_ent,b_ent,kind FROM kg_derived WHERE public_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()||r.getInt(3)<3)return null;
                long origin=r.getLong(1);for(Group g:groups(c,origin,v,v,reader)) {
                    List<JSONObject> mine=new ArrayList<>();for(JSONObject reason:g.contributions)if(id.equals(reason.optString("id")))mine.add(reason);
                    if(mine.isEmpty())continue;
                    JSONObject item=itemForLegacy(reader,c,origin,g,v,mine);KgJson.put(item,"id",id);
                    if(!preview)KgJson.put(item,"contributions",new JSONArray(mine));
                    KgJson.put(item,"subject",BusinessView.entityRef(c,origin,v));return item;
                }return null;
            }
        }
    }
    private static JSONObject itemForLegacy(KgReader reader,Connection c,long ent,Group group,Viewer v,List<JSONObject> reasons)throws SQLException {
        Group only=new Group(group.kind,group.other,0,group.outgoing);only.otherId=group.otherId;only.otherRef=group.otherRef;only.contributions.addAll(reasons);
        for(JSONObject reason:reasons)only.score=Math.max(only.score,reason.optDouble("score"));
        return new Suggestions(reader).item(c,ent,only,v,v);
    }
}
