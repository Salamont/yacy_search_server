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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity types, predicates and identity-key schemes of the graph
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.1, 4.3 and, for version 2, 23).
 * Seeded into {@code kg_vocab} at start; new terms need no schema change.
 * <p>
 * Version 2 (package 6, the business graph) adds jobs, the services'
 * categories and prices, the relations between organisations, industries
 * (NACE Rev. 2.1 and the Scoutro groups), organisation contacts and the
 * declared audiences. Controlled values are codes of
 * {@link net.yacy.scoutro.knowledge.vocab.Categories} and
 * {@link net.yacy.scoutro.knowledge.vocab.Nace}; structured values (a price,
 * a salary, a service area, a contact point) are canonical JSON objects
 * ({@link #T_JSON}) whose every number was read from the quoted source.
 * Observed customers are the relations {@link #CUSTOMER_OF} and
 * {@link #REFERENCE_FOR}; suggested matches are no statements at all
 * (derived, {@code kg_derived}), so the three audience layers never mix.
 */
public final class Vocabulary {

    public static final String VERSION = "2";

    // entity types (kg_vocab kind 1)
    public static final String ORGANIZATION = "organization";
    public static final String FACILITY = "facility";
    public static final String SITE = "site";
    public static final String PLACE = "place";
    public static final String SERVICE = "service";
    /** A job posting (version 2); switched on per collection. */
    public static final String JOB = "job";

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

    // version 2: services and prices (C)
    /** A Scoutro service category ({@code care/tagespflege}); several per service. */
    public static final String CATEGORY = "category";
    public static final String DESCRIPTION = "description";
    /** A published price ({@link #T_JSON}): amount or range, currency, unit, kind, conditions; never estimated. */
    public static final String PRICE = "price";

    // version 2: relations between organisations (D), all directed as stated by the source
    public static final String PARENT_OF = "parent_of";
    public static final String SUBSIDIARY_OF = "subsidiary_of";
    /** The carrier ("Träger") of an organisation or facility. */
    public static final String CARRIER_OF = "carrier_of";
    public static final String MEMBER_OF = "member_of";
    /** Membership in an association, chamber or guild. */
    public static final String ASSOCIATION_MEMBER = "association_member";
    public static final String PARTNER_OF = "partner_of";
    public static final String COOPERATION_WITH = "cooperation_with";
    /** "A customer_of B": A is named as a customer of B (an observed customer of B). */
    public static final String CUSTOMER_OF = "customer_of";
    /** "A reference_for B": A is named as a reference (project, client) of B. */
    public static final String REFERENCE_FOR = "reference_for";
    public static final String SUPPLIER_OF = "supplier_of";
    public static final String SERVICE_PROVIDER_FOR = "service_provider_for";
    /** "A brand_of B": A is a brand of B. */
    public static final String BRAND_OF = "brand_of";
    public static final String CERTIFIED_BY = "certified_by";
    public static final String FUNDED_BY = "funded_by";
    public static final String SPONSORED_BY = "sponsored_by";
    /** A certificate or standard without a certifying organisation ("ISO 9001"). */
    public static final String CERTIFICATION = "certification";

    // version 2: industry (F)
    /** A NACE Rev. 2.1 code at the safe level (section to class); the main industry is the best-supported one. */
    public static final String INDUSTRY = "industry";
    /** A Scoutro industry subcategory, the group of a vocabulary ({@code construction.tga}). */
    public static final String INDUSTRY_CATEGORY = "industry_category";

    // version 2: organisation contacts (G), never of persons (O7)
    public static final String FAX = "fax";
    public static final String CONTACT_FORM = "contact_form";
    /** A department or function with its role contact values ({@link #T_JSON}); never a person's name. */
    public static final String CONTACT_POINT = "contact_point";
    public static final String OFFICE_HOURS = "office_hours";
    public static final String SOCIAL_PROFILE = "social_profile";
    public static final String DIRECTIONS = "directions";

    // version 2: jobs (H)
    public static final String HIRING_ORGANIZATION = "hiring_organization";
    public static final String JOB_LOCATION = "job_location";
    public static final String EMPLOYMENT_TYPE = "employment_type";
    public static final String OCCUPATIONAL_FIELD = "occupational_field";
    /** A published salary ({@link #T_JSON}) with its unit; never estimated. */
    public static final String SALARY = "salary";
    public static final String START_DATE = "start_date";
    public static final String DATE_POSTED = "date_posted";
    public static final String VALID_THROUGH = "valid_through";
    public static final String APPLICATION_ROUTE = "application_route";

    // version 2: declared audience (I); observed customers are CUSTOMER_OF / REFERENCE_FOR, suggestions are derived
    public static final String CUSTOMER_TYPE = "customer_type";
    public static final String AUDIENCE_SEGMENT = "audience_segment";
    /** A NACE code of the industries an organisation or service is for. */
    public static final String TARGET_INDUSTRY = "target_industry";
    /** A Scoutro category or group the audience belongs to. */
    public static final String TARGET_CATEGORY = "target_category";
    public static final String COMPANY_SIZE = "company_size";
    /** The served area ({@link #T_JSON}): kind place, region, state, radius, national or international. */
    public static final String SERVICE_AREA = "service_area";
    public static final String SERVES_PLACE = "serves_place";
    /** The need or occasion an audience has, as the source words it. */
    public static final String NEED = "need";

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
    /**
     * The operator of the page's site when the page does not name it (a services or careers page): resolves to the
     * declared site operator of the registrable domain if there is exactly one, and is taken in by it later.
     */
    public static final String DOMAIN_OPERATOR = "domain_operator";
    /** A job posting within its registrable domain: employer, title and location. */
    public static final String JOB_POSTING = "job_posting";
    /** A place by country, level and name ({@code de|state|bayern}); global, so service areas and addresses meet. */
    public static final String PLACE_NAME = "place_name";
    /**
     * A service by its provider and its category or name, within the registrable domain: the day care of the
     * operator on the prices page and on the services page is one service (so two prices of it can conflict).
     */
    public static final String SERVICE_NAME = "service_name";

    // literal datatypes
    public static final String T_STRING = "string";
    public static final String T_PHONE = "phone";
    public static final String T_EMAIL = "email";
    public static final String T_URL = "url";
    public static final String T_ADDRESS = "address";
    public static final String T_GEO = "geo";
    /** Canonical JSON object (sorted keys); compared as is. */
    public static final String T_JSON = "json";
    /** ISO date {@code yyyy-mm-dd}. */
    public static final String T_DATE = "date";
    /** A code of a controlled vocabulary (category, NACE, customer type, ...). */
    public static final String T_CODE = "code";

    public static final Set<String> TYPES = set(ORGANIZATION, FACILITY, SITE, PLACE, SERVICE, JOB);

    /** Strong identifiers: global scope, merge across documents and domains; at most one value per entity. */
    public static final Set<String> STRONG_SCHEMES = set(REGISTER, VAT, LEI, WIKIDATA, IK);

    public static final Set<String> SCHEMES = set(REGISTER, VAT, LEI, WIKIDATA, IK, LD_ID, SITE_OPERATOR, OPERATOR_NAME, FACILITY_ADDRESS,
            DOC_LOCAL, DOMAIN_OPERATOR, JOB_POSTING, PLACE_NAME, SERVICE_NAME);

    /** Relations between organisations (version 2, part D). */
    public static final Set<String> BUSINESS_RELATIONS = set(PARENT_OF, SUBSIDIARY_OF, CARRIER_OF, MEMBER_OF, ASSOCIATION_MEMBER,
            PARTNER_OF, COOPERATION_WITH, CUSTOMER_OF, REFERENCE_FOR, SUPPLIER_OF, SERVICE_PROVIDER_FOR, BRAND_OF, CERTIFIED_BY,
            FUNDED_BY, SPONSORED_BY);

    /** Derived relations (no statements; {@code kg_derived}): never upgraded to a business relation. */
    public static final String LINKED_TO = "linked_to";
    public static final String SAME_OPERATOR = "same_operator";
    public static final String SUGGESTED_CUSTOMER = "suggested_customer";
    public static final String SUGGESTED_PARTNER = "suggested_partner";
    /** Kinds of {@code kg_derived}, by number. */
    public static final List<String> DERIVED_KINDS = List.of("", LINKED_TO, SAME_OPERATOR, SUGGESTED_CUSTOMER, SUGGESTED_PARTNER);

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
        // version 2
        literal(p, CATEGORY, false, T_CODE);
        literal(p, DESCRIPTION, false, T_STRING);
        literal(p, PRICE, false, T_JSON);
        for (final String r : new String[] {PARENT_OF, SUBSIDIARY_OF, CARRIER_OF, MEMBER_OF, ASSOCIATION_MEMBER, PARTNER_OF,
                COOPERATION_WITH, CUSTOMER_OF, REFERENCE_FOR, SUPPLIER_OF, SERVICE_PROVIDER_FOR, BRAND_OF, CERTIFIED_BY, FUNDED_BY,
                SPONSORED_BY}) {
            relation(p, r);
        }
        literal(p, CERTIFICATION, false, T_STRING);
        literal(p, INDUSTRY, false, T_CODE);
        literal(p, INDUSTRY_CATEGORY, false, T_CODE);
        literal(p, FAX, false, T_PHONE);
        literal(p, CONTACT_FORM, false, T_URL);
        literal(p, CONTACT_POINT, false, T_JSON);
        literal(p, OFFICE_HOURS, false, T_STRING);
        literal(p, SOCIAL_PROFILE, false, T_URL);
        literal(p, DIRECTIONS, false, T_STRING);
        relation(p, HIRING_ORGANIZATION);
        relation(p, JOB_LOCATION);
        literal(p, EMPLOYMENT_TYPE, false, T_CODE);
        literal(p, OCCUPATIONAL_FIELD, false, T_STRING);
        literal(p, SALARY, false, T_JSON);
        literal(p, START_DATE, true, T_DATE);
        literal(p, DATE_POSTED, true, T_DATE);
        literal(p, VALID_THROUGH, true, T_DATE);
        literal(p, APPLICATION_ROUTE, false, T_STRING);
        literal(p, CUSTOMER_TYPE, false, T_CODE);
        literal(p, AUDIENCE_SEGMENT, false, T_CODE);
        literal(p, TARGET_INDUSTRY, false, T_CODE);
        literal(p, TARGET_CATEGORY, false, T_CODE);
        literal(p, COMPANY_SIZE, false, T_CODE);
        literal(p, SERVICE_AREA, false, T_JSON);
        relation(p, SERVES_PLACE);
        literal(p, NEED, false, T_STRING);
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
            "workersunion", "librarysystem", "cooperative", "projectorganization", "searchrescueorganization", "brand");

    /** schema.org types read as sites (an address-bearing place that is no business), lower-cased. */
    public static final Set<String> SITE_TYPES = set("place", "civicstructure", "landmarksorhistoricalbuildings",
            "accommodation", "residence", "apartmentcomplex", "gatedresidencecommunity", "buildingcomplex");

    /** schema.org types read as jobs, lower-cased. */
    public static final Set<String> JOB_TYPES = set("jobposting");

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
        if (JOB_TYPES.contains(t)) {
            return JOB;
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
