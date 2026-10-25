package com.simweaver.common.hla.implHla3;

import com.simweaver.common.SimwUtils;
import com.simweaver.common.hla.FederationProbe;
import hla.rti1516e.*;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.exceptions.NotConnected;

import java.util.ArrayList;
import java.util.List;

public class FederationProbeHla3 extends NullFederateAmbassador implements FederationProbe {

    private String lsd;
    private RTIambassador rtiAmbassador;
    private boolean isConnected;
    private FederationExecutionInformationSet executionInfo;

    public FederationProbeHla3(String lsd) {
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
            rtiAmbassador.connect(this, CallbackModel.HLA_IMMEDIATE,lsd);
            isConnected = true;
        } catch (Exception e) {
            isConnected = false;
            System.out.println("FederationProbe with lsd: " + lsd);
            System.out.println("FederationProbe error: " + e.getMessage());
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
    public void reportFederationExecutions(FederationExecutionInformationSet theFederationExecutionInformationSet) throws FederateInternalError {
        this.executionInfo = theFederationExecutionInformationSet;
    }

    @Override
    public void connectionLost(String faultDescription) throws FederateInternalError {
        isConnected = false;
        rtiAmbassador = null;
        executionInfo = null;
    }

}
