package com.simweaver.rift.junktion;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.junction.Inspector;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.DatasourceManager;
import com.simweaver.rift.api.DataSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Rift as Junction's host: Junction's endpoints become Rift datasource configurations (the
 * "Junction" group of the configuration list), and "Inspect" on the Junction page creates a
 * normal Rift datasource that joins the endpoint's federation (or listens to its DIS network)
 * on its own - so it shows what everyone there sees, the gateway's copies included.
 *
 * <p>Limits: Rift datasources use the RTI on the class path and the local settings designator;
 * an endpoint with its own {@code endpoint.hla.rtijar}, or a Federate Protocol endpoint, is
 * inspected through the class path RTI. A DIS datasource shares the UDP port with the
 * endpoint (address reuse), which works for broadcast and multicast, not for unicast.
 *
 * <p>Configuration objects are cached by their settings: each Rift configuration has its own
 * federation probe, so the same instance is returned while nothing changed.
 */
public final class RiftJunktionHost implements Inspector {

    private final DatasourceManager datasources;
    private final boolean listConfigs;
    private final Map<String, Cached> cache = new ConcurrentHashMap<String, Cached>();

    /** @param datasources Rift's datasource manager */
    public RiftJunktionHost(DatasourceManager datasources) {
        this(datasources, true);
    }

    /**
     * @param datasources Rift's datasource manager
     * @param listConfigs false: the endpoints are not added to Rift's configuration list (server
     *                    setting {@code junction.datasourceconfigs=false}); Inspect still works
     */
    public RiftJunktionHost(DatasourceManager datasources, boolean listConfigs) {
        this.datasources = datasources;
        this.listConfigs = listConfigs;
    }

    private static final class Cached {
        final String signature;
        final DatasourceConfig config;

        Cached(String signature, DatasourceConfig config) {
            this.signature = signature;
            this.config = config;
        }
    }

    @Override
    public void advertise(final Supplier<List<EndpointRef>> endpoints) {
        if (!listConfigs) {
            return;
        }
        datasources.addExternalConfigs(() -> {
            List<DatasourceConfig> out = new ArrayList<DatasourceConfig>();
            for (EndpointRef r : endpoints.get()) {
                DatasourceConfig c = config(r);
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        });
    }

    @Override
    public String inspect(EndpointRef endpoint) throws InspectException {
        DatasourceConfig c = config(endpoint);
        if (c == null) {
            throw new InspectException("UNSUPPORTED_STANDARD", 400, "Rift cannot show a " + endpoint.config.getType() + " endpoint");
        }
        String federation = endpoint.config.getType().isDis() ? endpoint.target : endpoint.target;
        // a running endpoint is inspected with the LSD it connects with
        DatasourceManager.SetupResult r = datasources.setupDatasource(c, federation, endpoint.lsd);
        if (r.dataSource == null) {
            throw new InspectException(r.errorCode, r.status, r.message);
        }
        return r.dataSource.getId().toString();
    }

    @Override
    public boolean exists(String datasourceId) {
        try {
            DataSource ds = datasources.getDatasource(UUID.fromString(datasourceId));
            return ds != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void remove(String datasourceId) {
        if (exists(datasourceId)) {
            datasources.jsonTeardownDatasource(datasourceId);
        }
    }

    @Override
    public String pageOf(String datasourceId) {
        return "datasource.html?id=" + datasourceId;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> heartbeats(com.simweaver.common.dis.cdis.CdisSettings cs) {
        return (java.util.Map<String, Object>) cs.toMap().get("heartbeats");
    }

    /** The Rift configuration of an endpoint (cached), or null for a kind Rift has no datasource for. */
    private DatasourceConfig config(EndpointRef r) {
        EndpointConfig e = r.config;
        EndpointType t = e.getType();
        if (t == null || t.isJ2j()) {
            return null;                                          // a J2J session: nothing Rift could join
        }
        SimwProperties p = new SimwProperties();
        p.set("datasource.name", "Junction: " + r.label);
        String standard;
        if (t.isDis()) {
            standard = t == EndpointType.DIS6 ? "dis6" : t == EndpointType.CDIS ? "cdis" : "dis7";
            if (t == EndpointType.CDIS) {
                // the decoder forgets objects as the endpoint does
                com.simweaver.common.dis.cdis.CdisSettings cs = e.getCdisSettings();
                p.set("datasource.cdis.timeoutmultiplier", String.valueOf(cs.getTimeoutMultiplier()));
                for (java.util.Map.Entry<String, Object> hb : heartbeats(cs).entrySet()) {
                    p.set("datasource.cdis.heartbeat." + hb.getKey(), String.valueOf(hb.getValue()));
                }
            }
            p.set("datasource.dis.port", String.valueOf(e.getDisPort()));
            p.set("datasource.dis.interface", e.getDisInterface());
            p.set("datasource.dis.exid", r.target == null || r.target.isEmpty() ? String.valueOf(e.getDisExerciseId()) : r.target);
            p.set("datasource.dis.entitytimeoutsecs", String.valueOf(e.getDisEntityTimeoutSecs()));
        } else {
            standard = e.isHla4() ? "ieee1516-2025" : "ieee1516-2010";
            p.set("datasource.hla.lsd", String.join(";", e.getLsdOptions()));     // the choices, the default first
            p.set("datasource.hla.federation", r.target == null ? "" : r.target);
            p.set("datasource.hla.customfederatetype", "SimwRift-Junction");
        }
        p.set("datasource.standard", standard);
        // Rift prefixes relative names with its FOM folder (etc/fom/<standard>): keep the files
        // that are there short, give the others as absolute paths
        String stdDir = t.isDis() || !e.isHla4() ? "ieee1516-2010" : "ieee1516-2025";
        String root = new File("etc" + File.separator + "fom" + File.separator + stdDir).getAbsolutePath() + File.separator;
        List<String> foms = new ArrayList<String>();
        for (String f : e.getFomPaths()) {
            String abs = new File(f).getAbsolutePath();
            foms.add(abs.startsWith(root) ? abs.substring(root.length()) : abs);
        }
        p.set("datasource.hla.foms", e.isDynamicFom() ? "dynamic" : String.join(";", foms));   // the endpoint's FOM source
        p.set("datasource.origin", "junction");
        p.set("datasource.originlabel", "Junction: " + r.label);
        String signature = p.toMap().toString();
        Cached c = cache.get(r.key);
        if (c != null && c.signature.equals(signature)) {
            return c.config;
        }
        int id = ("junction:" + r.key).hashCode();
        DatasourceConfig dc = new DatasourceConfig(id, new File("junction/" + r.key.replace('/', '-')), p);
        cache.put(r.key, new Cached(signature, dc));
        return dc;
    }
}
