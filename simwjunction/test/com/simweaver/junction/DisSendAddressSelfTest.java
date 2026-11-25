package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisNetwork;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.dis.cdis.CdisCodec;
import com.simweaver.common.dis.cdis.CdisSettings;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.endpoint.dis.DisEndpoint;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 0.14.0: a list of DIS send addresses ({@code endpoint.dis.sendaddress=a;b:port;…}): parsing and
 * checking, host names, the endpoint settings, and a PDU relay sending every PDU to two unicast
 * receivers, an address nobody listens on, an IPv6 one (received, or counted as failing where the
 * host has no IPv6) and a multicast group, from one socket, with counters per address. C-DIS
 * encodes once for all of them.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.DisSendAddressSelfTest [fom root]</pre>
 */
public final class DisSendAddressSelfTest {

    private static int checks;
    private static int failures;

    private DisSendAddressSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        parsing();
        settings(fomRoot);
        relay(fomRoot, "dis7");
        relay(fomRoot, "cdis");
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void parsing() {
        DisNetwork n = DisNetwork.parse("0.0.0.0", 3000);
        check("empty: the default destination", n.destinations("").size() == 1 && n.destinations(null).size() == 1
                && n.destinations(" ; ").size() == 1);
        List<InetSocketAddress> d = n.destinations("10.0.0.5; 10.0.0.6:3001 ;239.1.2.3;192.168.1.255:3000;10.0.0.5");
        check("a list: unicast, port, multicast, broadcast; the port defaults to dis.port; duplicates once " + d, d.size() == 4
                && d.get(0).equals(new InetSocketAddress("10.0.0.5", 3000)) && d.get(1).getPort() == 3001
                && d.get(2).getAddress().isMulticastAddress() && DisNetwork.text(d.get(3)).equals("192.168.1.255:3000"));
        List<InetSocketAddress> v6 = n.destinations("[::1]:3002;fe80::1");
        check("IPv6: [address]:port and a bare address", v6.size() == 2 && v6.get(0).getPort() == 3002 && v6.get(1).getPort() == 3000
                && DisNetwork.text(v6.get(0)).startsWith("["));
        List<InetSocketAddress> h = n.destinations("localhost:3005");
        check("a host name is looked up " + h, h.size() == 1 && h.get(0).getAddress().isLoopbackAddress() && h.get(0).getPort() == 3005);
        try {
            n.destinations("10.0.0.1;no-such-host.invalid");
            check("a host name that cannot be resolved is refused", false);
        } catch (IllegalArgumentException e) {
            check("a host name that cannot be resolved is refused: " + e.getMessage(), e.getMessage().contains("no-such-host.invalid"));
        }
        List<String> p = DisNetwork.sendAddressProblems("10.0.0.1:99999;10.0.0.300;a b;:3000;10.0.0.2:x;[::1;ok-host.example:3000;10.1.1.1");
        check("problems: port range, bad IPv4, spaces, no host, port text, bracket; good ones pass " + p, p.size() == 6
                && p.get(0).contains("1-65535") && p.get(1).contains("not an IPv4") && p.get(5).contains("']' is missing"));
        check("no problems for a good list", DisNetwork.sendAddressProblems("192.168.1.255;sim-07:3001;[fe80::1]:3000").isEmpty());
    }

    private static void settings(File fomRoot) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File dir = Files.createTempDirectory("junction-sendaddr-cfg").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "a;b");
        p.set("junction.endpoint.a.standard", "dis7");
        p.set("junction.endpoint.a.hla.foms", rpr2);
        p.set("junction.endpoint.a.dis.sendaddress", "10.0.0.1;10.0.0.2:70000");
        p.set("junction.endpoint.b.standard", "dis6");
        p.set("junction.endpoint.b.hla.foms", rpr2);
        p.set("junction.endpoint.b.dis.sendaddress", "GROUPVAR1");
        p.set("junction.router.kind", "pdu");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "s.junction.settings"), p);
        check("a bad entry is a configuration error " + gc.getErrors(), gc.getErrors().toString().contains("endpoint.dis.sendaddress")
                && gc.getErrors().toString().contains("10.0.0.2:70000"));
        check("a group variable is checked once it has a value", !gc.getErrors().toString().contains("GROUPVAR1"));
    }

    /** A relay dis7 -> out ({@code standard}): every PDU to all send addresses. */
    private static void relay(File fomRoot, String standard) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        int inPort = freePort();
        int outPort = freePort();
        DatagramSocket r1 = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        DatagramSocket r2 = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        int nobody = freePort();
        DatagramSocket r6 = null;
        try {
            r6 = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("::1"), 0));
        } catch (Exception e) {
            r6 = null;                                     // no IPv6 here
        }
        int v6Port = r6 != null ? r6.getLocalPort() : freePort();
        int mPort = freePort();
        InetAddress group = InetAddress.getByName("239.255.42.99");
        MulticastSocket rm = null;
        NetworkInterface lo = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        try {
            rm = new MulticastSocket(mPort);
            rm.joinGroup(new InetSocketAddress(group, mPort), lo);
        } catch (Exception e) {
            if (rm != null) {
                rm.close();
            }
            rm = null;                                     // no multicast on the loopback here
        }
        for (DatagramSocket s : new DatagramSocket[] {r1, r2, r6, rm}) {
            if (s != null) {
                s.setSoTimeout(3000);
            }
        }
        String list = "127.0.0.1:" + r1.getLocalPort() + "; 127.0.0.1:" + r2.getLocalPort() + ";127.0.0.1:" + nobody
                + ";[::1]:" + v6Port + ";" + group.getHostAddress() + ":" + mPort;

        File dir = Files.createTempDirectory("junction-sendaddr").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Send list");
        p.set("junction.endpoints", "in;out");
        p.set("junction.endpoint.in.standard", "dis7");
        p.set("junction.endpoint.in.hla.foms", rpr2);
        p.set("junction.endpoint.in.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.in.dis.port", String.valueOf(inPort));
        p.set("junction.endpoint.in.dis.sendaddress", "127.0.0.1:" + freePort());
        p.set("junction.endpoint.out.standard", standard);
        p.set("junction.endpoint.out.hla.foms", rpr2);
        p.set("junction.endpoint.out.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.out.dis.port", String.valueOf(outPort));
        p.set("junction.endpoint.out.dis.sendaddress", list);
        p.set("junction.endpoint.out.dis.ttl", "1");
        p.set("junction.router.kind", "pdu");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "list.junction.settings"), p);
        check(standard + ": config valid " + gc.getErrors(), gc.isValid());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check(standard + ": running", gw.getState() == Gateway.State.RUNNING);
        DisEndpoint out = (DisEndpoint) gw.endpoint("out");
        check(standard + ": destination lists all five " + out.destinationText(), out.destinationText().split(", ").length == 5);

        DatagramSocket tx = new DatagramSocket();
        int n = 3;
        for (int i = 0; i < n; i++) {
            byte[] pdu = entity(i + 1);
            tx.send(new DatagramPacket(pdu, pdu.length, InetAddress.getLoopbackAddress(), inPort));
        }
        int got1 = count(r1, n), got2 = count(r2, n);
        check(standard + ": both unicast receivers get every PDU (" + got1 + ", " + got2 + ")", got1 == n && got2 == n);
        if (rm != null) {
            int gm = count(rm, n);
            check(standard + ": the multicast group gets every PDU (" + gm + ")", gm == n);
        } else {
            check(standard + ": (no multicast on the loopback here: skipped)", true);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sa = (List<Map<String, Object>>) out.diagnostics().get("sendaddresses");
        Map<String, Object> v6 = sa.get(3);
        if (r6 != null) {
            int g6 = count(r6, n);
            check(standard + ": the IPv6 receiver gets every PDU (" + g6 + ")", g6 == n);
        } else {
            check(standard + ": no IPv6 here: that address counts its errors, the others are not affected " + v6,
                    ((Number) v6.get("errors")).longValue() == n && v6.get("lasterror") != null);
        }
        check(standard + ": counters per address " + sa, sa.size() == 5 && ((Number) sa.get(0).get("sent")).longValue() == n
                && ((Number) sa.get(2).get("sent")).longValue() == n && ((Number) sa.get(0).get("errors")).longValue() == 0);
        check(standard + ": OUT counts PDUs, not copies: " + out.stats().toJson(), Json.toJson(out.stats()).contains("\"messages\":" + n));
        if (standard.equals("cdis")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cd = (Map<String, Object>) out.diagnostics().get("cdis");
            check("cdis: encoded once per PDU, not per address " + cd.get("encoded"), ((Number) cd.get("encoded")).longValue() == n);
        }
        check(standard + ": the gateway JSON has the addresses", Json.toJson(gw).contains("\"sendaddresses\""));
        tx.close();
        gw.stop();
        for (DatagramSocket s : new DatagramSocket[] {r1, r2, r6, rm}) {
            if (s != null) {
                s.close();
            }
        }
    }

    /** Datagrams received within the timeout, at most {@code n}. */
    private static int count(DatagramSocket s, int n) {
        int c = 0;
        byte[] buf = new byte[2048];
        try {
            while (c < n) {
                s.receive(new DatagramPacket(buf, buf.length));
                c++;
            }
        } catch (SocketTimeoutException e) {
            // fewer arrived
        } catch (Exception e) {
            System.out.println("  receive: " + e);
        }
        return c;
    }

    private static byte[] entity(int number) {
        DisWriter w = new DisWriter(144).header(7, 1, 1, 1, 0, 0);
        w.entityId(1, 2, number).u8(1).u8(0).entityType(new com.simweaver.common.dis.DisEntityType(1, 1, 225, 1, 1, 3, 0)).zeros(8);
        w.f32(0).f32(0).f32(0).f64(3_100_000).f64(1_000_000).f64(5_400_000).f32(0).f32(0).f32(0);
        w.u32(0).u8(1).zeros(15).zeros(24).u8(1).text("E" + number, 11).u32(0);
        return w.finish();
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
