package com.simweaver.rift.api;

import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.hla.RtiAvailability;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.datasources.RiftCdisConnection;
import com.simweaver.rift.datasources.RiftDis6Connection;
import com.simweaver.rift.datasources.RiftDis7Connection;
import com.simweaver.rift.datasources.RiftFederateHla3;
import com.simweaver.rift.datasources.RiftFederateHla4;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;

public class DatasourceFactory {

    /** Thrown when a configuration needs an HLA RTI library that is not on the class path. */
    public static final class RtiNotAvailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RtiNotAvailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }


    /**
     * Creates the datasource for the configuration's standard. DIS needs no RTI. For HLA, the
     * RTI library of that version must be on the class path, else RtiNotAvailableException.
     * Returns null for an unsupported standard.
     */
    public static DataSource createDatasource(DatasourceConfig config, String customFederation, DataSourceCallbackSink callbackSink) {
        return createDatasource(config, customFederation, null, callbackSink);
    }

    /** @param lsd 0.9.15, HLA: the LSD / RTI configuration name to connect with; null = the configuration's default */
    public static DataSource createDatasource(DatasourceConfig config, String customFederation, String lsd, DataSourceCallbackSink callbackSink) {
        switch (config.getStandard()) {
            case IEEE1516_2025:
                if (!RtiAvailability.HLA_4) throw noRti("IEEE 1516-2025", "hla.rti1516_2025", null);
                try {
                    return Hla4.create(config, customFederation, lsd, callbackSink);
                } catch (LinkageError e) {                 // RTI API present, but the library is incomplete
                    throw noRti("IEEE 1516-2025", "hla.rti1516_2025", e);
                }
            case IEEE1516_2010:
                if (!RtiAvailability.HLA_EVOLVED) throw noRti("IEEE 1516-2010", "hla.rti1516e", null);
                try {
                    return Hla3.create(config, customFederation, lsd, callbackSink);
                } catch (LinkageError e) {
                    throw noRti("IEEE 1516-2010", "hla.rti1516e", e);
                }
            case DIS6:
                return new RiftDis6Connection(config, customFederation, callbackSink);
            case DIS7:
                return new RiftDis7Connection(config, customFederation, callbackSink);
            case CDIS:
                return new RiftCdisConnection(config, customFederation, callbackSink);
            default:
                return null;
        }
    }

    private static RtiNotAvailableException noRti(String standard, String api, Throwable cause) {
        return new RtiNotAvailableException("This configuration needs the HLA RTI library for " + standard
                + " (" + api + ") on the class path. Set RTI_JAR in the start script.", cause);
    }

    // The HLA datasources are created in their own holder classes. The JVM loads a holder only
    // when it is first used (inside the try above), so this factory never needs the RTI classes.
    private static final class Hla3 {
        static DataSource create(DatasourceConfig c, String f, String lsd, DataSourceCallbackSink s) {
            return new RiftFederateHla3(c, f, lsd, s);
        }
    }

    private static final class Hla4 {
        static DataSource create(DatasourceConfig c, String f, String lsd, DataSourceCallbackSink s) {
            return new RiftFederateHla4(c, f, lsd, s);
        }
    }

    public static String generateDataSourceName(String configName, String federationName){
        Instant now = Instant.now();
        DateTimeFormatter militaryFormat = DateTimeFormatter.ofPattern("yyyyMMMdd'-'HHmm'Z'")
                .withZone(ZoneId.of("UTC"))
                .withLocale(Locale.forLanguageTag("us"));
        DateTimeFormatter isoZuluFormat = DateTimeFormatter.ISO_INSTANT;
        String time = militaryFormat.format(now).toUpperCase();

        return configName + " - " + federationName + " - " + time;
    }

    public static ArrayList<String> createInteractionClassNameList(InformationExchangeModel iem){
        return createInteractionClassNameList(iem.getInteractionClassRoot());
    }

    public static ArrayList<String> createInteractionClassNameList(InteractionClass interactionClass){
        ArrayList<String> childinteractionClassNames  = new ArrayList<>();
        for (InteractionClass childClass : interactionClass.getChildClasses()) {
            childinteractionClassNames.addAll(createInteractionClassNameList(childClass));
            childinteractionClassNames.add(childClass.getQualifiedName());
        }
        return childinteractionClassNames;
    }

    public static ArrayList<String> createObjectClassNameList(InformationExchangeModel iem){
        return createObjectClassNameList(iem.getObjectClassRoot());
    }

    public static ArrayList<String> createObjectClassNameList(ObjectClass objectClass){
        ArrayList<String> childObjectClassNames = new ArrayList<>();
        for (ObjectClass childClass : objectClass.getChildClasses()) {
            childObjectClassNames.addAll(createObjectClassNameList(childClass));
            childObjectClassNames.add(childClass.getQualifiedName());
        }
        return childObjectClassNames;
    }
}
