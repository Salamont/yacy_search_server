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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;

/**
 * Small global, content-versioned vocabularies. Collection categories never gate these signals.
 * <p>
 * 0.13.0 ({@code products.json} version 2): the products form a tree, vendor, product and module ({@code parents}: SAP, S/4HANA,
 * FI), and a text names every product it states, the finest too: "SAP S/4HANA FI" is S/4HANA and FI. A product never states its
 * modules ("SAP S/4HANA" proves no FI), and the vendor of {@code general} ("SAP") states nothing of its own beside a product of
 * it (general SAP proves no S/4HANA, as before). An alias inside the match of another product's alias is that product's
 * ("SAP BW" in "SAP BW/4HANA"). {@code service_types} name the kind of a service (consulting, implementation, migration,
 * integration, development, operation, training, licence), the second axis of an offer ("SAP FI – implementation").
 * <p>
 * 0.14.0 (version 3): {@code vendors} names the organisations behind a node of the tree ("SAP" for {@code sap}, "Amazon Web
 * Services" for {@code aws}); a vendor is never a client of its own products. The names of the products and of their vendors
 * are names, never generic words ({@link KgVocabularies.Snapshot#vocabularyWord}).
 */
public final class Signals {
    public final String version;
    private final Map<String,List<Pattern>> products,needs,serviceTypes;
    private final Map<String,String> parents;
    private final Set<String> general;
    /** 0.14.0: the written names of the products, the needs and the vendors (raw, as the files list them). */
    private final Map<String,List<String>> productNames,needNames,vendors;
    private Signals(Map<String,List<Pattern>> products,Map<String,List<Pattern>> needs,Map<String,List<Pattern>> serviceTypes,
            Map<String,String> parents,Set<String> general,Map<String,List<String>> productNames,Map<String,List<String>> needNames,
            Map<String,List<String>> vendors,String version) {
        this.products=products;this.needs=needs;this.serviceTypes=serviceTypes;this.parents=parents;this.general=general;
        this.productNames=productNames;this.needNames=needNames;this.vendors=vendors;this.version=version;
    }
    public static Signals load(File defaults,File overrides,List<String> problems) {
        Map<String,List<Pattern>> products=new LinkedHashMap<>(),needs=new LinkedHashMap<>(),types=new LinkedHashMap<>();
        Map<String,String> parents=new LinkedHashMap<>();Set<String> general=new LinkedHashSet<>();
        Map<String,List<String>> productNames=new LinkedHashMap<>(),needNames=new LinkedHashMap<>(),vendors=new LinkedHashMap<>();
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            JSONObject root=read(defaults,overrides,"products.json","products",products,productNames,hash,problems,3);
            if(root!=null&&!products.isEmpty()) {
                try {
                    tree(root,products,parents,general);
                    JSONObject st=root.optJSONObject("service_types");
                    if(st!=null)aliases(st,types,null);
                    JSONObject vs=root.optJSONObject("vendors");
                    if(vs!=null)for(String id:new TreeSet<>(vs.keySet())) {
                        if(!products.containsKey(id)) throw new IllegalArgumentException("invalid vendor product");
                        List<String> names=new ArrayList<>();JSONArray a=vs.getJSONArray(id);
                        for(int i=0;i<a.length();i++){String n=a.getString(i);if(n.isBlank()||n.length()>100) throw new IllegalArgumentException("invalid vendor");names.add(n);}
                        vendors.put(id,Collections.unmodifiableList(names));
                    }
                } catch(Exception e) {products.clear();parents.clear();general.clear();types.clear();productNames.clear();vendors.clear();
                    problems.add("products.json: "+e.getClass().getSimpleName());}
            }
            read(defaults,overrides,"needs.json","needs",needs,needNames,hash,problems,1);
            return new Signals(products,needs,types,parents,general,productNames,needNames,vendors,
                    java.util.HexFormat.of().formatHex(hash.digest()).substring(0,24));
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static JSONObject read(File defaults,File overrides,String filename,String field,Map<String,List<Pattern>> out,
            Map<String,List<String>> names,MessageDigest hash,List<String> problems,int maxVersion) {
        File f=overrides!=null && new File(overrides,filename).isFile() ? new File(overrides,filename) : new File(defaults,filename);
        try {
            if(f.length()>KgVocabularies.MAX_FILE_BYTES) throw new IllegalArgumentException("too large");
            byte[] bytes=Files.readAllBytes(f.toPath());hash.update(filename.getBytes(StandardCharsets.UTF_8));hash.update(bytes);
            JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            int v=root.optInt("version",0);if(v<1||v>maxVersion) throw new IllegalArgumentException("unsupported version");
            aliases(root.getJSONObject(field),out,names);
            return root;
        } catch(Exception e) {out.clear();names.clear();problems.add(filename+": "+e.getClass().getSimpleName());return null;}
    }
    private static void aliases(JSONObject entries,Map<String,List<Pattern>> out,Map<String,List<String>> names) throws JSONException {
        for(String id:new TreeSet<>(entries.keySet())) {
            if(!id.matches("[a-z][a-z0-9-]{1,63}")) throw new IllegalArgumentException("invalid code");
            JSONArray aliases=entries.getJSONArray(id);List<Pattern> patterns=new ArrayList<>();List<String> raw=new ArrayList<>();
            for(int i=0;i<aliases.length();i++) {
                String alias=aliases.getString(i);if(alias.isBlank()||alias.length()>100) throw new IllegalArgumentException("invalid alias");
                patterns.add(Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"+Pattern.quote(alias)+"(?![\\p{L}\\p{N}])"));raw.add(alias);
            }
            out.put(id,Collections.unmodifiableList(patterns));
            if(names!=null)names.put(id,Collections.unmodifiableList(raw));
        }
    }
    /** The tree of version 2: every parent a known product, no cycle; {@code general} only products of the tree. */
    private static void tree(JSONObject root,Map<String,List<Pattern>> products,Map<String,String> parents,Set<String> general) throws JSONException {
        JSONObject p=root.optJSONObject("parents");
        if(p!=null)for(String id:new TreeSet<>(p.keySet())) {
            String parent=p.getString(id);
            if(!products.containsKey(id)||!products.containsKey(parent)||id.equals(parent)) throw new IllegalArgumentException("invalid parent");
            parents.put(id,parent);
        }
        for(String id:parents.keySet()) {
            Set<String> seen=new HashSet<>();
            for(String x=id;x!=null;x=parents.get(x))if(!seen.add(x)) throw new IllegalArgumentException("cycle");
        }
        JSONArray g=root.optJSONArray("general");
        for(int i=0;g!=null&&i<g.length();i++) {
            if(!products.containsKey(g.getString(i))) throw new IllegalArgumentException("invalid general product");
            general.add(g.getString(i));
        }
    }
    private static Set<String> hits(Map<String,List<Pattern>> vocabulary,String text) {
        Set<String> result=new LinkedHashSet<>();
        for(Map.Entry<String,List<Pattern>> entry:vocabulary.entrySet()) for(Pattern p:entry.getValue())
            if(p.matcher(text).find()){result.add(entry.getKey());break;}
        return result;
    }
    /** The spans of a product's aliases in the text. */
    private List<int[]> spans(String id,String text) {
        List<int[]> out=new ArrayList<>();
        for(Pattern p:this.products.getOrDefault(id,List.of())){Matcher m=p.matcher(text);while(m.find())out.add(new int[]{m.start(),m.end()});}
        return out;
    }
    public Set<String> products(String text) {
        Set<String> out=hits(products,text);
        // The acronym alone is ambiguous. SAPV cannot match the SAP word boundary.
        if(out.contains("sap-sac")&&!Pattern.compile("(?iu)\\bSAP\\s+SAC\\b|Analytics\\s+Cloud").matcher(text).find()) out.remove("sap-sac");
        // an alias inside the match of another product's longer alias is that product's ("SAP BW" in "SAP BW/4HANA"); a product's
        // alias inside its module's ("S/4HANA" in "SAP S/4HANA Finance") is stated by it
        if(out.size()>1) {
            Map<String,List<int[]>> at=new HashMap<>();for(String id:out)at.put(id,spans(id,text));
            Set<String> covered=new HashSet<>();
            for(String id:out) {
                boolean all=!at.get(id).isEmpty();
                for(int[] s:at.get(id)) {
                    boolean inside=false;
                    for(Map.Entry<String,List<int[]>> o:at.entrySet())if(!o.getKey().equals(id)&&!ancestor(id,o.getKey()))
                        for(int[] t:o.getValue())if(t[0]<=s[0]&&s[1]<=t[1]&&t[1]-t[0]>s[1]-s[0])inside=true;
                    if(!inside){all=false;break;}
                }
                if(all)covered.add(id);
            }
            out.removeAll(covered);
        }
        // the general vendor states nothing beside a product of it: general SAP proves no S/4HANA
        for(Iterator<String> it=out.iterator();it.hasNext();) {String id=it.next();if(general.contains(id)&&descendantIn(id,out))it.remove();}
        return out;
    }
    /** True if {@code a} is an ancestor of {@code b} in the product tree. */
    public boolean ancestor(String a,String b) {
        for(String x=parents.get(b);x!=null;x=parents.get(x))if(x.equals(a))return true;
        return false;
    }
    private boolean descendantIn(String id,Set<String> set) {
        for(String x:set)if(!x.equals(id)&&ancestor(id,x))return true;
        return false;
    }
    /** 0.14.0: every written name of a product, a need and a vendor of the vocabularies (for {@link KgVocabularies.Snapshot#vocabularyWord}). */
    public List<String> names() {
        List<String> out=new ArrayList<>();
        for(List<String> l:productNames.values())out.addAll(l);
        for(List<String> l:vendors.values())out.addAll(l);
        return out;
    }
    /** 0.14.0: the written phrases of the needs (words of the vocabulary, no names). */
    public List<String> needPhrases() {
        List<String> out=new ArrayList<>();
        for(List<String> l:needNames.values())out.addAll(l);
        return out;
    }
    /** 0.14.0: the names of the vendors of a product: its own node's and its ancestors' ({@code vendors} of version 3). */
    public List<String> vendors(String product) {
        List<String> out=new ArrayList<>();
        for(String x=product;x!=null;x=parents.get(x))out.addAll(vendors.getOrDefault(x,List.of()));
        return out;
    }
    /** The parent of a product in the tree (its product or its vendor), or null. */
    public String parent(String product) {return parents.get(product);}
    public Set<String> needs(String text) {return hits(needs,text);}
    /** 0.13.0: the kinds of service a text names (consulting, implementation, migration, …), in the order of the vocabulary. */
    public Set<String> serviceTypes(String text) {return text==null?Set.of():hits(serviceTypes,text);}
}
