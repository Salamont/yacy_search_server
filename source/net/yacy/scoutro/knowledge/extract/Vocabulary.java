/*
 *  Vocabulary
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

package net.yacy.scoutro.knowledge.extract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity types, predicates and identity-key schemes of the graph, version 1
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.1 and 4.3). Seeded into {@code kg_vocab}
 * at start; new terms need no schema change. The industry vocabulary (open
 * point O2) extends {@link #FACILITY_KINDS}.
 */
public final class Vocabulary {

    public static final String VERSION = "1";

    // entity types (kg_vocab kind 1)
    public static final String ORGANIZATION = "organization";
    public static final String FACILITY = "facility";
    public static final String SITE = "site";
    public static final String PLACE = "place";
    public static final String SERVICE = "service";

    // predicates (kind 2)
    public static final String NAME = "name";
    public static final String ALIAS = "alias";
    public static final String LEGAL_FORM = "legal_form";
    public static final String ID_REGISTER = "identifier:register";
    public static final String ID_VAT = "identifier:vat";
    public static final String ID_LEI = "identifier:lei";
    public static final String ID_WIKIDATA = "identifier:wikidata";
    public static final String ID_IK = "identifier:ik";
    public static final String OPERATES = "operates";
    public static final String PART_OF = "part_of";
    public static final String LOCATED_AT = "located_at";
    public static final String IN_PLACE = "in_place";
    public static final String OFFERS = "offers";
    public static final String ADDRESS = "address";
    public static final String POSTAL_CODE = "postal_code";
    public static final String LOCALITY = "locality";
    public static final String GEO = "geo";
    public static final String PHONE = "phone";
    public static final String EMAIL = "email";
    public static final String WEBSITE = "website";
    public static final String OPENING_HOURS = "opening_hours";

    // identity-key schemes (kind 3)
    public static final String REGISTER = "register";
    public static final String VAT = "vat";
    public static final String LEI = "lei";
    public static final String WIKIDATA = "wikidata";
    public static final String IK = "ik";
    public static final String LD_ID = "ld_id";
    public static final String SITE_OPERATOR = "site_operator";
    /** The legal name of an organisation that is not the declared operator: finds the operator, never merges by itself. */
    public static final String OPERATOR_NAME = "operator_name";
    public static final String FACILITY_ADDRESS = "facility_address";
    public static final String DOC_LOCAL = "doc_local";

    // literal datatypes
    public static final String T_STRING = "string";
    public static final String T_PHONE = "phone";
    public static final String T_EMAIL = "email";
    public static final String T_URL = "url";
    public static final String T_ADDRESS = "address";
    public static final String T_GEO = "geo";

    public static final Set<String> TYPES = set(ORGANIZATION, FACILITY, SITE, PLACE, SERVICE);

    /** Strong identifiers: global scope, merge across documents and domains; at most one value per entity. */
    public static final Set<String> STRONG_SCHEMES = set(REGISTER, VAT, LEI, WIKIDATA, IK);

    public static final Set<String> SCHEMES = set(REGISTER, VAT, LEI, WIKIDATA, IK, LD_ID, SITE_OPERATOR, OPERATOR_NAME, FACILITY_ADDRESS,
            DOC_LOCAL);

    /** A predicate: its object is an entity ({@code relation}) or a literal of {@code datatype}. */
    public static final class Predicate {
        public final String name;
        public final boolean functional;
        public final boolean relation;
        public final String datatype;

        Predicate(final String name, final boolean functional, final boolean relation, final String datatype) {
            this.name = name;
            this.functional = functional;
            this.relation = relation;
            this.datatype = datatype;
        }
    }

    public static final Map<String, Predicate> PREDICATES;

    /** Predicate of the statement that shows each strong identifier scheme. */
    public static final Map<String, String> IDENTIFIER_PREDICATE;

    static {
        final Map<String, Predicate> p = new LinkedHashMap<>();
        literal(p, NAME, false, T_STRING);
        literal(p, ALIAS, false, T_STRING);
        literal(p, LEGAL_FORM, true, T_STRING);
        literal(p, ID_REGISTER, true, T_STRING);
        literal(p, ID_VAT, true, T_STRING);
        literal(p, ID_LEI, true, T_STRING);
        literal(p, ID_WIKIDATA, true, T_STRING);
        literal(p, ID_IK, true, T_STRING);
        relation(p, OPERATES);
        relation(p, PART_OF);
        relation(p, LOCATED_AT);
        relation(p, IN_PLACE);
        relation(p, OFFERS);
        literal(p, ADDRESS, false, T_ADDRESS);
        literal(p, POSTAL_CODE, false, T_STRING);
        literal(p, LOCALITY, false, T_STRING);
        literal(p, GEO, true, T_GEO);
        literal(p, PHONE, false, T_PHONE);
        literal(p, EMAIL, false, T_EMAIL);
        literal(p, WEBSITE, false, T_URL);
        literal(p, OPENING_HOURS, false, T_STRING);
        PREDICATES = Collections.unmodifiableMap(p);
        final Map<String, String> ids = new LinkedHashMap<>();
        ids.put(REGISTER, ID_REGISTER);
        ids.put(VAT, ID_VAT);
        ids.put(LEI, ID_LEI);
        ids.put(WIKIDATA, ID_WIKIDATA);
        ids.put(IK, ID_IK);
        IDENTIFIER_PREDICATE = Collections.unmodifiableMap(ids);
    }

    /**
     * schema.org types read as facilities (a physical branch with an address),
     * lower-cased. The type is also the facility kind: two facilities of
     * different kinds never merge (a day care and a residential home of the
     * same provider at the same address stay separate).
     */
    public static final Set<String> FACILITY_KINDS = set("localbusiness", "medicalbusiness", "medicalclinic", "physician",
            "dentist", "hospital", "pharmacy", "optician", "childcare", "daycare", "preschool",
            "school", "elementaryschool", "middleschool", "highschool", "collegeoruniversity", "library",
            "store", "restaurant", "cafeorcoffeeshop", "bakery", "barorpub", "foodestablishment", "hotel", "lodgingbusiness",
            "hostel", "professionalservice", "legalservice", "attorney", "notary", "financialservice", "accountingservice",
            "bankorcreditunion", "insuranceagency", "realestateagent", "automotivebusiness", "autorepair", "autodealer",
            "homeandconstructionbusiness", "electrician", "plumber", "roofingcontractor", "generalcontractor",
            "healthandbeautybusiness", "beautysalon", "hairsalon", "dayspa", "sportsactivitylocation", "exercisegym",
            "sportsclub", "entertainmentbusiness", "travelagency", "employmentagency", "governmentoffice", "postoffice",
            "emergencyservice", "firestation", "policestation", "animalshelter", "veterinarycare",
            "childcarecenter", "nursinghome", "seniorcenter", "communitycenter", "museum", "touristinformationcenter");

    /** schema.org types read as organisations (no physical branch), lower-cased. */
    public static final Set<String> ORGANIZATION_TYPES = set("organization", "corporation", "ngo", "governmentorganization",
            "educationalorganization", "medicalorganization", "newsmediaorganization", "sportsorganization", "performinggroup",
            "airline", "consortium", "fundingscheme", "politicalparty", "researchorganization", "onlinebusiness",
            "workersunion", "librarysystem", "cooperative", "projectorganization", "searchrescueorganization");

    /** schema.org types read as sites (an address-bearing place that is no business), lower-cased. */
    public static final Set<String> SITE_TYPES = set("place", "civicstructure", "landmarksorhistoricalbuildings",
            "accommodation", "residence", "apartmentcomplex", "gatedresidencecommunity", "buildingcomplex");

    public static final Set<String> SERVICE_TYPES = set("service", "product", "offer", "financialproduct",
            "governmentservice", "broadcastservice", "cableorsatelliteservice", "foodservice", "taxi", "taxiservice");

    private Vocabulary() {}

    public static Predicate predicate(final String name) {
        return PREDICATES.get(name);
    }

    /** The graph type of a schema.org type name, or null. */
    public static String typeOf(final String schemaType) {
        if (schemaType == null) {
            return null;
        }
        String t = schemaType.trim();
        final int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf(':'));
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        t = t.toLowerCase(Locale.ROOT);
        if (FACILITY_KINDS.contains(t)) {
            return FACILITY;
        }
        if (ORGANIZATION_TYPES.contains(t) || (t.endsWith("organization") && t.length() > 12)) {
            return ORGANIZATION;
        }
        if (SITE_TYPES.contains(t)) {
            return SITE;
        }
        if (SERVICE_TYPES.contains(t)) {
            return SERVICE;
        }
        return null;
    }

    /** The facility kind (lower-cased schema.org type) or null. */
    public static String facilityKind(final String schemaType) {
        if (schemaType == null) {
            return null;
        }
        String t = schemaType.trim();
        final int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf(':'));
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        t = t.toLowerCase(Locale.ROOT);
        return FACILITY_KINDS.contains(t) && !"localbusiness".equals(t) ? t : null;
    }

    private static void literal(final Map<String, Predicate> m, final String name, final boolean functional, final String datatype) {
        m.put(name, new Predicate(name, functional, false, datatype));
    }

    private static void relation(final Map<String, Predicate> m, final String name) {
        m.put(name, new Predicate(name, false, true, null));
    }

    private static Set<String> set(final String... values) {
        final Set<String> s = new TreeSet<>();
        Collections.addAll(s, values);
        return Collections.unmodifiableSet(s);
    }
}
