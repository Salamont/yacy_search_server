/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.store;

import java.sql.*;
import org.json.*;
import net.yacy.scoutro.knowledge.store.KgChangeLog.*;

/** Conjunction of evidence dependencies, with alternative scopes per dependency. */
public final class MatchingAccess {
    private MatchingAccess() { }
    public static boolean allowed(String chain,Viewer v) {
        try {
            JSONArray refs=new JSONArray(chain);if(refs.length()==0)return false;
            for(int i=0;i<refs.length();i++) {
                JSONArray choices=refs.getJSONArray(i);boolean found=false;
                for(int j=0;j<choices.length();j++)if(v.all()||v.collections().contains(choices.getInt(j))){found=true;break;}
                if(!found)return false;
            }return true;
        }catch(JSONException e){return false;}
    }
    public static String chain(Connection c,String id)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT "+MatchingSchema.CHAIN+" FROM kg_match_contribution m WHERE m.public_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):"[]";}
        }
    }
    public static void remember(Connection c,String id)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO kg_match_access VALUES(?,?)")) {
            p.setString(1,id);p.setString(2,chain(c,id));p.executeUpdate();
        }
    }
    public static Op visibleOp(Connection c,String id,Op op,Viewer v)throws SQLException {
        if(op!=Op.DELETE&&allowed(chain(c,id),v))return op;
        try(PreparedStatement p=c.prepareStatement("SELECT chain FROM kg_match_access WHERE contribution_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){while(r.next())if(allowed(r.getString(1),v))return Op.DELETE;}
        }return null;
    }
}
