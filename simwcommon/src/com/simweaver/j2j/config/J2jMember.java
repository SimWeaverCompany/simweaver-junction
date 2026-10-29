package com.simweaver.j2j.config;

import java.util.Collections;
import java.util.List;

/** One member of the J2J network, as {@code etc/j2j/network.settings} lists it. Immutable. */
public final class J2jMember {

    /** host:port */
    public static final class Address {
        public final String host;
        public final int port;

        public Address(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Address && ((Address) o).host.equalsIgnoreCase(host) && ((Address) o).port == port;
        }

        @Override
        public int hashCode() {
            return host.toLowerCase().hashCode() * 31 + port;
        }
    }

    private final String id;
    private final String name;
    private final String site;
    private final List<Address> addresses;
    private final Address fallback;
    private final boolean connect;
    private final long key;

    J2jMember(String id, String name, String site, List<Address> addresses, Address fallback, boolean connect) {
        this.id = id;
        this.name = name;
        this.site = site;
        this.addresses = Collections.unmodifiableList(addresses);
        this.fallback = fallback;
        this.connect = connect;
        this.key = J2jConfig.nodeKey(id);
    }

    public String id() {
        return id;
    }

    /** @return the display name (the id when none is set) */
    public String name() {
        return name;
    }

    /** @return the site for the diagram, or null */
    public String site() {
        return site;
    }

    /** @return the addresses to dial, in order (empty: it dials out, nobody dials it) */
    public List<Address> addresses() {
        return addresses;
    }

    /** @return the TLS-on-443 address of last resort, or null */
    public Address fallback() {
        return fallback;
    }

    /** @return false when {@code member.<id>.connect=false}: never dialled */
    public boolean connect() {
        return connect;
    }

    /** @return the 64-bit node key of the id (first 8 octets of SHA-256) */
    public long key() {
        return key;
    }

    /** @return true when this server should try to dial it */
    public boolean dialable() {
        return connect && (!addresses.isEmpty() || fallback != null);
    }

    /** A canonical line for the member-list hash. */
    String canonical() {
        StringBuilder b = new StringBuilder(id).append('|').append(name).append('|').append(site == null ? "" : site).append('|');
        for (Address a : addresses) {
            b.append(a).append(',');
        }
        b.append('|').append(fallback == null ? "" : fallback.toString()).append('|').append(connect);
        return b.toString();
    }
}
