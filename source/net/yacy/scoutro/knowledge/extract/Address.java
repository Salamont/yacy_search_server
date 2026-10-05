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
