package com.simweaver.rift;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.FederationProbe;
import com.simweaver.common.hla.FederationProbeFactory;
import com.simweaver.common.json.Json;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;

public class DatasourceConfig implements Json.Writable{



    public enum Standard {
        IEEE1516_2010("HLA Evolved (IEEE 1516-2010)"),
        IEEE1516_2025("HLA 4 (IEEE 1516-2025)"),
        DIS6("DIS 6 (IEEE 1278.1a-1998)"),
        DIS7("DIS 7 (IEEE 1278.1-2012)"),
        CDIS("C-DIS (SISO-STD-023-2024)"),
        OTHER("Other");

        private final String label;

        Standard(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    SimwProperties datasourceProps;

    private String name;
    private int id;
    private File file;
    private DatasourceConfig.Standard standard;
    private ArrayList<String> hlafoms;
    private boolean hlaDynamicFom;
    private int hlaFddTimeoutSecs;
    private String hlafomRootdir;
    private String hlalsd;
    private java.util.List<String> hlaLsdOptions;
    private String hlaFederation;
    //
    private String hlaFederateType;
    private boolean hlaReconnect;
    private int hlaReconnectIntervalSecs;
    //
    private String disInterface;
    private int disExId;
    private int disPort;
    private int disEntityTimeOutSecs;
    /** 0.9.18: the C-DIS decoder's settings ({@code datasource.cdis.*}) */
    private com.simweaver.common.dis.cdis.CdisSettings cdisSettings;
    //
    private FederationProbe federationProbe;
    //
    private String origin;
    private String originLabel;



    public DatasourceConfig(int id, File file, SimwProperties datasourceProps) {
        this.datasourceProps = datasourceProps;
        this.id = id;
        this.file = file;
        this.name = datasourceProps.getString("datasource.name",file.getName());
        this.standard = Standard.OTHER;
        // 0.9.15: a ;-separated list of choices, the first is the default
        this.hlaLsdOptions = com.simweaver.common.hla.session.LsdOptions.parse(datasourceProps.getString("datasource.hla.lsd",""));
        this.hlalsd = hlaLsdOptions.get(0);
        // 0.14.5: datasource.hla.customfomrootdir alone when set; else the site's etc/fom, then the program's fom
        String customFomRoot = datasourceProps.getString("datasource.hla.customfomrootdir", "").trim();
        this.hlafomRootdir = com.simweaver.common.config.SimwPaths.etcString("fom");
        switch (datasourceProps.getString("datasource.standard","other").trim().toLowerCase()){
            case "ieee1516-2010":
                this.standard = Standard.IEEE1516_2010;
                this.hlafomRootdir += File.separator + "ieee1516-2010";
                this.federationProbe = FederationProbeFactory.createHla3(this.hlalsd);
                break;

            case "ieee1516-2025":
                this.standard = Standard.IEEE1516_2025;
                this.hlafomRootdir += File.separator + "ieee1516-2025";
                this.federationProbe = FederationProbeFactory.createHla4(this.hlalsd);
                break;
            case  "dis6":
                this.standard = Standard.DIS6;
                this.hlafomRootdir += File.separator + "ieee1516-2010";
                this.federationProbe = FederationProbeFactory.createDis6("");
                break;
            case "dis7":
                this.standard = Standard.DIS7;
                this.hlafomRootdir += File.separator + "ieee1516-2010";
                this.federationProbe = FederationProbeFactory.createDis7("");
                break;
            case "cdis":
            case "c-dis":
                this.standard = Standard.CDIS;
                this.hlafomRootdir += File.separator + "ieee1516-2010";
                this.federationProbe = FederationProbeFactory.createCdis("");
                break;
            default:
                this.standard = Standard.OTHER;
                this.hlafomRootdir += File.separator +"ieee1516-2010";
                this.federationProbe = FederationProbeFactory.createMisc("");
                break;
        }

        this.hlafoms = new ArrayList<>();
        // NEW (0.9.14): datasource.hla.foms=dynamic takes the FOM from the federation (HLAcurrentFDD)
        String[] fomSetting = datasourceProps.getStringArray("datasource.hla.foms");
        this.hlaDynamicFom = com.simweaver.common.hla.session.DynamicFom.isDynamic(fomSetting)
                && (standard == Standard.IEEE1516_2010 || standard == Standard.IEEE1516_2025);
        this.hlaFddTimeoutSecs = Math.max(1, datasourceProps.getInt("datasource.hla.fddtimeoutseconds", 15));
        String stdFolder = standard == Standard.IEEE1516_2025 ? "ieee1516-2025" : "ieee1516-2010";
        java.util.List<java.nio.file.Path> roots = com.simweaver.common.config.SimwPaths.fomRoots(stdFolder, customFomRoot);
        this.hlafomRootdir = roots.get(0).toString();
        for (String fomFileName : hlaDynamicFom ? new String[0] : fomSetting) {
            // NEW (Junction): an absolute path is used as it is; 0.14.5: the first root that has the file
            hlafoms.add(com.simweaver.common.config.SimwPaths.findFom(roots, fomFileName.trim(), null).getPath());
        }
        // NEW (Junction): configurations made by another component, e.g. "junction"
        this.origin = datasourceProps.getString("datasource.origin", "");
        this.originLabel = datasourceProps.getString("datasource.originlabel", "");

        this.hlaFederation = datasourceProps.getString("datasource.hla.federation");
        //further customization:
        this.hlaFederateType = datasourceProps.getString("datasource.hla.customfederatetype","SimwRift");
        this.hlaReconnect = datasourceProps.getBoolean("datasource.hla.reconnectondisconnect",true);
        this.hlaReconnectIntervalSecs = datasourceProps.getInt("datasource.hla.reconnectintervalseconds",5);

        this.disInterface = datasourceProps.getString("datasource.dis.interface","0.0.0.0");
        this.disPort = datasourceProps.getInt("datasource.dis.port",3000);
        this.disEntityTimeOutSecs = datasourceProps.getInt("datasource.dis.entitytimeoutsecs",13);
        this.disExId = datasourceProps.getInt("datasource.dis.exid",1);
        java.util.List<String> cdisProblems = new ArrayList<>();
        this.cdisSettings = com.simweaver.common.dis.cdis.CdisSettings.fromProperties(datasourceProps, "datasource.cdis.", cdisProblems);
        if (standard == Standard.CDIS) {
            for (String problem : cdisProblems) {
                java.util.logging.Logger.getLogger(DatasourceConfig.class.getName())
                        .warning("Datasource " + name + ": " + problem + " (the default is used)");
            }
        }

        // 0.11.5: no connect here. The probe is shared (one per standard and LSD) and connects when
        // federations are first asked for (Create datasource), not each time the configurations are read

    }


    public SimwProperties getDatasourceProps() {
        return datasourceProps;
    }

    public String getName() {
        return name;
    }

    public int getId() {
        return id;
    }

    public File getFile() {
        return file;
    }

    public Standard getStandard() {
        return standard;
    }

    public ArrayList<String> getHlafoms() {
        return hlafoms;
    }

    /** @return true when the FOM comes from the federation's FDD ({@code datasource.hla.foms=dynamic}) */
    public boolean isHlaDynamicFom() {
        return hlaDynamicFom;
    }

    /** @return how long a dynamic datasource waits for the federation's FDD */
    public int getHlaFddTimeoutSecs() {
        return hlaFddTimeoutSecs;
    }

    public URL[] getHlaFomModuleUrls() {
        ArrayList<URL> urls = new ArrayList<>();
        for (String hlafom : hlafoms) {
            try{
                urls.add(Paths.get(hlafom).toUri().toURL());
            } catch (MalformedURLException e) {
            // Handle potential exception (e.g., return null or throw runtime exception)
                System.out.println("Invalid path sequence: " + hlafom);
            }
        }
        return urls.toArray(new URL[0]);
    }

    public String getHlafomRootdir() {
        return hlafomRootdir;
    }

    public String getHlalsd() {
        return hlalsd;
    }

    public String getHlaFederation() {
        return hlaFederation;
    }

    public String getHlaFederateType() {
        return hlaFederateType;
    }

    public boolean isHlaReconnect() {
        return hlaReconnect;
    }

    public int getHlaReconnectIntervalSecs() {
        return hlaReconnectIntervalSecs;
    }

    public String getDisInterface() {
        return disInterface;
    }

    public int getDisPort() {
        return disPort;
    }

    public int getDisEntityTimeOutSecs() {
        return disEntityTimeOutSecs;
    }

    public int getDisExId() {
        return disExId;
    }

    /** @return the C-DIS decoder settings (used by C-DIS datasources) */
    public com.simweaver.common.dis.cdis.CdisSettings getCdisSettings() {
        return cdisSettings;
    }

    public FederationProbe getFederationProbe() {
        return federationProbe;
    }

    /**
     * 0.9.15: the federation probe for an LSD (connected on first use and kept, like the default one).
     *
     * @param lsd null or the default = {@link #getFederationProbe()}
     */
    public FederationProbe getFederationProbe(String lsd) {
        if (lsd == null || lsd.trim().equals(hlalsd) || federationProbe == null) {
            return federationProbe;
        }
        String l = lsd.trim();
        if (standard != Standard.IEEE1516_2010 && standard != Standard.IEEE1516_2025) {
            return federationProbe;
        }
        // 0.11.5: shared and connected on first use (rescan), like the default one
        FederationProbe p = standard == Standard.IEEE1516_2025 ? FederationProbeFactory.createHla4(l) : FederationProbeFactory.createHla3(l);
        return p != null ? p : federationProbe;
    }

    /** @return the LSD choices of {@code datasource.hla.lsd}, the default first (never empty) */
    public java.util.List<String> getHlaLsdOptions() {
        return hlaLsdOptions;
    }

    /** NEW: "" for a settings file in etc/datasources, else the component that made it (e.g. "junction"). */
    public String getOrigin() {
        return origin;
    }

    /** NEW: where it came from, for the UI (e.g. "Junction: Blue - Red / blue"). */
    public String getOriginLabel() {
        return originLabel;
    }

    @Override
    public Object toJson() {

        Json.JsonObject jo = Json.object();
        jo.put("id",id);
        jo.put("name",name);
        jo.putIfNotNull("file",file.getName());
        jo.put("standard",standard);
        jo.put("standardlabel",standard.getLabel());
        jo.put("hlafomrootdir",hlafomRootdir);
        jo.put("hlafoms", hlaDynamicFom ? java.util.Collections.singletonList("dynamic") : hlafoms);
        jo.put("hladynamicfom", hlaDynamicFom);
        jo.put("hlalsd",hlalsd);
        jo.put("hlalsds",hlaLsdOptions);
        jo.put("hlafederation",hlaFederation);
        jo.put("hlafederatetype",hlaFederateType);
        jo.put("hlareconnect",hlaReconnect);
        jo.put("hlareconnectintervalsecs",hlaReconnectIntervalSecs);
        jo.put("disinterface",disInterface);
        jo.put("disport",disPort);
        jo.put("disentitytimeoutsecs",disEntityTimeOutSecs);
        jo.put("disexid",disExId);
        if (standard == Standard.CDIS) {
            jo.put("cdis", cdisSettings.toMap());
        }
        if (!origin.isEmpty()) {                       // NEW
            jo.put("origin", origin);
            jo.put("originlabel", originLabel);
        }
        return jo;

    }
}
