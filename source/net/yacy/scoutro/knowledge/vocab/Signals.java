/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.vocab;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Small global, content-versioned vocabularies. Collection categories never gate these signals. */
public final class Signals {
    public final String version;
    private final Map<String,List<Pattern>> products,needs;
    private Signals(Map<String,List<Pattern>> products,Map<String,List<Pattern>> needs,String version) {
        this.products=products;this.needs=needs;this.version=version;
    }
    public static Signals load(File defaults,File overrides,List<String> problems) {
        Map<String,List<Pattern>> products=new LinkedHashMap<>(),needs=new LinkedHashMap<>();
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            read(defaults,overrides,"products.json","products",products,hash,problems);
            read(defaults,overrides,"needs.json","needs",needs,hash,problems);
            return new Signals(products,needs,java.util.HexFormat.of().formatHex(hash.digest()).substring(0,24));
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static void read(File defaults,File overrides,String filename,String field,Map<String,List<Pattern>> out,
            MessageDigest hash,List<String> problems) {
        File f=overrides!=null && new File(overrides,filename).isFile() ? new File(overrides,filename) : new File(defaults,filename);
        try {
            if(f.length()>KgVocabularies.MAX_FILE_BYTES) throw new IllegalArgumentException("too large");
            byte[] bytes=Files.readAllBytes(f.toPath());hash.update(filename.getBytes(StandardCharsets.UTF_8));hash.update(bytes);
            JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(root.optInt("version",0)!=1) throw new IllegalArgumentException("unsupported version");
            JSONObject entries=root.getJSONObject(field);
            for(String id:new TreeSet<>(entries.keySet())) {
                if(!id.matches("[a-z][a-z0-9-]{1,63}")) throw new IllegalArgumentException("invalid code");
                JSONArray aliases=entries.getJSONArray(id);List<Pattern> patterns=new ArrayList<>();
                for(int i=0;i<aliases.length();i++) {
                    String alias=aliases.getString(i);if(alias.isBlank()||alias.length()>100) throw new IllegalArgumentException("invalid alias");
                    patterns.add(Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"+Pattern.quote(alias)+"(?![\\p{L}\\p{N}])"));
                }
                out.put(id,Collections.unmodifiableList(patterns));
            }
        } catch(Exception e) {out.clear();problems.add(filename+": "+e.getClass().getSimpleName());}
    }
    private static Set<String> hits(Map<String,List<Pattern>> vocabulary,String text) {
        Set<String> result=new LinkedHashSet<>();
        for(Map.Entry<String,List<Pattern>> entry:vocabulary.entrySet()) for(Pattern p:entry.getValue())
            if(p.matcher(text).find()){result.add(entry.getKey());break;}
        return result;
    }
    public Set<String> products(String text) {
        Set<String> out=hits(products,text);
        // The acronym alone is ambiguous. SAPV cannot match the SAP word boundary.
        if(out.contains("sap-sac")&&!Pattern.compile("(?iu)\\bSAP\\s+SAC\\b|Analytics\\s+Cloud").matcher(text).find()) out.remove("sap-sac");
        if(out.contains("sap-bw4"))out.remove("sap-bw");
        if(out.stream().anyMatch(s->s.startsWith("sap-")))out.remove("sap");
        return out;
    }
    public Set<String> needs(String text) {return hits(needs,text);}
}
