package com.simweaver.rift.datasources;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.dis.cdis.CdisCodec;
import com.simweaver.common.dis.cdis.CdisSettings;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.DatasourceManager;
import com.simweaver.rift.api.DataSource;
import com.simweaver.rift.api.DatasourceFactory;
import com.simweaver.rift.datasources.dis.IemRprModel;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;

/**
 * The Rift C-DIS datasource (0.9.18): configuration, C-DIS datagrams (full and partial updates)
 * decoded into the database, over UDP and directly.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.rift.datasources.CdisDatasourceSelfTest [fom root]</pre>
 */
public final class CdisDatasourceSelfTest {

    private static int checks;
    private static int failures;

    private CdisDatasourceSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        SimwProperties p = new SimwProperties();
        p.set("datasource.name", "C-DIS test");
        p.set("datasource.standard", "cdis");
        p.set("datasource.hla.foms", rpr2);
        p.set("datasource.dis.interface", "127.0.0.1");
        p.set("datasource.dis.port", String.valueOf(port));
        p.set("datasource.dis.exid", "4");
        p.set("datasource.cdis.heartbeat.entitystate", "2");
        DatasourceConfig cfg = new DatasourceConfig(1, new File("cdis-test.datasource.settings"), p);
        check("standard C-DIS", cfg.getStandard() == DatasourceConfig.Standard.CDIS);
        check("decoder settings", cfg.getCdisSettings().heartbeat(CdisCodec.ENTITY_STATE) == 2000);
        String json = com.simweaver.common.json.Json.toJson(cfg);
        check("shown in the configuration", json.contains("\"cdis\"") && json.contains("C-DIS (SISO-STD-023-2024)"));
        DataSource ds = DatasourceFactory.createDatasource(cfg, null, null, null);
        check("the factory makes a C-DIS datasource", ds instanceof RiftCdisConnection);
        RiftCdisConnection c = (RiftCdisConnection) ds;
        InformationExchangeModel iem = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        c.getDatabase().setInformationExchangeModel(iem);
        c.getDatabase().setDecoder(HlaDataDecoder.create(iem));
        DatasourceManager.OpResult r = c.start(new IemRprModel(iem));
        check("listening: " + r.getMessage(), r.isSuccess() && r.getMessage().contains("C-DIS"));

        // a full update over UDP, then partial ones
        CdisCodec tx = new CdisCodec(CdisSettings.DEFAULTS.withUpdateMode(CdisSettings.UpdateMode.PARTIAL));
        DatagramSocket out = new DatagramSocket();
        byte[] es = entity(4, 1, 2, 3, "ALPHA");
        for (int i = 0; i < 3; i++) {
            byte[] cdis = tx.encode(es, 0, es.length, i * 100L);
            out.send(new DatagramPacket(cdis, cdis.length, InetAddress.getLoopbackAddress(), port));
        }
        long end = System.currentTimeMillis() + 3000;
        while (c.getDatabase().getInstanceCount() == 0 && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        check("the entity is in the database", c.getDatabase().findByEntityId("1-2-3") != null
                || c.getDatabase().findByInstanceName("1-2-3") != null || c.getDatabase().getInstanceCount() == 1);
        while (((Number) c.getStatistics().get("pdus")).longValue() < 3 && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        Map<String, Object> st = c.getStatistics();
        @SuppressWarnings("unchecked")
        Map<String, Object> cs = (Map<String, Object>) st.get("cdis");
        check("statistics: C-DIS, 3 decoded (1 full, 2 partial) " + cs, "C-DIS".equals(st.get("protocol"))
                && ((Number) cs.get("decoded")).longValue() == 3 && ((Number) st.get("pdus")).longValue() == 3);

        // another exercise is filtered after decoding, as for DIS
        byte[] other = entity(5, 1, 2, 9, "BRAVO");
        byte[] oc = new CdisCodec(CdisSettings.DEFAULTS).encode(other, 0, other.length, 0);
        c.handleCdis(oc, 0, oc.length, "test", System.currentTimeMillis());
        check("another exercise is ignored", ((Number) c.getStatistics().get("otherexercise")).longValue() == 1);

        // garbage
        c.handleCdis(java.util.Arrays.copyOf(oc, oc.length - 3), 0, oc.length - 3, "test", System.currentTimeMillis());
        check("a C-DIS PDU longer than its datagram counts as malformed", ((Number) c.getStatistics().get("malformed")).longValue() == 1);
        out.close();
        c.disconnect();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] entity(int exercise, int site, int app, int number, String marking) {
        DisWriter w = new DisWriter(144).header(7, exercise, 1, 1, 0, 0);
        w.entityId(site, app, number).u8(1).u8(0).entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0)).zeros(8);
        w.f32(0).f32(0).f32(0).f64(3_100_000).f64(1_000_000).f64(5_400_000).f32(0).f32(0).f32(0);
        w.u32(0).u8(1).zeros(15).zeros(24).u8(1).text(marking, 11).u32(0);
        return w.finish();
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
