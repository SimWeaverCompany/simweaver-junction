package com.simweaver.rift;

import com.simweaver.common.config.SimwPaths;
import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.config.SiteFolders;
import com.simweaver.common.rest.auth.ApiAuth;
import com.simweaver.common.tls.ServerKeyStore;
import com.simweaver.rift.map.MapService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 0.14.5: the program folder and the site folder. Replaces SettingsFoldersSelfTest (0.11.4), whose checks are
 * kept (part 1). Then the folders themselves, making a new site, the lookups of delivered content, the HTTPS key
 * store (part 2, in this process), and real servers started from a program folder laid out as the release zip:
 * the simple way (unzip and run, twice), a site of its own with --init, the lock, --health, and a tree from
 * before 0.14.5 (part 3).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.rift.SiteFoldersSelfTest [simwrift/rtdir [the jar]]</pre>
 *
 * Part 3 needs the jar ({@code out/artifacts/simweaverrift/simweaverrift.jar}, made by build.sh); without it,
 * it is skipped.
 */
public final class SiteFoldersSelfTest {

    private static int checks;
    private static int failures;
    private static Path work;

    private SiteFoldersSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path rtdir = Paths.get(args.length > 0 ? args[0] : "simwrift/rtdir").toAbsolutePath().normalize();
        Path jar = Paths.get(args.length > 1 ? args[1] : "out/artifacts/simweaverrift/simweaverrift.jar").toAbsolutePath().normalize();
        work = Files.createTempDirectory("simw-site").toRealPath();
        try {
            settings();
            folders(jar);
            seeding();
            lookups();
            keyStore();
            SimwPaths.reset();
            if (Files.isRegularFile(jar)) {
                servers(rtdir, jar);
            } else {
                System.out.println("part 3 skipped: " + jar + " not found (run build.sh first)");
            }
        } finally {
            SimwPaths.reset();
            deleteTree(work);
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- part 1: the settings files (0.11.4), the installation as program and site ----

    private static void settings() throws Exception {
        Path inst = Files.createDirectories(work.resolve("install"));
        Files.createDirectories(inst.resolve("etc"));
        write(inst.resolve("etc/server.root.settings"), "webserver.port=9001\nlogdir=./log\nsyslog.host=a\n");

        SimwProperties c = Server.readSettings(inst, new String[0]);
        check("no etcdir: the installation's etc", c.getInt("webserver.port", 0) == 9001
                && SimwPaths.etc().equals(inst.resolve("etc")) && !SimwPaths.isMoved());
        check("defaults in it: tokens, FOMs", ApiAuth.tokenFile(c, inst).equals(inst.resolve("etc/auth/api-tokens.settings").normalize())
                && SimwPaths.etcString("fom").equals(inst.resolve("etc").resolve("fom").toString()));
        check("the installation is program and site: not a site of its own", !SimwPaths.isSite()
                && SimwPaths.program().equals(inst) && SimwPaths.root().equals(inst));

        Path conf = Files.createDirectories(inst.resolve("conf"));
        write(inst.resolve("etc/server.root.settings"), "webserver.port=9001\nsyslog.host=a\netcdir=conf\n");
        write(conf.resolve("server.root.settings"), "webserver.port=9002\n");
        c = Server.readSettings(inst, new String[0]);
        check("etcdir=conf in the installation's file: its settings on top, the rest kept",
                c.getInt("webserver.port", 0) == 9002 && "a".equals(c.getString("syslog.host", null))
                        && SimwPaths.etc().equals(conf) && SimwPaths.isMoved());
        check("defaults follow it: tokens, junctions, j2j, datasources", ApiAuth.tokenFile(c, inst).equals(conf.resolve("auth/api-tokens.settings").normalize())
                && SimwPaths.etc("j2j", "network.key").equals(conf.resolve("j2j").resolve("network.key"))
                && SimwPaths.etc("datasources").equals(conf.resolve("datasources")));

        Path other = Files.createDirectories(work.resolve("other-etc"));
        write(other.resolve("server.root.settings"), "webserver.port=9003\nlogdir=/var/log/simweaver\n");
        c = Server.readSettings(inst, new String[] {"etcdir=" + other, "webserver.port=9100"});
        check("etcdir on the command line (absolute) wins; the command line's keys win over both files",
                SimwPaths.etc().equals(other) && c.getInt("webserver.port", 0) == 9100
                        && "/var/log/simweaver".equals(c.getString("logdir", null)) && "a".equals(c.getString("syslog.host", null)));

        Path bare = Files.createDirectories(work.resolve("bare-etc"));
        c = Server.readSettings(inst, new String[] {"etcdir=" + bare});
        check("a configuration folder without server.root.settings: the installation's settings alone",
                SimwPaths.etc().equals(bare) && c.getInt("webserver.port", 0) == 9001);

        Path empty = Files.createDirectories(work.resolve("empty"));
        c = Server.readSettings(empty, new String[0]);
        check("no settings file: defaults, etc", SimwPaths.etc().equals(empty.resolve("etc")) && c.getInt("webserver.port", 8843) == 8843);
        SimwPaths.setEtc(null);
        check("reset: etc", SimwPaths.etc().equals(SimwPaths.DEFAULT_ETC));
    }

    // ---- part 2: the folders, a new site, the lookups, the key store ----

    private static void folders(Path jar) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        check("classes not from a jar: the program folder is the working folder", SimwPaths.detectProgram(SimwPaths.class).equals(cwd));
        if (Files.isRegularFile(jar)) {
            Path prog = Files.createDirectories(work.resolve("detect/simweaver-junction/lib"));
            Files.copy(jar, prog.resolve("simweaverrift.jar"));
            Path link = work.resolve("detect/current");
            Files.createSymbolicLink(link, work.resolve("detect/simweaver-junction"));
            check("<program>/lib/x.jar: the program folder", detectFrom(prog.resolve("simweaverrift.jar")).equals(work.resolve("detect/simweaver-junction")));
            check("through a link to the program folder: the real folder", detectFrom(link.resolve("lib/simweaverrift.jar")).equals(work.resolve("detect/simweaver-junction")));
            Path loose = Files.createDirectories(work.resolve("detect/loose"));
            Files.copy(jar, loose.resolve("x.jar"));
            check("a jar not in lib: its folder", detectFrom(loose.resolve("x.jar")).equals(loose));
        }

        Path program = Files.createDirectories(work.resolve("prog"));
        check("root= wins over JUNCTION_SITE", SimwPaths.chooseRoot("/srv/a", "/srv/b", program).equals(Paths.get("/srv/a")));
        check("JUNCTION_SITE when no root=", SimwPaths.chooseRoot(" ", "/srv/b", program).equals(Paths.get("/srv/b")));
        check("neither: the program folder", SimwPaths.chooseRoot(null, "", program).equals(program));
        check("a relative root: against the working folder", SimwPaths.chooseRoot("site1", null, program).equals(cwd.resolve("site1")));

        Path site = Files.createDirectories(work.resolve("site"));
        Files.createDirectories(site.resolve("etc"));
        write(site.resolve("etc/server.root.settings"), "webserver.port=9201\n");
        SimwProperties c = Server.readSettings(program, site, new String[0]);
        check("a site of its own: its etc/server.root.settings", c.getInt("webserver.port", 0) == 9201
                && SimwPaths.isSite() && SimwPaths.root().equals(site) && SimwPaths.program().equals(program));
        check("etc is absolute, in the site", SimwPaths.etc().equals(site.resolve("etc")) && SimwPaths.etc().isAbsolute());
        check("relative paths in settings: against the site", SimwPaths.site("logs").equals(site.resolve("logs"))
                && SimwPaths.site("etc/tls/x.p12").equals(site.resolve("etc/tls/x.p12")) && SimwPaths.site("").equals(site));
        check("absolute paths in settings: as they are", SimwPaths.site("/var/log/x").equals(Paths.get("/var/log/x")));
        check("the default key store: etc/tls/server.p12 in the site", ServerKeyStore.defaultPath().equals(site.resolve("etc/tls/server.p12"))
                && Server.keyStoreFile(c).equals(site.resolve("etc/tls/server.p12")));
        c.set("webserver.keystore.path", "keystore.jks");
        check("a key store setting from before: relative to the site", Server.keyStoreFile(c).equals(site.resolve("keystore.jks")));
        check("delivered files: in the program folder", SimwPaths.delivered("fom", "ieee1516-2010").equals(program.resolve("fom/ieee1516-2010")));

        c = Server.readSettings(program, site, new String[] {"etcdir=conf"});
        check("etcdir relative: against the site", SimwPaths.etc().equals(site.resolve("conf")) && SimwPaths.isMoved());
    }

    private static Path detectFrom(Path jar) throws Exception {
        try (URLClassLoader l = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> k = l.loadClass(SimwPaths.class.getName());
            Method m = k.getMethod("detectProgram", Class.class);
            return (Path) m.invoke(null, k);
        }
    }

    private static void seeding() throws Exception {
        Path program = Files.createDirectories(work.resolve("seed/prog"));
        Path t = program.resolve(SiteFolders.TEMPLATES);
        write(Files.createDirectories(t.resolve("junctions/endpoints")).resolve("a.endpoint.settings"), "x=1\n");
        write(t.resolve("server.root.settings"), "webserver.port=8843\n");
        Files.createDirectories(t.resolve("empty-folder"));
        Path etc = work.resolve("seed/site/etc");
        int n = SiteFolders.seed(program, etc);
        check("a new etc: every template file copied", n == 2 && Files.isRegularFile(etc.resolve("junctions/endpoints/a.endpoint.settings"))
                && "webserver.port=8843\n".equals(read(etc.resolve("server.root.settings"))));
        check("empty folders too", Files.isDirectory(etc.resolve("empty-folder")));
        check("no temporary folder left behind", list(etc.getParent()).size() == 1);
        write(etc.resolve("server.root.settings"), "webserver.port=9999\n");
        Files.delete(etc.resolve("junctions/endpoints/a.endpoint.settings"));
        write(t.resolve("new-in-this-release.txt"), "n\n");
        check("an existing etc: nothing copied", SiteFolders.seed(program, etc) == 0);
        check("a changed file is never overwritten", "webserver.port=9999\n".equals(read(etc.resolve("server.root.settings"))));
        check("a deleted sample stays deleted; new templates do not come in", !Files.exists(etc.resolve("junctions/endpoints/a.endpoint.settings"))
                && !Files.exists(etc.resolve("new-in-this-release.txt")));
        Path noTemplates = Files.createDirectories(work.resolve("seed/bare-prog"));
        Path etc2 = work.resolve("seed/site2/etc");
        check("no etc.dist in the program: only the folder is made", SiteFolders.seed(noTemplates, etc2) == -1 && Files.isDirectory(etc2));

        List<String> notes = new ArrayList<>();
        Path etc3 = work.resolve("seed/site3/etc");
        check("Server.seed: made, with a note", Server.seed(program, etc3, notes) == null && notes.size() == 1 && notes.get(0).startsWith("new: "));
        notes.clear();
        check("Server.seed again: nothing, no note", Server.seed(program, etc3, notes) == null && notes.isEmpty());
    }

    private static void lookups() throws Exception {
        Path program = Files.createDirectories(work.resolve("look/prog"));
        Path site = Files.createDirectories(work.resolve("look/site"));
        SimwPaths.setFolders(program, site, null, false);

        // FOMs: the site's etc/fom first, then the program's fom
        Path pf = Files.createDirectories(program.resolve("fom/ieee1516-2010"));
        Path sf = Files.createDirectories(site.resolve("etc/fom/ieee1516-2010"));
        write(pf.resolve("RPR.xml"), "delivered");
        write(pf.resolve("Only-delivered.xml"), "delivered");
        write(sf.resolve("RPR.xml"), "own");
        write(sf.resolve("Own.xml"), "own");
        List<Path> roots = SimwPaths.fomRoots("ieee1516-2010", null);
        check("FOM roots: the site's, then the program's", roots.size() == 2 && roots.get(0).equals(sf) && roots.get(1).equals(pf));
        check("a module in both: the site's", SimwPaths.findFom(roots, "RPR.xml", site.toFile()).toPath().equals(sf.resolve("RPR.xml")));
        check("a module only delivered: the program's", SimwPaths.findFom(roots, "Only-delivered.xml", site.toFile()).toPath().equals(pf.resolve("Only-delivered.xml")));
        check("a module of the site's own", SimwPaths.findFom(roots, "Own.xml", site.toFile()).toPath().equals(sf.resolve("Own.xml")));
        check("an absolute module: as it is", SimwPaths.findFom(roots, "/x/y.xml", site.toFile()).getPath().equals(new File("/x/y.xml").getPath()));
        write(Files.createDirectories(site.resolve("myfoms")).resolve("m.xml"), "m");
        check("a module relative to the site (as before 0.14.5)", SimwPaths.findFom(roots, "myfoms/m.xml", site.toFile()).toPath().equals(site.resolve("myfoms/m.xml")));
        check("a missing module: named in the site's folder", SimwPaths.findFom(roots, "Nope.xml", site.toFile()).toPath().equals(sf.resolve("Nope.xml")));
        List<Path> explicit = SimwPaths.fomRoots("ieee1516-2010", "lab/fom");
        check("an explicit FOM root: alone, relative to the site", explicit.size() == 1 && explicit.get(0).equals(site.resolve("lab/fom/ieee1516-2010")));
        SimwPaths.setFolders(program, program, null, false);
        check("the simple way: the program folder's etc/fom, then its fom",
                SimwPaths.fomRoots("ieee1516-2010", "").equals(List.of(program.resolve("etc/fom/ieee1516-2010"), pf)));
        SimwPaths.setFolders(program, site, null, false);

        // SISO-REF-010 and the RPR table: the site's when it has the file
        Path sEnum = site.resolve("etc/enumerations/SISO-REF-010.xml");
        Path pEnum = program.resolve("enumerations/SISO-REF-010.xml");
        write(Files.createDirectories(pEnum.getParent()).resolve("SISO-REF-010.xml"), "p");
        check("SISO-REF-010: the program's when the site has none", SimwPaths.findDelivered(sEnum, pEnum).equals(pEnum));
        write(Files.createDirectories(sEnum.getParent()).resolve("SISO-REF-010.xml"), "s");
        check("SISO-REF-010: the site's when it has one", SimwPaths.findDelivered(sEnum, pEnum).equals(sEnum));
        check("neither: the site's (named in a message)", SimwPaths.findDelivered(site.resolve("a"), program.resolve("b")).equals(site.resolve("a")));
        Path junctions = Files.createDirectories(site.resolve("etc/junctions"));
        write(Files.createDirectories(program.resolve("mappings")).resolve("rpr2-rpr3.mapping"), "p");
        check("the RPR table: the program's mappings", mappingsDir(junctions.toFile()).toPath().equals(program.resolve("mappings")));
        write(Files.createDirectories(junctions.resolve("mappings")).resolve("rpr2-rpr3.mapping"), "s");
        check("the RPR table: the site's junctions/mappings when it has one", mappingsDir(junctions.toFile()).toPath().equals(junctions.resolve("mappings")));

        // the shadow notice
        List<SiteFolders.Shadow> sh = SiteFolders.shadowed(site.resolve("etc/fom"), program.resolve("fom"));
        check("shadow: RPR.xml in both, different", sh.size() == 1 && sh.get(0).site.equals(sf.resolve("RPR.xml")) && !sh.get(0).identical);
        write(sf.resolve("Only-delivered.xml"), "delivered");
        sh = SiteFolders.shadowed(site.resolve("etc/fom"), program.resolve("fom"));
        check("shadow: a copy the same as delivered", sh.size() == 2 && sh.stream().anyMatch(x -> x.identical && x.site.endsWith("Only-delivered.xml")));
        check("shadow: none when the folders are one", SiteFolders.shadowed(program.resolve("fom"), program.resolve("fom")).isEmpty());
        check("shadow: none when the site has no folder", SiteFolders.shadowed(site.resolve("nothing"), program.resolve("fom")).isEmpty());

        // maps: overlays in the site and in the program folder; a layer in both is the site's (read once)
        Path so = Files.createDirectories(site.resolve("maps/overlays"));
        Path po = Files.createDirectories(program.resolve("maps/overlays"));
        emptyZip(so.resolve("coast.zip"));
        emptyZip(po.resolve("coast.zip"));
        emptyZip(po.resolve("borders.zip"));
        SimwProperties mc = new SimwProperties();
        mc.set("map.cache.dir", work.resolve("look/cache").toString());
        MapService ms = new MapService(mc, site.toFile());
        long coast = ms.problems().stream().filter(p -> p.contains("coast.zip")).count();
        long borders = ms.problems().stream().filter(p -> p.contains("borders.zip")).count();
        check("overlays: a layer in both folders is read once; the program's own ones too", coast == 1 && borders == 1);
        mc.set("map.overlays.dir", "maps/overlays");
        ms = new MapService(mc, site.toFile());
        check("map.overlays.dir set: that folder alone", ms.problems().stream().noneMatch(p -> p.contains("borders.zip"))
                && ms.problems().stream().anyMatch(p -> p.contains("coast.zip")));
        SimwPaths.reset();
    }

    private static File mappingsDir(File junctions) throws Exception {
        Method m = com.simweaver.junction.JunctionManager.class.getDeclaredMethod("mappingsDir", File.class);
        m.setAccessible(true);
        return (File) m.invoke(null, junctions);
    }

    private static void keyStore() throws Exception {
        Path f = work.resolve("tls/etc/tls/server.p12");
        char[] pw = "password".toCharArray();
        ServerKeyStore.Result r = ServerKeyStore.ensure(f, pw, "junction.example.org;10.1.2.3");
        check("made when missing", r.made && Files.isRegularFile(f));
        KeyStore ks = ServerKeyStore.load(f, pw);
        check("a PKCS12 file", "PKCS12".equalsIgnoreCase(ks.getType()) && ks.isKeyEntry(ServerKeyStore.ALIAS));
        X509Certificate cert = (X509Certificate) ks.getCertificate(ServerKeyStore.ALIAS);
        cert.checkValidity();
        cert.verify(cert.getPublicKey());
        check("self-signed, valid now, EC", cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())
                && "EC".equals(cert.getPublicKey().getAlgorithm()));
        long days = TimeUnit.MILLISECONDS.toDays(cert.getNotAfter().getTime() - cert.getNotBefore().getTime());
        check("valid for 5 years", days >= 365 * 5 - 1 && days <= 366 * 5 + 1);
        List<String> san = new ArrayList<>();
        Collection<List<?>> alt = cert.getSubjectAlternativeNames();
        for (List<?> e : alt) {
            san.add(String.valueOf(e.get(1)));
        }
        check("SAN: localhost, 127.0.0.1, ::1 and webserver.tls.san", san.contains("localhost") && san.contains("127.0.0.1")
                && san.stream().anyMatch(s -> s.equals("0:0:0:0:0:0:0:1") || s.equals("::1"))
                && san.contains("junction.example.org") && san.contains("10.1.2.3"));
        check("for a TLS server", cert.getExtendedKeyUsage() != null && cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1")
                && cert.getBasicConstraints() == -1);
        check("the fingerprint printed is the file's", r.fingerprint.equals(ServerKeyStore.fingerprintOf(f, pw))
                && r.fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
        if (f.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            check("readable by the owner only", "rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(f))));
        }
        byte[] before = Files.readAllBytes(f);
        ServerKeyStore.Result again = ServerKeyStore.ensure(f, pw, "other.example.org");
        check("never replaced: the same file and fingerprint", !again.made && again.fingerprint.equals(r.fingerprint)
                && java.util.Arrays.equals(before, Files.readAllBytes(f)));
        check("no temporary file left behind", list(f.getParent()).size() == 1);

        // a JKS key store from before 0.14.5, whatever its name
        KeyStore jks = KeyStore.getInstance("JKS");
        jks.load(null, null);
        jks.setKeyEntry("myserver", ks.getKey(ServerKeyStore.ALIAS, pw), pw, ks.getCertificateChain(ServerKeyStore.ALIAS));
        Path old = work.resolve("tls/keystore.jks");
        try (OutputStream o = Files.newOutputStream(old)) {
            jks.store(o, pw);
        }
        check("a JKS key store still opens", ServerKeyStore.load(old, pw).isKeyEntry("myserver"));
        boolean wrongPw;
        try {
            ServerKeyStore.load(f, "wrong".toCharArray());
            wrongPw = false;
        } catch (IOException | java.security.GeneralSecurityException e) {
            wrongPw = true;
        }
        check("a wrong password: an error", wrongPw);
        check("webserver.tls.san entries: names and addresses", ServerKeyStore.sanProblem("a.example.org") == null
                && ServerKeyStore.sanProblem("10.0.0.1") == null && ServerKeyStore.sanProblem("fe80::1") == null
                && ServerKeyStore.sanProblem("not a name!") != null);
    }

    // ---- part 3: real servers, from a program folder laid out as the release ----

    private static void servers(Path rtdir, Path jar) throws Exception {
        // --health with nothing listening
        SimwProperties h = new SimwProperties();
        int dead = freePort();
        h.set("webserver.port", String.valueOf(dead));
        check("--health: 1 when nothing answers", Server.health(h, quiet()) == 1);

        // the simple way: a fresh unzip, run.sh with no arguments, started from another folder
        Path program = unzip(rtdir, jar, "simple/simweaver-junction");
        Path elsewhere = Files.createDirectories(work.resolve("simple/elsewhere"));
        int port = freePort();
        String portArg = "webserver.port=" + port;
        Map<String, String> unzipped = snapshot(program);
        Process p = start(program, elsewhere, "simple-1", portArg);
        boolean up = waitUp(p, port);
        check("simple: the server starts from a fresh unzip", up);
        String out1 = output("simple-1");
        check("simple: etc made from etc.dist", Files.isRegularFile(program.resolve("etc/server.root.settings"))
                && read(program.resolve("etc/server.root.settings")).equals(read(program.resolve("etc.dist/server.root.settings")))
                && out1.contains("made from"));
        check("simple: the key store made in etc/tls", Files.isRegularFile(program.resolve("etc/tls/server.p12")) && out1.contains("made now for"));
        check("simple: the logs in logs", Files.isDirectory(program.resolve("logs")) && !Files.exists(program.resolve("log")));
        check("simple: the start-up lines name the program folder as the site", out1.contains("(the program folder)"));
        check("simple: nothing made in the working folder", list(elsewhere).isEmpty());
        check("simple: the lock holds the pid", Files.isRegularFile(program.resolve(SiteFolders.LOCK_FILE))
                && read(program.resolve(SiteFolders.LOCK_FILE)).trim().equals(String.valueOf(p.pid())));
        Map<String, String> made = snapshot(program);
        List<String> changed = changes(unzipped, made);
        check("simple: only etc, logs, cache and .lock are new: " + changed, changed.stream().allMatch(x -> x.startsWith("+etc/")
                || x.startsWith("+logs/") || x.startsWith("+cache/") || x.equals("+.lock")));

        // a second server on the same site: refused
        Process p2 = start(program, elsewhere, "simple-2nd", "webserver.port=" + freePort());
        boolean ended = p2.waitFor(60, TimeUnit.SECONDS);
        check("lock: a second server on the same site ends with 2", ended && p2.exitValue() == 2
                && output("simple-2nd").contains("in use by another server (pid " + p.pid() + ")"));
        if (!ended) {
            p2.destroyForcibly();
        }

        // --health against the running server
        Process hp = start(program, elsewhere, "simple-health", "--health", portArg);
        check("--health: 0 while it runs", hp.waitFor(60, TimeUnit.SECONDS) && hp.exitValue() == 0 && output("simple-health").contains("answers"));
        stop(p);
        hp = start(program, elsewhere, "simple-health2", "--health", portArg);
        check("--health: 1 after it stopped", hp.waitFor(60, TimeUnit.SECONDS) && hp.exitValue() == 1);

        // the second start changes nothing in etc
        Map<String, String> etcBefore = snapshot(program.resolve("etc"));
        p = start(program, elsewhere, "simple-3", portArg);
        check("simple: the second start", waitUp(p, port));
        stop(p);
        String out3 = output("simple-3");
        check("simple: the second start changes nothing in etc: " + changes(etcBefore, snapshot(program.resolve("etc"))),
                etcBefore.equals(snapshot(program.resolve("etc"))));
        check("simple: the second start makes no key and seeds nothing", !out3.contains("made now for") && !out3.contains("made from"));

        // a site of its own: --init, then start; the program folder untouched
        Path program2 = unzip(rtdir, jar, "site/simweaver-junction");
        Map<String, String> prog2 = snapshot(program2);
        Path site = work.resolve("site/var/lib/simweaver-junction");
        Process ip = start(program2, elsewhere, "site-init", "--root=" + site, "--init");
        check("--init: ends with 0", ip.waitFor(60, TimeUnit.SECONDS) && ip.exitValue() == 0);
        String init = output("site-init");
        check("--init: etc, the key store and logs in the site", Files.isRegularFile(site.resolve("etc/server.root.settings"))
                && Files.isRegularFile(site.resolve("etc/tls/server.p12")) && Files.isDirectory(site.resolve("logs"))
                && init.contains("The site is ready"));
        check("--init: no lock left, no server", !lockHeld(site));
        ip = start(program2, elsewhere, "site-init2", "--root=" + site, "--init");
        check("--init again: 0, everything was there", ip.waitFor(60, TimeUnit.SECONDS) && ip.exitValue() == 0
                && output("site-init2").contains("not changed") && output("site-init2").contains("(was there)"));
        int port2 = freePort();
        p = start(program2, elsewhere, "site-run", "root=" + site, "webserver.port=" + port2);
        check("site: the server starts", waitUp(p, port2));
        stop(p);
        check("site: the start-up lines name the site", output("site-run").contains(site + " (root)"));
        check("site: the program folder is not changed: " + changes(prog2, snapshot(program2)), prog2.equals(snapshot(program2)));

        // JUNCTION_SITE
        Path site2 = work.resolve("site/env-site");
        ip = start(program2, elsewhere, "env-init", Map.of(SimwPaths.SITE_VARIABLE, site2.toString()), "--init");
        check("JUNCTION_SITE: the site", ip.waitFor(60, TimeUnit.SECONDS) && ip.exitValue() == 0
                && Files.isRegularFile(site2.resolve("etc/tls/server.p12")));

        // an etc that exists without the sample folders (a developer's tree): told once, clearly, and where they are
        Path bare = work.resolve("site/bare-etc");
        Files.createDirectories(bare.resolve("etc"));
        int port4 = freePort();
        write(bare.resolve("etc/server.root.settings"), "webserver.port=" + port4 + "\n");
        p = start(program2, elsewhere, "bare", "--root=" + bare);
        check("an etc without samples: the server starts", waitUp(p, port4));
        stop(p);
        String bo = output("bare");
        check("an etc without samples: not seeded, says the folders do not exist and where the samples are",
                !Files.exists(bare.resolve("etc/datasources")) && !bo.contains("Error reading")
                        && bo.contains("No datasource configurations: the folder") && bo.contains("etc.dist")
                        && bo.contains("does not exist: no gateway configurations"));

        // a program folder that cannot be written, the simple way (not as root: root writes anywhere)
        Path ro = unzip(rtdir, jar, "ro/simweaver-junction");
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
        boolean writable;
        try {
            Files.delete(Files.createFile(ro.resolve("probe")));
            writable = true;
        } catch (IOException e) {
            writable = false;
        }
        if (writable) {
            System.out.println("read-only program folder: skipped (this account writes anywhere)");
        } else {
            Process rp = start(ro, elsewhere, "ro", "webserver.port=" + freePort());
            check("a program folder that cannot be written: ends with 2 and says what to do", rp.waitFor(60, TimeUnit.SECONDS)
                    && rp.exitValue() == 2 && output("ro").contains("Unpack the zip into a folder you can write"));
        }
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));

        // a tree from before 0.14.5: etc with copies of the FOMs and the RPR table, keystore.jks named, logdir=log
        Path legacy = unzip(rtdir, jar, "legacy/simweaver-junction");
        int port3 = freePort();
        copyTree(legacy.resolve("etc.dist"), legacy.resolve("etc"));
        write(legacy.resolve("etc/server.root.settings"), "webserver.port=" + port3 + "\nlogdir=log\nwebserver.keystore.path=keystore.jks\n"
                + "webserver.keystore.pwd=password\nmap.cache.dir=maps/cache\n");
        Path fomFile = firstFile(legacy.resolve("fom/ieee1516-2010"));
        Path ownFom = legacy.resolve("etc/fom/ieee1516-2010").resolve(fomFile.getFileName().toString());
        Files.createDirectories(ownFom.getParent());
        Files.copy(fomFile, ownFom);
        Files.createDirectories(legacy.resolve("etc/junctions/mappings"));
        Files.copy(legacy.resolve("mappings/rpr2-rpr3.mapping"), legacy.resolve("etc/junctions/mappings/rpr2-rpr3.mapping"));
        Map<String, String> legacyEtc = snapshot(legacy.resolve("etc"));
        p = start(legacy, legacy, "legacy", new String[0]);
        check("legacy: the server starts", waitUp(p, port3));
        stop(p);
        String lo = output("legacy");
        check("legacy: etc is not changed", legacyEtc.equals(snapshot(legacy.resolve("etc"))));
        check("legacy: its explicit log folder and a key store at the old path", Files.isDirectory(legacy.resolve("log"))
                && !Files.exists(legacy.resolve("logs")) && Files.isRegularFile(legacy.resolve("keystore.jks")));
        check("legacy: one line about the copies that hide delivered files", lo.contains("hide delivered ones")
                && lo.contains("the same as delivered"));
    }

    private static Path unzip(Path rtdir, Path jar, String name) throws IOException {
        Path d = Files.createDirectories(work.resolve(name));
        Files.createDirectories(d.resolve("lib"));
        Files.copy(jar, d.resolve("lib/simweaverrift.jar"));
        for (String x : new String[] {"etc.dist", "junctions.dist", "mappings"}) {
            copyTree(rtdir.resolve(x), d.resolve(x));
        }
        for (String x : new String[] {"fom", "enumerations", "webroot"}) {       // large and read-only: links do
            Files.createSymbolicLink(d.resolve(x), rtdir.resolve(x));
        }
        return d;
    }

    private static Process start(Path program, Path cwd, String name, String... args) throws IOException {
        return start(program, cwd, name, Map.of(), args);
    }

    private static Process start(Path program, Path cwd, String name, Map<String, String> env, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx256m");
        cmd.add("-cp");
        cmd.add(program.resolve("lib/simweaverrift.jar").toString());
        cmd.add("com.simweaver.rift.Server");
        cmd.addAll(java.util.Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(work.resolve(name + ".out").toFile());
        pb.environment().remove(SimwPaths.SITE_VARIABLE);
        pb.environment().putAll(env);
        return pb.start();
    }

    private static String output(String name) throws IOException {
        Path f = work.resolve(name + ".out");
        return Files.exists(f) ? read(f) : "";
    }

    private static boolean waitUp(Process p, int port) throws InterruptedException {
        SimwProperties c = new SimwProperties();
        c.set("webserver.port", String.valueOf(port));
        long end = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive()) {
                return false;
            }
            if (Server.health(c, quiet()) == 0) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static void stop(Process p) throws InterruptedException {
        p.destroy();
        if (!p.waitFor(40, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static boolean lockHeld(Path root) {
        Path f = root.resolve(SiteFolders.LOCK_FILE);
        if (!Files.exists(f)) {
            return false;
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock l = ch.tryLock();
            if (l != null) {
                l.release();
                return false;
            }
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    // ---- helpers ----

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    /** relative path (files only, links not followed) to SHA-256 */
    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return m;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                m.put(rel, attrs.isSymbolicLink() ? "link" : sha(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return m;
    }

    private static List<String> changes(Map<String, String> a, Map<String, String> b) {
        List<String> l = new ArrayList<>();
        for (String k : b.keySet()) {
            if (!a.containsKey(k)) {
                l.add("+" + k);
            } else if (!a.get(k).equals(b.get(k))) {
                l.add("~" + k);
            }
        }
        for (String k : a.keySet()) {
            if (!b.containsKey(k)) {
                l.add("-" + k);
            }
        }
        return l;
    }

    private static String sha(Path f) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static Path firstFile(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().findFirst().orElseThrow(() -> new IOException("no file in " + dir));
        }
    }

    private static void emptyZip(Path f) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f))) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("no layers".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    private static List<Path> list(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.collect(java.util.stream.Collectors.toList());
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) {
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // a temporary folder
        }
    }

    static void write(Path p, String s) throws IOException {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
