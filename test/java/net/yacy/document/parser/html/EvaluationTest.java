package net.yacy.document.parser.html;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The evaluation models (Scoutro R5): a file's rule version, the first locator per subject (around a named group
 * {@code at}), a file with an unknown element that loads its other rules instead of failing, and a later file of the
 * same name replacing the earlier one.
 */
public class EvaluationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void unload() {
        Evaluation.remove("evaltest");
        Evaluation.remove("evaltest2");
    }

    private File model(final String name, final String text) throws Exception {
        final File f = new File(this.tmp.getRoot(), "parser." + name + ".properties");
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void aModelNamesItsVersionAndWhereItMatched() throws Exception {
        Evaluation.add(model("evaltest", "version=7\nthing_scriptcode=(?s).*(?<at>THING-MARKER).*\nother_bodyclass=.*plain.*\n"));
        assertEquals("7", Evaluation.modelVersion("evaltest"));
        final Evaluation e = new Evaluation();
        final String code = "x".repeat(1000) + " THING-MARKER(); " + "y".repeat(1000);
        e.match(Evaluation.Element.scriptcode, code);
        e.match(Evaluation.Element.scriptcode, "THING-MARKER again");
        final String[] at = e.getLocator("evaltest", "thing");
        assertEquals("scriptcode", at[0]);
        assertTrue("the excerpt is around the marker: " + at[1], at[1].contains("THING-MARKER"));
        assertTrue(at[1].length() <= Evaluation.MAX_LOCATOR);
        assertTrue("the first match is kept", at[1].startsWith("x"));
        assertEquals(2, e.getScores("evaltest").get("thing"));
        assertNull(e.getLocator("evaltest", "other"));
    }

    @Test
    public void anUnknownElementSkipsItsRuleOnlyAndALaterFileReplacesTheEarlier() throws Exception {
        Evaluation.add(model("evaltest2", "a_nosuchelement=.*\nb_comment=(?s).*(?<at>B-MARK).*\n"));
        Evaluation e = new Evaluation();
        e.match(Evaluation.Element.comment, "has B-MARK");
        assertEquals(1, e.getScores("evaltest2").get("b"));
        assertNull("no version named", Evaluation.modelVersion("evaltest2"));
        // the same model name again (DATA/SETTINGS after defaults): only the later rules apply
        final File settings = this.tmp.newFolder("settings");
        final File again = new File(settings, "parser.evaltest2.properties");
        Files.write(again.toPath(), "version=2\nc_comment=(?s).*(?<at>C-MARK).*\n".getBytes(StandardCharsets.UTF_8));
        Evaluation.add(again);
        assertEquals("2", Evaluation.modelVersion("evaltest2"));
        e = new Evaluation();
        e.match(Evaluation.Element.comment, "has B-MARK and C-MARK");
        assertEquals("the earlier rules are gone", 0, e.getScores("evaltest2").get("b"));
        assertEquals(1, e.getScores("evaltest2").get("c"));
    }
}
