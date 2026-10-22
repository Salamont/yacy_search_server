/*
 *  CollectionMigrationTest
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.sync.SolrDoc;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The end of the collection defaults built into Scoutro ({@link CollectionMigration}): an installation that had them keeps
 * every identity the code before the change computed, and a new installation gets none of them.
 * <p>
 * {@link #FORMER} holds what the code at commit 1d5fa4bf4 (the last one with the four built-in collections) computed for each
 * setup below, with its own {@code defaults/scoutro/knowledge/categories.json}: the extractor identity of every page
 * ({@link SolrDoc#extractors}), {@link KgConfig#extractionKey}, and per collection the vocabulary, the facility kinds of the LLM
 * tier and the first eight bytes of an LLM cache key (one fixed chunk and context, those kinds). The same report is computed here
 * with the current code after the migration, from the current defaults; any difference would extract every page again or ask
 * the model again.
 */
public class CollectionMigrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void restore() {
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), null);
    }

    static final String FOUR = "edelsenior-web,checkthecoach-web,stackfinder-web,bauteamcheck-web";
    static final List<String> NAMES = List.of("edelsenior-web", "checkthecoach-web", "stackfinder-web", "bauteamcheck-web",
            "newportal-web", "alpha_x");

    /** Computed by the code at 1d5fa4bf4 (old defaults, no migration) for the setups of {@link #setups}. */
    static final String FORMER = """
            == noneStar
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={}
            == noneList
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={"alpha_x":[],"bauteamcheck-web":["electrician","generalcontractor","homeandconstructionbusiness","housepainter","hvacbusiness","locksmith","plumber","roofingcontractor"],"checkthecoach-web":["exercisegym","professionalservice","sportsactivitylocation","sportsclub"],"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"],"stackfinder-web":["professionalservice","store"]}
            == edelOffStar
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,3102fe3a
            extractionKey={edelsenior-web=}||{}
              edelsenior-web vocab=null kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={}
            == edelOffList
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,3102fe3a
            extractionKey={edelsenior-web=}||{}
              edelsenior-web vocab=null kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={"bauteamcheck-web":["electrician","generalcontractor","homeandconstructionbusiness","housepainter","hvacbusiness","locksmith","plumber","roofingcontractor"],"checkthecoach-web":["exercisegym","professionalservice","sportsactivitylocation","sportsclub"],"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"],"stackfinder-web":["professionalservice","store"]}
            == newportalStar
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,601a93f3
            extractionKey={newportal-web=software}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=software kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={}
            == newportalList
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,601a93f3
            extractionKey={newportal-web=software}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=software kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={"bauteamcheck-web":["electrician","generalcontractor","homeandconstructionbusiness","housepainter","hvacbusiness","locksmith","plumber","roofingcontractor"],"checkthecoach-web":["exercisegym","professionalservice","sportsactivitylocation","sportsclub"],"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"],"newportal-web":[],"stackfinder-web":["professionalservice","store"]}
            == fixedUnfollowed
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,56707d66
            extractionKey={bauteamcheck-web=}||{checkthecoach-web=true}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds= key=5eb86dc130b43bc0
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=null kinds= key=5eb86dc130b43bc0
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"],"stackfinder-web":["professionalservice","store"]}
            == remapStar
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-ffe3a2e79047-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=software kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={}
            == remapList
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-ffe3a2e79047-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=software kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={"bauteamcheck-web":["electrician","generalcontractor","homeandconstructionbusiness","housepainter","hvacbusiness","locksmith","plumber","roofingcontractor"],"checkthecoach-web":["exercisegym","professionalservice","sportsactivitylocation","sportsclub"],"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"],"stackfinder-web":["professionalservice","store"]}
            == earlyFileList
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-44c7a4bdf84d-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=coaching kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[coaching, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={"bauteamcheck-web":["electrician","generalcontractor","homeandconstructionbusiness","housepainter","hvacbusiness","locksmith","plumber","roofingcontractor"],"checkthecoach-web":["exercisegym","professionalservice","sportsactivitylocation","sportsclub"],"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"],"stackfinder-web":["professionalservice","store"]}
            == invalidMapUnfollowedKey
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-77a395381244-1047-signals-222778f8d6a129fa06a550b3,84cf94d5
            extractionKey={stackfinder-web=software}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds= key=5eb86dc130b43bc0
              stackfinder-web vocab=software kinds= key=5eb86dc130b43bc0
              bauteamcheck-web vocab=construction kinds= key=5eb86dc130b43bc0
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              llmKindsJson={"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"]}
            == extraMappedUnfollowedKey
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-21fb237d6efd-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds= key=5eb86dc130b43bc0
              stackfinder-web vocab=software kinds= key=5eb86dc130b43bc0
              bauteamcheck-web vocab=construction kinds= key=5eb86dc130b43bc0
              newportal-web vocab=software kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              llmKindsJson={"edelsenior-web":["adultdaycare","assistedliving","hospital","medicalclinic","nursinghome","outpatientcare","seniorcenter"]}
            == kindsKeyStar
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,seniorcenter key=92035bc08b333881
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=adultdaycare,assistedliving,hospital,medicalclinic,nursinghome,outpatientcare,professionalservice,seniorcenter,store key=9f6783f0c57549f6
              llmKindsJson={}
            == kindsKeyList
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds=hospital key=636f64b4292aa52f
              checkthecoach-web vocab=coaching kinds=exercisegym,professionalservice,sportsactivitylocation,sportsclub key=6ebfd35e574fa2e2
              stackfinder-web vocab=software kinds=professionalservice,store key=944ef6d5ab236077
              bauteamcheck-web vocab=construction kinds=electrician,generalcontractor,homeandconstructionbusiness,housepainter,hvacbusiness,locksmith,plumber,roofingcontractor key=69b854ebc7c7ddea
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds=hospital,professionalservice,store key=efac9912a7b36118
              llmKindsJson={"bauteamcheck-web":["electrician","generalcontractor","homeandconstructionbusiness","housepainter","hvacbusiness","locksmith","plumber","roofingcontractor"],"checkthecoach-web":["exercisegym","professionalservice","sportsactivitylocation","sportsclub"],"edelsenior-web":["hospital"],"stackfinder-web":["professionalservice","store"]}
            == graphOff
            extractors=jsonld/5,metadata/1,rule/6,vocabulary/3-9192e239c049-1047-signals-222778f8d6a129fa06a550b3,d8ffcd44
            extractionKey={}||{}
              edelsenior-web vocab=care kinds= key=5eb86dc130b43bc0
              checkthecoach-web vocab=coaching kinds= key=5eb86dc130b43bc0
              stackfinder-web vocab=software kinds= key=5eb86dc130b43bc0
              bauteamcheck-web vocab=construction kinds= key=5eb86dc130b43bc0
              newportal-web vocab=null kinds= key=5eb86dc130b43bc0
              alpha_x vocab=null kinds= key=5eb86dc130b43bc0
              multi vocabs=[care, software] kinds= key=5eb86dc130b43bc0
              llmKindsJson={}
            """;

    /** One installation: its settings and an operator vocabulary file (name, content) or none. */
    static final class Setup {
        final Map<String, String> settings;
        final String[] file;

        Setup(final Map<String, String> settings, final String[] file) {
            this.settings = settings;
            this.file = file;
        }
    }

    static Map<String, Setup> setups() {
        final Map<String, Setup> s = new LinkedHashMap<>();
        s.put("noneStar", new Setup(m(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*"), null));
        s.put("noneList", new Setup(m(KgConfig.COLLECTIONS, FOUR + ",alpha_x", KgConfig.LLM_COLLECTIONS, FOUR + ",alpha_x"), null));
        s.put("edelOffStar", new Setup(m(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "edelsenior-web",
                ""), null));
        s.put("edelOffList", new Setup(m(KgConfig.COLLECTIONS, FOUR, KgConfig.LLM_COLLECTIONS, FOUR, KgConfig.VOCAB_PREFIX + "edelsenior-web",
                ""), null));
        s.put("newportalStar", new Setup(m(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "newportal-web",
                "software"), null));
        s.put("newportalList", new Setup(m(KgConfig.COLLECTIONS, FOUR + ",newportal-web", KgConfig.LLM_COLLECTIONS, FOUR + ",newportal-web",
                KgConfig.VOCAB_PREFIX + "newportal-web", "software"), null));
        s.put("fixedUnfollowed", new Setup(m(KgConfig.COLLECTIONS, "edelsenior-web,stackfinder-web", KgConfig.LLM_COLLECTIONS,
                "edelsenior-web,stackfinder-web", KgConfig.VOCAB_PREFIX + "bauteamcheck-web", "", KgConfig.VOCAB_PREFIX + "newportal-web",
                "software", KgConfig.JOBS_EXTRACT_PREFIX + "checkthecoach-web", "true", KgConfig.PRICES_STALE_DAYS + ".bauteamcheck-web", "30"),
                null));
        final String[] remap = {"zz-remap.json", "{\"schema\":\"scoutro.kg.categories\",\"collections\":{\"checkthecoach-web\":\"software\"}}"};
        s.put("remapStar", new Setup(m(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*"), remap));
        s.put("remapList", new Setup(m(KgConfig.COLLECTIONS, FOUR, KgConfig.LLM_COLLECTIONS, FOUR), remap));
        // an operator file that sorts before the migrated one
        s.put("earlyFileList", new Setup(m(KgConfig.COLLECTIONS, FOUR, KgConfig.LLM_COLLECTIONS, FOUR), new String[] {"000-a.json",
                "{\"schema\":\"scoutro.kg.categories\",\"collections\":{\"edelsenior-web\":\"coaching\"}}"}));
        s.put("invalidMapUnfollowedKey", new Setup(m(KgConfig.COLLECTIONS, "edelsenior-web", KgConfig.LLM_COLLECTIONS, "edelsenior-web",
                KgConfig.VOCAB_PREFIX + "stackfinder-web", "software"), new String[] {"zz-bad.json",
                        "{\"schema\":\"scoutro.kg.categories\",\"collections\":{\"stackfinder-web\":\"nosuchvocab\"}}"}));
        s.put("extraMappedUnfollowedKey", new Setup(m(KgConfig.COLLECTIONS, "edelsenior-web", KgConfig.LLM_COLLECTIONS, "edelsenior-web",
                KgConfig.VOCAB_PREFIX + "newportal-web", "care"), new String[] {"zz-extra.json",
                        "{\"schema\":\"scoutro.kg.categories\",\"collections\":{\"newportal-web\":\"software\"}}"}));
        // the kinds key of a collection that is not named while every collection is followed was never read
        s.put("kindsKeyStar", new Setup(m(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*", "scoutro.kg.llm.kinds.edelsenior-web",
                "hospital"), null));
        s.put("kindsKeyList", new Setup(m(KgConfig.COLLECTIONS, FOUR, KgConfig.LLM_COLLECTIONS, FOUR, "scoutro.kg.llm.kinds.edelsenior-web",
                "hospital"), null));
        s.put("graphOff", new Setup(m(KgConfig.ENABLED, "false"), null));
        return s;
    }

    static Map<String, String> m(final String... kv) {
        final Map<String, String> out = new HashMap<>();
        out.put(KgConfig.ENABLED, "true");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }

    /** The expected report of one setup. */
    static String former(final String setup) {
        final String all = FORMER;
        final int from = all.indexOf("== " + setup + "\n");
        assertTrue("no former values of " + setup, from >= 0);
        final int start = from + setup.length() + 4;
        final int end = all.indexOf("== ", start);
        // the extractor versions of the recorded code are those of its time: the data quality programme (WP-F) raised them
        // on purpose, for one re-read of tiers 1 and 2; every other part of the identity must stay what the former code computed
        return all.substring(start, end < 0 ? all.length() : end).replace("jsonld/5,metadata/1,rule/6,vocabulary/3",
                "jsonld/" + net.yacy.scoutro.knowledge.extract.JsonLdExtractor.VERSION + ",metadata/"
                        + net.yacy.scoutro.knowledge.extract.MetadataExtractor.VERSION + ",rule/"
                        + net.yacy.scoutro.knowledge.extract.RuleExtractor.VERSION + ",vocabulary/"
                        + net.yacy.scoutro.knowledge.extract.Vocabulary.VERSION)
                // 0.12.0 changed the default categories on purpose (gate T0, the software vocabulary), within the same re-read:
                // each recorded hash of the categories with a setup's collections becomes exactly one new hash
                .replaceAll("vocabulary/(\\d+)-(9192e239c049|ffe3a2e79047|44c7a4bdf84d|77a395381244|21fb237d6efd)-",
                        "vocabulary/$1-<categories $2>-")
                .replace("<categories 9192e239c049>", "4ba098e42de3").replace("<categories ffe3a2e79047>", "2437ec2944e9")
                .replace("<categories 44c7a4bdf84d>", "60c8699722ff").replace("<categories 77a395381244>", "14e7b56fe8ff")
                .replace("<categories 21fb237d6efd>", "b234856e4422");
    }

    /** The report of {@link #FORMER} for the vocabularies in force and these settings. */
    static String report(final Map<String, String> settings) {
        final KgVocabularies.Snapshot v = KgVocabularies.get();
        final KgConfig cfg = KgConfig.read(settings::get, new ArrayList<>(settings.keySet()));
        assertTrue(cfg.problems().toString(), cfg.valid());
        final StringBuilder sb = new StringBuilder();
        sb.append("extractors=").append(SolrDoc.extractors(cfg)).append('\n');
        sb.append("extractionKey=").append(cfg.extractionKey()).append('\n');
        for (final String c : NAMES) {
            sb.append("  ").append(c).append(" vocab=").append(cfg.vocabularyOf(c, v.categories.collections)).append(" kinds=")
                    .append(String.join(",", cfg.llmKinds(List.of(c)))).append(" key=").append(key(cfg.llmKinds(List.of(c)))).append('\n');
        }
        final List<String> multi = List.of("edelsenior-web", "stackfinder-web");
        sb.append("  multi vocabs=").append(cfg.vocabulariesOf(multi, v.categories.collections)).append(" kinds=")
                .append(String.join(",", cfg.llmKinds(multi))).append(" key=").append(key(cfg.llmKinds(multi))).append('\n');
        sb.append("  llmKindsJson=").append(cfg.toJson().opt("llmKinds")).append('\n');
        return sb.toString();
    }

    /** The first eight bytes of the cache key of one fixed request with these kinds. */
    static String key(final Set<String> kinds) {
        final byte[] k = LlmExtractor.cacheKey("prompt-hash", "OLLAMA/model", new LlmExtractor.Chunk("Tagespflege und Kurzzeitpflege.", 0),
                "Title", "example.de", "de", List.of(), kinds);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", k[i] & 0xff));
        }
        return sb.toString();
    }

    /** An installation as the former code left it: a graph file and the operator's vocabulary file of the setup. */
    private File installation(final Setup s) throws Exception {
        final File data = this.tmp.newFolder();
        final KgPaths paths = new KgPaths(data);
        assertTrue(paths.dir.mkdirs());
        assertTrue(paths.db.createNewFile());
        if (s.file != null) {
            final File dir = KgRuntime.vocabularyDir(data);
            assertTrue(dir.mkdirs());
            Files.write(new File(dir, s.file[0]).toPath(), s.file[1].getBytes(StandardCharsets.UTF_8));
        }
        return data;
    }

    /** (3) Every setup keeps the extractor identity, the vocabularies, the kinds and the cache keys of the former code. */
    @Test
    public void theMigrationKeepsEveryIdentityTheFormerCodeComputed() throws Exception {
        for (final Map.Entry<String, Setup> e : setups().entrySet()) {
            final File data = installation(e.getValue());
            final Map<String, String> settings = new HashMap<>(e.getValue().settings);
            final CollectionMigration.Result r = CollectionMigration.run(data, settings::get, settings::put, new ArrayList<>(settings.keySet()));
            assertNotNull(e.getKey(), r);
            assertTrue(e.getKey(), r.existing);
            assertEquals("graph", r.evidence);
            assertEquals(e.getKey(), FOUR_SORTED, settings.get(KgConfig.COLLECTION_PROFILES_MIGRATION));
            KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), KgRuntime.vocabularyDir(data));
            assertEquals(e.getKey(), former(e.getKey()), report(settings));
        }
    }

    static final String FOUR_SORTED = "bauteamcheck-web,checkthecoach-web,edelsenior-web,stackfinder-web";

    /** The counter-check of (3): without the migration the same installations would get other identities. */
    @Test
    public void withoutTheMigrationTheIdentitiesWouldChange() throws Exception {
        int changed = 0;
        for (final Map.Entry<String, Setup> e : setups().entrySet()) {
            final File data = installation(e.getValue());
            KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), KgRuntime.vocabularyDir(data));
            final String report = report(e.getValue().settings);
            final String former = former(e.getKey());
            if (!former.equals(report)) {
                changed++;
            }
            if (e.getKey().equals("noneStar")) {
                assertNotEquals("the extractor identity of every page would change", former.substring(0, former.indexOf('\n')),
                        report.substring(0, report.indexOf('\n')));
            }
        }
        assertEquals("every setup differs without the migration", setups().size(), changed);
    }

    /** What the file holds: the map without what an operator file maps, and the former kinds only where the profile differs. */
    @Test
    public void theFileHoldsOnlyWhatTheOperatorFilesAndProfilesDoNotGive() throws Exception {
        final Map<String, Setup> s = setups();
        File data = installation(s.get("noneStar"));
        Map<String, String> settings = new HashMap<>(s.get("noneStar").settings);
        CollectionMigration.Result r = CollectionMigration.run(data, settings::get, settings::put, settings.keySet());
        assertEquals(CollectionMigration.FORMER_VOCABULARIES, r.carried);
        assertTrue("every profile gives the kinds the name gave", r.fixedKinds.isEmpty());
        final JSONObject file = new JSONObject(new String(Files.readAllBytes(new File(KgRuntime.vocabularyDir(data), CollectionMigration.FILE)
                .toPath()), StandardCharsets.UTF_8));
        assertEquals("scoutro.kg.categories", file.getString("schema"));
        assertEquals(4, file.getJSONObject("collections").length());
        assertFalse(file.has("collection_llm_kinds"));

        data = installation(s.get("remapList"));
        settings = new HashMap<>(s.get("remapList").settings);
        r = CollectionMigration.run(data, settings::get, settings::put, settings.keySet());
        assertFalse("an operator file maps it: not written again", r.carried.containsKey("checkthecoach-web"));
        assertEquals(3, r.carried.size());
        assertEquals("its former kinds stay: software would give others", Map.of("checkthecoach-web",
                CollectionMigration.FORMER_KINDS.get("checkthecoach-web")), r.fixedKinds);

        data = installation(s.get("newportalStar"));
        settings = new HashMap<>(s.get("newportalStar").settings);
        r = CollectionMigration.run(data, settings::get, settings::put, settings.keySet());
        assertEquals("a collection set to a vocabulary had no kinds by its name", Map.of("newportal-web", Set.of()), r.fixedKinds);
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), KgRuntime.vocabularyDir(data));
        assertEquals(Set.of(), KgVocabularies.get().categories.collectionLlmKinds.get("newportal-web"));
        assertTrue(KgVocabularies.get().problems.toString(), KgVocabularies.get().problems.isEmpty());
    }

    /** (4) The step runs once; a lost marker only records the marker again and never rewrites the installation's file. */
    @Test
    public void theMigrationIsIdempotent() throws Exception {
        final Setup s = setups().get("edelOffList");
        final File data = installation(s);
        final Map<String, String> settings = new HashMap<>(s.settings);
        assertNotNull(CollectionMigration.run(data, settings::get, settings::put, new ArrayList<>(settings.keySet())));
        final File file = new File(KgRuntime.vocabularyDir(data), CollectionMigration.FILE);
        final byte[] first = Files.readAllBytes(file.toPath());
        final Map<String, String> after = new HashMap<>(settings);
        assertNull("ran before", CollectionMigration.run(data, settings::get, settings::put, new ArrayList<>(settings.keySet())));
        assertArrayEquals(first, Files.readAllBytes(file.toPath()));
        assertEquals(after, settings);
        // the operator edits the file; the marker gets lost: the file is the operator's now and stays as it is
        final byte[] edited = new String(first, StandardCharsets.UTF_8).replace("\"bauteamcheck-web\": \"construction\",\n", "")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(java.util.Arrays.equals(first, edited));
        Files.write(file.toPath(), edited);
        settings.remove(KgConfig.COLLECTION_PROFILES_MIGRATION);
        final CollectionMigration.Result again = CollectionMigration.run(data, settings::get, settings::put, new ArrayList<>(settings.keySet()));
        assertTrue(again.existing);
        assertFalse(again.written);
        assertArrayEquals(edited, Files.readAllBytes(file.toPath()));
        assertEquals(after, settings);
        // without a writer of settings (no peer) the step never runs
        settings.remove(KgConfig.COLLECTION_PROFILES_MIGRATION);
        assertNull(CollectionMigration.run(data, settings::get, null, settings.keySet()));
        assertFalse(settings.containsKey(KgConfig.COLLECTION_PROFILES_MIGRATION));
    }

    /** (4) A new installation gets nothing but the empty marker; the former names mean nothing to it. */
    @Test
    public void aNewInstallationGetsNothing() throws Exception {
        final File data = this.tmp.newFolder();
        final Map<String, String> settings = m(KgConfig.COLLECTIONS, "my-portal-web,example-care", KgConfig.LLM_COLLECTIONS, "*");
        final CollectionMigration.Result r = CollectionMigration.run(data, settings::get, settings::put, new ArrayList<>(settings.keySet()));
        assertFalse(r.existing);
        assertFalse(r.written);
        assertEquals("", settings.get(KgConfig.COLLECTION_PROFILES_MIGRATION));
        assertFalse("not even the directory", new KgPaths(data).dir.exists());
        assertNull(CollectionMigration.run(data, settings::get, settings::put, settings.keySet()));
        // a graph built later (by this version) is no reason to carry anything over
        assertTrue(new KgPaths(data).dir.mkdirs() && new KgPaths(data).db.createNewFile());
        assertNull(CollectionMigration.run(data, settings::get, settings::put, settings.keySet()));
        assertFalse(KgRuntime.vocabularyDir(data).exists());
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), KgRuntime.vocabularyDir(data));
        assertTrue("the shipped defaults map no collection", KgVocabularies.get().categories.collections.isEmpty());
        final Map<String, String> four = m(KgConfig.COLLECTIONS, FOUR, KgConfig.LLM_COLLECTIONS, "*",
                KgConfig.COLLECTION_PROFILES_MIGRATION, "");
        final KgConfig cfg = KgConfig.read(four::get, four.keySet());
        assertTrue(cfg.migratedCollections.isEmpty());
        for (final String c : FOUR.split(",")) {
            assertNull(c, cfg.vocabularyOf(c, KgVocabularies.get().categories.collections));
            assertTrue(c, cfg.llmKinds(List.of(c)).isEmpty());
        }
    }

    /** A file that cannot be written keeps the graph off for this start (rather than extracting every page again). */
    @Test
    public void aFailedStepKeepsTheGraphOffUntilItSucceeds() throws Exception {
        final File data = this.tmp.newFolder();
        final File dir = KgRuntime.vocabularyDir(data);
        assertTrue(dir.getParentFile().mkdirs());
        assertTrue("a file where the directory belongs", dir.createNewFile());
        final Map<String, String> settings = m(KgConfig.COLLECTIONS, "edelsenior-web");
        final KgRuntime.Env env = KgTestSupport.env(data, settings, new KgTestSupport.Probe()).withKeys(() -> new ArrayList<>(
                settings.keySet())).withSettings(settings::put);
        KgRuntime r = new KgRuntime(env);
        r.open();
        try {
            assertEquals(KgRuntime.State.UNAVAILABLE, r.state());
            assertEquals(KgException.START_FAILED, r.reason());
            assertFalse("not recorded: the step runs again", settings.containsKey(KgConfig.COLLECTION_PROFILES_MIGRATION));
        } finally {
            r.close();
        }
        assertTrue(dir.delete());
        r = new KgRuntime(env);
        r.open();
        try {
            assertEquals(KgRuntime.State.RUNNING, r.state());
            assertEquals(FOUR_SORTED, settings.get(KgConfig.COLLECTION_PROFILES_MIGRATION));
            assertEquals("care", r.config().vocabularyOf("edelsenior-web", KgVocabularies.get().categories.collections));
            assertEquals(1L, KgTestSupport.count(r, "SELECT count(*) FROM kg_event WHERE code = 'collection_profiles_migrated'"));
        } finally {
            r.close();
        }
    }

    /** An installation without a graph counts as existing when its settings name a former collection, and only then. */
    @Test
    public void settingsThatNameAFormerCollectionMarkAnExistingInstallation() throws Exception {
        final File data = this.tmp.newFolder();
        final Map<String, String> settings = m(KgConfig.JOBS_COLLECTIONS, "stackfinder-web", KgConfig.ENABLED, "false");
        final CollectionMigration.Result r = CollectionMigration.run(data, settings::get, settings::put, new ArrayList<>(settings.keySet()));
        assertTrue(r.existing);
        assertEquals("setting " + KgConfig.JOBS_COLLECTIONS, r.evidence);
        assertFalse(r.graph);
        assertTrue(new File(KgRuntime.vocabularyDir(data), CollectionMigration.FILE).isFile());
        assertEquals("setting scoutro.kg.vocab.bauteamcheck-web", CollectionMigration.namedBySettings(
                m(KgConfig.VOCAB_PREFIX + "bauteamcheck-web", "")::get, List.of(KgConfig.VOCAB_PREFIX + "bauteamcheck-web")));
        assertNull("a similar name is no former collection", CollectionMigration.namedBySettings(
                m(KgConfig.COLLECTIONS, "edelsenior-web-neu")::get, List.of(KgConfig.COLLECTIONS)));
    }
}
