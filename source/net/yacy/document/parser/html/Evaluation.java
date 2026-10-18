/**
 *  Evaluation
 *  Copyright 2011 by Michael Peter Christen
 *  First released 28.04.2011 at https://yacy.net
 *
 *  $LastChangedDate: 2011-03-08 02:51:51 +0100 (Di, 08 Mrz 2011) $
 *  $LastChangedRevision: 7567 $
 *  $LastChangedBy: low012 $
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.document.parser.html;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.yacy.cora.sorting.ClusteredScoreMap;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.util.MemoryControl;


/**
 * This class provides methods to use a pattern analysis for html files
 * The pattern analysis is generic and can be configured using a field-name/pattern property
 * configuration file.
 * Such a configuration file has names of the structure
 * &lt;subject-name&gt;_&lt;document-element&gt;
 * and values are regular java expressions
 * A html file is scanned for pattern matchings within a specific &lt;document-element&gt;
 * and if such a matching can be found then the &lt;attribute-name&gt; is collected as
 * subject for the scanned document
 * patternProperties files must have special file names where the file name
 * starts with the word "parser." and must end with ".properties"
 * everything between this is a name for a solr multi-value field where
 * the collected subject names are stored to
 * <p>
 * A file may name its rule version with the key {@code version} (no element); every
 * match remembers the first content it matched per subject (the element and the
 * matched value), so a result can say where a subject was found. A later file of
 * the same name replaces an earlier one (DATA/SETTINGS overrides defaults).
 */
public class Evaluation {

    private static final List<Model> models = new java.util.concurrent.CopyOnWriteArrayList<Model>(); // the list of all models that shall be applied

    /** The longest matched content a locator keeps (characters). */
    public static final int MAX_LOCATOR = 240;

    public static enum Element {
        text,
        title,
        bodyclass,
        divid,
        csspath,
        metagenerator,
        url,
        scriptpath,
        scriptcode,
        framepath,
        iframepath,
        imgpath,
        apath,
        comment;
    }

    private static class Attribute {
        public String subject; // the name of the attribute
        public Pattern pattern; // the pattern that must match for that attribute
        public Attribute(final String subject, final Pattern pattern) {
            this.subject = subject;
            this.pattern = pattern;
        }

        @Override
        public String toString() {
            return this.subject + ":" + this.pattern.toString();
        }
    }

    private static class Model {

        private final String modelName;
        private final String version; // the rule version a file names with the key "version", or null
        private final Map<Element, List<Attribute>> elementMatcher; // a mapping from element-names to lists of Attributes

        public Model(final File patternProperties) throws IOException {
            if (!patternProperties.exists()) throw new IOException("File does not exist: " + patternProperties);
            final String name = patternProperties.getName();
            if (!name.startsWith("parser.")) throw new IOException("file name must start with 'parser.': " + name);
            if (!name.endsWith(".properties")) throw new IOException("file name must end with '.properties': " + name);
            this.modelName = name.substring(7, name.length() - 11);
            if (this.modelName.length() < 1) throw new IOException("file name too short: " + name);

            // load the file
            final Properties p = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(patternProperties), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            final String v0 = p.getProperty("version");
            this.version = v0 == null || v0.trim().isEmpty() ? null : v0.trim();

            // iterate through the properties and generate method patterns
            this.elementMatcher = new HashMap<Element, List<Attribute>>();
            String subject, elementName;
            Element element;
            Pattern pattern;
            for (final Map.Entry<Object, Object> entry: p.entrySet()) {
                final String k = (String) entry.getKey();
                final String v = (String) entry.getValue();
                if ("version".equals(k)) continue;
                final int w = k.indexOf('_');
                if (w < 0) {
                    ConcurrentLog.severe("PatternAnalysis", "wrong configuration in " + name + ": separator '_' missing: " + k);
                    continue;
                }
                subject = k.substring(0, w);
                elementName = k.substring(w + 1);
                try {
                    pattern = Pattern.compile(v);
                } catch (final PatternSyntaxException e) {
                    ConcurrentLog.severe("PatternAnalysis", "bad pattern in " + name + ": '" + k + "=" + v + "' - " + e.getDescription());
                    continue;
                }
                try {
                    element = Element.valueOf(elementName);
                } catch (final IllegalArgumentException e) {
                    ConcurrentLog.severe("PatternAnalysis", "unknown element in " + name + ": " + elementName);
                    continue;
                }
                List<Attribute> attributeList = this.elementMatcher.get(element);
                if (attributeList == null) {
                    attributeList = new ArrayList<Attribute>();
                    this.elementMatcher.put(element, attributeList);
                }
                attributeList.add(new Attribute(subject, pattern));
            }
        }

        public String getName() {
            return this.modelName;
        }

        public String getVersion() {
            return this.version;
        }

        /**
         * match elementContents for a specific elementName
         * @param element - the name of the element as Element enum type
         * @param content - the content of the element
         * @return a list of subject names that match with the element
         */
        public ClusteredScoreMap<String> match(final Element element, final CharSequence content) {
            return match(element, content, null);
        }

        /**
         * As {@link #match(Element, CharSequence)}; with {@code starts}, also where in the content each subject's pattern
         * matched: the start of its named group {@code at} if the pattern has one, else 0.
         */
        ClusteredScoreMap<String> match(final Element element, final CharSequence content, final Map<String, Integer> starts) {
            final ClusteredScoreMap<String> subjects = new ClusteredScoreMap<String>(false);
            final List<Attribute> patterns = this.elementMatcher.get(element);
            if (patterns == null) return subjects;
            for (final Attribute attribute: patterns) {
                final java.util.regex.Matcher m = attribute.pattern.matcher(content);
                if (m.matches()) {
                    subjects.inc(attribute.subject);
                    if (starts != null && !starts.containsKey(attribute.subject)) {
                        int at = 0;
                        if (attribute.pattern.pattern().contains("(?<at>")) {
                            try {
                                at = Math.max(0, m.start("at"));
                            } catch (final IllegalArgumentException e) {
                                at = 0;
                            }
                        }
                        starts.put(attribute.subject, at);
                    }
                }
            }
            return subjects;
        }

        @Override
        public String toString() {
            return this.modelName + ":" + this.elementMatcher.toString();
        }

    }

    private final Map<String, ClusteredScoreMap<String>> modelMap; // a map from model names to attribute scores
    private final Map<String, Map<String, String[]>> locators; // model name -> subject -> {element, first matched content}

    public Evaluation() {
        this.modelMap = new HashMap<String, ClusteredScoreMap<String>>();
        this.locators = new HashMap<String, Map<String, String[]>>();
    }

    @Override
    public String toString() {
        return this.modelMap.toString();
    }

    /**
     * produce all model names
     * @return a set of model names
     */
    public Set<String> getModelNames() {
        return this.modelMap.keySet();
    }

    /**
     * Calculate the scores for a model.
     * The scores is a attribute/count map which count how often a specific attribute was found
     * @param modelName
     * @return the calculated scores
     */
    public ClusteredScoreMap<String> getScores(final String modelName) {
        return this.modelMap.get(modelName);
    }

    /**
     * add a model to the evaluation set
     * @param f
     * @throws IOException
     */
    public static void add(final File f) throws IOException {
        final Model pattern = new Model(f);
        for (final Model m : models) {
            if (m.getName().equals(pattern.getName())) models.remove(m);
        }
        models.add(pattern);
    }

    /** Removes a loaded model (tests). */
    static void remove(final String modelName) {
        for (final Model m : models) {
            if (m.getName().equals(modelName)) models.remove(m);
        }
    }

    /** The rule version a loaded model's file names, or null (no such model, or no version). */
    public static String modelVersion(final String modelName) {
        for (final Model m : models) {
            if (m.getName().equals(modelName)) return m.getVersion();
        }
        return null;
    }

    /**
     * Where a subject of a model was found first: {element name, matched content (at most {@link #MAX_LOCATOR}
     * characters)}; null if it was not found.
     */
    public String[] getLocator(final String modelName, final String subject) {
        final Map<String, String[]> m = this.locators.get(modelName);
        return m == null ? null : m.get(subject);
    }

    /**
     * match some content within a specific element
     * this will increase statistic counters for models if a model matches
     * @param element - the element where a matching is made
     * @param content - the content of the element which shall be matched
     */
    public void match(final Element element, final CharSequence content) {
        if (models.isEmpty()) return; // fast return if this feature is not used
        ClusteredScoreMap<String> newScores, oldScores;
        for (final Model pattern: models) {
            final Map<String, Integer> starts = new HashMap<String, Integer>();
            newScores = pattern.match(element, content, starts);
            oldScores = getScores(pattern.getName());
            if (oldScores == null) {
                oldScores = new ClusteredScoreMap<String>(false);
                this.modelMap.put(pattern.getName(), oldScores);
            }
            oldScores.inc(newScores);
            if (!newScores.isEmpty()) {
                Map<String, String[]> first = this.locators.get(pattern.getName());
                if (first == null) {
                    first = new LinkedHashMap<String, String[]>();
                    this.locators.put(pattern.getName(), first);
                }
                final java.util.Iterator<String> subjects = newScores.keys(false);
                while (subjects.hasNext()) {
                    final String subject = subjects.next();
                    if (!first.containsKey(subject)) {
                        first.put(subject, new String[] {element.name(), window(content, starts.getOrDefault(subject, 0))});
                    }
                }
            }
        }
    }

    /** At most {@link #MAX_LOCATOR} characters of the content from shortly before {@code at}, whitespace collapsed. */
    static String window(final CharSequence content, final int at) {
        final int from = Math.max(0, Math.min(content.length(), at) - 40);
        final int to = Math.min(content.length(), from + MAX_LOCATOR);
        return content.subSequence(from, to).toString().replaceAll("\\s+", " ").trim();
    }

    public void match(final Element element, final char[] content) {
        if (models.isEmpty()) return; // fast return if this feature is not used
        if (MemoryControl.request(content.length * 2, false)) {
            match(element, new String(content) /*Segment(content, 0, content.length)*/);
        }
    }

    public static void main(String[] args) {
        String t =
            "// [CDATA[\n" +
            "var gaJsH xost = ((\"https:\" == document.location.protocol) ? \"https://ssl.\" : \"http://www.\");\n" +
            "document.write(unescape(\"%3Cscript xsrc='\" + gaJ sHost + \"google-analytics.com/ga.js'\n" +
            "type='text/javascript'%3E%3C/script%3E\"));\"\n" +
            "\"// ]]\"";
        Pattern p = Pattern.compile("(?s).*gaJsHost.*|(?s).*_gat._anonymizeIp.*");
        if (p.matcher(t).matches()) System.out.println("1");
    }

}
