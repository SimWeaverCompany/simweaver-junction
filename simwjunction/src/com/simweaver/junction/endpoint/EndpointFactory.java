package com.simweaver.junction.endpoint;

import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.endpoint.dis.DisEndpoint;
import com.simweaver.junction.endpoint.hla.HlaEndpoint;

import java.io.File;

/**
 * Creates endpoints from their configuration. Never needs the RTI classes itself: the HLA
 * endpoint reaches the RTI only through {@code common.hla.session}, which loads it by
 * reflection (class path or isolated library), so a missing RTI is reported, not thrown.
 */
public final class EndpointFactory {

    private EndpointFactory() {
    }

    /**
     * @param cfg        the endpoint configuration
     * @param target     federation (HLA) or exercise id (DIS); null or blank = the configured default
     * @param gatewayId  the gateway's id
     * @param runtimeDir the runtime folder
     * @return the endpoint, not opened
     * @throws EndpointException {@code CONFIG_INVALID} for an unknown or broken configuration
     */
    public static Endpoint create(EndpointConfig cfg, String target, String gatewayId, File runtimeDir) throws EndpointException {
        return create(cfg, target, null, gatewayId, runtimeDir);
    }

    /**
     * @param lsd HLA: the LSD / RTI configuration name to connect with; null = the configured default
     */
    public static Endpoint create(EndpointConfig cfg, String target, String lsd, String gatewayId, File runtimeDir) throws EndpointException {
        if (cfg.getType() == null) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + ": unknown standard", null);
        }
        if (!cfg.getErrors().isEmpty()) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, String.join("; ", cfg.getErrors()), null);
        }
        String t = target == null || target.trim().isEmpty() ? cfg.getDefaultTarget() : target.trim();
        if (cfg.getType().isHla()) {
            return new HlaEndpoint(cfg, t, lsd, gatewayId, runtimeDir);
        }
        if (cfg.getType().isDis()) {
            return new DisEndpoint(cfg, t, gatewayId);
        }
        throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + ": " + cfg.getType() + " not supported", null);
    }

    /**
     * Checked without connecting or loading the RTI.
     *
     * @return why an endpoint of this configuration cannot run here (missing RTI library), or null
     */
    public static String unavailableReason(EndpointConfig cfg, File runtimeDir) {
        if (cfg.getType() == null) {
            return "unknown standard";
        }
        if (cfg.getType().isHla()) {
            return HlaEndpoint.unavailableReason(cfg, runtimeDir);
        }
        return null;
    }
}
