package com.simweaver.common.hla.implHla4;

import com.simweaver.common.SimwUtils;
import com.simweaver.common.hla.FederationProbe;
import hla.rti1516_2025.*;
import hla.rti1516_2025.exceptions.FederateInternalError;
import hla.rti1516_2025.exceptions.NotConnected;


import java.util.ArrayList;
import java.util.List;

public class FederationProbeHla4 extends NullFederateAmbassador implements FederationProbe {

    private String lsd;
    private RTIambassador rtiAmbassador;
    private boolean isConnected;
    private FederationExecutionInformationSet executionInfo;

    public FederationProbeHla4(String lsd) {
        this.lsd = lsd;
        this.rtiAmbassador = null;
        this.isConnected = false;
        this.executionInfo = null;
    }

    @Override
    public void startProbe() {
        connect();
        rescan();
    }

    private void connect(){

        // create ambassador
        try {
            rtiAmbassador = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
            RtiConfiguration configuration = RtiConfiguration.createConfiguration().withConfigurationName(this.lsd);
            rtiAmbassador.connect(this, CallbackModel.HLA_IMMEDIATE,configuration);
            isConnected = true;
        } catch (Exception e) {
            isConnected = false;
        }
    }

    @Override
    public void rescan() {

        if (!isConnected || rtiAmbassador == null){
            connect();
        }

        if (isConnected && rtiAmbassador != null){
            try {
                rtiAmbassador.listFederationExecutions();
                SimwUtils.sleep(500);
            } catch (NotConnected e) {
                connect();
                try {
                    rtiAmbassador.listFederationExecutions();
                    SimwUtils.sleep(500);
                } catch (Exception ex) {
                }

            } catch (Exception e) {

            }
        }
    }

    @Override
    public synchronized List<String> getFederations() {
        ArrayList<String> fedexList = new ArrayList<>();
        if (executionInfo != null){
            for (FederationExecutionInformation federationExecutionInformation : executionInfo) {
                fedexList.add(federationExecutionInformation.federationExecutionName);
            }
        }
        return fedexList;
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
