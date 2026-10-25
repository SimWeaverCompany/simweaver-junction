package com.simweaver.common.hla;

import java.util.Collection;
import java.util.List;

public interface FederationProbe {

    // initiates the probe. Can possibly start scanning
    void startProbe();

    // fetches the latest data from the source.
    void rescan();

    // returns the list of current available federations
    List<String> getFederations();

}
