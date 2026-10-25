package com.simweaver.junction.router;

import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.ClassFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link RouteTable}s of a gateway once at start: for every ordered pair of
 * endpoints, which classes go where and what happens to each member (see the design, §5-6).
 * Also writes the compatibility report the page shows.
 *
 * <p>A class passes from S to D when the router filter and S's in-filter allow its name and
 * D has the class (after renaming) and D's out-filter allows it. When D lacks it (or blocks
 * it) and promotion is on, the nearest superclass that passes is used. A member passes when
 * the destination class has it (after renaming); equal encoding signatures pass the octets
 * as they are, otherwise they are transcoded.
 */
public final class RouteCompiler {

    /** Never bridged: owned by the RTI. */
    private static final String PRIVILEGE_TO_DELETE = "HLAprivilegeToDeleteObject";
    /** Entries per report list (raised by tools). */
    static int REPORT_CAP = 300;

    /** One endpoint as the compiler sees it. */
    public static final class Side {
        final String id;
        final ModelTools tools;
        final ClassFilter in;
        final ClassFilter out;
        final ValueMap values;

        /**
         * @param id    endpoint id
         * @param tools its model tools
         * @param in    its in-filter (what it contributes)
         * @param out   its out-filter (what it receives)
         */
        public Side(String id, ModelTools tools, ClassFilter in, ClassFilter out) {
            this(id, tools, in, out, ValueMap.NONE);
        }

        /**
         * @param values the router's value rules in this endpoint's model (applied to what it contributes)
         */
        public Side(String id, ModelTools tools, ClassFilter in, ClassFilter out, ValueMap values) {
            this.id = id;
            this.tools = tools;
            this.in = in == null ? ClassFilter.ALL : in;
            this.out = out == null ? ClassFilter.ALL : out;
            this.values = values == null ? ValueMap.NONE : values;
        }
    }

    private final ClassFilter filter;
    private final boolean promote;
    private final ConversionRules.Provider rules;

    /**
     * @param filter  the router's class filter
     * @param promote use the nearest passing superclass when a class does not pass
     * @param rules   renames per pair of models
     */
    public RouteCompiler(ClassFilter filter, boolean promote, ConversionRules.Provider rules) {
        this.filter = filter == null ? ClassFilter.ALL : filter;
        this.promote = promote;
        this.rules = rules == null ? ConversionRules.Provider.NONE : rules;
    }

    /**
     * @param s source
     * @param d destination
     * @return the routes from s to d, with the report
     */
    public RouteTable compile(Side s, Side d) {
        ConversionRules r = rules.rulesFor(s.tools.model(), d.tools.model());
        RouteTable t = new RouteTable(s.id, d.id, r.describe());
        InformationExchangeModel sm = s.tools.model();
        InformationExchangeModel dm = d.tools.model();

        for (ObjectClass c : sm.getObjectClasses()) {
            if (c.isRoot()) {
                continue;
            }
            String name = c.getQualifiedName();
            if (!filter.allowsObjectClass(name) || !s.in.allowsObjectClass(name)) {
                t.report.blocked(name);
                continue;
            }
            String mapped = nvl(r.objectClass(name), name);
            ObjectClass dc = dm.getObjectClass(mapped);
            boolean promoted = false;
            while (dc == null || dc.isRoot() || !d.out.allowsObjectClass(dc.getQualifiedName())) {
                if (!promote) {
                    dc = null;
                    break;
                }
                if (dc == null) {
                    int dot = mapped.lastIndexOf('.');
                    if (dot < 0) {
                        break;
                    }
                    mapped = mapped.substring(0, dot);
                    dc = dm.getObjectClass(mapped);
                } else {
                    dc = dc.getParentClass();
                }
                promoted = true;
                if (dc != null && dc.isRoot()) {
                    dc = null;
                    break;
                }
            }
            if (dc == null) {
                t.report.noCounterpart(name);
                continue;
            }
            ClassRoute cr = new ClassRoute(c.getId(), dc.getId(), name, dc.getQualifiedName(), promoted);
            for (ClassAttribute sa : c.getAttributes()) {
                if (PRIVILEGE_TO_DELETE.equals(sa.getName())) {
                    continue;
                }
                String dn = memberName(r, c, sa.getName(), true);
                ClassAttribute da = dc.getAttribute(dn);
                if (da == null) {
                    t.report.memberDropped(name + "." + sa.getName(), "no attribute " + dn + " on " + dc.getQualifiedName());
                    continue;
                }
                MemberRoute mr = member(sa.getId(), da.getId(), name + "." + sa.getName(), dc.getQualifiedName() + "." + da.getName(),
                        sa.getDataType(), da.getDataType(), s, d.tools, r, t.report);
                if (mr != null) {
                    cr.add(mr);
                }
            }
            if (cr.isEmpty()) {
                t.report.noCounterpart(name + " (no attribute passes)");
                continue;
            }
            t.objects.put(c.getId(), cr);
            t.report.objectRoute(cr);
        }

        for (InteractionClass c : sm.getInteractionClasses()) {
            if (c.isRoot()) {
                continue;
            }
            String name = c.getQualifiedName();
            if (!filter.allowsInteractionClass(name) || !s.in.allowsInteractionClass(name)) {
                t.report.blocked(name);
                continue;
            }
            String mapped = nvl(r.interactionClass(name), name);
            InteractionClass dc = dm.getInteractionClass(mapped);
            boolean promoted = false;
            while (dc == null || dc.isRoot() || !d.out.allowsInteractionClass(dc.getQualifiedName())) {
                if (!promote) {
                    dc = null;
                    break;
                }
                if (dc == null) {
                    int dot = mapped.lastIndexOf('.');
                    if (dot < 0) {
                        break;
                    }
                    mapped = mapped.substring(0, dot);
                    dc = dm.getInteractionClass(mapped);
                } else {
                    dc = dc.getParentClass();
                }
                promoted = true;
                if (dc != null && dc.isRoot()) {
                    dc = null;
                    break;
                }
            }
            if (dc == null) {
                t.report.noCounterpart(name);
                continue;
            }
            ClassRoute cr = new ClassRoute(c.getId(), dc.getId(), name, dc.getQualifiedName(), promoted);
            for (ClassParameter sp : c.getParameters()) {
                String dn = memberName(r, c, sp.getName(), false);
                ClassParameter dp = dc.getParameter(dn);
                if (dp == null) {
                    t.report.memberDropped(name + "." + sp.getName(), "no parameter " + dn + " on " + dc.getQualifiedName());
                    continue;
                }
                MemberRoute mr = member(sp.getId(), dp.getId(), name + "." + sp.getName(), dc.getQualifiedName() + "." + dp.getName(),
                        sp.getDataType(), dp.getDataType(), s, d.tools, r, t.report);
                if (mr != null) {
                    cr.add(mr);
                }
            }
            // an interaction without parameters still means something (e.g. StartResume subclasses)
            t.interactions.put(c.getId(), cr);
            t.report.interactionRoute(cr);
        }
        return t;
    }

    private static String nvl(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    /** The destination member name: a rule for the class or one of its superclasses, else the same name. */
    private static String memberName(ConversionRules r, Object cls, String member, boolean attribute) {
        Object c = cls;
        while (c != null) {
            String qn = c instanceof ObjectClass ? ((ObjectClass) c).getQualifiedName() : ((InteractionClass) c).getQualifiedName();
            String n = attribute ? r.attribute(qn, member) : r.parameter(qn, member);
            if (n != null && !n.isEmpty()) {
                return n;
            }
            c = c instanceof ObjectClass ? ((ObjectClass) c).getParentClass() : ((InteractionClass) c).getParentClass();
        }
        return member;
    }

    private static MemberRoute member(int sid, int did, String sname, String dname, DataType st, DataType dt,
                                      Side side, ModelTools d, ConversionRules r, Report report) {
        ModelTools s = side.tools;
        if (st == null || dt == null) {
            report.memberDropped(sname, "datatype not resolved");
            return null;
        }
        String ss = s.analysis().signature(st);
        String ds = d.analysis().signature(dt);
        MemberRoute.Op op;
        com.simweaver.common.hla.codec.TranscodeRules tr = r.transcodeRules();
        boolean valueRule = tr != null && tr.changesValues(st.getName(), dt.getName());   // e.g. a value map between two 8-bit enums
        if (ss.equals(ds) && !valueRule) {
            op = s.analysis().containsReference(st) ? MemberRoute.Op.REWRITE_REFS : MemberRoute.Op.PASS;
        } else {
            op = MemberRoute.Op.TRANSCODE;
            report.transcoded(sname, st.getName() + " -> " + dt.getName());
        }
        MemberRoute mr = new MemberRoute(sid, did, sname, dname, op, st, dt, s, d, r.transcodeRules(), side.values);
        if (mr.hasValueRules()) {
            report.valueRules(sname, st.getName());
        }
        return mr;
    }

    // ------------------------------------------------------------------ report

    /** What a route table does, for the page. */
    public static final class Report implements Json.Writable {
        int objectRoutes;
        int interactionRoutes;
        int passMembers;
        int rewriteMembers;
        int transcodeMembers;
        final List<String> blocked = new ArrayList<String>();
        final List<String> noCounterpart = new ArrayList<String>();
        final List<String> promoted = new ArrayList<String>();
        final Map<String, String> droppedMembers = new LinkedHashMap<String, String>();
        final Map<String, String> transcodedMembers = new LinkedHashMap<String, String>();
        final Map<String, String> valueRuleMembers = new LinkedHashMap<String, String>();
        int valueRuleCount;
        int blockedCount;
        int noCounterpartCount;
        int droppedCount;

        void blocked(String n) {
            blockedCount++;
            if (blocked.size() < REPORT_CAP) {
                blocked.add(n);
            }
        }

        void noCounterpart(String n) {
            noCounterpartCount++;
            if (noCounterpart.size() < REPORT_CAP) {
                noCounterpart.add(n);
            }
        }

        void memberDropped(String n, String why) {
            droppedCount++;
            if (droppedMembers.size() < REPORT_CAP) {
                droppedMembers.put(n, why);
            }
        }

        void transcoded(String n, String how) {
            if (transcodedMembers.size() < REPORT_CAP) {
                transcodedMembers.put(n, how);
            }
        }

        void valueRules(String n, String type) {
            valueRuleCount++;
            if (valueRuleMembers.size() < REPORT_CAP) {
                valueRuleMembers.put(n, type);
            }
        }

        void objectRoute(ClassRoute cr) {
            objectRoutes++;
            count(cr);
        }

        void interactionRoute(ClassRoute cr) {
            interactionRoutes++;
            count(cr);
        }

        private void count(ClassRoute cr) {
            if (cr.promoted && promoted.size() < REPORT_CAP) {
                promoted.add(cr.sourceName + " -> " + cr.destinationName);
            }
            for (MemberRoute m : cr.members()) {
                switch (m.op) {
                    case PASS:
                        passMembers++;
                        break;
                    case REWRITE_REFS:
                        rewriteMembers++;
                        break;
                    default:
                        transcodeMembers++;
                }
            }
        }

        @Override
        public Object toJson() {
            return Json.object()
                    .put("objectclasses", objectRoutes)
                    .put("interactionclasses", interactionRoutes)
                    .put("members", Json.object().put("pass", passMembers).put("rewritereferences", rewriteMembers)
                            .put("transcode", transcodeMembers).put("dropped", droppedCount).put("valuerules", valueRuleCount))
                    .put("blockedcount", blockedCount)
                    .put("blocked", blocked)
                    .put("nocounterpartcount", noCounterpartCount)
                    .put("nocounterpart", noCounterpart)
                    .put("promoted", promoted)
                    .put("droppedmembers", droppedMembers)
                    .put("transcodedmembers", transcodedMembers)
                    .put("valuerulemembers", valueRuleMembers);
        }
    }
}
