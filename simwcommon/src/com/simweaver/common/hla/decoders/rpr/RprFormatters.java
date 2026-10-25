package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecoderOptions;
import com.simweaver.common.hla.decoders.ValueFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The RPR-FOM (v2 and v3) formatters and count rules. They are keyed on datatype names with a
 * shape check, so they also apply to NETN and other FOMs that build on RPR datatypes, and stay
 * out of the way when a FOM defines a datatype with the same name but another structure.
 *
 * <ul>
 *   <li>{@code MarkingStruct}: the marking as plain text.</li>
 *   <li>{@code EntityTypeStruct}: {@code 1.3.203.3.3.3.0} (or the generic record display).</li>
 *   <li>{@code EntityIdentifierStruct} {@code 205-3-42}, {@code FederateIdentifierStruct}
 *       {@code 205-3}, {@code EventIdentifierStruct} {@code issuer/17}.</li>
 *   <li>{@code WorldLocationStruct} (v2) / {@code LocationStruct} (v3): latitude, longitude and
 *       height derived from ECEF.</li>
 *   <li>{@code SpatialVariantStruct}: position, local heading/pitch/roll, NED and body
 *       vectors, speeds; the kinematic state is attached for "now" values ({@link RprSpatial}).</li>
 *   <li>References (contract 4.1): {@code RTIobjectId} / {@code RTIobjectIdArray} as object
 *       names, {@code EntityIdentifierStruct} as entity ids except on identity attributes.</li>
 *   <li>Link 16 (RPR v2 Link-16 BOM): {@code JTIDSHeaderStruct} unpacked, {@code TADILJWordStruct}
 *       split into J-series messages ({@code J3.2 Air Track (3 words)}), {@code NPGNumber} named
 *       ({@link Link16}; identification only, no MIL-STD-6016 fields).</li>
 * </ul>
 */
public final class RprFormatters {

    /** How {@code EntityTypeStruct} is shown. */
    public enum EntityTypeText {
        /** {@code 1.3.203.3.3.3.0}. */
        DOTTED,
        /** The generic record display ({@code 1, 3, 203, 3, 3, 3, 0}). */
        STRUCT
    }

    /** Count rule for {@code SilentEntityStruct.EntityAppearance} (RPR v2 note). */
    public static final String SILENT_ENTITY_APPEARANCE_RULE = "NumberOfAppearanceRecords";
    /** Count rule for {@code SphericalHarmonicAntennaStruct.Coefficients} (RPR v2 note). */
    public static final String SPHERICAL_HARMONIC_COEFFICIENTS_RULE = "Order*Order+2*Order+1";

    private final String entityIdSeparator;
    private final EntityTypeText entityTypeText;
    private final boolean references;
    private final boolean link16;
    private final Link16.BitOrder link16BitOrder;

    private RprFormatters(Builder b) {
        this.entityIdSeparator = b.entityIdSeparator;
        this.entityTypeText = b.entityTypeText;
        this.references = b.references;
        this.link16 = b.link16;
        this.link16BitOrder = b.link16BitOrder;
    }

    /** @return a builder with the defaults (dashes, dotted entity types, references on) */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Adds the default RPR formatters and count rules to decoder options.
     *
     * @param options options builder
     * @return the same builder
     */
    public static DecoderOptions.Builder install(DecoderOptions.Builder options) {
        return builder().build().installInto(options);
    }

    /**
     * Adds these formatters and the RPR count rules to decoder options.
     *
     * @param options options builder
     * @return the same builder
     */
    public DecoderOptions.Builder installInto(DecoderOptions.Builder options) {
        options.formatters(formatters());
        options.countRule("SilentEntityStruct.EntityAppearance", SILENT_ENTITY_APPEARANCE_RULE);
        options.countRule("SphericalHarmonicAntennaStruct.Coefficients", SPHERICAL_HARMONIC_COEFFICIENTS_RULE);
        return options;
    }

    /** @return the formatters, in application order */
    public List<ValueFormatter> formatters() {
        List<ValueFormatter> l = new ArrayList<ValueFormatter>();
        l.add(new MarkingFormatter());
        l.add(new EntityTypeFormatter(entityTypeText));
        l.add(new IdentifierFormatter(entityIdSeparator, references));
        l.add(new LocationFormatter());
        l.add(new SpatialFormatter());
        if (references) {
            l.add(new ObjectNameFormatter());
        }
        if (link16) {
            l.add(new Link16Formatter(link16BitOrder));
        }
        return Collections.unmodifiableList(l);
    }

    /** @return separator between site, application and entity number */
    public String getEntityIdSeparator() {
        return entityIdSeparator;
    }

    /** @return entity type display */
    public EntityTypeText getEntityTypeText() {
        return entityTypeText;
    }

    /** @return how Link 16 J-words and JTIDS headers are unpacked */
    public Link16.BitOrder getLink16BitOrder() {
        return link16BitOrder;
    }

    /** Builder. */
    public static final class Builder {
        private String entityIdSeparator = "-";
        private EntityTypeText entityTypeText = EntityTypeText.DOTTED;
        private boolean references = true;
        private boolean link16 = true;
        private Link16.BitOrder link16BitOrder = Link16.BitOrder.AUTO;

        private Builder() {
        }

        /**
         * @param v separator for {@code site-app-entity}; dashes by default. Reference keys
         *          always use dashes.
         * @return this
         */
        public Builder entityIdSeparator(String v) {
            this.entityIdSeparator = v == null ? "-" : v;
            return this;
        }

        /** @param v entity type display @return this */
        public Builder entityTypeText(EntityTypeText v) {
            this.entityTypeText = v;
            return this;
        }

        /** @param v false to skip reference tagging @return this */
        public Builder references(boolean v) {
            this.references = v;
            return this;
        }

        /** @param v false to leave the Link 16 BOM values undecoded (raw octets) @return this */
        public Builder link16(boolean v) {
            this.link16 = v;
            return this;
        }

        /**
         * @param v bit packing of J-words and JTIDS headers; {@link Link16.BitOrder#AUTO} (default)
         *          picks the packing for which the words form complete messages
         * @return this
         */
        public Builder link16BitOrder(Link16.BitOrder v) {
            this.link16BitOrder = v == null ? Link16.BitOrder.AUTO : v;
            return this;
        }

        /** @return the formatter set */
        public RprFormatters build() {
            return new RprFormatters(this);
        }
    }
}
