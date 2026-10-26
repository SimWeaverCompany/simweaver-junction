package com.simweaver.junction.config;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One endpoint of a gateway, from an endpoint kit ({@code etc/junctions/endpoints/*.endpoint.settings})
 * or inline in a gateway file. All keys start with {@code endpoint.}; see the Junction
 * README for the full list. Immutable.
 */
public final class EndpointConfig implements Json.Writable {

    /** Default federate type. */
    public static final String DEFAULT_FEDERATE_TYPE = "SimwJunction";

    private final String id;
    private final String name;
    private final String source;
    private final EndpointType type;
    private final String standardSetting;
    private final List<String> errors = new ArrayList<String>();

    // HLA
    private final String lsd;
    private final List<String> lsdOptions;
    private final List<String> fomNames;
    private final List<String> fomPaths;
    private final String fomRootDir;
    private final String federation;
    private final String federateType;
    private final String federateName;
    private final boolean createFederation;
    /** endpoint.hla.foms=dynamic: no FOM files, the federation's FDD (HLAcurrentFDD) after the join */
    private final boolean dynamicFom;
    private final int fddTimeoutSecs;
    /** endpoint.hla.createfoms (0.9.21): the modules a dynamic endpoint creates a missing federation with */
    private final List<String> createFomNames;
    private final List<String> createFomPaths;
    private final String fddClass;
    private final String fddAttribute;
    private final boolean destroyFederation;
    private final String rtiJar;
    private final String rtiFactory;
    private final String rtiAddress;
    private final String rtiSettings;
    private final boolean reconnect;
    private final int reconnectIntervalSecs;
    private final boolean talkHla4;
    private final List<String> referenceTypes;
    // DIS
    private final String disInterface;
    private final int disPort;
    private final int disExerciseId;
    private final int disSiteId;
    private final int disApplicationId;
    private final int disHeartbeatSecs;
    private final int disEntityTimeoutSecs;
    private final String disSendAddress;
    private final int disTtl;
    private final boolean disKeepEntityIds;
    private final com.simweaver.common.dis.cdis.CdisSettings cdisSettings;
    // per-endpoint filters (optional): what this endpoint contributes (in) and receives (out)
    private final ClassFilter inFilter;
    private final ClassFilter outFilter;

    /**
     * @param id      the endpoint id within its gateway (or the kit id)
     * @param p       properties with {@code endpoint.*} keys
     * @param source  where it came from (file name), for messages
     * @param baseDir runtime directory (FOM paths, RTI jars are relative to it)
     */
    public EndpointConfig(String id, SimwProperties p, String source, File baseDir) {
        this.id = id;
        this.source = source;
        this.name = p.getString("endpoint.name", id);
        this.standardSetting = p.getString("endpoint.standard", "");
        EndpointType t = EndpointType.fromSetting(standardSetting);
        if (t == null) {
            errors.add("endpoint " + id + ": unknown endpoint.standard '" + standardSetting
                    + "' (ieee1516-2010, ieee1516-2025, ieee1516-2025-fedpro, dis6, dis7, cdis, talk)");
        }
        this.type = t;
        String talkStd = p.getString("endpoint.talk.hlastandard", "ieee1516-2010");
        this.talkHla4 = EndpointType.fromSetting(talkStd) == EndpointType.HLA4;

        // 0.9.15: a ;-separated list of choices, the first is the default
        this.lsdOptions = com.simweaver.common.hla.session.LsdOptions.parse(p.getString("endpoint.hla.lsd", ""));
        this.lsd = lsdOptions.get(0);
        String std = isHla4() ? "ieee1516-2025" : "ieee1516-2010";
        this.fomRootDir = p.getString("endpoint.hla.fomrootdir", "etc" + File.separator + "fom") + File.separator + std;
        String[] foms = p.getStringArray("endpoint.hla.foms");
        boolean dyn = com.simweaver.common.hla.session.DynamicFom.isDynamic(foms);
        if (foms.length == 0 && t == EndpointType.TALK) {
            dyn = true;               // 0.9.21: no Talk FOM is delivered; without FOM files it comes from the federation
        }
        this.dynamicFom = dyn && t != null && t.isHla();
        if (dyn) {
            foms = new String[0];
        }
        this.fddTimeoutSecs = Math.max(1, p.getInt("endpoint.hla.fddtimeoutseconds", 15));
        this.fddClass = p.getString("endpoint.hla.fddclass", com.simweaver.common.hla.session.DynamicFom.FEDERATION_CLASS).trim();
        this.fddAttribute = p.getString("endpoint.hla.fddattribute", com.simweaver.common.hla.session.DynamicFom.FDD_ATTRIBUTE).trim();
        if (foms.length == 0 && t != null && t.isDis()) {
            if (dyn) {
                errors.add("endpoint " + id + ": a DIS endpoint cannot take its FOM from a federation (endpoint.hla.foms=dynamic)");
            }
            foms = new String[] {"RPR_FOM_v2.0_Link16_1516-2010.xml"};
        }
        this.fomNames = Collections.unmodifiableList(Arrays.asList(foms));
        this.fomPaths = Collections.unmodifiableList(resolveFoms(foms, baseDir));
        String[] createFoms = p.getStringArray("endpoint.hla.createfoms");
        if (createFoms.length > 0 && !dynamicFom) {
            errors.add("endpoint " + id + ": endpoint.hla.createfoms is for endpoints that take their FOM from the federation"
                    + " (endpoint.hla.foms=dynamic)");
            createFoms = new String[0];
        }
        this.createFomNames = Collections.unmodifiableList(Arrays.asList(createFoms));
        this.createFomPaths = Collections.unmodifiableList(resolveFoms(createFoms, baseDir));
        this.federation = p.getString("endpoint.hla.federation", "");
        this.federateType = p.getString("endpoint.hla.federatetype", DEFAULT_FEDERATE_TYPE);
        this.federateName = p.getString("endpoint.hla.federatename", "");
        // a dynamic endpoint has no FOM to create a federation with, unless endpoint.hla.createfoms gives one
        this.createFederation = (!dynamicFom || !createFomPaths.isEmpty()) && p.getBoolean("endpoint.hla.createfederation", true);
        this.destroyFederation = p.getBoolean("endpoint.hla.destroyfederation", false);
        this.rtiJar = p.getString("endpoint.hla.rtijar", "");
        this.rtiFactory = p.getString("endpoint.hla.rtifactory", "");
        this.rtiAddress = p.getString("endpoint.hla.rtiaddress", "");
        this.rtiSettings = p.getString("endpoint.hla.rtisettings", "").replace("\\n", "\n");
        this.reconnect = p.getBoolean("endpoint.hla.reconnect", true);
        this.reconnectIntervalSecs = Math.max(1, p.getInt("endpoint.hla.reconnectintervalseconds", 5));
        String[] refs = p.getStringArray("endpoint.hla.referencetypes");
        this.referenceTypes = refs.length == 0 ? Collections.singletonList("RTIobjectId")
                : Collections.unmodifiableList(Arrays.asList(refs));

        this.disInterface = p.getString("endpoint.dis.interface", "0.0.0.0");
        this.disPort = p.getInt("endpoint.dis.port", 3000);
        this.disExerciseId = p.getInt("endpoint.dis.exid", 1);
        this.disSiteId = p.getInt("endpoint.dis.siteid", 900);
        this.disApplicationId = p.getInt("endpoint.dis.appid", 1);
        this.disHeartbeatSecs = Math.max(1, p.getInt("endpoint.dis.heartbeatsecs", 5));
        this.disEntityTimeoutSecs = p.getInt("endpoint.dis.entitytimeoutsecs", 12);
        this.disSendAddress = p.getString("endpoint.dis.sendaddress", "");
        this.disTtl = p.getInt("endpoint.dis.ttl", 8);
        String ids = p.getString("endpoint.dis.entityids", "keep").trim().toLowerCase(java.util.Locale.ROOT);
        this.disKeepEntityIds = !"assign".equals(ids);
        // 0.9.18: C-DIS encoder/decoder settings
        List<String> cdisErrors = new ArrayList<String>();
        this.cdisSettings = com.simweaver.common.dis.cdis.CdisSettings.fromProperties(p, "endpoint.cdis.", cdisErrors);
        if (t == EndpointType.CDIS) {
            for (String e : cdisErrors) {
                errors.add("endpoint " + id + ": " + e);
            }
        }

        this.inFilter = ClassFilter.fromProperties(p, "endpoint.filter.in.");
        this.outFilter = ClassFilter.fromProperties(p, "endpoint.filter.out.");

        if (t != null && t.isHla() && fomNames.isEmpty() && !dynamicFom) {
            errors.add("endpoint " + id + ": endpoint.hla.foms is empty");
        }
        for (int i = 0; i < createFomPaths.size(); i++) {
            if (!new File(createFomPaths.get(i)).exists()) {
                errors.add("endpoint " + id + ": FOM module of endpoint.hla.createfoms not found: " + createFomPaths.get(i));
            }
        }
        for (int i = 0; i < fomPaths.size(); i++) {
            if (!new File(fomPaths.get(i)).exists()) {
                errors.add("endpoint " + id + ": FOM module not found: " + fomPaths.get(i));
            }
        }
        if (t == EndpointType.HLA4_FEDPRO && rtiAddress.isEmpty()) {
            errors.add("endpoint " + id + ": a Federate Protocol endpoint needs endpoint.hla.rtiaddress");
        }
        if (t != null && t.isDis() && (disExerciseId < 0 || disExerciseId > 255)) {
            errors.add("endpoint " + id + ": endpoint.dis.exid must be 0-255");
        }
        if (t != null && t.isDis() && (disSiteId < 1 || disSiteId > 65534 || disApplicationId < 1 || disApplicationId > 65534)) {
            errors.add("endpoint " + id + ": endpoint.dis.siteid and endpoint.dis.appid must be 1-65534");
        }
    }

    private List<String> resolveFoms(String[] names, File baseDir) {
        List<String> out = new ArrayList<String>();
        for (String n : names) {
            String s = n.trim();
            if (s.isEmpty()) {
                continue;
            }
            File f = new File(s);
            if (!f.isAbsolute()) {
                File inRoot = new File(fomRootDir, s);
                File rel = baseDir == null ? f : new File(baseDir, s);
                if (!inRoot.isAbsolute() && baseDir != null) {
                    inRoot = new File(baseDir, inRoot.getPath());
                }
                f = inRoot.exists() || !rel.exists() ? inRoot : rel;
            }
            if (f.isDirectory()) {
                File[] xml = f.listFiles((d, x) -> x.toLowerCase(Locale.ROOT).endsWith(".xml"));
                if (xml != null) {
                    Arrays.sort(xml);
                    for (File x : xml) {
                        out.add(x.getPath());
                    }
                }
            } else {
                out.add(f.getPath());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ accessors

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** @return the file this came from */
    public String getSource() {
        return source;
    }

    /** @return the type, or null when {@code endpoint.standard} is unknown */
    public EndpointType getType() {
        return type;
    }

    /** @return errors found while reading (missing FOMs, bad values): the endpoint cannot be used */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /** @return true when the HLA API is 1516-2025 (HLA4, FEDPRO, or TALK on HLA 4) */
    public boolean isHla4() {
        return type == EndpointType.HLA4 || type == EndpointType.HLA4_FEDPRO || type == EndpointType.TALK && talkHla4;
    }

    /** @return the default LSD (1516-2010) / RTI configuration name (1516-2025): the first choice; "" = the RTI's default */
    public String getLsd() {
        return lsd;
    }

    /** @return the LSD choices of {@code endpoint.hla.lsd}, the default first (never empty) */
    public List<String> getLsdOptions() {
        return lsdOptions;
    }

    /** @return the folder relative FOM names are found in (endpoint.hla.fomrootdir plus the standard) */
    public String getFomRootDir() {
        return fomRootDir;
    }

    /** @return the FOM names as configured */
    public List<String> getFomNames() {
        return fomNames;
    }

    /** @return the FOM files, resolved */
    public List<String> getFomPaths() {
        return fomPaths;
    }

    /** @return the default federation (HLA) */
    public String getFederation() {
        return federation;
    }

    public String getFederateType() {
        return federateType;
    }

    /** @return the federate name, "" = let the RTI choose */
    public String getFederateName() {
        return federateName;
    }

    public boolean isCreateFederation() {
        return createFederation;
    }

    /** @return true when the FOM comes from the federation ({@code endpoint.hla.foms=dynamic}) */
    public boolean isDynamicFom() {
        return dynamicFom;
    }

    /**
     * @return {@code endpoint.hla.createfoms}, resolved: the modules a dynamic endpoint creates its
     *         federation with when it is not running (empty: it only joins)
     */
    public List<String> getCreateFomPaths() {
        return createFomPaths;
    }

    /** @return {@code endpoint.hla.createfoms} as configured */
    public List<String> getCreateFomNames() {
        return createFomNames;
    }

    /** @return how long a dynamic endpoint waits for the federation's FDD */
    public int getFddTimeoutSecs() {
        return fddTimeoutSecs;
    }

    /** @return the MOM class with the FDD ({@code endpoint.hla.fddclass}) */
    public String getFddClass() {
        return fddClass;
    }

    /** @return the MOM attribute with the FDD ({@code endpoint.hla.fddattribute}) */
    public String getFddAttribute() {
        return fddAttribute;
    }

    public boolean isDestroyFederation() {
        return destroyFederation;
    }

    /** @return the RTI library setting ("" = class path) */
    public String getRtiJar() {
        return rtiJar;
    }

    public String getRtiFactory() {
        return rtiFactory;
    }

    public String getRtiAddress() {
        return rtiAddress;
    }

    public String getRtiSettings() {
        return rtiSettings;
    }

    public boolean isReconnect() {
        return reconnect;
    }

    public int getReconnectIntervalSecs() {
        return reconnectIntervalSecs;
    }

    /** @return datatype names that hold object instance names */
    public List<String> getReferenceTypes() {
        return referenceTypes;
    }

    public String getDisInterface() {
        return disInterface;
    }

    public int getDisPort() {
        return disPort;
    }

    /** @return the default exercise id (0 = all when receiving) */
    public int getDisExerciseId() {
        return disExerciseId;
    }

    public int getDisSiteId() {
        return disSiteId;
    }

    public int getDisApplicationId() {
        return disApplicationId;
    }

    public int getDisHeartbeatSecs() {
        return disHeartbeatSecs;
    }

    public int getDisEntityTimeoutSecs() {
        return disEntityTimeoutSecs;
    }

    /** @return where DIS PDUs are sent ("" = derived from the interface) */
    public String getDisSendAddress() {
        return disSendAddress;
    }

    public int getDisTtl() {
        return disTtl;
    }

    /**
     * {@code endpoint.dis.entityids}: {@code keep} (default) sends an HLA entity with its
     * EntityIdentifier as its DIS entity id when that id is free on the network;
     * {@code assign} always gives it an id of this endpoint (siteid, appid, a counter).
     */
    public boolean isDisKeepEntityIds() {
        return disKeepEntityIds;
    }

    /** @return the C-DIS settings ({@code endpoint.cdis.*}; used by C-DIS endpoints) */
    public com.simweaver.common.dis.cdis.CdisSettings getCdisSettings() {
        return cdisSettings;
    }

    /** @return what this endpoint contributes (classes it receives and hands to the router) */
    public ClassFilter getInFilter() {
        return inFilter;
    }

    /** @return what this endpoint is given by the router */
    public ClassFilter getOutFilter() {
        return outFilter;
    }

    /** @return the default target: the federation (HLA) or the exercise id (DIS) */
    /** 0.9.16: the target setting as written, when it holds group variables (e.g. "GROUPVAR1-Talk"); null otherwise */
    private volatile String targetTemplate;

    void setTargetTemplate(String t) {
        this.targetTemplate = t;
    }

    /** @return the target setting before group variables are replaced, when it has any; else null */
    public String getTargetTemplate() {
        return targetTemplate;
    }

    public String getDefaultTarget() {
        if (type != null && type.isDis()) {
            return String.valueOf(disExerciseId);
        }
        return federation;
    }

    @Override
    public Object toJson() {
        Json.JsonObject o = Json.object();
        o.put("id", id);
        o.put("name", name);
        o.put("source", source);
        o.put("standard", type == null ? standardSetting : type.setting());
        o.put("standardlabel", type == null ? "Unknown (" + standardSetting + ")" : type.label());
        o.put("ishla", type != null && type.isHla());
        o.put("isdis", type != null && type.isDis());
        o.put("istalk", type == EndpointType.TALK);
        o.put("defaulttarget", getDefaultTarget());
        o.putIfNotNull("targettemplate", targetTemplate);
        if (type != null && type.isHla()) {
            o.put("hlaversion", isHla4() ? "IEEE 1516-2025" : "IEEE 1516-2010");
            o.put("lsd", lsd);
            o.put("lsds", lsdOptions);
            o.put("foms", dynamicFom ? java.util.Collections.singletonList("dynamic") : fomNames);
            o.put("dynamicfom", dynamicFom);
            if (!createFomNames.isEmpty()) {
                o.put("createfoms", createFomNames);
            }
            o.put("federatetype", federateType);
            o.put("createfederation", createFederation);
            o.put("rtijar", rtiJar);
            o.putIfNotNull("rtiaddress", rtiAddress.isEmpty() ? null : rtiAddress);
        }
        if (type != null && type.isDis()) {
            o.put("disinterface", disInterface);
            o.put("disport", disPort);
            o.put("disexid", disExerciseId);
            o.put("dissiteid", disSiteId);
            o.put("disappid", disApplicationId);
            o.put("disentityids", disKeepEntityIds ? "keep" : "assign");
            o.putIfNotNull("dissendaddress", disSendAddress.isEmpty() ? null : disSendAddress);
            o.put("foms", fomNames);
        }
        if (type == EndpointType.CDIS) {
            o.put("iscdis", true);
            o.put("cdis", cdisSettings.toMap());
        }
        o.put("errors", errors);
        return o;
    }
}
