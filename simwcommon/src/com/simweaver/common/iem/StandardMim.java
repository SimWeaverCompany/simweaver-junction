package com.simweaver.common.iem;

/**
 * The built-in standard MIM used when no MIM is supplied through {@link IemOptions#setStandardMim(String)}.
 * <p>
 * It is expressed as an ordinary OMT document and goes through exactly the same parser and merge
 * rules as user modules. It contains:
 * <ul>
 * <li>{@code HLAobjectRoot} with {@code HLAprivilegeToDeleteObject}, and {@code HLAinteractionRoot}</li>
 * <li>the predefined basic representations, plus the HLA 4 unsigned ones for IEEE 1516-2025</li>
 * <li>the predefined simple, enumerated and array datatypes that FOMs commonly reference
 *     (HLAASCIIstring, HLAunicodeString, HLAboolean, HLAopaqueData, HLAinteger64Time, ...)</li>
 * </ul>
 * It deliberately does <em>not</em> contain the HLAmanager (MOM) object and interaction classes or the
 * MOM-only datatypes; supply the RTI's {@code HLAstandardMIM.xml} for those.
 */
final class StandardMim {

    static final String LABEL = "<built-in MIM>";

    private StandardMim() {
    }

    static String xml(HlaVersion version) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<objectModel xmlns=\"").append(version.getXmlNamespace()).append("\">\n");
        sb.append(" <modelIdentification><name>HLAstandardMIM</name><type>FOM</type><version>")
                .append(version == HlaVersion.HLA_4 ? "2025" : "2010")
                .append("</version><description>Built-in subset of the HLA standard MIM (roots and predefined"
                        + " datatypes)</description></modelIdentification>\n");
        // ---------------------------------------------------------------- classes
        sb.append(" <objects><objectClass><name>HLAobjectRoot</name><sharing>Neither</sharing>"
                + "<semantics>Root of the object class hierarchy</semantics>"
                + "<attribute><name>HLAprivilegeToDeleteObject</name><dataType>HLAtoken</dataType>"
                + "<updateType>Static</updateType><updateCondition>NA</updateCondition>"
                + "<ownership>DivestAcquire</ownership><sharing>PublishSubscribe</sharing>"
                + "<transportation>HLAreliable</transportation><order>TimeStamp</order>"
                + "<semantics>Privilege to delete the object instance</semantics></attribute>"
                + "</objectClass></objects>\n");
        sb.append(" <interactions><interactionClass><name>HLAinteractionRoot</name><sharing>Neither</sharing>"
                + "<transportation>HLAreliable</transportation><order>Receive</order>"
                + "<semantics>Root of the interaction class hierarchy</semantics></interactionClass>"
                + "</interactions>\n");
        // ---------------------------------------------------------------- datatypes
        sb.append(" <dataTypes>\n  <basicDataRepresentations>\n");
        for (String e : new String[] {"BE", "LE"}) {
            String endian = "BE".equals(e) ? "Big" : "Little";
            basic(sb, "HLAinteger16" + e, 16, "Integer in the range [-2^15, 2^15 - 1]", endian,
                    "16-bit two's complement signed integer");
            basic(sb, "HLAinteger32" + e, 32, "Integer in the range [-2^31, 2^31 - 1]", endian,
                    "32-bit two's complement signed integer");
            basic(sb, "HLAinteger64" + e, 64, "Integer in the range [-2^63, 2^63 - 1]", endian,
                    "64-bit two's complement signed integer");
            basic(sb, "HLAfloat32" + e, 32, "Single-precision floating point number", endian,
                    "32-bit IEEE 754 normalized single-precision format");
            basic(sb, "HLAfloat64" + e, 64, "Double-precision floating point number", endian,
                    "64-bit IEEE 754 normalized double-precision format");
            basic(sb, "HLAoctetPair" + e, 16, "16-bit value", endian, "Assumed to be portable among devices");
        }
        basic(sb, "HLAoctet", 8, "8-bit value", "Big", "Assumed to be portable among hardware devices");
        if (version == HlaVersion.HLA_4) {
            basic(sb, "HLAunsignedInteger8", 8, "Integer in the range [0, 2^8 - 1]", "Big", "8-bit unsigned integer");
            for (String e : new String[] {"BE", "LE"}) {
                String endian = "BE".equals(e) ? "Big" : "Little";
                basic(sb, "HLAunsignedInteger16" + e, 16, "Integer in the range [0, 2^16 - 1]", endian,
                        "16-bit unsigned integer");
                basic(sb, "HLAunsignedInteger32" + e, 32, "Integer in the range [0, 2^32 - 1]", endian,
                        "32-bit unsigned integer");
                basic(sb, "HLAunsignedInteger64" + e, 64, "Integer in the range [0, 2^64 - 1]", endian,
                        "64-bit unsigned integer");
            }
        }
        sb.append("  </basicDataRepresentations>\n  <simpleDataTypes>\n");
        simple(sb, "HLAASCIIchar", "HLAoctet", "Standard ASCII character (see ANSI Std x3.4-1986)");
        simple(sb, "HLAunicodeChar", "HLAoctetPairBE", "Unicode UTF-16 character (see The Unicode Standard)");
        simple(sb, "HLAbyte", "HLAoctet", "Uninterpreted 8-bit byte");
        simple(sb, "HLAcount", "HLAinteger32BE", "Count");
        simple(sb, "HLAseconds", "HLAinteger32BE", "Time in seconds");
        simple(sb, "HLAmsec", "HLAinteger32BE", "Time in milliseconds");
        simple(sb, "HLAindex", "HLAinteger32BE", "Index");
        simple(sb, "HLAinteger64Time", "HLAinteger64BE", "Standard integer time representation");
        simple(sb, "HLAfloat64Time", "HLAfloat64BE", "Standard floating point time representation");
        sb.append("  </simpleDataTypes>\n  <enumeratedDataTypes>\n");
        sb.append("   <enumeratedData><name>HLAboolean</name><representation>HLAinteger32BE</representation>"
                + "<semantics>Standard boolean type</semantics>"
                + "<enumerator><name>HLAfalse</name><value>0</value></enumerator>"
                + "<enumerator><name>HLAtrue</name><value>1</value></enumerator></enumeratedData>\n");
        sb.append("  </enumeratedDataTypes>\n  <arrayDataTypes>\n");
        array(sb, "HLAASCIIstring", "HLAASCIIchar", "ASCII string representation");
        array(sb, "HLAunicodeString", "HLAunicodeChar", "Unicode string representation");
        array(sb, "HLAopaqueData", "HLAbyte", "Uninterpreted sequence of bytes");
        array(sb, "HLAtoken", "HLAbyte", "Token");
        array(sb, "HLAhandle", "HLAbyte", "Encoded value of a handle");
        array(sb, "HLAtransportationName", "HLAunicodeChar", "Transportation type name");
        array(sb, "HLAupdateRateName", "HLAunicodeChar", "Update rate name");
        array(sb, "HLAlogicalTime", "HLAbyte", "Encoded logical time");
        array(sb, "HLAtimeInterval", "HLAbyte", "Encoded logical time interval");
        array(sb, "HLAhandleList", "HLAhandle", "List of encoded handles");
        sb.append("  </arrayDataTypes>\n </dataTypes>\n</objectModel>\n");
        return sb.toString();
    }

    private static void basic(StringBuilder sb, String name, int size, String interp, String endian, String enc) {
        sb.append("   <basicData><name>").append(name).append("</name><size>").append(size)
                .append("</size><interpretation>").append(interp).append("</interpretation><endian>")
                .append(endian).append("</endian><encoding>").append(enc).append("</encoding></basicData>\n");
    }

    private static void simple(StringBuilder sb, String name, String rep, String sem) {
        sb.append("   <simpleData><name>").append(name).append("</name><representation>").append(rep)
                .append("</representation><units>NA</units><resolution>NA</resolution><accuracy>NA</accuracy>"
                        + "<semantics>").append(sem).append("</semantics></simpleData>\n");
    }

    private static void array(StringBuilder sb, String name, String element, String sem) {
        sb.append("   <arrayData><name>").append(name).append("</name><dataType>").append(element)
                .append("</dataType><cardinality>Dynamic</cardinality><encoding>HLAvariableArray</encoding>"
                        + "<semantics>").append(sem).append("</semantics></arrayData>\n");
    }
}
