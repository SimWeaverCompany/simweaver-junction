package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.Reference;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.hla.decoders.ValueKind;
import com.simweaver.common.iem.ClassMember;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.MemberOnClass;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * RPR identifiers as text, SITE-APP-INSTANCE with dashes by default:
 * <ul>
 *   <li>{@code FederateIdentifierStruct} (SiteID, ApplicationID): {@code 205-3}</li>
 *   <li>{@code EntityIdentifierStruct} (FederateIdentifier, EntityNumber): {@code 205-3-42}</li>
 *   <li>{@code EventIdentifierStruct} (EventCount, IssuingObjectIdentifier):
 *       {@code HLAobject_17_42/17}</li>
 * </ul>
 * An {@code EntityIdentifierStruct} is tagged as an {@link Reference#ENTITY_ID} reference
 * (key always {@code site-app-entity} with dashes) except when it is the object's own
 * identity attribute ({@code BaseEntity.EntityIdentifier}, {@code Minefield.MinefieldIdentifier},
 * {@code EnvironmentObject.ObjectIdentifier}, {@code EnvironmentProcess.ProcessIdentifier},
 * {@code GriddedData.GridIdentifier}) or the null id {@code 0-0-0}.
 */
final class IdentifierFormatter implements ValueFormatter {

    /** Identity attributes: declaring class (without root) + '.' + attribute name. */
    static final Set<String> IDENTITY_ATTRIBUTES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "BaseEntity.EntityIdentifier",
        "Minefield.MinefieldIdentifier",
        "EnvironmentObject.ObjectIdentifier",
        "EnvironmentProcess.ProcessIdentifier",
        "GriddedData.GridIdentifier")));

    private static final String FEDERATE = "FederateIdentifierStruct";
    private static final String ENTITY = "EntityIdentifierStruct";
    private static final String EVENT = "EventIdentifierStruct";

    private final String separator;
    private final boolean references;

    IdentifierFormatter(String separator, boolean references) {
        this.separator = separator;
        this.references = references;
    }

    @Override
    public Collection<String> getDataTypeNames() {
        return Arrays.asList(FEDERATE, ENTITY, EVENT);
    }

    @Override
    public boolean accepts(DataType type) {
        FixedRecordDataType r = Shapes.record(type, 2);
        if (r == null) {
            return false;
        }
        String n = type.getName();
        if (FEDERATE.equals(n)) {
            return Shapes.isInteger(Shapes.field(r, 0), 2) && Shapes.isInteger(Shapes.field(r, 1), 2);
        }
        if (ENTITY.equals(n)) {
            DataType fed = Shapes.field(r, 0);
            FixedRecordDataType f = Shapes.record(fed, 2);
            return f != null && Shapes.isInteger(Shapes.field(f, 0), 2) && Shapes.isInteger(Shapes.field(f, 1), 2)
                && Shapes.isInteger(Shapes.field(r, 1), 2);
        }
        if (EVENT.equals(n)) {
            return Shapes.isInteger(Shapes.field(r, 0), 4) && Shapes.isCharArray(Shapes.field(r, 1));
        }
        return false;
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (value.hasError() || value.getChildren().size() != 2) {
            return value;
        }
        String n = value.getDataTypeName();
        if (FEDERATE.equals(n)) {
            return value.toBuilder().text(u16(value, 0) + separator + u16(value, 1)).build();
        }
        if (ENTITY.equals(n)) {
            DecodedValue fed = value.getChildren().get(0);
            if (fed.getChildren().size() != 2) {
                return value;
            }
            long site = u16(fed, 0);
            long app = u16(fed, 1);
            long num = u16(value, 1);
            DecodedValue.Builder b = value.toBuilder().text(site + separator + app + separator + num);
            if (references && !(site == 0 && app == 0 && num == 0) && !isIdentity(ctx)) {
                b.reference(Reference.of(Reference.ENTITY_ID, entityKey(site, app, num)));
            }
            return b.build();
        }
        if (EVENT.equals(n)) {
            DecodedValue issuer = value.getChildren().get(1);
            String who = issuer.getString() != null ? stripNul(issuer.getString()) : issuer.getText();
            return value.toBuilder().text(who + "/" + value.getChildren().get(0).getLong()).build();
        }
        return value;
    }

    /**
     * @param site site id
     * @param app  application id
     * @param num  entity number
     * @return the reference key, always with dashes
     */
    static String entityKey(long site, long app, long num) {
        return site + "-" + app + "-" + num;
    }

    private static long u16(DecodedValue v, int i) {
        return v.getChildren().get(i).getLong() & 0xffffL;
    }

    private static boolean isIdentity(FormatContext ctx) {
        MemberOnClass<?, ?> m = ctx.getMember();
        if (m == null || ctx.getDepth() != 0) {
            return false;
        }
        ClassMember d = m.getDeclaration();
        String key = d.getDeclaringClass().getQualifiedNameWithoutRoot() + "." + d.getName();
        return IDENTITY_ATTRIBUTES.contains(key);
    }

    static String stripNul(String s) {
        int end = s.indexOf('\0');
        return end < 0 ? s : s.substring(0, end);
    }

    static boolean isText(DecodedValue v) {
        return v.getKind() == ValueKind.TEXT && v.getString() != null;
    }
}
