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
        if(wanted.equals(current)) return;
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
                s.executeUpdate("INSERT OR IGNORE INTO kg_collection(name) SELECT name FROM previous.kg_collection");
                s.executeUpdate("DELETE FROM kg_observation_event WHERE observation_id IN (SELECT public_id FROM previous.kg_observation)");
                s.executeUpdate("DELETE FROM kg_observation WHERE public_id IN (SELECT public_id FROM previous.kg_observation)"
                        + " OR EXISTS(SELECT 1 FROM previous.kg_observation p WHERE p.source_id=kg_observation.source_id"
                        + " AND p.content_revision=kg_observation.content_revision AND p.subject_id=kg_observation.subject_id"
                        + " AND p.predicate=kg_observation.predicate AND p.value=kg_observation.value AND p.locator=kg_observation.locator"
                        + " AND p.tier=kg_observation.tier AND p.extractor=kg_observation.extractor AND p.quote=kg_observation.quote)");
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
                // Replace the insertion/scope events generated above with the authoritative original event history.
                s.executeUpdate("DELETE FROM kg_observation_event WHERE observation_id IN(SELECT public_id FROM previous.kg_observation)");
                s.executeUpdate("INSERT INTO kg_observation_event(observation_id,kind,at,before_value,after_value)"
                        + " SELECT observation_id,kind,at,before_value,after_value FROM previous.kg_observation_event ORDER BY event_rowid");
                final long wanted = KgStore.queryLong(c,"SELECT count(*) FROM previous.kg_observation");
                final long got = KgStore.queryLong(c,"SELECT count(*) FROM kg_observation WHERE public_id IN(SELECT public_id FROM previous.kg_observation)");
                if (wanted != got) throw new SQLException("incomplete observation carry");
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
}
