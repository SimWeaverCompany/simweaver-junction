package com.simweaver.common.hla.implHla4;

import com.simweaver.common.SimwUtils;
import com.simweaver.common.hla.FederationProbe;
import com.simweaver.common.hla.ProbeStatus;
import com.simweaver.common.hla.session.RtiLibrary;
import hla.rti1516_2025.*;
import hla.rti1516_2025.exceptions.FederateInternalError;
import hla.rti1516_2025.exceptions.NotConnected;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lists the federations of an RTI (IEEE 1516-2025) for the Create datasource dialog.
 *
 * <p>0.11.5: one probe per RTI configuration name, shared by every configuration that uses it
 * ({@link com.simweaver.common.hla.FederationProbeFactory}); it connects when federations are
 * first asked for, not when a configuration is read. A failed connect is reported once
 * ({@link ProbeStatus}), and the connect takes its turn with the other connects on the class path
 * RTI ({@link RtiLibrary#connectLock()}).
 */
public class FederationProbeHla4 extends NullFederateAmbassador implements FederationProbe {

    private static final Logger LOG = Logger.getLogger(FederationProbeHla4.class.getName());

    private final String lsd;
    private final ProbeStatus status;
    private volatile RTIambassador rtiAmbassador;
    private volatile boolean isConnected;
    private volatile FederationExecutionInformationSet executionInfo;

    public FederationProbeHla4(String lsd) {
        this.lsd = lsd == null ? "" : lsd;
        this.status = new ProbeStatus("IEEE 1516-2025", "RTI configuration", this.lsd);
    }

    @Override
    public void startProbe() {
        rescan();
    }

    private void connect() {
        if (!status.mayTry()) {
            return;                                    // failed a moment ago: do not wait for the RTI again yet
        }
        try {
            RTIambassador a = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
            synchronized (RtiLibrary.classPath().connectLock()) {     // one connect at a time per RTI library
                a.connect(this, CallbackModel.HLA_IMMEDIATE, RtiConfiguration.createConfiguration().withConfigurationName(lsd));
            }
            rtiAmbassador = a;
            isConnected = true;
            status.connected();
        } catch (Exception e) {
            isConnected = false;
            rtiAmbassador = null;
            status.failed(e);
        }
    }

    @Override
    public synchronized void rescan() {
        if (!isConnected || rtiAmbassador == null) {
            connect();
        }
        if (!isConnected || rtiAmbassador == null) {
            return;
        }
        try {
            list();
        } catch (NotConnected e) {
            isConnected = false;
            connect();
            if (isConnected) {
                try {
                    list();
                } catch (Exception ex) {
                    LOG.log(Level.FINE, status + ": listing the federations failed", ex);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, status + ": listing the federations failed", e);
        }
    }

    private void list() throws Exception {
        RTIambassador a = rtiAmbassador;
        if (a == null) {
            throw new NotConnected("not connected");
        }
        a.listFederationExecutions();
        SimwUtils.sleep(500);                          // the answer is a callback
    }

    @Override
    public synchronized List<String> getFederations() {
        ArrayList<String> fedexList = new ArrayList<>();
        FederationExecutionInformationSet info = executionInfo;
        if (info != null) {
            for (FederationExecutionInformation federationExecutionInformation : info) {
                fedexList.add(federationExecutionInformation.federationExecutionName);
            }
        }
        return fedexList;
    }

    /** @return the connection state (0.11.5) */
    public ProbeStatus status() {
        return status;
    }

    // federate ambassador callbacks

    @Override
    public void reportFederationExecutions(FederationExecutionInformationSet report) throws FederateInternalError {
        this.executionInfo = report;
    }

    @Override
    public void connectionLost(String faultDescription) throws FederateInternalError {
        isConnected = false;
        rtiAmbassador = null;
        executionInfo = null;
    }
}
