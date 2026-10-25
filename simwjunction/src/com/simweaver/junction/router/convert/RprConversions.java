package com.simweaver.junction.router.convert;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.TranscodeRules;
import com.simweaver.common.iem.FomFamily;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.router.ConversionRules;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The RPR FOM v2.0 / v3.0 conversion rules, from {@code etc/junctions/mappings/rpr2-rpr3.mapping}
 * (both directions from one file; v2 on the left, v3 on the right):
 *
 * <pre>
 * class.HLAinteractionRoot.WeaponFire = HLAinteractionRoot.Fire.WeaponFire
 * attribute.HLAobjectRoot.BaseEntity.PhysicalEntity.CulturalFeature.ExternalLightsOn = ExteriorLightsOn
 * parameter.HLAinteractionRoot.MinefieldQuery.RequestingEntityIdentifier = RequestingIdentifier
 * field.SpatialFPStruct.WorldLocation = SpatialFPStruct.Location
 * default.SpatialFPStruct.OtherParameters =
 * enum.DamageStatusEnum32.3 = AppearanceDamageEnum8.3
 * </pre>
 *
 * <ul>
 *   <li>{@code class}: object or interaction class (both ways; when several v2 classes map to
 *       one v3 class, the last line gives the way back); {@code classup.} v2 to v3 only,
 *       {@code classdown.<v3 class> = <v2 class>} v3 to v2 only.</li>
 *   <li>{@code attribute} / {@code parameter}: on that class and its subclasses.</li>
 *   <li>{@code field}: a record field renamed (datatype names on each side).</li>
 *   <li>{@code default}: a v3 field without a v2 source (written as its default, no warning);
 *       {@code default2.} the same for a v2 field without a v3 source.</li>
 *   <li>{@code enum}: one enumerator value mapped (when the name does not match).</li>
 * </ul>
 * Without a rule, names stay the same; enumerators are matched by name, then by value.
 *
 * <p>Models are recognised by their FOM family ({@link FomFamily#RPR_FOM_V2} /
 * {@link FomFamily#RPR_FOM_V3}); DIS endpoints are RPR v2 models. Thread safe; the file is read
 * again when it changes.
 */
public final class RprConversions implements ConversionRules.Provider {

    private static final Logger LOG = Logger.getLogger(RprConversions.class.getName());

    /** File name of the mapping. */
    public static final String FILE = "rpr2-rpr3.mapping";

    private final File file;
    private volatile long loadedModified = -1;
    private volatile Rules up = new Rules("RPR v2.0 -> v3.0 (no mapping file)");
    private volatile Rules down = new Rules("RPR v3.0 -> v2.0 (no mapping file)");

    /** @param mappingsDir the mappings folder */
    public RprConversions(File mappingsDir) {
        this.file = new File(mappingsDir, FILE);
        reloadIfChanged();
    }

    @Override
    public ConversionRules rulesFor(InformationExchangeModel source, InformationExchangeModel destination) {
        boolean s2 = source.hasFomFamily(FomFamily.RPR_FOM_V2);
        boolean s3 = source.hasFomFamily(FomFamily.RPR_FOM_V3);
        boolean d2 = destination.hasFomFamily(FomFamily.RPR_FOM_V2);
        boolean d3 = destination.hasFomFamily(FomFamily.RPR_FOM_V3);
        if (s2 && !s3 && d3 && !d2) {
            reloadIfChanged();
            return up;
        }
        if (s3 && !s2 && d2 && !d3) {
            reloadIfChanged();
            return down;
        }
        return ConversionRules.NONE;
    }

    /** @return the v2 to v3 rules */
    public ConversionRules upgrade() {
        reloadIfChanged();
        return up;
    }

    /** @return the v3 to v2 rules */
    public ConversionRules downgrade() {
        reloadIfChanged();
        return down;
    }

    private synchronized void reloadIfChanged() {
        long m = file.isFile() ? file.lastModified() : 0;
        if (m == loadedModified) {
            return;
        }
        loadedModified = m;
        if (m == 0) {
            Rules u = new Rules("RPR v2.0 -> v3.0 (no mapping file: same names)");
            Rules d = new Rules("RPR v3.0 -> v2.0 (no mapping file: same names)");
            TranscodeRules.Builder tu = TranscodeRules.builder();
            TranscodeRules.Builder td = TranscodeRules.builder();
            RprStructuralAdapters.register(tu, td);
            u.transcode = tu.build();
            d.transcode = td.build();
            up = u;
            down = d;
            return;
        }
        SimwProperties p = new SimwProperties();
        try {
            p.load(file);
        } catch (IOException e) {
            LOG.warning("Junction: cannot read " + file + ": " + e.getMessage());
            return;
        }
        Rules u = new Rules("RPR v2.0 -> v3.0 (" + file.getName() + ")");
        Rules d = new Rules("RPR v3.0 -> v2.0 (" + file.getName() + ")");
        TranscodeRules.Builder tu = TranscodeRules.builder();
        TranscodeRules.Builder td = TranscodeRules.builder();
        RprStructuralAdapters.register(tu, td);
        int count = 0;
        for (String key : p.getKeys()) {
            String v = p.getString(key, "").trim();
            try {
                if (key.startsWith("classup.")) {
                    u.classes.put(key.substring(8), v);                   // v2 -> v3 only
                } else if (key.startsWith("classdown.")) {
                    d.classes.put(key.substring(10), v);                  // v3 -> v2 only (v3 class on the left)
                } else if (key.startsWith("class.")) {
                    String a = key.substring(6);
                    u.classes.put(a, v);
                    d.classes.put(v, a);
                } else if (key.startsWith("attribute.") || key.startsWith("parameter.")) {
                    boolean attr = key.startsWith("attribute.");
                    String rest = key.substring("attribute.".length());     // same length as "parameter."
                    int dot = rest.lastIndexOf('.');
                    String cls = rest.substring(0, dot);
                    String member = rest.substring(dot + 1);
                    (attr ? u.attributes : u.parameters).put(cls + "#" + member, v);
                    // the reverse is keyed by the v3 class name, known only after all class lines: below
                    d.pendingMembers.add(new String[] {attr ? "a" : "p", cls, member, v});
                } else if (key.startsWith("field.")) {
                    String[] left = split2(key.substring(6));
                    String[] right = split2(v);
                    tu.field(right[0], right[1], left[1]);          // v3 field takes the v2 field
                    td.field(left[0], left[1], right[1]);           // v2 field takes the v3 field
                } else if (key.startsWith("default2.")) {
                    String[] f = split2(key.substring(9));
                    td.defaulted(f[0], f[1]);
                } else if (key.startsWith("default.")) {
                    String[] f = split2(key.substring(8));
                    tu.defaulted(f[0], f[1]);
                } else if (key.startsWith("enum.")) {
                    String rest = key.substring(5);
                    int dot = rest.lastIndexOf('.');
                    String[] r = split2(v);
                    long from = Long.parseLong(rest.substring(dot + 1));
                    long to = Long.parseLong(r[1]);
                    tu.enumValue(rest.substring(0, dot), r[0], from, to);
                    td.enumValue(r[0], rest.substring(0, dot), to, from);
                } else {
                    continue;
                }
                count++;
            } catch (RuntimeException e) {
                LOG.warning("Junction: " + file.getName() + ": cannot use '" + key + "': " + e.getMessage());
            }
        }
        // member rules of the v3 side, keyed by the v3 class name (now that all classes are known)
        for (String[] pm : d.pendingMembers) {
            String v3cls = u.classes.containsKey(pm[1]) ? u.classes.get(pm[1]) : pm[1];
            ("a".equals(pm[0]) ? d.attributes : d.parameters).put(v3cls + "#" + pm[3], pm[2]);
        }
        d.pendingMembers.clear();
        u.transcode = tu.build();
        d.transcode = td.build();
        up = u;
        down = d;
        LOG.info("Junction: " + count + " RPR v2/v3 conversion rules from " + file);
    }

    private static String[] split2(String typeDotField) {
        int dot = typeDotField.lastIndexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("expected <datatype>.<field>: " + typeDotField);
        }
        return new String[] {typeDotField.substring(0, dot).trim(), typeDotField.substring(dot + 1).trim()};
    }

    /** One direction. */
    private static final class Rules implements ConversionRules {
        final String description;
        final Map<String, String> classes = new HashMap<String, String>();
        final Map<String, String> attributes = new HashMap<String, String>();
        final Map<String, String> parameters = new HashMap<String, String>();
        final List<String[]> pendingMembers = new java.util.ArrayList<String[]>();
        volatile TranscodeRules transcode = TranscodeRules.NONE;

        Rules(String description) {
            this.description = description;
        }

        @Override
        public String objectClass(String source) {
            return classes.get(source);
        }

        @Override
        public String interactionClass(String source) {
            return classes.get(source);
        }

        @Override
        public String attribute(String sourceClass, String attribute) {
            return attributes.get(sourceClass + "#" + attribute);
        }

        @Override
        public String parameter(String sourceClass, String parameter) {
            return parameters.get(sourceClass + "#" + parameter);
        }

        @Override
        public TranscodeRules transcodeRules() {
            return transcode;
        }

        @Override
        public String describe() {
            return description;
        }
    }

    /** @return an unmodifiable view for tests */
    Map<String, String> classMap(boolean upward) {
        return Collections.unmodifiableMap(upward ? up.classes : down.classes);
    }
}
