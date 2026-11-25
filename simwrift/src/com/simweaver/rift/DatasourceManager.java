package com.simweaver.rift;


import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.FederationProbe;
import com.simweaver.common.json.Json;
import com.simweaver.common.logging.Events;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.common.rest.XrSseHub;
import com.simweaver.rift.api.DataSource;
import com.simweaver.rift.api.DataSourceCallbackSink;
import com.simweaver.rift.api.DatasourceFactory;
import com.simweaver.rift.api.RiftEventPublisher;
import com.simweaver.rift.api.RiftListProfiles;
import com.simweaver.rift.api.RiftPublisherListener;
import com.simweaver.rift.db.RiftDatabase;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class DatasourceManager implements DataSourceCallbackSink {

    private static final Logger LOG = Logger.getLogger(DatasourceManager.class.getName());

    /** NEW: optional list-view profiles (see RiftListProfiles.fromProperties); RPR defaults when absent. */
    /** 0.11.4: in the configuration folder (etcdir) */
    private static Path listProfilesFile() {
        return com.simweaver.common.config.SimwPaths.etc("listprofiles.properties");
    }

    private volatile List<DatasourceConfig> configs;                       // FIX: replaced by reloadConfigs on HTTP threads
    private final List<DataSource> dataSources = new CopyOnWriteArrayList<>();   // FIX: read/changed by HTTP and RTI threads
    private final XrSseHub eventHub;

    // NEW: live updates per datasource, and the state shown in the page header
    private final RiftListProfiles listProfiles;
    private final Map<UUID, RiftEventPublisher> publishers = new ConcurrentHashMap<>();
    /** 0.12.0: the Comms view of each datasource */
    private final Map<UUID, com.simweaver.rift.comms.CommsTracker> comms = new ConcurrentHashMap<>();
    private final Map<UUID, String[]> states = new ConcurrentHashMap<>();  // {state, statemessage}
    // 0.9.22: {configuration name, federation or ""} per datasource, for the log events
    private final Map<UUID, String[]> labels = new ConcurrentHashMap<>();

    // 0.14.0: datasources that lost their RTI connection (and were removed), newest first, at most 20
    private final java.util.ArrayDeque<String[]> recentLosses = new java.util.ArrayDeque<>();

    // NEW: version, CPU and memory for GET /api/serverinfo (the web UI's top bar)
    private final ServerInfo serverInfo = new ServerInfo("SimWeaver Rift", Version::getVersion);

    // NEW (Junction): configurations supplied by other components, listed after the files
    private final List<java.util.function.Supplier<List<DatasourceConfig>>> externalConfigs = new CopyOnWriteArrayList<>();


    public DatasourceManager(XrSseHub eventHub) {
        this.configs = new ArrayList<>();
        this.eventHub = eventHub;
        this.listProfiles = loadListProfiles();
        reloadConfigs();
    }

    /** NEW: the list-view profiles; give the same instance to ApiRequestEntryPoint. */
    public RiftListProfiles getListProfiles() {
        return listProfiles;
    }

    public void reloadConfigs(){

        Path dirPath = com.simweaver.common.config.SimwPaths.etc("datasources");               // 0.11.4: etcdir

        try (Stream<Path> stream = Files.list(dirPath)) {

            List<File> files = stream
                    .filter(Files::isRegularFile) // Excludes directories, symlinks, etc.
                    .filter(Files::isReadable)    // Checks if the file is readable
                    .map(Path::toFile)            // Converts Path to java.io.File object
                    .collect(Collectors.toList());

            List<DatasourceConfig> loaded = new ArrayList<>();                  // FIX: swap in when complete


            for (File file : files) {
                SimwProperties datasourceProps = new SimwProperties();
                datasourceProps.load(file);

                // FIX: the second check was datasource.name again; without datasource.standard the
                // getString(...) below threw a NullPointerException and the whole reload failed
                if (datasourceProps.hasValue("datasource.name")
                        && datasourceProps.hasValue("datasource.standard")
                    && (datasourceProps.getString("datasource.standard").equalsIgnoreCase("IEEE1516-2010")
                || datasourceProps.getString("datasource.standard").equalsIgnoreCase("IEEE1516-2025") ||
                        datasourceProps.getString("datasource.standard").equalsIgnoreCase("DIS6")
                || datasourceProps.getString("datasource.standard").equalsIgnoreCase("DIS7")
                || datasourceProps.getString("datasource.standard").equalsIgnoreCase("CDIS")
                || datasourceProps.getString("datasource.standard").equalsIgnoreCase("C-DIS"))){
                    // the file has the basic elements of validity
                    DatasourceConfig config = new DatasourceConfig(file.hashCode(),file, datasourceProps);
                    loaded.add(config);
                }
            }
            addExternal(loaded);
            this.configs = loaded;


        } catch (IOException e) {
            LOG.warning("Error reading the datasource configurations: " + e.getMessage());
            // NEW: the external configurations do not depend on the folder
            List<DatasourceConfig> loaded = new ArrayList<>();
            addExternal(loaded);
            this.configs = loaded;
        }
    }

    /**
     * NEW (Junction): adds a source of configurations that are not files in etc/datasources.
     * They are listed with the others (GET /api/datasourceconfigs) and can be created like
     * them; the supplier is asked again on every reload and should return the same instances
     * while nothing changed (each instance has its own federation probe).
     */
    public void addExternalConfigs(java.util.function.Supplier<List<DatasourceConfig>> source) {
        externalConfigs.add(source);
        reloadConfigs();
    }

    private void addExternal(List<DatasourceConfig> into) {
        for (java.util.function.Supplier<List<DatasourceConfig>> source : externalConfigs) {
            try {
                List<DatasourceConfig> l = source.get();
                if (l != null) into.addAll(l);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "External datasource configurations failed: " + e, e);
            }
        }
    }

    public synchronized String jsonGetDatasourceConfigs() {
        reloadConfigs();
        return Json.object().put("datasourceconfigs",this.configs).toString();
    }

    public String jsonGetDatasources() {
        return Json.object().put("datasources",this.dataSources).toString();
    }

    public String jsonGetDatasourceConfigAutoFederations(String datasourceConfigId) {
        return jsonGetDatasourceConfigAutoFederations(datasourceConfigId, null);
    }

    /** @param lsd 0.9.15: ask the RTI with this LSD instead of the configuration's default */
    public String jsonGetDatasourceConfigAutoFederations(String datasourceConfigId, String lsd) {
        List<String> federations = new ArrayList<>();

        try{
            int intId = Integer.parseInt(datasourceConfigId);
            for (DatasourceConfig config : this.configs) {
                if (config.getId() == intId){
                    FederationProbe federationProbe = config.getFederationProbe(lsd);
                    if (federationProbe != null){
                        federationProbe.rescan();
                        federations = federationProbe.getFederations();
                    }

                }
            }

        }catch (NumberFormatException nfe){
            LOG.fine("autofederations: '" + datasourceConfigId + "' is not a configuration id");
        }

        return Json.object().put("federations",federations).toString();
    }

    public int getConfigCount() {
        return configs.size();
    }

    public XrResultPair jsonSetupDatasource(String configid, String federation) {
        return jsonSetupDatasource(configid, federation, null);
    }

    /** @param lsd 0.9.15, HLA: the LSD / RTI configuration name to connect with; null = the configuration's default */
    public XrResultPair jsonSetupDatasource(String configid, String federation, String lsd) {
        int intConfigId;
        try{
            intConfigId = Integer.parseInt(configid);
        }
        catch (NumberFormatException nfe){
            return new XrResultPair(createJsonResultFailure("INVALID_REQUEST","Config ID must be an integer"),400);
        }
        for (DatasourceConfig config : this.configs) {
            if (config.getId() == intConfigId){
                return jsonSetupDatasource(config, federation, lsd);
            }
        }
        return new XrResultPair(createJsonResultFailure("CONFIG_NOT_FOUND","The specified configuration doesn't exist"),404);
    }

    /** NEW (Junction): creates a datasource from a configuration object (also one that is not in the list). */
    public XrResultPair jsonSetupDatasource(DatasourceConfig config, String federation) {
        return jsonSetupDatasource(config, federation, null);
    }

    public XrResultPair jsonSetupDatasource(DatasourceConfig config, String federation, String lsd) {
        SetupResult r = setupDatasource(config, federation, lsd);
        if (r.dataSource != null) {
            return new XrResultPair(createJsonCreateResultSuccess(r.dataSource),201);
        }
        return new XrResultPair(createJsonResultFailure(r.errorCode, r.message), r.status);
    }

    /** NEW (Junction): the result of {@link #setupDatasource}: the datasource, or why there is none. */
    public static final class SetupResult {
        public final DataSource dataSource;
        public final int status;
        public final String errorCode;
        public final String message;

        SetupResult(DataSource ds, int status, String errorCode, String message) {
            this.dataSource = ds;
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
        }
    }

    /** NEW (Junction): connects, joins and subscribes a datasource of this configuration. */
    public SetupResult setupDatasource(DatasourceConfig config, String federation) {
        return setupDatasource(config, federation, null);
    }

    /** @param lsd 0.9.15, HLA: the LSD / RTI configuration name to connect with; null = the configuration's default */
    public SetupResult setupDatasource(DatasourceConfig config, String federation, String lsd) {
        SetupResult r = createDatasource(config, federation, lsd);
        String fed = federation == null || federation.trim().isEmpty() ? null : federation.trim();
        if (r.dataSource != null) {
            labels.put(r.dataSource.getId(), new String[] {config.getName(), fed == null ? "" : fed,
                    config.getStandard() == null ? "" : config.getStandard().name(),
                    config.getStandard() == null ? "" : config.getStandard().getLabel(), java.time.Instant.now().toString()});
            Events.notice(LOG, "datasource.created", "Datasource " + config.getName() + (fed == null ? "" : " (" + fed + ")")
                    + " created", "id", r.dataSource.getId(), "config", config.getName(), "standard", config.getStandard() == null ? null : config.getStandard().name(),
                    "federation", fed, "lsd", lsd == null || lsd.isEmpty() ? null : lsd);
        } else {
            Events.warning(LOG, "datasource.createfailed", "Datasource " + config.getName() + (fed == null ? "" : " (" + fed + ")")
                    + " could not be created: " + r.message, "config", config.getName(), "standard", config.getStandard() == null ? null : config.getStandard().name(),
                    "federation", fed, "lsd", lsd == null || lsd.isEmpty() ? null : lsd, "code", r.errorCode);
        }
        return r;
    }

    private SetupResult createDatasource(DatasourceConfig config, String federation, String lsd) {
        DataSource dataSource;
        try {
            dataSource = DatasourceFactory.createDatasource(config, federation, lsd, this);
        } catch (DatasourceFactory.RtiNotAvailableException e) {
            return new SetupResult(null, 503, "RTI_NOT_AVAILABLE", e.getMessage());
        }
        if (dataSource == null) {
            return new SetupResult(null, 400, "UNSUPPORTED_STANDARD", "No datasource for the standard " + config.getStandard());
        }

        // NEW: live updates. The listener is added before joining, so every discovery
        // during the join reaches the page (and the class counts stay right).
        RiftDatabase db = dataSource.getDatabase();
        RiftEventPublisher publisher = new RiftEventPublisher(eventHub, dataSource.getId().toString(),
                db.resolver(), listProfiles);
        db.addListener(new RiftPublisherListener(publisher));
        publishers.put(dataSource.getId(), publisher);
        // 0.12.0: the Comms view (Talk channels, radio frequencies, chat, audio)
        com.simweaver.rift.comms.CommsTracker tracker = new com.simweaver.rift.comms.CommsTracker(db, dataSource.getId().toString(),
                eventHub, commsSettings(config));
        db.addListener(tracker);
        comms.put(dataSource.getId(), tracker);
        setState(dataSource.getId(), "JOINING", null);

        OpResult result = dataSource.connectJoinSub();
        if (result.isSuccess()){
            dataSources.add(dataSource);
            setState(dataSource.getId(), "JOINED", null);
            return new SetupResult(dataSource, 201, null, null);
        }
        else{
            // FIX: a failed join left the RTI connected and the database threads running
            dataSource.disconnect();
            discard(dataSource.getId(), db);
            return new SetupResult(null, 500, "INTERNAL_ERROR", result.getMessage());
        }
    }

    /**
     * FIX: the reply carries the new datasource's id and name, as GET /api/datasources lists it, so
     * the front end can highlight the new row (it was {configid, federation}).
     */
    private String createJsonCreateResultSuccess(DataSource dataSource){
        return Json.object().put("success",true).put("datasource",dataSource).toString();
    }

    private String createJsonResultFailure(String errorCode,String errorMessage){
        Json.JsonObject errorObject = Json.object().put("code", errorCode).put("message", errorMessage);
        return Json.object().put("success",false).put("error",errorObject).toString();
    }

    public int getDatasourceCount() {
        return dataSources.size();
    }

    /**
     * NEW: GET /api/serverinfo, e.g.
     * {"serverinfo":{"name":"SimWeaver Rift","version":"1.4.2","started":"...","now":"...","uptimeseconds":5400,
     *  "pid":12345,"java":"11.0.21","os":"Windows 10 10.0 amd64",
     *  "cpu":{"process":3.4,"system":17.2,"cores":8},
     *  "memory":{"heapused":...,"heapcommitted":...,"heapmax":...,"nonheapused":...,
     *            "processcommitted":...,"physicaltotal":...,"physicalfree":...},
     *  "threads":57,"datasources":2}}
     * CPU in percent of the whole machine; memory in bytes; null where the JVM cannot tell.
     */
    public String jsonGetServerInfo() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("serverinfo", serverInfo.snapshot(dataSources.size()));
        return Jsons.toJson(m);
    }


    public XrResultPair jsonTeardownDatasource(String datasourceId) {
        for (DataSource dataSource : dataSources) {
            if (dataSource.getId().toString().equalsIgnoreCase(datasourceId)){
                OpResult disconnectResult = dataSource.disconnect();
                String[] label = labels.getOrDefault(dataSource.getId(), new String[] {"?", ""});
                String what = "Datasource " + label[0] + (label[1].isEmpty() ? "" : " (" + label[1] + ")");
                if (disconnectResult.isSuccess()){
                    labels.remove(dataSource.getId());
                    Events.notice(LOG, "datasource.removed", what + " removed", "id", dataSource.getId(), "config", label[0],
                            "federation", label[1].isEmpty() ? null : label[1]);
                    dataSources.remove(dataSource);
                    // NEW: open pages go back to the overview; the database threads stop
                    RiftEventPublisher publisher = publishers.get(dataSource.getId());
                    if (publisher != null) publisher.datasourceRemoved();
                    discard(dataSource.getId(), dataSource.getDatabase());
                    return new XrResultPair("",204);                              // FIX: 204 has no body
                }
                else{
                    Events.warning(LOG, "datasource.removefailed", what + " could not be removed: " + disconnectResult.getMessage(),
                            "id", dataSource.getId(), "config", label[0], "federation", label[1].isEmpty() ? null : label[1],
                            "code", "RESIGN_FAILED");
                    return new XrResultPair(createJsonResultFailure("RESIGN_FAILED", disconnectResult.getMessage()),409);
                }
            }
        }
        return new XrResultPair(createJsonResultFailure("UNKNOWN_DATASOURCE","The datasource do not exist"),404);
    }

    /** NEW: the state for the page header ("JOINING", "JOINED", "CONNECTION_LOST"), or null. */
    public String getDatasourceState(UUID datasourceId) {
        String[] s = states.get(datasourceId);
        return s == null ? null : s[0];
    }

    /** NEW: the message that goes with the state, or null when all is well. */
    public String getDatasourceStateMessage(UUID datasourceId) {
        String[] s = states.get(datasourceId);
        return s == null ? null : s[1];
    }

    private void setState(UUID datasourceId, String state, String message) {
        states.put(datasourceId, new String[] {state, message});
        RiftEventPublisher publisher = publishers.get(datasourceId);
        if (publisher != null) publisher.stateChanged(state, message);
    }

    /** 0.12.0: the Comms view of a datasource, or null. */
    public com.simweaver.rift.comms.CommsTracker getComms(UUID datasourceId) {
        return datasourceId == null ? null : comms.get(datasourceId);
    }

    /** {@code datasource.talk.samplerate} (16000) and {@code datasource.talk.pcmbyteorder} (little) of a configuration. */
    static com.simweaver.rift.comms.CommsTracker.Settings commsSettings(DatasourceConfig config) {
        com.simweaver.common.config.SimwProperties p = config.getDatasourceProps();
        if (p == null) {
            return com.simweaver.rift.comms.CommsTracker.Settings.defaults();
        }
        int rate = p.getInt("datasource.talk.samplerate", 16000);
        String order = p.getString("datasource.talk.pcmbyteorder", "little").trim().toLowerCase(java.util.Locale.ROOT);
        return new com.simweaver.rift.comms.CommsTracker.Settings(rate, !order.startsWith("big"));
    }

    /** Forgets a datasource's publisher and state, and stops its database (not on the caller's thread). */
    private void discard(UUID datasourceId, RiftDatabase db) {
        com.simweaver.rift.comms.CommsTracker tracker = comms.remove(datasourceId);
        if (tracker != null) {
            tracker.closeAll();
        }
        publishers.remove(datasourceId);
        states.remove(datasourceId);
        if (db != null) {
            Thread t = new Thread(db::close, "rift-db-close");        // close() may wait up to 5 s for the backlog
            t.setDaemon(true);
            t.start();
        }
    }

    private static RiftListProfiles loadListProfiles() {
        Path LIST_PROFILES_FILE = listProfilesFile();
        if (!Files.isReadable(LIST_PROFILES_FILE)) return RiftListProfiles.rprDefaults();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(LIST_PROFILES_FILE)) {
            p.load(in);
        } catch (IOException e) {
            LOG.warning("Error reading " + LIST_PROFILES_FILE + ": " + e.getMessage() + " (using the RPR defaults)");
            return RiftListProfiles.rprDefaults();
        }
        return RiftListProfiles.fromProperties(p, RiftListProfiles.PROPERTY_PREFIX, RiftListProfiles.rprDefaults());
    }



    // overrides of DataSourceCallbackSink


    /**
     * Called on an RTI thread when a datasource lost its RTI connection. Keeps the previous
     * behaviour (the datasource is dropped) and tells open pages first.
     */
    @Override
    public void connectionLost(UUID datasourceId) {
        connectionLost(datasourceId, null);
    }

    /** 0.9.22: with the reason, logged as datasource.connectionlost. */
    @Override
    public void connectionLost(UUID datasourceId, String reason) {
        for (DataSource dataSource : dataSources) {
            if (dataSource.getId().equals(datasourceId)){                  // FIX: was ==, a reference comparison
                String[] label = labels.getOrDefault(datasourceId, new String[] {"?", ""});
                labels.remove(datasourceId);
                Events.warning(LOG, "datasource.connectionlost", "Datasource " + label[0]
                        + (label[1].isEmpty() ? "" : " (" + label[1] + ")") + " lost its connection"
                        + (reason == null || reason.isEmpty() ? "" : ": " + reason) + "; it is removed",
                        "id", datasourceId, "config", label[0], "federation", label[1].isEmpty() ? null : label[1],
                        "by", "rti");
                setState(datasourceId, "CONNECTION_LOST", "The connection to the RTI was lost");
                synchronized (recentLosses) {                              // 0.14.0: for the MCP health check
                    recentLosses.addFirst(new String[] {java.time.Instant.now().toString(), label[0], label[1],
                            reason == null ? "" : reason});
                    while (recentLosses.size() > 20) {
                        recentLosses.removeLast();
                    }
                }
                RiftEventPublisher publisher = publishers.get(datasourceId);
                if (publisher != null) publisher.datasourceRemoved();
                dataSources.remove(dataSource);                            // FIX: safe while iterating (copy-on-write list)
                discard(datasourceId, dataSource.getDatabase());
            }
        }
    }

    /**
     * 0.14.0 (MCP tools): the running datasources: id, name, config, standard, standardlabel,
     * federation (HLA) or "", created, state, statemessage, objects, database state.
     */
    public List<Map<String, Object>> describeDatasources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataSource ds : dataSources) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            Object own = com.simweaver.common.rest.JsonParser.parse(Json.toJson(ds));
            String[] label = labels.getOrDefault(ds.getId(), new String[] {"?", "", "", "", ""});
            m.put("id", ds.getId().toString());
            m.put("name", own instanceof Map ? ((Map<?, ?>) own).get("name") : null);
            m.put("config", label[0]);
            m.put("standard", label.length > 2 ? label[2] : "");
            m.put("standardlabel", label.length > 3 ? label[3] : "");
            m.put("federation", label[1]);
            m.put("created", label.length > 4 ? label[4] : null);
            m.put("state", getDatasourceState(ds.getId()));
            m.put("statemessage", getDatasourceStateMessage(ds.getId()));
            RiftDatabase db = ds.getDatabase();
            m.put("objects", db == null ? 0 : db.getInstanceCount());
            m.put("database", db == null ? null : db.getState().name());
            out.add(m);
        }
        return out;
    }

    /** 0.14.0: {time, config, federation, reason} of the datasources lost lately, newest first. */
    public List<String[]> recentLosses() {
        synchronized (recentLosses) {
            return new ArrayList<>(recentLosses);
        }
    }

    /** 0.14.0: the configurations (reloaded), for the MCP tools. */
    public synchronized List<DatasourceConfig> getConfigs() {
        reloadConfigs();
        return new ArrayList<>(configs);
    }

    @Override
    public DataSource getDatasource(UUID uuid) {
        for (DataSource dataSource : dataSources) {
            if (dataSource.getId().equals(uuid)){
                return dataSource;
            }
        }
        return null;
    }


    ///////////////////////////////

    // helper class
    public static class OpResult{
       private boolean success;
       private String message;

        public OpResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
