/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.derive;

import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.json.*;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.document.encoding.ASCII;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.*;
import net.yacy.scoutro.knowledge.publish.*;
import net.yacy.scoutro.knowledge.read.*;
import net.yacy.scoutro.knowledge.store.*;

/** Bounded local SQLite/extraction/derive/read probe, not a production throughput benchmark. */
public final class MatchingLoadProbe {
    private static final int PROVIDERS=12,CANDIDATES=240;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("new disposable directory required");
        Path root=Path.of(args[0]);if(Files.exists(root))throw new IllegalArgumentException("refusing existing data");
        Files.createDirectories(root);
        long start=System.nanoTime(),now=System.currentTimeMillis();
        KgConfig cfg=KgTestSupport.config(KgTestSupport.enabled());KgPaths paths=new KgPaths(root.toFile());
        try(KgStore store=KgStore.open(paths,cfg,new StorageGuard(cfg,paths,new KgTestSupport.Probe(),()->now),KgStore.SQLITE,()->now)) {
            Terms terms=new Terms();store.write(WriteClass.SYSTEM,0,c->{terms.seed(c);return null;});Publisher publisher=new Publisher(cfg,terms);
            String first=null;
            for(int i=0;i<PROVIDERS+CANDIDATES;i++) {
                boolean provider=i<PROVIDERS;String name=(provider?"Provider ":"Candidate ")+i;
                String url="https://load-"+i+".fixture.test/";DigestURL digest=new DigestURL(url);
                JSONObject org=KgJson.obj("@type","Organization","name",name,"url",url);
                if(provider)KgJson.put(org,"makesOffer",new JSONArray()
                        .put(KgJson.obj("@type","Offer","itemOffered",KgJson.obj("@type","Service","name","SAP Beratung")))
                        .put(KgJson.obj("@type","Offer","itemOffered",KgJson.obj("@type","Service","name","Revit Schulung"))));
                else KgJson.put(org,"description",i%2==0?"Wir nutzen SAP intern.":"Wir nutzen Revit intern.");
                Extraction ex=new Extraction(200);new JsonLdExtractor(200).extract(List.of(org.toString()),url,digest.getHost(),"de",ex);
                Publisher.Doc doc=new Publisher.Doc();doc.docId=ASCII.String(digest.hash());doc.url=url;doc.host=digest.getHost();doc.hostId=digest.hosthash();
                doc.language="de";doc.collections=List.of(provider?"providers":"candidates");doc.state=1;doc.loadedAt=1_700_000_000_000L;
                doc.solrVersion=i+1;doc.token=new byte[8];doc.inputHash=new byte[16];doc.contentHash=Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256").digest(org.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),16);
                store.write(WriteClass.GROWTH,0,c->publisher.apply(c,doc,-1,ex,now));
                if(i==0)first=store.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT public_id FROM kg_entity e JOIN kg_vocab v ON v.term_id=e.type WHERE v.name='organization'")){r.next();return r.getString(1);}});
            }
            double publish=(System.nanoTime()-start)/1e9;long deriveStart=System.nanoTime();int runs=0,checked=0;JSONObject run;
            do {run=new MatchingService(store,cfg,400).run(now);runs++;checked+=run.getInt("checked");if(runs>100)throw new AssertionError("unfinished bounded calculation");}
            while(!run.getBoolean("cycle_complete"));
            double derive=(System.nanoTime()-deriveStart)/1e9;
            KgReader reader=new KgReader(store,cfg,()->now);Suggestions suggestions=new Suggestions(reader);
            long readStart=System.nanoTime();int offset=0,seen=0,pages=0;Set<String> ids=new HashSet<>();
            do {
                JSONObject page=suggestions.page(first,offset,100,reader.viewer(List.of("providers")),reader.viewer(List.of("providers","candidates")));
                if(page.getInt("total")!=CANDIDATES)throw new AssertionError("group count lost candidates");
                JSONArray items=page.getJSONArray("items");for(int i=0;i<items.length();i++)if(!ids.add(items.getJSONObject(i).getJSONObject("other").getString("id")))throw new AssertionError("duplicate pagination");
                seen+=items.length();pages++;if(page.isNull("next_offset"))break;offset=page.getInt("next_offset");
            }while(pages<10);
            if(seen!=CANDIDATES)throw new AssertionError("pagination gap");
            double reads=(System.nanoTime()-readStart)/1e9;
            long archive=store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation"));
            long contributions=store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_match_contribution"));
            if(contributions!=PROVIDERS*CANDIDATES)throw new AssertionError("calculation gap: "+contributions);
            store.checkpoint();
            JSONObject report=KgJson.obj("providers",PROVIDERS,"candidates",CANDIDATES,"source_documents",PROVIDERS+CANDIDATES,"observations",archive,
                    "contributions",contributions,"work_budget_per_run",400,"runs",runs,"checked",checked,"publish_s",publish,"derive_s",derive,"read_3_pages_s",reads,
                    "database_bytes",Files.size(paths.db.toPath()),"heap_used_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),"processing_gaps",0,"pagination_duplicates",0);
            Files.writeString(root.resolve("load-report.json"),report.toString(2));System.out.println(report.toString());
        } finally {
            net.yacy.cora.protocol.Domains.close();net.yacy.cora.util.ConcurrentLog.shutdown();
        }
    }
}
