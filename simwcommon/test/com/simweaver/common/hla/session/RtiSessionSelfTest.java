package com.simweaver.common.hla.session;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Self test of {@link RtiSession} and {@link RtiLibrary} against the test RTIs (fakerti-1516e.jar,
 * fakerti-2025.jar). Run it WITHOUT any HLA API on the class path; the RTIs are loaded only
 * through isolated libraries:
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.hla.session.RtiSessionSelfTest &lt;folder with the two jars&gt;</pre>
 */
public final class RtiSessionSelfTest {

    private static int checks;
    private static int failures;

    private RtiSessionSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "fakerti/build");
        File jar1516e = new File(dir, "fakerti-1516e.jar");
        File jar2025 = new File(dir, "fakerti-2025.jar");

        // the class path has no RTI: the class path library must say so, without throwing linkage errors
        RtiLibrary cp = RtiLibrary.classPath();
        check("no 1516e RTI on the class path", !cp.isAvailable(RtiSession.Version.HLA_1516E));
        try {
            cp.createSession(RtiSession.Version.HLA_1516E);
            check("class path session must fail", false);
        } catch (RtiSessionException e) {
            check("RtiNotAvailable on the class path", "RtiNotAvailable".equals(e.getRtiExceptionName()));
        }

        RtiLibrary lib3 = RtiLibrary.isolated(Collections.singletonList(jar1516e));
        RtiLibrary lib4 = RtiLibrary.fromSetting(jar2025.getName(), dir);
        check("same jars, same library", lib3 == RtiLibrary.isolated(Collections.singletonList(jar1516e)));
        check("1516e jar has 1516e", lib3.isAvailable(RtiSession.Version.HLA_1516E));
        check("1516e jar has no 2025", !lib3.isAvailable(RtiSession.Version.HLA_1516_2025));
        check("2025 jar has 2025", lib4.isAvailable(RtiSession.Version.HLA_1516_2025));
        try {
            lib3.createSession(RtiSession.Version.HLA_1516_2025);
            check("wrong version must fail", false);
        } catch (RtiSessionException e) {
            check("wrong version: RtiNotAvailable", "RtiNotAvailable".equals(e.getRtiExceptionName()));
        }
        try {
            RtiLibrary.fromSetting("does/not/exist.jar", dir);
            check("missing jar must fail", false);
        } catch (IllegalArgumentException e) {
            check("missing jar reported", e.getMessage().contains("not found"));
        }

        exercise("1516-2010", lib3, RtiSession.Version.HLA_1516E);
        exercise("1516-2025", lib4, RtiSession.Version.HLA_1516_2025);

        // both at once, in one JVM
        RtiSession a = lib3.createSession(RtiSession.Version.HLA_1516E);
        RtiSession b = lib4.createSession(RtiSession.Version.HLA_1516_2025);
        check("isolated loaders differ", a.getClass().getClassLoader() != b.getClass().getClassLoader());
        check("session class not from the application", a.getClass().getClassLoader() != RtiSessionSelfTest.class.getClassLoader());
        check("interface shared with the application", a instanceof RtiSession && b instanceof RtiSession);

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void exercise(String label, RtiLibrary lib, RtiSession.Version v) throws Exception {
        Rec ra = new Rec();
        Rec rb = new Rec();
        RtiSession a = lib.createSession(v);
        RtiSession b = lib.createSession(v);
        check(label + ": version", a.version() == v);
        a.connect(ConnectSettings.of("test"), ra);
        b.connect(ConnectSettings.defaults().withRtiAddress("localhost:15164"), rb);
        String fa = a.join("Fed-" + label, "Tester", "A", Collections.<String>emptyList(), true);
        String fb = b.join("Fed-" + label, "Tester", null, Collections.<String>emptyList(), false);
        check(label + ": joined with name", "A".equals(fa) && !fb.isEmpty());
        List<String> feds = b.listFederations(2000);
        check(label + ": federation listed " + feds, feds.contains("Fed-" + label));

        int clsA = a.objectClass("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform");
        int attrA = a.attribute(clsA, "Spatial");
        int attrA2 = a.attribute(clsA, "Marking");
        a.publishObjectClass(clsA, new int[] {attrA, attrA2});
        int clsB = b.objectClass("HLAobjectRoot.BaseEntity");
        int attrB = b.attribute(clsB, "Spatial");
        b.subscribeObjectClass(clsB, new int[] {attrB});

        a.reserveName("Tank-1");
        String[] res = ra.take("name");
        check(label + ": name reserved", res != null && res[1].equals("Tank-1") && res[2].equals("true"));
        a.reserveName("Tank-1");
        res = ra.take("name");
        check(label + ": second reservation fails", res != null && res[2].equals("false"));
        long inst = a.registerObject(clsA, "Tank-1");
        check(label + ": object name", "Tank-1".equals(a.objectName(inst)));
        String[] d = rb.take("discovered");
        check(label + ": discovered as known class", d != null && d[2].equals(String.valueOf(clsB)) && d[3].equals("Tank-1") && d[4].equals("A"));

        a.updateAttributes(inst, new int[] {attrA, attrA2}, new byte[][] {{1, 2, 3}, {9}}, new byte[] {7});
        String[] r = rb.take("reflected");
        check(label + ": reflected only the subscribed attribute", r != null && r[2].equals("[" + attrB + "]") && r[3].equals("[[1, 2, 3]]") && r[4].equals("A"));

        int icB = b.interactionClass("HLAinteractionRoot.WeaponFire");
        int pB = b.parameter(icB, "EventIdentifier");
        b.publishInteractionClass(icB);
        int icA = a.interactionClass("HLAinteractionRoot");
        a.subscribeInteractionClass(icA);
        b.sendInteraction(icB, new int[] {pB}, new byte[][] {{5, 5}}, null);
        String[] rx = ra.take("received");
        check(label + ": interaction received as subscribed class", rx != null && rx[1].equals(String.valueOf(icA)) && rx[3].equals("[[5, 5]]"));

        long seen = Long.parseLong(d[1]);
        b.requestAttributeUpdate(seen, new int[] {attrB}, null);
        String[] p = ra.take("provide");
        check(label + ": provide requested", p != null && p[1].equals(String.valueOf(inst)));

        a.deleteObject(inst, null);
        String[] rm = rb.take("removed");
        check(label + ": removed", rm != null && rm[1].equals(d[1]));

        a.resign(false);
        b.resign(true);
        check(label + ": resigned", !a.isJoined() && !b.isJoined());
        a.disconnect();
        b.disconnect();
        check(label + ": disconnected", !a.isConnected());
        try {
            a.objectClass("X");
            check(label + ": services after disconnect must fail", false);
        } catch (RtiSessionException e) {
            check(label + ": NotConnected after disconnect", "NotConnected".equals(e.getRtiExceptionName()));
        }
        try {
            RtiSession c = lib.createSession(v);
            c.connect(ConnectSettings.defaults(), new Rec());
            c.join("NoSuchFederation", "T", null, Collections.<String>emptyList(), false);
            check(label + ": join of a missing federation must fail", false);
        } catch (RtiSessionException e) {
            check(label + ": FederationExecutionDoesNotExist", "FederationExecutionDoesNotExist".equals(e.getRtiExceptionName()));
        }
    }

    /** Records callbacks as string arrays: {kind, ...}. */
    private static final class Rec implements RtiSessionListener {
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();

        String[] take(String kind) throws InterruptedException {
            long end = System.currentTimeMillis() + 3000;
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    if (e == null) {
                        return null;
                    }
                    if (e[0].equals(kind)) {
                        return e;
                    }
                    skipped.add(e);
                }
                return null;
            } finally {
                q.addAll(skipped);
            }
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name, producer});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer) {
            q.add(new String[] {"reflected", String.valueOf(instance), Arrays.toString(attributes), Arrays.deepToString(values), producer});
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer) {
            q.add(new String[] {"received", String.valueOf(interactionClass), Arrays.toString(parameters), Arrays.deepToString(values)});
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
            q.add(new String[] {"provide", String.valueOf(instance)});
        }

        @Override
        public void nameReserved(String name, boolean success) {
            q.add(new String[] {"name", name, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
            q.add(new String[] {"lost", reason});
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
