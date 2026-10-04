/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.crawler.retrieval;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpResponse;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.net.InetAddresses;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.cora.protocol.ClientIdentification;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.http.HTTPClient;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.crawler.CrawlStacker;
import net.yacy.crawler.CrawlSwitchboard;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.crawler.robots.RobotsTxt;
import net.yacy.data.WorkTables;
import net.yacy.peers.graphics.WebStructureGraph;
import net.yacy.repository.LoaderDispatcher;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import sun.misc.Unsafe;

/** Actual HTTPLoader redirect execution; only HTTP transport and DNS are fakes. */
public class RobotsRedirectSecurityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String START = "https://crawl-fixture.com/robots.txt";
    private static final String NEXT = "https://next-fixture.com/policy.txt";
    private static final byte[] BODY = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
    private final List<String> fetched = new ArrayList<>();
    private final List<String> resolved = new ArrayList<>();
    private final List<InetAddress[]> pinned = new ArrayList<>();
    private final Map<String, Reply> replies = new HashMap<>();
    private final Map<String, InetAddress[]> addresses = new HashMap<>();
    private Switchboard board;
    private Map<String, String> config;
    private HTTPLoader loader;
    private Runnable afterFetch;

    /** Skip application constructors: no peers, scheduler, DATA or database. */
    private static <T> T fake(final Class<T> type) throws Exception {
        final Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void field(final Object object, final String name, final Object value) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(object, value);
                return;
            } catch (final NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static InetAddress ip(final String value) {
        // Guava parses IP literals without invoking any DNS resolver.
        return InetAddresses.forString(value);
    }

    @Before
    public void setup() throws Exception {
        this.board = fake(Switchboard.class);
        this.config = new ConcurrentHashMap<>();
        this.config.put(SwitchboardConstants.NETWORK_DOMAIN, "global");
        this.config.put(SwitchboardConstants.CRAWLER_RECORD_REDIRECTS, "false");
        field(this.board, "configProps", this.config);
        this.board.webStructure = fake(FakeWebStructure.class);
        final CrawlStacker stacker = fake(CrawlStacker.class);
        field(stacker, "acceptLocalURLs", false);
        field(stacker, "acceptGlobalURLs", true);
        this.board.crawlStacker = stacker;
        this.addresses.put("crawl-fixture.com", new InetAddress[] {ip("8.8.8.8")});
        this.addresses.put("next-fixture.com", new InetAddress[] {ip("8.8.4.4")});
        // YaCy hashes/locality checks see synthetic cache entries, not real DNS.
        Domains.setHostName(ip("8.8.8.8"), "crawl-fixture.com");
        Domains.setHostName(ip("8.8.4.4"), "next-fixture.com");
        Domains.setHostName(ip("127.0.0.1"), "localhost");
        this.loader = new HTTPLoader(this.board, new ConcurrentLog("robots-security-test"),
                agent -> new FakeClient(), host -> {
                    this.resolved.add(host);
                    final InetAddress[] mapped = this.addresses.get(host);
                    if (mapped != null) return mapped;
                    final String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
                    if ("localhost".equals(literal)) return new InetAddress[] {ip("127.0.0.1")};
                    if (InetAddresses.isInetAddress(literal)) return new InetAddress[] {ip(literal)};
                    throw new UnknownHostException("unmapped fake DNS target");
                });
    }

    @AfterClass
    public static void closeHttpInfrastructure() throws Exception {
        HTTPClient.closeConnectionManager();
    }

    private Response load() throws IOException {
        return this.loader.loadRobots(new Request(new DigestURL(START), null),
                200000, ClientIdentification.yacyInternetCrawlerAgent);
    }

    private void redirect(final String source, final String target) {
        this.replies.put(source, new Reply(302, target));
    }

    private void ok(final String url) {
        this.replies.put(url, new Reply(200, null));
    }

    private void blocked(final String target) throws Exception {
        redirect(START, target);
        final IOException error = assertThrows(IOException.class, this::load);
        assertTrue(error.getMessage(), error.getMessage().contains("robots target"));
        assertEquals(List.of(START), this.fetched);
    }

    @Test
    public void normalRobotsAllowed() throws Exception {
        ok(START);
        assertArrayEquals(BODY, load().getContent());
        assertEquals(List.of(START), this.fetched);
    }

    @Test
    public void publicRedirectAllowed() throws Exception {
        redirect(START, NEXT);
        ok(NEXT);
        assertArrayEquals(BODY, load().getContent());
        assertEquals(List.of(START, NEXT), this.fetched);
        assertEquals(List.of("crawl-fixture.com", "next-fixture.com"), this.resolved);
        assertArrayEquals(new InetAddress[]{ip("8.8.8.8")},this.pinned.get(0));
        assertArrayEquals(new InetAddress[]{ip("8.8.4.4")},this.pinned.get(1));
    }

    @Test
    public void publicHttpRedirectCodesAllowed() throws Exception {
        final String target = "http://next-fixture.com/policy.txt";
        for (final int status : new int[] {301, 302, 303, 307, 308}) {
            this.fetched.clear();
            this.replies.put(START, new Reply(status, target));
            ok(target);
            assertArrayEquals(BODY, load().getContent());
            assertEquals(List.of(START, target), this.fetched);
        }
    }

    @Test public void loopbackBlocked() throws Exception { blocked("http://127.0.0.1/internal"); }
    @Test public void privateIpv4Blocked() throws Exception { blocked("http://10.0.0.10/internal"); }
    @Test public void linkLocalBlocked() throws Exception { blocked("http://169.254.169.254/internal"); }
    @Test public void ipv6LoopbackBlocked() throws Exception { blocked("http://[::1]/internal"); }
    @Test public void localhostBlocked() throws Exception { blocked("http://localhost/internal"); }

    @Test
    public void knownTldWithPrivateDnsBlocked() throws Exception {
        this.addresses.put("next-fixture.com", new InetAddress[] {ip("10.0.0.10")});
        blocked(NEXT);
    }

    @Test
    public void mixedDnsAnswersBlocked() throws Exception {
        this.addresses.put("next-fixture.com", new InetAddress[] {ip("8.8.4.4"), ip("10.0.0.10")});
        blocked(NEXT);
    }

    @Test
    public void sameHostRedirectResolvesAgain() throws Exception {
        redirect(START, "/policy");
        this.afterFetch = () -> this.addresses.put("crawl-fixture.com", new InetAddress[] {ip("10.0.0.10")});
        assertThrows(IOException.class, this::load);
        assertEquals(List.of(START), this.fetched);
        assertEquals(List.of("crawl-fixture.com", "crawl-fixture.com"), this.resolved);
    }

    @Test
    public void relativeRedirectNormalized() throws Exception {
        redirect(START, "../policy.txt");
        ok("https://crawl-fixture.com/policy.txt");
        assertArrayEquals(BODY, load().getContent());
        assertEquals(List.of(START, "https://crawl-fixture.com/policy.txt"), this.fetched);
    }

    @Test
    public void publicIpv6RedirectAllowed() throws Exception {
        this.addresses.put("next-fixture.com", new InetAddress[] {ip("2001:4860:4860::8888")});
        redirect(START, NEXT);
        ok(NEXT);
        assertArrayEquals(BODY, load().getContent());
        assertEquals(List.of(START, NEXT), this.fetched);
    }

    @Test
    public void unsuitableAddressRangesBlocked() throws Exception {
        for (final String address : List.of("0.0.0.0", "224.0.0.1", "100.64.0.1",
                "198.18.0.1", "192.0.2.1", "::", "fd00::1", "2001:db8::1")) {
            this.fetched.clear();
            this.addresses.put("next-fixture.com", new InetAddress[] {ip(address)});
            blocked(NEXT);
        }
    }

    @Test
    public void latePrivateHopBlocked() throws Exception {
        redirect(START, NEXT);
        redirect(NEXT, "http://127.0.0.1/internal");
        assertThrows(IOException.class, this::load);
        assertEquals(List.of(START, NEXT), this.fetched);
    }

    @Test
    public void loopBounded() throws Exception {
        redirect(START, START);
        assertEquals("robots redirect limit exceeded", assertThrows(IOException.class, this::load).getMessage());
        assertEquals(HTTPLoader.DEFAULT_CRAWLING_RETRY_COUNT + 1, this.fetched.size());
    }

    @Test
    public void longChainBounded() throws Exception {
        String source = START;
        for (int n = 0; n <= HTTPLoader.DEFAULT_CRAWLING_RETRY_COUNT; n++) {
            final String target = "https://next-fixture.com/" + n;
            redirect(source, target);
            source = target;
        }
        assertEquals("robots redirect limit exceeded", assertThrows(IOException.class, this::load).getMessage());
        assertEquals(HTTPLoader.DEFAULT_CRAWLING_RETRY_COUNT + 1, this.fetched.size());
    }

    @Test
    public void protocolsAndCredentialsBlocked() throws Exception {
        for (final String target : List.of("ftp://next-fixture.com/robots.txt",
                "file:///private-file", "https://user:secret@next-fixture.com/policy")) {
            this.fetched.clear();
            blocked(target);
        }
    }

    @Test
    public void initialPrivateTargetNeverFetched() throws Exception {
        final Request request = new Request(new DigestURL("http://10.0.0.10/robots.txt"), null);
        assertThrows(IOException.class, () -> this.loader.loadRobots(request, 200000,
                ClientIdentification.yacyInternetCrawlerAgent));
        assertTrue(this.fetched.isEmpty());
    }

    @Test
    public void unresolvedDnsFailsClosed() throws Exception {
        this.addresses.put("next-fixture.com", new InetAddress[0]);
        blocked(NEXT);
    }

    @Test
    public void dnsLookupFailureNeverFetched() throws Exception {
        redirect(START, "https://unmapped-fixture.com/policy");
        assertThrows(UnknownHostException.class, this::load);
        assertEquals(List.of(START), this.fetched);
    }

    @Test
    public void localAndAnyModesKeepLocalRedirects() throws Exception {
        for (final String mode : List.of("local", "any")) {
            this.config.put(SwitchboardConstants.NETWORK_DOMAIN, mode);
            this.fetched.clear();
            this.resolved.clear();
            final String target = "http://127.0.0.1/robots.txt";
            redirect(START, target);
            ok(target);
            assertArrayEquals(BODY, load().getContent());
            assertEquals(List.of(START, target), this.fetched);
            assertTrue(this.resolved.isEmpty());
        }
    }

    @Test
    public void ordinaryProfilelessLoadRetainsItsBehavior() throws Exception {
        redirect(START, NEXT);
        ok(NEXT);
        assertArrayEquals(BODY, this.loader.load(new Request(new DigestURL(START), null), null,
                200000, null, ClientIdentification.yacyInternetCrawlerAgent).getContent());
        assertEquals(List.of(START, NEXT), this.fetched);
        assertTrue(this.resolved.isEmpty());
    }

    @Test
    public void publicCrawlRedirectStillGoesThroughStacker() throws Exception {
        redirect(START, NEXT);
        field(this.board.crawlStacker, "crawler", fake(CrawlSwitchboard.class));
        final CrawlProfile profile = new CrawlProfile(Map.of(CrawlProfile.CrawlAttribute.NAME.key, "public-fixture"));
        final IOException result = assertThrows(IOException.class, () -> this.loader.load(
                new Request(new DigestURL(START), null), profile, 200000, null,
                ClientIdentification.yacyInternetCrawlerAgent));
        // A missing profile is rejected by the actual stacker: the loader did
        // not bypass crawl acceptance to fetch the redirect immediately.
        assertTrue(result.getMessage(), result.getMessage().contains("LOST STACKER PROFILE"));
        assertEquals(List.of(START), this.fetched);
        assertTrue(this.resolved.isEmpty());
    }

    private LoaderDispatcher dispatcher() throws Exception {
        final LoaderDispatcher dispatcher = new LoaderDispatcher(this.board);
        final Field field = LoaderDispatcher.class.getDeclaredField("httpLoader");
        field.setAccessible(true);
        field.set(dispatcher, this.loader);
        return dispatcher;
    }

    @Test
    public void dispatcherPropagatesRobotsContext() throws Exception {
        final LoaderDispatcher dispatcher = dispatcher();
        redirect(START, "http://127.0.0.1/internal");
        assertThrows(IOException.class, () -> dispatcher.loadRobots(new Request(new DigestURL(START), null),
                ClientIdentification.yacyInternetCrawlerAgent));
        assertEquals(List.of(START), this.fetched);
    }

    @Test
    public void robotsGetEntryUsesProtectedDispatcher() throws Exception {
        redirect(START, "http://127.0.0.1/internal");
        final WorkTables tables = new WorkTables(this.temporary.newFolder());
        final RobotsTxt robots = new RobotsTxt(tables, dispatcher(), 1);
        try {
            // Existing unavailable-robots fallback is unchanged; no private fetch.
            assertNull(robots.getEntry(new DigestURL(START), ClientIdentification.yacyInternetCrawlerAgent));
            assertEquals(List.of(START), this.fetched);
        } finally {
            robots.close();
            tables.close();
        }
    }

    @Test
    public void robotsEnsureExistUsesProtectedDispatcher() throws Exception {
        redirect(START, "http://127.0.0.1/internal");
        final WorkTables tables = new WorkTables(this.temporary.newFolder());
        final RobotsTxt robots = new RobotsTxt(tables, dispatcher(), 1);
        try {
            robots.ensureExist(new DigestURL(START), ClientIdentification.yacyInternetCrawlerAgent, false);
            assertEquals(List.of(START), this.fetched);
        } finally {
            robots.close();
            tables.close();
        }
    }
    private record Reply(int status, String location) { }

    private final class FakeClient extends HTTPClient {
        private HttpResponse response;
        FakeClient() { super(ClientIdentification.yacyInternetCrawlerAgent); }
        @Override public void pinRobotsTarget(final MultiProtocolURL url, final InetAddress[] validated) {
            pinned.add(validated.clone());
        }
        @Override
        public byte[] GETbytes(final MultiProtocolURL url, final String user, final String password,
                final int maxBytes, final boolean concurrent) {
            final String key = url.toNormalform(false);
            fetched.add(key);
            if (afterFetch != null) afterFetch.run();
            final Reply reply = replies.get(key);
            assertNotNull("Unexpected HTTP target " + key, reply);
            this.response = new BasicHttpResponse(HttpVersion.HTTP_1_1, reply.status(), "fake");
            if (reply.location() != null) this.response.addHeader("Location", reply.location());
            return reply.status() == 200 ? BODY : new byte[0];
        }
        @Override public HttpResponse getHttpResponse() { return this.response; }
        @Override public void close() { }
    }

    private static final class FakeWebStructure extends WebStructureGraph {
        private FakeWebStructure() { super(null); }
        @Override public void generateCitationReference(final DigestURL from, final DigestURL to) { }
    }

}
