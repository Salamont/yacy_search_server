/*
 *  Address
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

import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * A postal address as extracted. {@link #complete()} addresses (street,
 * house number, postal code, locality) can serve as part of the
 * {@code facility_address} identity key; incomplete ones are only shown.
 */
public final class Address {

    /**
     * A word that is no town after a postal code ("12345 Telefon …"), as a negative lookahead; also inside {@link #TOWN}, before
     * the word after a prefix ("Bad") or a binder ("an der").
     */
    public static final String NOT_A_TOWN = "(?!(?:Tel|Telefon|Telefax|Fax|Fon|E-?Mail|Mail|Web|Internet|Deutschland|Germany|Österreich|Austria"
            + "|Schweiz|Registergericht|Amtsgericht|Handelsregister|Geschäftsführ|Geschaeftsfuehr|Vertreten|USt|Steuer|Phone|Mobil|Kontakt|Vorstand"
            + "|Inhaber|Verantwortlich|Sitz|OT|Ortsteil|Postfach|Diese|Dieser|Unsere|Unser|Ihre|Ihr|Alle|Wir|Sie|Hier|Mehr|Weitere|Zur|Zum|Und)"
            + "(?![\\p{L}]))";
    /**
     * A prefix of a town of two words ("Bad Rappenau", "Sankt Augustin", "Neu Wulmstorf", "Bergisch Gladbach", "Heilbad Heiligenstadt"):
     * only after such a word a second plain word is the town's (rule/12, docs/SCOUTRO_KG_FACILITIES.md section 12). Before, any
     * capitalised word after the town's first word was taken ("Musterstadt. Diese Seite" gave "Musterstadt. Diese"), and a
     * prefix with a dot was cut ("St. Ingbert" gave "St").
     */
    public static final String TOWN_PREFIX = "(?:Bad|Sankt|Heilbad|Seebad|Ostseebad|Nordseebad|Kurort|Lutherstadt|Hansestadt|Neu|Alt|Klein|Groß"
            + "|Gross|Hohen|Ober|Unter|Nieder|Markt|Schloß|Schloss|Bergisch|Schwäbisch|Fränkisch|Hessisch|Westfälisch|Hohenlohe|Löwenberger|Abu)";
    /**
     * The name of a town after its postal code, as the rules and the place reader share it (rule/12, llm/8): a prefix word of
     * {@link #TOWN_PREFIX} or "St."/"Skt." with the town's word, or one word ("Musterstadt", "Garmisch-Partenkirchen", "Horn-Bad
     * Meinberg"); then, if the page writes it, a binder with a capitalised word ("Frankfurt am Main", "Brandenburg an der Havel",
     * "Bernau bei Berlin", "Rothenburg ob der Tauber", "Limburg a. d. Lahn") or a qualifier in parentheses ("Halle (Saale)", "Essen
     * (Oldenburg)"). No sentence's end and no word of the sentence after it is part of the town. No capturing group inside.
     */
    public static final String TOWN = "(?:" + TOWN_PREFIX + "\\s+" + NOT_A_TOWN + "|St\\.\\s?|Skt\\.\\s?)?\\p{Lu}[\\p{L}\\-]+"
            + "(?:(?<=-Bad|-Sankt|-St\\.)\\s+\\p{Lu}[\\p{L}\\-]+)?"
            + "(?:\\s+(?:am|an\\s+der|an\\s+den|a\\.\\s?d\\.|im|in\\s+der|bei|ob\\s+der|vor\\s+der|unter\\s+dem|auf\\s+der|zu|an|i\\.\\s?d\\.)\\s+"
            + NOT_A_TOWN + "\\p{Lu}[\\p{L}\\-]+|\\s+(?:i\\.\\s?Br\\.|a\\.\\s?M\\.)|\\s+\\(\\p{Lu}[\\p{L}\\-.]+(?:\\s\\p{Lu}[\\p{L}\\-.]+)?\\))?";

    public final String street;
    public final String number;
    public final String postalCode;
    public final String locality;
    public final String country;

    public Address(final String street, final String number, final String postalCode, final String locality,
            final String country) {
        this.street = Normalizers.text(street);
        this.number = Normalizers.text(number);
        this.postalCode = Normalizers.text(postalCode);
        this.locality = Normalizers.text(locality);
        this.country = Normalizers.text(country);
    }

    /** Street and house number given together ("Musterstraße 12a") are split here. */
    public static Address of(final String streetAndNumber, final String postalCode, final String locality, final String country) {
        final String s = Normalizers.text(streetAndNumber);
        if (s == null) {
            return new Address(null, null, postalCode, locality, country);
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?\\D)\\s*(\\d{1,4}\\s?[a-zA-Z]?(?:\\s?[-/]\\s?\\d{1,4}\\s?[a-zA-Z]?)?)$").matcher(s);
        if (m.matches()) {
            return new Address(m.group(1).replaceAll("[,\\s]+$", ""), m.group(2), postalCode, locality, country);
        }
        return new Address(s, null, postalCode, locality, country);
    }

    public boolean complete() {
        return this.street != null && this.number != null && this.postalCode != null && this.locality != null;
    }

    /** One-line display form: "Musterstraße 12a, 12345 Berlin". */
    public String display() {
        final StringBuilder b = new StringBuilder();
        if (this.street != null) {
            b.append(this.street);
            if (this.number != null) {
                b.append(' ').append(this.number);
            }
        }
        final String city = join(this.postalCode, this.locality);
        if (city != null) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(city);
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** Normalised comparison form of a complete address, null otherwise. */
    public String key() {
        if (!complete()) {
            return null;
        }
        return Normalizers.streetKey(this.street) + " " + this.number.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT)
                + "|" + this.postalCode.replaceAll("\\s+", "") + "|" + Normalizers.key(this.locality);
    }

    private static String join(final String a, final String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " " + b;
    }
}
