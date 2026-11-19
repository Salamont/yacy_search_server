/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

import net.yacy.ai.ToolProvider;
import net.yacy.ai.ToolTestSettings;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Measures the chat's job and provider search on the invented stock of {@link SearchScaleFixture} (the size of a production
 * peer): the time of each step, found and shown, completeness, and what the direct index was asked. One scenario per run,
 * so that a run can start with cold caches; the checks against the fixture's expected counts fail the run.
 *
 * <p>Usage: {@code SearchScaleBench <fixture directory> <scenario> [budget ms]} from the repository root; scenarios:
 * {@code jobs:<collection or *>:<question>}, {@code providers:<collection or *>:<question>},
 * {@code tool:<collection or *>:<tool>:<arguments JSON>}, and {@code check} for the checks of completeness (every hit of one
 * collection among those of all collections, the unique postings of one employer, both wordings of a place).
 */
public final class SearchScaleBench {

    /** what the direct index was asked: questions, pages returned with their text, characters of that text */
    static final long[] INDEX = new long[4];

    static ToolContext.Index counting(final ToolContext.Index index) {
        return new ToolContext.Index() {
            @Override
            public ToolContext.IndexPage find(final List<String> words, final String collection, final Collection<String> urls, final int offset,
                    final int count) throws Exception {
                final ToolContext.IndexPage page = index.find(words, collection, urls, offset, count);
                INDEX[0]++;
                INDEX[1] += page.pages.size();
                for (final RagCandidate c : page.pages) INDEX[2] += c.text.length();
                return page;
            }

            @Override
            public Set<String> matching(final List<String> words, final boolean phrase, final String collection, final Collection<String> urls)
                    throws Exception {
                INDEX[0] += (urls.size() + SolrIndex.URL_BATCH - 1) / SolrIndex.URL_BATCH;
                return index.matching(words, phrase, collection, urls);
            }

            @Override
            public ToolContext.HostCount hosts(final List<List<String>> anyOf, final String collection) throws Exception {
                INDEX[0]++;
                return index.hosts(anyOf, collection);
            }

            @Override
            public List<RagCandidate> hostPages(final List<List<String>> anyOf, final String collection, final Collection<String> hosts,
                    final int perHost) throws Exception {
                final List<RagCandidate> pages = index.hostPages(anyOf, collection, hosts, perHost);
                INDEX[0] += (hosts.size() + SolrIndex.HOST_BATCH - 1) / SolrIndex.HOST_BATCH;
                INDEX[1] += pages.size();
                for (final RagCandidate c : pages) INDEX[2] += c.text.length();
                return pages;
            }
        };
    }

    private static KgReader reader;
    private static ToolContext.Index index;

    static ToolContext context(final String collection) {
        final ToolContext.Graph graph = new ToolContext.Graph() {
            @Override
            public KgReader reader() {
                return SearchScaleBench.reader;
            }

            @Override
            public Viewer viewer(final KgReader r, final String c) throws Exception {
                return r.viewer(c == null ? null : List.of(c));
            }
        };
        return new ToolContext(collection, true, (concepts, q, c, n) -> new ArrayList<>(), graph, 16000, "bench").withIndex(index);
    }

    private static void require(final boolean condition, final String what) {
        if (!condition) throw new AssertionError(what);
        System.out.println("CHECK ok: " + what);
    }

    public static void main(final String[] args) throws Exception {
        final File dir = new File(args[0]);
        final String scenario = args[1];
        final long budget = args.length > 2 ? Long.parseLong(args[2]) : JobSearch.MAX_MILLIS;
        ToolTestSettings.use((key, dflt) -> key.endsWith(".enabled") ? "true" : dflt);
        final JSONObject expected = new JSONObject(new String(Files.readAllBytes(new File(dir, "expected.json").toPath()), StandardCharsets.UTF_8));
        final KgStore store = SearchScaleFixture.openStore(dir);
        reader = new KgReader(store, SearchScaleFixture.config(), () -> SearchScaleFixture.NOW);
        final EmbeddedInstance instance = SearchScaleFixture.openSolr(dir);
        final EmbeddedSolrConnector solr = new EmbeddedSolrConnector(instance);
        index = counting(new SolrIndex(() -> solr));
        int exit = 0;
        try {
            if ("check".equals(scenario)) {
                check(expected);
            } else {
                final String[] parts = scenario.split(":", 4);
                final String collection = "*".equals(parts[1]) ? null : parts[1];
                final ToolContext context = context(collection);
                final JSONObject out = new JSONObject(true);
                out.put("version", "after").put("scenario", scenario).put("budgetMs", budget);
                final long t0 = System.nanoTime();
                if ("jobs".equals(parts[0])) {
                    final JobSearch.Result r = JobSearch.run(context, JobSearch.plan(parts[2]), System::currentTimeMillis, budget);
                    out.put("totalMs", (System.nanoTime() - t0) / 1_000_000L);
                    out.put("criteria", r.plan.criteria()).put("found", r.found).put("records", r.records).put("shown", r.jobs.size())
                            .put("lowerBound", r.lowerBound()).put("scanned", r.scanned).put("gaps", r.gaps)
                            .put("error", r.error == null ? JSONObject.NULL : r.error).put("phasesMs", new JSONObject(r.phases));
                } else if ("providers".equals(parts[0])) {
                    final ServiceSearch.Result r = ServiceSearch.run(context, ServiceSearch.plan(parts[2]), List.of(), System::currentTimeMillis, budget);
                    out.put("totalMs", (System.nanoTime() - t0) / 1_000_000L);
                    out.put("services", r.plan.services).put("place", r.plan.place == null ? JSONObject.NULL : r.plan.place).put("found", r.found)
                            .put("shown", r.providers.size()).put("lowerBound", r.lowerBound()).put("perPlace", new JSONObject(r.perPlace))
                            .put("servicePages", r.servicePages).put("gaps", r.gaps).put("phasesMs", new JSONObject(r.phases));
                } else {
                    final String raw = ToolProvider.executeTool(parts[2], parts[3], collection == null, context);
                    out.put("totalMs", (System.nanoTime() - t0) / 1_000_000L);
                    final JSONObject r = new JSONObject(raw);
                    r.remove("providers");
                    r.remove("postings");
                    r.remove("searched");
                    out.put("resultChars", raw.length()).put("result", r);
                }
                out.put("index", new JSONObject(true).put("questions", INDEX[0]).put("pagesLoadedWithText", INDEX[1]).put("textCharsLoaded", INDEX[2]));
                System.out.println("BENCH " + out);
            }
        } catch (final AssertionError e) {
            System.out.println("CHECK FAILED: " + e.getMessage());
            exit = 1;
        } finally {
            instance.close();
            store.close();
        }
        System.exit(exit);
    }

    /** Completeness on one stock: counts as the fixture placed them, and every hit of one collection among those of all. */
    private static void check(final JSONObject expected) throws Exception {
        final JSONObject subject = expected.getJSONObject("postingsOfSubject");
        final JobSearch.Plan plan = JobSearch.plan("Wie viele Stellenanzeigen zu " + SearchScaleFixture.SUBJECT + " gibt es?");
        require(plan.criteria().equals(List.of(SearchScaleFixture.SUBJECT)), "the count question has the subject only: " + plan.criteria());
        final JobSearch.Result all = JobSearch.run(context(null), plan);
        require(all.complete && all.found == subject.getInt("all"), "all collections: " + all.found + " postings of the subject, complete");
        int sum = 0;
        for (final String c : SearchScaleFixture.COLLECTIONS) {
            final JobSearch.Result one = JobSearch.run(context(c), plan);
            require(one.complete && one.found == subject.getJSONObject(c).getInt("found"), c + ": " + one.found + " postings of the subject, complete");
            require(all.ids.containsAll(one.ids), "every posting found in " + c + " is among those of all collections");
            sum += one.found;
        }
        require(sum == all.found, "the collections' postings together are those of all collections (" + sum + ")");
        final JobSearch.Result list = JobSearch.run(context(null), JobSearch.plan("Zeige alle Stellenanzeigen zu " + SearchScaleFixture.SUBJECT));
        require(list.ids.equals(all.ids), "\"Zeige alle\" and \"Wie viele\" find the same postings");
        // the employer with seven records, two of them one posting
        final JSONObject employer = expected.getJSONObject("employer");
        final JobSearch.Result kranich = JobSearch.run(context(SearchScaleFixture.STACK), JobSearch.plan("Zeige alle Stellenanzeigen von Kranich"));
        require(kranich.complete && kranich.records == employer.getInt("records") && kranich.found == employer.getInt("unique"),
                "one employer: " + kranich.records + " records, " + kranich.found + " unique postings");
        // the providers
        final JSONObject p = expected.getJSONObject("providers");
        final ServiceSearch.Plan service = ServiceSearch.plan("Wie viele Anbieter für Tragwerksplanung gibt es in " + SearchScaleFixture.PLACE + "?");
        final ServiceSearch.Result bau = ServiceSearch.run(context(SearchScaleFixture.BAU), service, List.of());
        require(!bau.lowerBound() && bau.found == p.getInt("found"), "providers of bau-web: " + bau.found + ", complete " + bau.gaps);
        require(bau.perPlace.get(ServiceSearch.AT_PLACE) == p.getInt("location") && bau.perPlace.get(ServiceSearch.SERVES_PLACE) == p.getInt("service_area")
                && bau.perPlace.get(ServiceSearch.MENTIONS_PLACE) == p.getInt("mentioned") && bau.perPlace.get(ServiceSearch.NO_PLACE) == p.getInt("none"),
                "located, serving, mentioning, unrelated: " + bau.perPlace);
        final ServiceSearch.Result every = ServiceSearch.run(context(null), service, List.of());
        require(!every.lowerBound() && every.found == p.getInt("foundAllCollections") && every.keys.containsAll(bau.keys),
                "all collections hold every provider of bau-web (" + every.found + ")");
        // both wordings of the place, fixed tool arguments
        final String with = ToolProvider.executeTool("provider_search", "{\"query\":\"Tragwerksplanung in " + SearchScaleFixture.PLACE + "\"}", false,
                context(SearchScaleFixture.BAU));
        final String bare = ToolProvider.executeTool("provider_search", "{\"query\":\"Tragwerksplanung " + SearchScaleFixture.PLACE + "\"}", false,
                context(SearchScaleFixture.BAU));
        final JSONObject a = new JSONObject(with);
        final JSONObject b = new JSONObject(bare);
        require(!a.has("error") && a.getInt("found") == bau.found && a.getJSONObject("per_place").toString().equals(b.getJSONObject("per_place").toString())
                && a.getJSONArray("providers").toString().equals(b.getJSONArray("providers").toString()) && !a.getBoolean("lower_bound"),
                "provider_search with and without \"in\": the same " + a.getInt("found") + " providers, " + a.getJSONObject("per_place"));
    }
}
