package com.simweaver.rift;

import com.simweaver.common.config.SimwPaths;
import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.rest.auth.ApiAuth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 0.11.4: where the settings come from. The installation's etc/server.root.settings; etcdir (in it
 * or on the command line) moves the configuration folder, whose server.root.settings is read on
 * top; the command line wins; every configuration default follows the folder.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.rift.SettingsFoldersSelfTest</pre>
 */
public final class SettingsFoldersSelfTest {

    private static int checks;
    private static int failures;

    private SettingsFoldersSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path inst = Files.createTempDirectory("simw-install");
        Files.createDirectories(inst.resolve("etc"));
        write(inst.resolve("etc/server.root.settings"), "webserver.port=9001\nlogdir=./log\nsyslog.host=a\n");

        SimwProperties c = Server.readSettings(inst, new String[0]);
        check("no etcdir: the installation's etc", c.getInt("webserver.port", 0) == 9001
                && SimwPaths.etc().equals(inst.resolve("etc")) && !SimwPaths.isMoved());
        check("defaults in it: tokens, FOMs", ApiAuth.tokenFile(c, inst).equals(inst.resolve("etc/auth/api-tokens.settings").normalize())
                && SimwPaths.etcString("fom").equals(inst.resolve("etc").resolve("fom").toString()));

        // etcdir in the installation's file, relative: that folder's settings on top
        Path conf = inst.resolve("conf");
        Files.createDirectories(conf);
        write(inst.resolve("etc/server.root.settings"), "webserver.port=9001\nsyslog.host=a\netcdir=conf\n");
        write(conf.resolve("server.root.settings"), "webserver.port=9002\n");
        c = Server.readSettings(inst, new String[0]);
        check("etcdir=conf in the installation's file: its settings on top, the rest kept",
                c.getInt("webserver.port", 0) == 9002 && "a".equals(c.getString("syslog.host", null))
                        && SimwPaths.etc().equals(conf) && SimwPaths.isMoved());
        check("defaults follow it: tokens, junctions, j2j, datasources", ApiAuth.tokenFile(c, inst).equals(conf.resolve("auth/api-tokens.settings").normalize())
                && SimwPaths.etc("j2j", "network.key").equals(conf.resolve("j2j").resolve("network.key"))
                && SimwPaths.etc("datasources").equals(conf.resolve("datasources")));

        // etcdir on the command line, absolute, wins over the file's; a key on the command line wins over both
        Path other = Files.createTempDirectory("simw-etc");
        write(other.resolve("server.root.settings"), "webserver.port=9003\nlogdir=/var/log/simweaver\n");
        c = Server.readSettings(inst, new String[] {"etcdir=" + other, "webserver.port=9100"});
        check("etcdir on the command line (absolute) wins; the command line's keys win over both files",
                SimwPaths.etc().equals(other) && c.getInt("webserver.port", 0) == 9100
                        && "/var/log/simweaver".equals(c.getString("logdir", null)) && "a".equals(c.getString("syslog.host", null)));

        // a folder without its own settings file: only the installation's
        Path bare = Files.createTempDirectory("simw-bare");
        c = Server.readSettings(inst, new String[] {"etcdir=" + bare});
        check("a configuration folder without server.root.settings: the installation's settings alone",
                SimwPaths.etc().equals(bare) && c.getInt("webserver.port", 0) == 9001);

        // no settings at all
        Path empty = Files.createTempDirectory("simw-empty");
        c = Server.readSettings(empty, new String[0]);
        check("no settings file: defaults, etc", SimwPaths.etc().equals(empty.resolve("etc")) && c.getInt("webserver.port", 8843) == 8843);
        SimwPaths.setEtc(null);
        check("reset: etc", SimwPaths.etc().equals(SimwPaths.DEFAULT_ETC));

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void write(Path p, String s) throws Exception {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
