package com.simweaver.junction;

import com.simweaver.junction.config.EndpointConfig;

import java.util.List;
import java.util.function.Supplier;

/**
 * What the host application (Rift) offers Junction for inspecting endpoints: a datasource
 * that joins the endpoint's federation (or listens to its DIS network) on its own, shown in
 * the host's datasource page. Junction does not depend on the host; the host implements this.
 */
public interface Inspector {

    /** An endpoint as the host should offer it. */
    final class EndpointRef {
        public final String key;
        public final EndpointConfig config;
        public final String target;
        public final String label;
        /** The LSD the endpoint connects with, or null: the configured choices (a configuration, not a running endpoint). */
        public final String lsd;

        /**
         * @param key    stable key (gateway config id + endpoint id)
         * @param config the endpoint
         * @param target its federation / exercise id
         * @param label  for the host's lists, e.g. "Blue - Red / blue"
         */
        public EndpointRef(String key, EndpointConfig config, String target, String label) {
            this(key, config, target, label, null);
        }

        /** @param lsd the LSD a running endpoint connects with; null = the configured choices */
        public EndpointRef(String key, EndpointConfig config, String target, String label, String lsd) {
            this.key = key;
            this.config = config;
            this.target = target;
            this.label = label;
            this.lsd = lsd;
        }
    }

    /** Thrown when no datasource could be created. */
    final class InspectException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final int status;

        public InspectException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String getCode() {
            return code;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * Creates a datasource for an endpoint.
     *
     * @return the datasource id (the host's page is opened with it)
     * @throws InspectException when it cannot be created
     */
    String inspect(EndpointRef endpoint) throws InspectException;

    /** @return true while the datasource exists */
    boolean exists(String datasourceId);

    /** Removes a datasource created by {@link #inspect}. */
    void remove(String datasourceId);

    /** @return the host page that shows a datasource, e.g. "datasource.html?id=..." */
    String pageOf(String datasourceId);

    /**
     * The endpoints the host should list as its own configurations (Rift: the Junction group of
     * the datasource configurations).
     */
    void advertise(Supplier<List<EndpointRef>> endpoints);

    /** Nothing to inspect with: Junction without a host. */
    Inspector NONE = new Inspector() {
        @Override
        public String inspect(EndpointRef endpoint) throws InspectException {
            throw new InspectException("NOT_AVAILABLE", 501, "No inspection host (Rift) is attached");
        }

        @Override
        public boolean exists(String datasourceId) {
            return false;
        }

        @Override
        public void remove(String datasourceId) {
        }

        @Override
        public String pageOf(String datasourceId) {
            return null;
        }

        @Override
        public void advertise(Supplier<List<EndpointRef>> endpoints) {
        }
    };
}
