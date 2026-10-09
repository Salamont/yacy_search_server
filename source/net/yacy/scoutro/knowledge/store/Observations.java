/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.store;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Durable, source-grounded business history. No references to live row IDs. */
public final class Observations {
    private Observations() { }

    /** Applies a source's actual CURRENT classification to every archived revision,
     * including a source that is now outside the extraction collections. */
    public static void classify(final Connection tx, final String source, final java.util.Collection<String> names) throws SQLException {
        final java.util.Set<String> wanted=new java.util.TreeSet<>();
        for(String name:names) if(name.matches("[A-Za-z0-9_-]{1,64}")) wanted.add(name);
        final java.util.Set<String> current=new java.util.TreeSet<>();
        try(PreparedStatement p=tx.prepareStatement("SELECT DISTINCT c.name FROM kg_observation_scope s JOIN kg_collection c USING(coll_id)"
                + " JOIN kg_observation o USING(observation_rowid) WHERE o.source_id=?")) {
            p.setString(1,source);try(ResultSet r=p.executeQuery()){while(r.next()) current.add(r.getString(1));}
        }
        if(wanted.equals(current)) {
            final long incomplete;
            try(PreparedStatement p=tx.prepareStatement("SELECT count(*) FROM kg_observation o WHERE o.source_id=? AND"
                    + " (SELECT count(*) FROM kg_observation_scope s WHERE s.observation_rowid=o.observation_rowid)<>?")) {
                p.setString(1,source);p.setInt(2,wanted.size());try(ResultSet r=p.executeQuery()){r.next();incomplete=r.getLong(1);}
            }
            if(incomplete==0)return;
        }
        try (PreparedStatement del=tx.prepareStatement("DELETE FROM kg_observation_scope WHERE observation_rowid IN"
                + "(SELECT observation_rowid FROM kg_observation WHERE source_id=?)")) {
            del.setString(1,source);del.executeUpdate();
        }
        try (PreparedStatement coll=tx.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)");
                PreparedStatement scope=tx.prepareStatement("INSERT OR IGNORE INTO kg_observation_scope SELECT o.observation_rowid,c.coll_id"
                + " FROM kg_observation o,kg_collection c WHERE o.source_id=? AND c.name=?")) {
            for(String name:wanted) {
                coll.setString(1,name);coll.executeUpdate();scope.setString(1,source);scope.setString(2,name);scope.executeUpdate();
            }
        }
    }

    public static void capture(final Connection tx, final long document) throws SQLException {
        try (Statement s = tx.createStatement()) {
            s.executeUpdate(ObservationSchema.capture("d.doc_rowid=" + document));
        }
    }

    /** Idempotent migration/backfill of evidence that still exists. No re-extraction. */
    public static void captureExisting(final Connection tx) throws SQLException {
        try (Statement s = tx.createStatement()) {
            s.executeUpdate(ObservationSchema.capture("1"));
        }
    }

    /** Final rebuild carry after workers and the old writer have stopped.
     * The old archive is authoritative for its IDs, corrections and classification.
     * Newly extracted shadow-only observations remain. Restore deliberately never calls this.
     */
    public static void carryInto(final File target, final File previous) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + target.getAbsolutePath())) {
            try (Statement s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); }
            try (PreparedStatement p = c.prepareStatement("ATTACH DATABASE ? AS previous")) {
                p.setString(1, previous.getAbsolutePath()); p.execute();
            }
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                // Map collection IDs by name: the shadow assigns its own integer IDs.
                s.executeUpdate("CREATE TEMP TABLE observation_actor_map AS SELECT source_id,subject_type,predicate,value,locator,quote,"
                        +"CASE WHEN count(DISTINCT organization_id)=1 THEN min(organization_id) ELSE NULL END AS organization_id"
                        +" FROM kg_observation GROUP BY source_id,subject_type,predicate,value,locator,quote");
                s.executeUpdate("INSERT OR IGNORE INTO kg_collection(name) SELECT name FROM previous.kg_collection");
                s.executeUpdate("DELETE FROM kg_observation_event WHERE observation_id IN (SELECT public_id FROM previous.kg_observation)");
                s.executeUpdate("DELETE FROM kg_observation WHERE public_id IN (SELECT public_id FROM previous.kg_observation)"
                        + " OR EXISTS(SELECT 1 FROM previous.kg_observation p WHERE p.source_id=kg_observation.source_id"
                        + " AND p.content_revision=kg_observation.content_revision AND p.subject_id=kg_observation.subject_id"
                        + " AND p.predicate=kg_observation.predicate AND p.value=kg_observation.value AND p.locator=kg_observation.locator"
                        + " AND p.tier=kg_observation.tier AND p.extractor=kg_observation.extractor AND p.quote=kg_observation.quote)");
                s.executeUpdate("DELETE FROM kg_observation_event WHERE NOT EXISTS(SELECT 1 FROM kg_observation o WHERE o.public_id=observation_id)");
                final StringBuilder columns = new StringBuilder();
                try (ResultSet r = s.executeQuery("PRAGMA table_info(kg_observation)")) {
                    while (r.next()) {
                        final String name = r.getString("name");
                        if (!"observation_rowid".equals(name)) {
                            if (columns.length() > 0) columns.append(',');
                            columns.append(name);
                        }
                    }
                }
                s.executeUpdate("INSERT INTO kg_observation(" + columns + ") SELECT " + columns + " FROM previous.kg_observation");
                // Delete classifications added by insertion triggers, then copy CURRENT classification, never origin_scopes.
                s.executeUpdate("DELETE FROM kg_observation_scope WHERE observation_rowid IN"
                        + " (SELECT observation_rowid FROM kg_observation WHERE public_id IN(SELECT public_id FROM previous.kg_observation))");
                s.executeUpdate("INSERT INTO kg_observation_scope SELECT n.observation_rowid,c.coll_id FROM previous.kg_observation_scope ps"
                        + " JOIN previous.kg_observation p ON p.observation_rowid=ps.observation_rowid"
                        + " JOIN previous.kg_collection pc ON pc.coll_id=ps.coll_id JOIN kg_collection c ON c.name=pc.name"
                        + " JOIN kg_observation n ON n.public_id=p.public_id");
                // Shadow-only new revisions of known sources must also inherit their final current classification.
                s.executeUpdate("DELETE FROM kg_observation_scope WHERE observation_rowid IN(SELECT o.observation_rowid FROM kg_observation o"
                        + " WHERE o.public_id NOT IN(SELECT public_id FROM previous.kg_observation) AND (o.source_id IN(SELECT source_id FROM previous.kg_observation)"
                        + " OR o.source_id IN(SELECT doc_id FROM previous.kg_doc)))");
                s.executeUpdate("INSERT OR IGNORE INTO kg_observation_scope SELECT n.observation_rowid,c.coll_id FROM kg_observation n"
                        + " JOIN previous.kg_doc d ON d.doc_id=n.source_id JOIN previous.kg_doc_collection dc ON dc.doc_rowid=d.doc_rowid"
                        + " JOIN previous.kg_collection pc ON pc.coll_id=dc.coll_id JOIN kg_collection c ON c.name=pc.name"
                        + " WHERE n.public_id NOT IN(SELECT public_id FROM previous.kg_observation)");
                s.executeUpdate("INSERT OR IGNORE INTO kg_observation_scope SELECT n.observation_rowid,c.coll_id FROM kg_observation n"
                        + " JOIN previous.kg_observation p ON p.source_id=n.source_id JOIN previous.kg_observation_scope ps ON ps.observation_rowid=p.observation_rowid"
                        + " JOIN previous.kg_collection pc ON pc.coll_id=ps.coll_id JOIN kg_collection c ON c.name=pc.name"
                        + " WHERE n.public_id NOT IN(SELECT public_id FROM previous.kg_observation) AND NOT EXISTS(SELECT 1 FROM previous.kg_doc d WHERE d.doc_id=n.source_id)");
                // Replace the insertion/scope events generated above with the authoritative original event history.
                s.executeUpdate("DELETE FROM kg_observation_event WHERE observation_id IN(SELECT public_id FROM previous.kg_observation)");
                s.executeUpdate("INSERT INTO kg_observation_event(public_id,observation_id,kind,at,before_value,after_value)"
                        + " SELECT e.public_id,e.observation_id,e.kind,e.at,e.before_value,CASE WHEN e.kind LIKE 'scope_%' THEN CAST(nc.coll_id AS TEXT) ELSE e.after_value END"
                        + " FROM previous.kg_observation_event e LEFT JOIN previous.kg_collection pc ON e.kind LIKE 'scope_%' AND pc.coll_id=CAST(e.after_value AS INTEGER)"
                        + " LEFT JOIN kg_collection nc ON nc.name=pc.name ORDER BY e.event_rowid");
                // A live redirect may select just one part of an old identity split.
                // Reassign archived actors only from matching source-local grounding; ambiguity stays unresolved.
                s.executeUpdate("UPDATE kg_observation SET organization_id=(SELECT m.organization_id FROM observation_actor_map m"
                        +" WHERE m.source_id=kg_observation.source_id AND m.subject_type=kg_observation.subject_type AND m.predicate=kg_observation.predicate"
                        +" AND m.value=kg_observation.value AND m.locator=kg_observation.locator AND m.quote=kg_observation.quote) WHERE EXISTS"
                        + "(SELECT 1 FROM kg_entity_redirect r WHERE r.public_id=kg_observation.organization_id)");
                final long wanted = KgStore.queryLong(c,"SELECT count(*) FROM previous.kg_observation");
                final long got = KgStore.queryLong(c,"SELECT count(*) FROM kg_observation WHERE public_id IN(SELECT public_id FROM previous.kg_observation)");
                if (wanted != got) throw new SQLException("incomplete observation carry");
                if(KgStore.queryLong(c,"SELECT CAST(value AS INTEGER) FROM previous.kg_meta WHERE key='schema_version'")>=6) {
                    // Preserve archive-backed proposal IDs through the rebuild. Rules and current assignments
                    // are still revalidated when read. A fresh epoch needs no old feed access chains.
                    s.executeUpdate("INSERT OR REPLACE INTO kg_match_contribution(public_id,proposal_id,kind,provider_id,candidate_id,rule,rule_version,"
                            +"service_key,signal_key,corroboration_key,refs,computed_at,seen_generation) SELECT public_id,proposal_id,kind,provider_id,"
                            +"candidate_id,rule,rule_version,service_key,signal_key,corroboration_key,refs,computed_at,seen_generation FROM previous.kg_match_contribution");
                    s.executeUpdate("INSERT OR IGNORE INTO kg_match_ref SELECT r.contribution_id,r.observation_id FROM previous.kg_match_ref r"
                            +" JOIN kg_observation o ON o.public_id=r.observation_id");
                    try(ResultSet r=s.executeQuery("SELECT public_id FROM kg_match_contribution")) {
                        java.util.List<String> ids=new java.util.ArrayList<>();while(r.next())ids.add(r.getString(1));
                        for(String id:ids)MatchingAccess.remember(c,id);
                    }
                    KgStore.putMeta(c,"matching_work","{}");
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
}
