package net.yacy.scoutro.knowledge.quality;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;

/**
 * A model for the quality check that never leaves 127.0.0.1: an OpenAI-compatible endpoint whose answers the test
 * decides per reference page, called through the real client ({@link net.yacy.scoutro.knowledge.extract.YacyLlmClient}).
 * Example data only.
 */
final class QualityFixture implements AutoCloseable {

    /** The page a request is about (its title line), and the known entities of its DATA block by name. */
    static final class Ask {
        final String caseId;
        final Map<String, String> known = new HashMap<>();
        final JSONObject body;

        Ask(final JSONObject body) {
            this.body = body;
            final JSONArray messages = body.optJSONArray("messages");
            final String user = messages == null ? "" : messages.optJSONObject(messages.length() - 1).optString("content");
            final Matcher t = Pattern.compile("(?m)^title: (.*)$").matcher(user);
            String id = null;
            if (t.find()) {
                for (final QualityCases.Case c : QualityCases.CASES) {
                    if (c.title.equals(t.group(1).trim())) {
                        id = c.id;
                    }
                }
            }
            this.caseId = id;
            final Matcher k = Pattern.compile("(?m)^(k\\d+): (\\w+) \"(.*)\"$").matcher(user);
            while (k.find()) {
                this.known.put(k.group(3), k.group(1));
            }
        }

        /** The id of a known entity with this name, or the new id given. */
        String id(final String name, final String otherwise) {
            return this.known.getOrDefault(name, otherwise);
        }
    }

    final HttpServer server;
    final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    /** The answer's content per request; null answers HTTP 500. */
    volatile Function<Ask, String> answer = QualityFixture::ideal;
    /** The finish reason per request ({@code length}: the answer stopped at the output cap). */
    volatile Function<Ask, String> finish = a -> "stop";
    /** Rejects a request with a response_format (HTTP 400), as an endpoint without structured output does. */
    volatile boolean rejectSchema;
    /** Reports no token usage. */
    volatile boolean noUsage;
    /** Holds every request until released (a cancel test). */
    volatile CountDownLatch hold;
    volatile CountDownLatch arrived = new CountDownLatch(1);
    final File dir;
    final Map<String, String> settings = new HashMap<>();
    volatile KnowledgePrompt prompt = KnowledgePrompt.defaults();
    volatile ModelQualityCheck.Candidate candidate;
    volatile boolean graphRunning = true;

    QualityFixture() throws IOException {
        this.dir = Files.createTempDirectory("scoutro-quality").toFile();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            JSONObject body;
            try {
                body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (final Exception e) {
                body = new JSONObject();
            }
            this.requests.add(body);
            this.arrived.countDown();
            final CountDownLatch h = this.hold;
            if (h != null) {
                try {
                    h.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = 200;
            byte[] out;
            if (this.rejectSchema && body.has("response_format")) {
                status = 400;
                out = "{\"error\":{\"message\":\"response_format is not supported\",\"code\":\"unsupported\"}}".getBytes(StandardCharsets.UTF_8);
            } else {
                final Ask ask = new Ask(body);
                final String content = this.answer.apply(ask);
                if (content == null) {
                    status = 500;
                    out = new byte[0];
                } else {
                    final String usage = this.noUsage ? "" : ",\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":200}";
                    out = ("{\"model\":\"fixture/served-model\",\"provider\":\"FixtureProvider\",\"choices\":[{\"message\":{\"content\":"
                            + JSONObject.quote(content) + "},\"finish_reason\":" + JSONObject.quote(this.finish.apply(ask)) + "}]" + usage + "}")
                            .getBytes(StandardCharsets.UTF_8);
                }
            }
            try {
                exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (final IOException e) {
                // the client gave up
            }
            exchange.close();
        });
        this.server.start();
        this.candidate = candidate(2048, 16384, "supported");
    }

    String stub() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    ModelQualityCheck.Candidate candidate(final int maxTokens, final int numCtx, final String format) {
        return new ModelQualityCheck.Candidate("OPENAI", stub(), "fixture-model", maxTokens, numCtx, format, "unsupported", false,
                "sk-fixture-secret-0123456789");
    }

    ModelQualityCheck check() {
        return new ModelQualityCheck(new ModelQualityCheck.Environment() {
            @Override
            public List<ModelQualityCheck.Candidate> candidates() {
                return List.of(QualityFixture.this.candidate);
            }

            @Override
            public ModelQualityCheck.Inputs inputs() {
                return QualityFixture.this.graphRunning
                        ? new ModelQualityCheck.Inputs(QualityFixture.this.settings::get, QualityFixture.this.prompt) : null;
            }

            @Override
            public File dir() {
                return QualityFixture.this.dir;
            }
        });
    }

    String key() {
        return this.candidate.key();
    }

    @Override
    public void close() {
        final CountDownLatch h = this.hold;
        if (h != null) {
            h.countDown();
        }
        this.server.stop(0);
    }

    // ------------------------------------------------------------ answers

    static JSONObject entity(final String id, final String type, final String name, final String quote) {
        return KgJson.obj("id", id, "type", type, "name", name, "quote", quote);
    }

    static JSONObject claim(final String s, final String p, final String o, final String quote) {
        return KgJson.obj("subject", s, "predicate", p, "object", o, "quote", quote);
    }

    static JSONObject value(final String s, final String p, final String quote) {
        return KgJson.obj("subject", s, "predicate", p, "quote", quote);
    }

    static String answer(final List<JSONObject> entities, final List<JSONObject> claims, final List<JSONObject> values) {
        return KgJson.obj("entities", new JSONArray(entities), "claims", new JSONArray(claims), "values", new JSONArray(values)).toString();
    }

    static final String EMPTY = "{\"entities\":[],\"claims\":[],\"values\":[]}";

    /** What a correct reading of each reference page answers (set by hand from the page). */
    static String ideal(final Ask a) {
        final List<JSONObject> e = new ArrayList<>(), c = new ArrayList<>(), v = new ArrayList<>();
        if (a.caseId == null) {
            return EMPTY;
        }
        switch (a.caseId) {
            case "own_job_ad": {
                final String job = a.id("Pflegefachkraft (m/w/d)", "e2");
                final String q = "Die Muster Pflege gGmbH sucht ab sofort eine Pflegefachkraft (m/w/d)";
                e.add(entity("e1", "organization", "Muster Pflege gGmbH", q));
                if (job.startsWith("e")) {
                    e.add(entity(job, "job", "Pflegefachkraft (m/w/d)", q));
                }
                c.add(claim(job, "hiring_organization", "e1", q));
                break;
            }
            case "intermediary":
                e.add(entity("e1", "job", "Industriemechaniker (m/w/d)", "suchen wir einen Industriemechaniker (m/w/d) in Vollzeit"));
                e.add(entity("e2", "organization", "Beispiel Personal GmbH", "Beispiel Personal GmbH – Ihre Personalvermittlung"));
                break;
            case "job_portal": {
                final String job = a.id("Bauleiter (m/w/d)", "e2");
                final String q = "Die Beispiel Bau GmbH sucht einen Bauleiter (m/w/d)";
                e.add(entity("e1", "organization", "Beispiel Bau GmbH", q));
                if (job.startsWith("e")) {
                    e.add(entity(job, "job", "Bauleiter (m/w/d)", q));
                }
                c.add(claim(job, "hiring_organization", "e1", q));
                break;
            }
            case "team_vs_service": {
                final String q = "Kontakt zur Muster Haustechnik GmbH. Unsere Leistungen: Heizungswartung und Badsanierung";
                e.add(entity("e1", "organization", "Muster Haustechnik GmbH", q));
                e.add(entity("e2", "service", "Heizungswartung", "Unsere Leistungen: Heizungswartung und Badsanierung"));
                e.add(entity("e3", "service", "Badsanierung", "Unsere Leistungen: Heizungswartung und Badsanierung"));
                c.add(claim("e1", "offers", "e2", q));
                c.add(claim("e1", "offers", "e3", q));
                break;
            }
            case "page_tool_vs_product": {
                final String q = "Die Muster Software GmbH verkauft MusterText Pro";
                e.add(entity("e1", "organization", "Muster Software GmbH", q));
                e.add(entity("e2", "service", "MusterText Pro", q));
                c.add(claim("e1", "offers", "e2", q));
                break;
            }
            case "navigation_next_to_content": {
                final String org = a.id("Muster Pflege gGmbH", "e1");
                final String q = "Die Muster Pflege gGmbH bietet in Musterstadt Tagespflege für Senioren an";
                if (org.startsWith("e")) {
                    e.add(entity(org, "organization", "Muster Pflege gGmbH", q));
                }
                e.add(entity("e2", "service", "Tagespflege", q));
                c.add(claim(org, "offers", "e2", q));
                break;
            }
            case "price_with_service":
                e.add(entity("e1", "service", "Unterhaltsreinigung", "Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde zzgl. MwSt."));
                e.add(entity("e2", "service", "Glasreinigung", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt."));
                v.add(value("e1", "price", "Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde zzgl. MwSt."));
                v.add(value("e2", "price", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt."));
                break;
            case "software_competence_vs_internal_use": {
                final String q = "Leistungen der Muster IT-Beratung GmbH. Wir bieten Beispiel-ERP-Beratung für mittelständische Unternehmen an";
                e.add(entity("e1", "organization", "Muster IT-Beratung GmbH", q));
                e.add(entity("e2", "service", "Beispiel-ERP-Beratung", "Wir bieten Beispiel-ERP-Beratung für mittelständische Unternehmen an"));
                c.add(claim("e1", "offers", "e2", q));
                break;
            }
            case "contradictory_sources":
                e.add(entity("e1", "service", "Tagespflege", "kostet die Tagespflege 89 € pro Tag"));
                v.add(value("e1", "price", "kostet die Tagespflege 89 € pro Tag"));
                break;
            case "missing_stays_unknown": {
                final String job = a.id("Lagerhelfer (m/w/d)", "e2");
                final String q = "Die Muster Logistik GmbH sucht einen Lagerhelfer (m/w/d)";
                e.add(entity("e1", "organization", "Muster Logistik GmbH", q));
                if (job.startsWith("e")) {
                    e.add(entity(job, "job", "Lagerhelfer (m/w/d)", q));
                }
                c.add(claim(job, "hiring_organization", "e1", q));
                break;
            }
            default:
                return EMPTY;
        }
        return answer(e, c, v);
    }

    /** The settings of a graph with the LLM tier's defaults (the reference pages bring their own collection). */
    static KgConfig settings() {
        return ModelQualityCheck.settings(k -> null);
    }
}
