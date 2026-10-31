package com.simweaver.common.infra;

/** DIS PDU type names (IEEE 1278.1-2012 / SISO-REF-010), for the Infra view. */
public final class DisPduTypes {

    private static final String[] NAMES = {
        "Other", "Entity State", "Fire", "Detonation", "Collision", "Service Request", "Resupply Offer",
        "Resupply Received", "Resupply Cancel", "Repair Complete", "Repair Response", "Create Entity",
        "Remove Entity", "Start/Resume", "Stop/Freeze", "Acknowledge", "Action Request", "Action Response",
        "Data Query", "Set Data", "Data", "Event Report", "Comment", "Electromagnetic Emission", "Designator",
        "Transmitter", "Signal", "Receiver", "IFF", "Underwater Acoustic", "Supplemental Emission",
        "Intercom Signal", "Intercom Control", "Aggregate State", "IsGroupOf", "Transfer Ownership", "IsPartOf",
        "Minefield State", "Minefield Query", "Minefield Data", "Minefield Response NACK",
        "Environmental Process", "Gridded Data", "Point Object State", "Linear Object State",
        "Areal Object State", "TSPI", "Appearance", "Articulated Parts", "LE Fire", "LE Detonation",
        "Create Entity-R", "Remove Entity-R", "Start/Resume-R", "Stop/Freeze-R", "Acknowledge-R",
        "Action Request-R", "Action Response-R", "Data Query-R", "Set Data-R", "Data-R", "Event Report-R",
        "Comment-R", "Record-R", "Set Record-R", "Record Query-R", "Collision-Elastic", "Entity State Update",
        "Directed Energy Fire", "Entity Damage Status", "Information Operations Action",
        "Information Operations Report", "Attribute"
    };

    private DisPduTypes() {
    }

    /** @return the name of a PDU type, or "PDU type n" */
    public static String name(int type) {
        return type >= 0 && type < NAMES.length ? NAMES[type] : "PDU type " + type;
    }

    /** @return true for the Live Entity family (TSPI ... LE Detonation): 8-bit site and application numbers */
    static boolean liveEntity(int type) {
        return type >= 46 && type <= 50;
    }

    /** @return true when the PDU's first record is the state of one entity (Entity State, ESU, TSPI) */
    static boolean entityState(int type) {
        return type == 1 || type == 67 || type == 46;
    }
}
