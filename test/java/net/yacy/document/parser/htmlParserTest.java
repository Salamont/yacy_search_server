package net.yacy.document.parser;

import static net.yacy.document.parser.htmlParser.parseToScraper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.Test;

import net.yacy.cora.document.id.AnchorURL;
import net.yacy.document.Document;
import net.yacy.document.Parser;
import net.yacy.document.VocabularyScraper;
import net.yacy.document.parser.html.ContentScraper;
import net.yacy.document.parser.html.ImageEntry;
import net.yacy.document.parser.html.TagValency;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

public class htmlParserTest {

        @Test
	public void testGetRealCharsetEncoding() {
		String[][] testStrings = new String[][] {
	       new String[]{null,null},
	       new String[]{"windows1250","windows-1250"},
	       new String[]{"windows_1250","windows-1250"},
	       new String[]{"ISO-8859-1", StandardCharsets.ISO_8859_1.name()},
	       new String[]{"ISO8859-1", StandardCharsets.ISO_8859_1.name()},
	       new String[]{"ISO-88591", StandardCharsets.ISO_8859_1.name()},
	       new String[]{"ISO88591", StandardCharsets.ISO_8859_1.name()},
	       new String[]{"iso_8859_1", StandardCharsets.ISO_8859_1.name()},
	       new String[]{"cp-1252","windows-1252"},
	       new String[]{"gb_2312","gb2312"},           // was: x-EUC-CN
	       new String[]{"gb_2312-80","gb2312"},           // was: x-EUC-CN
	       new String[]{"UTF-8;", StandardCharsets.UTF_8.name()}
		};
		
		for (int i=0; i < testStrings.length; i++) {
			// desired conversion result
			String shouldBe = testStrings[i][1];
			shouldBe = shouldBe!=null ? shouldBe.toLowerCase(Locale.ROOT) : null;
			
			// conversion result
			String charset = htmlParser.patchCharsetEncoding(testStrings[i][0]);
			
			// test if equal
			assertEquals(shouldBe, charset!=null ? charset.toLowerCase(Locale.ROOT) : null);
			System.out.println("testGetRealCharsetEncoding: " + (testStrings[i][0]!=null?testStrings[i][0]:"null") + " -> " + (charset!=null?charset:"null") + " | Supported: " + (charset!=null?Charset.isSupported(charset):false));
			
		}
		
	}

    /**
     * Test of parse method, of class htmlParser.
     * - test getCharset
     * @throws IOException 
     */
    @Test
    public void testParse() throws Parser.Failure, InterruptedException, IOException {
        System.out.println("htmlParser.parse");

        String[] testFiles = {
            "umlaute_html_iso.html",
            "umlaute_html_utf8.html",
            "umlaute_html_namedentities.html"};

        final String mimetype = "text/html";
        //final String resulttxt = "In München steht ein Hofbräuhaus. Dort gibt es Bier aus Maßkrügen.";

        for (String testfile : testFiles) {
            final String filename = "test/parsertest/" + testfile;
            final File file = new File(filename);

            final AnchorURL url = new AnchorURL("http://localhost/" + filename);
            System.out.println("parse file: " + filename);

            htmlParser p = new htmlParser();
            FileInputStream inStream = null;
            try {
            	inStream = new FileInputStream(file);
            	
                final Document[] docs = p.parse(url, mimetype, null, new VocabularyScraper(), 0, inStream);

                Document doc = docs[0];
                String txt = doc.getCharset();
                assertTrue("get Charset", txt != null);
                System.out.println("detected charset = " + txt);
            } finally {
            	if(inStream != null) {
            		inStream.close();
            	}
            }
        }
    }
    
	/**
	 * Test the htmlParser.parse() method, with no charset information, neither
	 * provided by HTTP header nor by meta tags or attributes.
	 * 
	 * @throws Exception
	 *             when an unexpected error occurred
	 */
	@Test
	public void testParseHtmlWithoutCharset() throws Exception {
		final AnchorURL url = new AnchorURL("http://localhost/test.html");
		final String mimetype = "text/html";
		final StringBuilder testHtml = new StringBuilder("<!DOCTYPE html><html><body><p>");
		/*
		 * Include some non ASCII characters : once encoded they should make the charset
		 * detector to detect the exact encoding
		 */
		testHtml.append("In München steht ein Hofbräuhaus.\n" + "Dort gibt es Bier aus Maßkrügen.<br>");
		testHtml.append("<a href=\"http://localhost/doc1.html\">First link</a>");
		testHtml.append("<a href=\"http://localhost/doc2.html\">Second link</a>");
		testHtml.append("<a href=\"http://localhost/doc3.html\">Third link</a>");
		testHtml.append("</p></body></html>");

		final htmlParser parser = new htmlParser();

		final Charset[] charsets = new Charset[] { StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1 };

		for (final Charset charset : charsets) {
			try (InputStream sourceStream = new ByteArrayInputStream(testHtml.toString().getBytes(charset));) {
				final Document[] docs = parser.parse(url, mimetype, null, new VocabularyScraper(), 0, sourceStream);
				final Document doc = docs[0];
				assertEquals(3, doc.getAnchors().size());
				assertTrue(doc.getTextString().contains("Maßkrügen"));
				assertEquals(charset.toString(), doc.getCharset());
			}
		}
	}
	
	/**
	 * Test the htmlParser.parse() method, when filtering out div elements on their CSS class.
	 * 
	 * @throws Exception
	 *             when an unexpected error occurred
	 */
	@Test
	public void testParseHtmlDivClassFilter() throws Exception {
		final AnchorURL url = new AnchorURL("http://localhost/test.html");
		final String mimetype = "text/html";
		final StringBuilder testHtml = new StringBuilder("<!DOCTYPE html><head><title>Test document</title></head>");

		testHtml.append("<div class=\"top\">Top text");
		testHtml.append("<a href=\"http://localhost/top.html\">Top link</a>");
		testHtml.append("</div>");

		testHtml.append("<div class=\"optional\">Some optional content");
		testHtml.append("<a href=\"http://localhost/content.html\">Link from optional block</a>");
		testHtml.append("</div>");

		testHtml.append("<p class=\"optional\">A paragraph</p>");

		testHtml.append("<div class=\"optional-text\">Text-only optional block</div>");
		
		testHtml.append("<div class=\"optional desc\">");
		testHtml.append("<div class=\"optional child\">");
		testHtml.append("<div class=\"child\">");
		testHtml.append("<p>Child text at depth 3</p>");
		testHtml.append("</div></div></div>");

		testHtml.append("<div class=\"bottom optional media\" itemscope itemtype=\"https://schema.org/LocalBusiness\"><img itemprop=\"logo\" src=\"http://localhost/image.png\" alt=\"Our Company\"></div>");
		
		final htmlParser parser = new htmlParser();

		/* No CSS class filter */
		try (InputStream sourceStream = new ByteArrayInputStream(
				testHtml.toString().getBytes(StandardCharsets.UTF_8));) {
			final Document[] docs = parser.parse(url, mimetype, null, new VocabularyScraper(), 0, sourceStream);
			final Document doc = docs[0];
			final String parsedDext = doc.getTextString();
			
			/* Check everything has been parsed */
			assertEquals(2, doc.getAnchors().size());
			assertEquals(1, doc.getImages().size());
			assertEquals(1, doc.getLinkedDataTypes().size());
			assertTrue(parsedDext.contains("Top"));
			assertTrue(parsedDext.contains("Some"));
			assertTrue(parsedDext.contains("from"));
			assertTrue(parsedDext.contains("paragraph"));
			assertTrue(parsedDext.contains("Text-only"));
			assertTrue(parsedDext.contains("depth"));
		}
		
		/* Filter on CSS classes with no matching elements */
		try (InputStream sourceStream = new ByteArrayInputStream(
				testHtml.toString().getBytes(StandardCharsets.UTF_8));) {
			final Set<String> ignore = new HashSet<>();
			ignore.add("opt");
			ignore.add("head");
			ignore.add("container");
			final Document[] docs = parser.parse(url, mimetype, null, new VocabularyScraper(), 0, sourceStream);
			final Document doc = docs[0];
			final String parsedDext = doc.getTextString();
			
			/* Check everything has been parsed */
			assertEquals(2, doc.getAnchors().size());
			assertEquals(1, doc.getImages().size());
			assertEquals(1, doc.getLinkedDataTypes().size());
			assertTrue(parsedDext.contains("Top"));
			assertTrue(parsedDext.contains("Some"));
			assertTrue(parsedDext.contains("from"));
			assertTrue(parsedDext.contains("paragraph"));
			assertTrue(parsedDext.contains("Text-only"));
			assertTrue(parsedDext.contains("depth"));
		}
		
		/* Filter on CSS class with matching elements */
		try (InputStream sourceStream = new ByteArrayInputStream(
				testHtml.toString().getBytes(StandardCharsets.UTF_8));) {
			final Set<String> ignore = new HashSet<>();
			ignore.add("optional");
			final Document[] docs = parser.parse(url, mimetype, null, TagValency.EVAL, ignore, new VocabularyScraper(), 0, sourceStream);
			final Document doc = docs[0];
			final String parsedDext = doc.getTextString();
			
			/* Check matching blocks have been ignored */
			assertEquals(1, doc.getAnchors().size());
			assertEquals("http://localhost/top.html", doc.getAnchors().iterator().next().toString());
			assertEquals(0, doc.getLinkedDataTypes().size());
			assertEquals(0, doc.getImages().size());
			assertFalse(parsedDext.contains("Some"));
			assertFalse(parsedDext.contains("from"));
			assertFalse(parsedDext.contains("depth"));
			
			/* Check non-matching blocks have been normally parsed */
			assertTrue(parsedDext.contains("Top"));
			assertTrue(parsedDext.contains("Text-only"));
			assertTrue(parsedDext.contains("paragraph"));
		}
	}
    
    /**
     * Test the htmlParser.parseWithLimits() method with test content within bounds.
     * @throws Exception when an unexpected error occurred
     */
    @Test
    public void testParseWithLimitsUnreached() throws Exception {
        System.out.println("htmlParser.parse");

        String[] testFiles = {
            "umlaute_html_iso.html",
            "umlaute_html_utf8.html",
            "umlaute_html_namedentities.html"};

        final String mimetype = "text/html";
        //final String resulttxt = "In München steht ein Hofbräuhaus. Dort gibt es Bier aus Maßkrügen.";

        htmlParser parser = new htmlParser();
        for (final String testfile : testFiles) {
            final String fileName = "test" + File.separator + "parsertest" + File.separator + testfile;
            final File file = new File(fileName);

            final AnchorURL url = new AnchorURL("http://localhost/" + fileName);

            try (final FileInputStream inStream = new FileInputStream(file);) {
            	
                final Document[] docs = parser.parseWithLimits(url, mimetype, null, new VocabularyScraper(), 0, inStream, 1000, 10000, null);
                final Document doc = docs[0];
                assertNotNull("Parser result must not be null for file " + fileName, docs);
                final String parsedText = doc.getTextString();
				assertNotNull("Parsed text must not be empty for file " + fileName, parsedText);
				assertTrue("Parsed text must contain test word with umlaut char in file " + fileName,
						parsedText.contains("Maßkrügen"));
				assertEquals("Test anchor must have been parsed for file " + fileName, 1, doc.getAnchors().size());
				assertFalse("Parsed document should not be marked as partially parsed for file " + fileName, doc.isPartiallyParsed());
                
            }
        }
    }
    
	/**
	 * Test the htmlParser.parseWithLimits() method, with various maxLinks values
	 * ranging from zero to the exact anchors number contained in the test content.
	 * 
	 * @throws Exception
	 *             when an unexpected error occurred
	 */
	@Test
	public void testParseWithLimitsOnAnchors() throws Exception {
		final AnchorURL url = new AnchorURL("http://localhost/test.html");
		final String mimetype = "text/html";
		final String charset = StandardCharsets.UTF_8.name();
		final StringBuilder testHtml = new StringBuilder("<!DOCTYPE html><html><body><p>");
		testHtml.append("<a href=\"http://localhost/doc1.html\">First link</a>");
		testHtml.append("<a href=\"http://localhost/doc2.html\">Second link</a>");
		testHtml.append("<a href=\"http://localhost/doc3.html\">Third link</a>");
		testHtml.append("</p></body></html>");

		final htmlParser parser = new htmlParser();

		for (int maxLinks = 0; maxLinks <= 3; maxLinks++) {
			try (InputStream sourceStream = new ByteArrayInputStream(
					testHtml.toString().getBytes(StandardCharsets.UTF_8));) {
				final Document[] docs = parser.parseWithLimits(url, mimetype, charset, new VocabularyScraper(), 0,
						sourceStream, maxLinks, Long.MAX_VALUE, null);
				final Document doc = docs[0];
				assertEquals(maxLinks, doc.getAnchors().size());
				assertEquals("The parsed document should be marked as partially parsed only when the limit is exceeded",
						maxLinks < 3, doc.isPartiallyParsed());
			}
		}
	}
    
	/**
	 * Test the htmlParser.parseWithLimits() method, with various maxLinks values
	 * ranging from zero the exact RSS feed links number contained in the test
	 * content.
	 * 
	 * @throws Exception
	 *             when an unexpected error occurred
	 */
	@Test
	public void testParseWithLimitsOnRSSFeeds() throws Exception {
		final AnchorURL url = new AnchorURL("http://localhost/test.html");
		final String mimetype = "text/html";
		final String charset = StandardCharsets.UTF_8.name();
		final StringBuilder testHtml = new StringBuilder("<!DOCTYPE html><html>");
		testHtml.append("<head>");
		testHtml.append(
				"<link rel=\"alternate\" type=\"application/rss+xml\" title=\"Feed1\" href=\"http://localhost/rss1.xml\" />");
		testHtml.append(
				"<link rel=\"alternate\" type=\"application/rss+xml\" title=\"Feed2\" href=\"http://localhost/rss2.xml\" />");
		testHtml.append(
				"<link rel=\"alternate\" type=\"application/rss+xml\" title=\"Feed3\" href=\"http://localhost/rss3.xml\" />");
		testHtml.append("</head>");
		testHtml.append("<body><p>HTML test content</p></body></html>");

		final htmlParser parser = new htmlParser();

		for (int maxLinks = 0; maxLinks <= 3; maxLinks++) {
			try (InputStream sourceStream = new ByteArrayInputStream(
					testHtml.toString().getBytes(StandardCharsets.UTF_8));) {
				final Document[] docs = parser.parseWithLimits(url, mimetype, charset, new VocabularyScraper(), 0,
						sourceStream, maxLinks, Long.MAX_VALUE, null);
				final Document doc = docs[0];
				assertEquals(maxLinks, doc.getRSS().size());
				assertEquals("The parsed document should be marked as partially parsed only when the limit is exceeded",
						maxLinks < 3, doc.isPartiallyParsed());
			}
		}
    }
    
    /**
     * Test of parseToScraper method, of class htmlParser.
     */
    @Test
    public void testParseToScraper_4args() throws Exception {
        // test link with inline html in text
        // expectation to deliver pure text as it is possibly indexed in outboundlinks_anchortext_txt/inboundlinks_anchortext_txt
        final AnchorURL url = new AnchorURL("http://localhost/");
        final String charset = StandardCharsets.UTF_8.name();
        final String testhtml = "<html><body>"
                + "<a href='x1.html'><span>testtext</span></a>" // "testtext"
                + "<a href=\"http://localhost/x2.html\">   <i id=\"home-icon\" class=\"img-sprite\"></i>Start</a>" // "Start"
                + "<a href='x1.html'><span class='button'><img src='pic.gif'/></span></a>" // ""  + image
                + "<figure><img width=\"550px\" title=\"image as exemple\" alt=\"image as exemple\" src=\"./img/my_image.png\"></figrue>" // + img width 550 (+html5 figure)
                + "</body></html>";

        ContentScraper scraper = parseToScraper(url, charset, TagValency.IGNORE, new HashSet<String>(), new VocabularyScraper(), 0, testhtml, 10, 10);
        List<AnchorURL> anchorlist = scraper.getAnchors();

        String linktxt = anchorlist.get(0).getTextProperty();
        assertEquals("testtext", linktxt);

        linktxt = anchorlist.get(1).getTextProperty();
        assertEquals("Start", linktxt);

        linktxt = anchorlist.get(2).getTextProperty();
        assertEquals("", linktxt);

        int cnt = scraper.getImages().size();
        assertEquals(2,cnt);
        ImageEntry img = scraper.getImages().get(1);
        assertEquals(550,img.width());
    }
    
    /**
     * Test parser resistance against nested anchors pattern 
     * (<a> tag embedding other <a> tags : invalid HTML, but occasionally encountered in some real-world Internet resources. 
     * See case reported at http://forum.yacy-websuche.de/viewtopic.php?f=23&t=6005). 
     * The parser must be able to terminate in a finite time.
     * @throws IOException when an unexpected error occurred
     */
    @Test
    public void testParseToScraperNestedAnchors() throws IOException {
        final AnchorURL url = new AnchorURL("http://localhost/");
        final String charset = StandardCharsets.UTF_8.name();
        final StringBuilder testHtml = new StringBuilder("<!DOCTYPE html><html><body><p>");
        /* With prior recursive processing implementation and an average 2017 desktop computer, 
         * computing time started to be problematic over a nesting depth of 21 */
        final int nestingDepth = 30;
        for (int count = 0; count < nestingDepth; count++) {
        	testHtml.append("<a href=\"http://localhost/doc" + count + ".html\">");
        }
        testHtml.append("<img src=\"./img/my_image.png\">");
        for (int count = 0; count < nestingDepth; count++) {
        	testHtml.append("</a>");
        }
        testHtml.append("</p></body></html>");
        
        ContentScraper scraper = parseToScraper(url, charset, TagValency.IGNORE, new HashSet<String>(), new VocabularyScraper(), 0, testHtml.toString(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(nestingDepth, scraper.getAnchors().size());
        assertEquals(1, scraper.getImages().size());

    }

    /**
     * Test of parseToScraper method, of class htmlParser
     * for scraping tag content from text (special test to verify <style> not counted as text
     */
    @Test
    public void testParseToScraper_TagTest() throws Exception {
        final AnchorURL url = new AnchorURL("http://localhost/");
        final String charset = StandardCharsets.UTF_8.name();
        final String textSource = "test text";
        final String testhtml = "<html>"
                + "<head><style type=\"text/css\"> h1 { color: #ffffff; }</style></head>"
                + "<body>"
                + "<p>" + textSource + "</p>"
                + "</body></html>";

        ContentScraper scraper = parseToScraper(url, charset, TagValency.IGNORE, new HashSet<String>(), new VocabularyScraper(), 0, testhtml, 10, 10);

        String txt = scraper.getText();
        System.out.println("ScraperTagTest: [" + textSource + "] = [" + txt + "]");
        assertEquals(txt, textSource + ".");
    }

    /**
     * Test for parseToScraper of class htmlParser for scraping html with a
     * <script> tag which contains code with similar to other opening tag
     * like "<a " see https://github.com/yacy/yacy_search_server/issues/109
     */
    @Test
    public void testParseToScraper_ScriptTag() throws MalformedURLException, IOException {
        final AnchorURL url = new AnchorURL("http://localhost/");
        final String charset = StandardCharsets.UTF_8.name();
        final String textSource = "test text";
        // extract from test case provided by https://github.com/yacy/yacy_search_server/issues/109
        String testhtml = "<!doctype html>"
                + "<html class=\"a-no-js\" data-19ax5a9jf=\"dingo\">"
                + "<head><script>var aPageStart = (new Date()).getTime();</script><meta charset=\"utf-8\"><!--  emit CSM JS -->\n"
                + "<script>\n"
                + "function D(){if(E){var a=f.innerWidth?{w:f.innerWidth,h:f.innerHeight}:{w:k.clientWidth,h:k.clientHeight};5<Math.abs(a.w-\n"
                //  the  50<a  is a possible error case
                + "P.w)||50<a.h-P.h?(P=a,Q=4,(a=l.mobile||l.tablet?450<a.w&&a.w>a.h:1250==a.w)?C(k,\"a-ws\"):ca(k,\"a-ws\")):Q--&&(ea=setTimeout(D,16))}}function na(a){(E=void 0===a?!E:!!a)&&D()}"
                + "</script>\n"
                + "</head>\n"
                + "<body>" + textSource + "</body>\n"
                + "</html>";
        ContentScraper scraper = parseToScraper(url, charset, TagValency.IGNORE, new HashSet<String>(), new VocabularyScraper(), 0, testhtml, 10, 10);

        String txt = scraper.getText();
        System.out.println("ScraperScriptTagTest: [" + textSource + "] = [" + txt + "]");
        assertEquals(txt, textSource);
    }

    /**
     * Test for parseToScraper of class htmlParser with quoted attribute content
     * containing ">" characters (for example Alpine.js x-data arrow functions).
     * Attribute values must never leak as plain text.
     */
    @Test
    public void testParseToScraper_QuotedAttributeWithGt() throws MalformedURLException, IOException {
        final AnchorURL url = new AnchorURL("http://localhost/");
        final String charset = StandardCharsets.UTF_8.name();
        final String textSource = "Visible text";
        final String testhtml = "<html><body>"
                + "<div class=\"relative flex items-start justify-between fluid-container-full\""
                + " x-data=\"{"
                + "mainMenuOpen: false,"
                + "isDesktop: false,"
                + "mobileMainNavHeight: 384,"
                + "init() {"
                + "this.checkIsDesktop();"
                + "},"
                + "checkIsDesktop() {"
                + "this.isDesktop = window.matchMedia('screen and (min-width:1024px)').matches;"
                + "},"
                + "closeMainMenu() {"
                + "this.mainMenuOpen = false;"
                + "},"
                + "openMainMenu() {"
                + "this.mainMenuOpen = true;"
                + "},"
                + "onSubMenuIntersect(el) {"
                + "this.$nextTick(() => {"
                + "this.mobileMainNavHeight = el.getBoundingClientRect().height;"
                + "});"
                + "}"
                + "}\">"
                + "<p>" + textSource + "</p>"
                + "</div>"
                + "</body></html>";

        final ContentScraper scraper = parseToScraper(url, charset, TagValency.IGNORE, new HashSet<String>(),
                new VocabularyScraper(), 0, testhtml, 10, 10);

        final String txt = scraper.getText();
        assertEquals(textSource + ".", txt);
        assertFalse(txt.contains("getBoundingClientRect"));
        assertFalse(txt.contains("mobileMainNavHeight"));
    }

    /**
     * Test for parseToScraper of class htmlParser with quoted attribute content
     * containing a comparison using ">" and additional Alpine-style attributes.
     * Attribute names/values must never leak as plain text.
     */
    @Test
    public void testParseToScraper_QuotedAttributeComparisonGt() throws MalformedURLException, IOException {
        final AnchorURL url = new AnchorURL("http://localhost/");
        final String charset = StandardCharsets.UTF_8.name();
        final String textSource = "Next image";
        final String testhtml = "<html><body>"
                + "<button "
                + "type=\"button\" "
                + "class=\"splide__arrow splide__arrow--prev absolute left-1 top-1/2 z-10 -translate-y-1/2 lg:left-2.5 [@media(pointer:coarse)]:hidden\" "
                + "x-show=\"imgModalItems.length > 1\" "
                + "@click=\"lightboxPrev()\""
                + ">"
                + textSource
                + "</button>"
                + "</body></html>";

        final ContentScraper scraper = parseToScraper(url, charset, TagValency.IGNORE, new HashSet<String>(),
                new VocabularyScraper(), 0, testhtml, 10, 10);

        final String txt = scraper.getText();
        assertTrue(txt.contains(textSource));
        assertFalse(txt.contains("@click"));
        assertFalse(txt.contains("lightboxPrev"));
        assertFalse(txt.contains("imgModalItems.length"));
    }

    /**
     * Parse like the crawler does: htmlParser.parse, whose document text is
     * indexed as text_t.
     */
    private static Document parseLikeCrawler(final String html) throws Exception {
        try (final InputStream sourceStream = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8))) {
            return new htmlParser().parse(new AnchorURL("http://example.org/page.html"), "text/html",
                    StandardCharsets.UTF_8.name(), TagValency.EVAL, new HashSet<String>(), new VocabularyScraper(), 0,
                    sourceStream)[0];
        }
    }

    private static String page(final String head, final String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Example page</title>" + head
                + "</head><body>" + body + "<p>Last paragraph of the page.</p></body></html>";
    }

    /**
     * A quoted attribute value without its closing quote runs to the next quote
     * (as in browsers), but the text after that must not be lost.
     */
    @Test
    public void testParseAttributeWithMissingClosingQuote() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the link.</p>"
                + "<a href=\"/contact>Contact us</a><p>Swallowed by the attribute value.</p>"
                + "<ul><li><a href=\"/next\">Next page</a></li></ul>"
                + "<p>Paragraph after the missing quote.</p>")).getTextString();
        assertTrue(text, text.contains("Paragraph before the link"));
        assertTrue(text, text.contains("Next page"));
        assertTrue(text, text.contains("Paragraph after the missing quote"));
        assertTrue(text, text.contains("Last paragraph of the page"));
    }

    /** An apostrophe in an unquoted attribute value is no quote. */
    @Test
    public void testParseUnquotedAttributeWithApostrophe() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the image.</p>"
                + "<img alt=Peter's src=photo.jpg>"
                + "<p>Paragraph after the apostrophe.</p>")).getTextString();
        assertTrue(text, text.contains("Paragraph after the apostrophe"));
        assertTrue(text, text.contains("Last paragraph of the page"));
    }

    /** A backslash does not escape a quote in HTML: 'Peter\'s' ends after the backslash. */
    @Test
    public void testParseBackslashEscapedApostropheInAttribute() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the box.</p>"
                + "<div data-title='Peter\\'s choice'>Text inside the box.</div>"
                + "<p>Paragraph after the escaped apostrophe.</p>")).getTextString();
        assertTrue(text, text.contains("Text inside the box"));
        assertTrue(text, text.contains("Paragraph after the escaped apostrophe"));
        assertTrue(text, text.contains("Last paragraph of the page"));
    }

    /** A '<' that starts no tag is text, also when an apostrophe follows later. */
    @Test
    public void testParseLessThanInTextFollowedByApostrophe() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the prices.</p>"
                + "<p>Prices from < 100 EUR, it's cheap.</p>"
                + "<p>Paragraph after the less-than sign.</p>"
                + "<p>As 3<5 holds, we are done.</p>")).getTextString();
        assertTrue(text, text.contains("Paragraph after the less-than sign"));
        assertTrue(text, text.contains("Prices from < 100 EUR, it's cheap"));
        assertTrue(text, text.contains("As 3<5 holds, we are done"));
        assertTrue(text, text.contains("Last paragraph of the page"));
    }

    /**
     * Script code is raw text up to its end tag: neither '<' nor an unbalanced or
     * escaped quote nor a "// don't" comment in it may swallow the text after it.
     */
    @Test
    public void testParseInlineScriptWithLessThanAndQuotes() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the scripts.</p>"
                + "<script>if (a < b) { msg = 'don\\'t'; }</script>"
                + "<p>Paragraph after the first script.</p>"
                + "<script>if (x<0) { // don't go below zero\n x = 0; }</script>"
                + "<p>Paragraph after the second script.</p>"
                + "<script type=\"text/javascript\">var s = \"it's <b>\" + '</p>'; if (n<2) { t = '\\''; }</SCRIPT >"
                + "<p>Paragraph after the third script.</p>")).getTextString();
        assertTrue(text, text.contains("Paragraph after the first script"));
        assertTrue(text, text.contains("Paragraph after the second script"));
        assertTrue(text, text.contains("Paragraph after the third script"));
        assertTrue(text, text.contains("Last paragraph of the page"));
        assertFalse("script code is no text: " + text, text.contains("msg"));
        assertFalse("script code is no text: " + text, text.contains("below zero"));
        assertFalse("script code is no text: " + text, text.contains("var s"));
    }

    /** Script code inside a link is no link text, even when it holds a bare '<'. */
    @Test
    public void testParseScriptCodeIsNoAnchorText() throws Exception {
        final ContentScraper scraper = parseToScraper(new AnchorURL("http://example.org/"), StandardCharsets.UTF_8.name(),
                TagValency.EVAL, new HashSet<String>(), new VocabularyScraper(), 0,
                "<html><body><a href=\"/next\"><script>if (a<b) show();</script>Link text</a><p>After the link.</p></body></html>",
                10, 10);
        assertEquals(1, scraper.getAnchors().size());
        assertEquals("Link text", scraper.getAnchors().get(0).getTextProperty());
        assertTrue(scraper.getText(), scraper.getText().contains("After the link"));
    }

    /**
     * A '<' inside a JSON-LD string must neither swallow the page text nor cut the
     * captured JSON-LD block.
     */
    @Test
    public void testParseJsonLdWithLessThanInString() throws Exception {
        final String ld = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Example Org\","
                + "\"description\":\"<b>Fair</b> prices < 100 EUR for \\\"members\\\" and more\"}";
        JsonLdCapture.activate(8, 64 * 1024, true, Set.of());
        try {
            final Document doc = parseLikeCrawler(page("<script type=\"application/ld+json\">" + ld + "</script>",
                    "<h1>Welcome</h1><p>Paragraph after the JSON-LD block.</p>"));
            final String text = doc.getTextString();
            assertTrue(text, text.contains("Welcome"));
            assertTrue(text, text.contains("Paragraph after the JSON-LD block"));
            assertTrue(text, text.contains("Last paragraph of the page"));
            assertFalse("JSON-LD is no text: " + text, text.contains("members"));
            final JsonLdCapture.Blocks blocks = ((ContentScraper) doc.getScraperObject()).getLdJsonBlocks();
            assertNotNull(blocks);
            assertEquals("the JSON-LD block is captured intact", List.of(ld), blocks.list());
        } finally {
            JsonLdCapture.clear();
        }
    }

    /** An encoded '<' in text is decoded after the tags are stripped, so it cannot cut the text. */
    @Test
    public void testParseLtEntityInText() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the entity.</p>"
                + "<p>Price &lt; 100 EUR, members pay less.</p>"
                + "<p>Since 5 &gt; 3 &amp; 2 &lt; 3, all is well.</p>"
                + "<p>Paragraph after the entity.</p>")).getTextString();
        assertTrue(text, text.contains("Price < 100 EUR, members pay less"));
        assertTrue(text, text.contains("Since 5 > 3 & 2 < 3, all is well"));
        assertTrue(text, text.contains("Paragraph after the entity"));
        assertTrue(text, text.contains("Last paragraph of the page"));
    }

    /** A few stray control characters in a text page do not make it binary. */
    @Test
    public void testParseStrayControlCharacter() throws Exception {
        final String text = parseLikeCrawler(page("", "<p>Paragraph before the control character.</p>"
                + "<p>A stray \u0003 control character and a \u0000 null.</p>"
                + "<p>Paragraph after the control character.</p>")).getTextString();
        assertTrue(text, text.contains("A stray control character and a null"));
        assertTrue(text, text.contains("Paragraph after the control character"));
        assertTrue(text, text.contains("Last paragraph of the page"));
    }

    /** Binary data offered as text/html is still rejected, as is text full of control characters. */
    @Test
    public void testParseBinaryIsStillRejected() throws Exception {
        final String[] binaries = { "image_green_sd.png", "YaCyLogo_120ppi.jpg", "umlaute_html_utf8.html.gz",
                "umlaute_windows.pdf" };
        for (final String binary : binaries) {
            final File file = new File("test" + File.separator + "parsertest" + File.separator + binary);
            for (final Charset charset : new Charset[] { StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1 }) {
                try (final InputStream sourceStream = new FileInputStream(file)) {
                    new htmlParser().parse(new AnchorURL("http://example.org/" + binary), "text/html", charset.name(),
                            TagValency.EVAL, new HashSet<String>(), new VocabularyScraper(), 0, sourceStream);
                    fail(binary + " (" + charset + ") must be rejected as binary");
                } catch (final Parser.Failure e) {
                    assertTrue(e.getMessage(), e.getMessage().startsWith("Binary data"));
                }
            }
        }
        final StringBuilder controls = new StringBuilder();
        for (int i = 0; i < 9; i++) controls.append("<p>Paragraph ").append(i).append(" \u0001</p>");
        final String[] texts = {
                page("", controls.toString()), // more than a few control characters
                "<p>short \u0002 text</p>" // one control character in fewer than 100 characters
        };
        for (final String html : texts) {
            try {
                parseLikeCrawler(html);
                fail("must be rejected as binary: " + html);
            } catch (final Parser.Failure e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Binary data"));
            }
        }
    }
}
