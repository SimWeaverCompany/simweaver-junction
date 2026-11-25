package com.simweaver.common.dis;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where DIS is received and sent, from a {@code disinterface} and {@code disport} setting
 * (Rift datasources, Junktion endpoints). Copied from Rift's {@code DisEndpoint} into common
 * for Junktion; it adds the sending side: {@link #destination(String)} and {@link #openSender(int)}.
 *
 * <pre>
 * disinterface              meaning
 * (empty), *, any, 0.0.0.0  broadcast and unicast on every interface
 * 192.168.10.5 | eth0       broadcast and unicast on that interface (address or interface name)
 * 239.1.2.3                 multicast group 239.1.2.3, joined on the default interface
 * 239.1.2.3@eth0            multicast group, joined on that interface (address or name)
 * </pre>
 *
 * The socket allows address reuse, so several DIS datasources (and other DIS tools) can listen
 * on the same port for broadcast and multicast traffic.
 *
 * <p>Limiting broadcast reception to one interface needs the socket bound to that interface's
 * address. Windows delivers broadcasts to such a socket; Linux and macOS do not, so there the
 * socket is bound to all interfaces and {@link #note()} says so. Multicast is always received
 * on the interface the group is joined on.
 */
public final class DisNetwork {

    public static final int DEFAULT_PORT = 3000;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final int port;
    private final InetAddress group;            // multicast group, or null
    private final NetworkInterface networkInterface;
    private final InetAddress bindAddress;      // null = all interfaces
    private final String description;
    private final String note;

    private DisNetwork(int port, InetAddress group, NetworkInterface nif, InetAddress bindAddress, String description, String note) {
        this.port = port;
        this.group = group;
        this.networkInterface = nif;
        this.bindAddress = bindAddress;
        this.description = description;
        this.note = note;
    }

    /**
     * @param disInterface the configuration value (see the class comment), may be null
     * @param port         UDP port, 1-65535 (0 = {@link #DEFAULT_PORT})
     * @throws IllegalArgumentException with a message for the user when the value cannot be used
     */
    public static DisNetwork parse(String disInterface, int port) {
        int p = port == 0 ? DEFAULT_PORT : port;
        if (p < 1 || p > 65535) throw new IllegalArgumentException("DIS port must be 1-65535, got " + port);
        String v = disInterface == null ? "" : disInterface.trim();
        String lower = v.toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "*".equals(v) || "any".equals(lower) || "0.0.0.0".equals(v)) {
            return new DisNetwork(p, null, null, null, "UDP port " + p + " on all interfaces", null);
        }
        int at = v.indexOf('@');
        if (at >= 0) {
            InetAddress g = address(v.substring(0, at).trim());
            if (!g.isMulticastAddress()) throw new IllegalArgumentException("'" + v.substring(0, at).trim() + "' is not a multicast group address");
            NetworkInterface nif = networkInterface(v.substring(at + 1).trim());
            return new DisNetwork(p, g, nif, null, "multicast " + g.getHostAddress() + " on " + nif.getName() + ", UDP port " + p, null);
        }
        NetworkInterface named = interfaceByName(v);
        if (named == null) {
            InetAddress a = address(v);
            if (a.isMulticastAddress()) {
                return new DisNetwork(p, a, null, null, "multicast " + a.getHostAddress() + " on the default interface, UDP port " + p, null);
            }
            named = networkInterface(v);
        }
        InetAddress local = firstIpv4(named);
        if (isWindows() && local != null) {
            return new DisNetwork(p, null, named, local, "UDP port " + p + " on " + named.getName() + " (" + local.getHostAddress() + ")", null);
        }
        return new DisNetwork(p, null, named, null, "UDP port " + p + " on all interfaces",
                "disinterface " + v + ": this operating system delivers broadcasts only to sockets bound to all interfaces, so DIS is received on every interface");
    }

    /** Opens the socket (address reuse, 4 MB receive buffer, the given read timeout) and joins the group if any. */
    public MulticastSocket open(int readTimeoutMillis) throws IOException {
        MulticastSocket s = new MulticastSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(bindAddress == null ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port));
            s.setReceiveBufferSize(4 << 20);
            s.setSoTimeout(readTimeoutMillis);
            if (group != null) s.joinGroup(new InetSocketAddress(group, port), networkInterface);
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    public int port() {
        return port;
    }

    /** NEW: the interface a multicast group is joined on / sending goes out of, or null (default). */
    public NetworkInterface networkInterface() {
        return networkInterface;
    }

    /**
     * NEW: where PDUs are sent.
     *
     * @param sendAddress an explicit IP address (unicast, broadcast or multicast), or null / ""
     *                    for the default: the multicast group; else the directed broadcast address
     *                    of the interface; else 255.255.255.255
     * @return the destination with this network's port
     * @throws IllegalArgumentException for a value that is not an IP literal
     */
    public InetSocketAddress destination(String sendAddress) {
        String v = sendAddress == null ? "" : sendAddress.trim();
        if (!v.isEmpty()) {
            int colon = v.lastIndexOf(':');
            int p = port;
            if (colon > 0 && v.indexOf(':') == colon) {
                p = Integer.parseInt(v.substring(colon + 1).trim());
                v = v.substring(0, colon).trim();
            }
            return new InetSocketAddress(address(v), p);
        }
        if (group != null) {
            return new InetSocketAddress(group, port);
        }
        if (networkInterface != null) {
            for (java.net.InterfaceAddress ia : networkInterface.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) {
                    return new InetSocketAddress(ia.getBroadcast(), port);
                }
            }
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255}), port);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 0.14.0: where PDUs are sent, for a {@code ;}-separated list of send addresses: unicast,
     * broadcast and multicast mixed, each {@code host} or {@code host:port} ({@code [v6]:port} for
     * IPv6), the port defaulting to this network's. IP literals are parsed; host names are looked
     * up now, once. An empty list is the default destination ({@link #destination(String)}).
     *
     * @return the destinations in order, duplicates removed; never empty
     * @throws IllegalArgumentException naming the entry that cannot be used (syntax, port, a host
     *                                  name that cannot be resolved)
     */
    public List<InetSocketAddress> destinations(String sendAddresses) {
        List<String> entries = entries(sendAddresses);
        List<InetSocketAddress> out = new ArrayList<>();
        if (entries.isEmpty()) {
            out.add(destination(""));
            return out;
        }
        for (String e : entries) {
            String why = entryProblem(e);
            if (why != null) {
                throw new IllegalArgumentException("send address " + why);
            }
            String[] hp = hostPort(e);
            int p = hp[1] == null ? port : Integer.parseInt(hp[1]);
            InetAddress a;
            if (IPV4.matcher(hp[0]).matches() || hp[0].indexOf(':') >= 0) {
                a = address(hp[0]);
            } else {
                try {
                    a = InetAddress.getByName(hp[0]);          // a host name: looked up once, now
                } catch (IOException ex) {
                    throw new IllegalArgumentException("send address '" + e + "': the host name " + hp[0] + " cannot be resolved");
                }
            }
            InetSocketAddress d = new InetSocketAddress(a, p);
            if (!out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 0.14.0: the problems of a send address list, without looking up host names (for checking
     * settings).
     *
     * @return one text per bad entry, empty when every entry can be used
     */
    public static List<String> sendAddressProblems(String sendAddresses) {
        List<String> out = new ArrayList<>();
        for (String e : entries(sendAddresses)) {
            String why = entryProblem(e);
            if (why != null) {
                out.add(why);
            }
        }
        return out;
    }

    /** The entries of a send address list: split on ';', trimmed, empty ones left out. */
    public static List<String> entries(String sendAddresses) {
        List<String> out = new ArrayList<>();
        if (sendAddresses != null) {
            for (String s : sendAddresses.split(";")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static final Pattern HOST_NAME = Pattern.compile("(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\\.?");

    /** @return {host, port or null} */
    private static String[] hostPort(String e) {
        if (e.startsWith("[")) {
            int close = e.indexOf(']');
            if (close < 0) {
                return new String[] {e, null};
            }
            String host = e.substring(1, close);
            String rest = e.substring(close + 1);
            return new String[] {host, rest.startsWith(":") ? rest.substring(1) : rest.isEmpty() ? null : rest};
        }
        int first = e.indexOf(':');
        if (first > 0 && first == e.lastIndexOf(':')) {
            return new String[] {e.substring(0, first).trim(), e.substring(first + 1).trim()};
        }
        return new String[] {e, null};                         // a host, or an IPv6 literal without a port
    }

    private static String entryProblem(String e) {
        String[] hp = hostPort(e);
        if (e.startsWith("[") && e.indexOf(']') < 0) {
            return "'" + e + "': ']' is missing";
        }
        if (hp[1] != null) {
            int p;
            try {
                p = Integer.parseInt(hp[1]);
            } catch (NumberFormatException x) {
                return "'" + e + "': '" + hp[1] + "' is not a port";
            }
            if (p < 1 || p > 65535) {
                return "'" + e + "': the port must be 1-65535";
            }
        }
        String h = hp[0];
        if (h.isEmpty()) {
            return "'" + e + "': no host";
        }
        if (IPV4.matcher(h).matches()) {
            for (String part : h.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return "'" + e + "': " + h + " is not an IPv4 address";
                }
            }
            return null;
        }
        if (h.indexOf(':') >= 0) {
            try {
                InetAddress.getByName(h);                        // an IPv6 literal: parsed, not resolved
                return null;
            } catch (IOException x) {
                return "'" + e + "': " + h + " is not an IPv6 address";
            }
        }
        if (h.matches("[0-9.]+") || !HOST_NAME.matcher(h).matches()) {
            return "'" + e + "': " + h + " is neither an IP address nor a host name";
        }
        return null;
    }

    /** "192.168.1.255:3000" (IPv6 in brackets). */
    public static String text(InetSocketAddress d) {
        String h = d.getAddress().getHostAddress();
        return (h.indexOf(':') >= 0 ? "[" + h + "]" : h) + ":" + d.getPort();
    }

    /**
     * NEW: a socket to send from: an ephemeral port, broadcast allowed, multicast out of this
     * network's interface with the given TTL. A separate socket gives this sender's datagrams a
     * source address and port of their own, so a receiver of the same application can tell its
     * own traffic apart (loopback of broadcast and multicast).
     */
    public MulticastSocket openSender(int ttl) throws IOException {
        MulticastSocket s = new MulticastSocket(bindAddress == null ? new InetSocketAddress(0) : new InetSocketAddress(bindAddress, 0));
        try {
            s.setBroadcast(true);
            s.setTimeToLive(Math.max(1, Math.min(255, ttl)));
            if (networkInterface != null) {
                s.setNetworkInterface(networkInterface);
            }
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /** The multicast group, or null for broadcast / unicast. */
    public InetAddress group() {
        return group;
    }

    /** For log and status messages, e.g. "UDP port 3000 on all interfaces". */
    public String description() {
        return description;
    }

    /** A remark worth logging once (e.g. the interface could not be enforced), or null. */
    public String note() {
        return note;
    }

    @Override
    public String toString() {
        return description;
    }

    // ------------------------------------------------------------------ helpers

    /** An IP literal; never a DNS lookup (the DIS network may have no name service). */
    private static InetAddress address(String text) {
        if (!IPV4.matcher(text).matches() && text.indexOf(':') < 0) {
            throw new IllegalArgumentException("'" + text + "' is neither an IP address nor a network interface of this computer"
                    + interfaceList());
        }
        try {
            return InetAddress.getByName(text);           // a literal: parsed, not resolved
        } catch (IOException e) {
            throw new IllegalArgumentException("'" + text + "' is not a valid IP address");
        }
    }

    /** A network interface by name or by one of its addresses. */
    private static NetworkInterface networkInterface(String text) {
        NetworkInterface n = interfaceByName(text);
        if (n != null) return n;
        InetAddress a = address(text);
        try {
            n = NetworkInterface.getByInetAddress(a);
        } catch (SocketException e) {
            n = null;
        }
        if (n == null) throw new IllegalArgumentException(text + " is not an address of this computer" + interfaceList());
        return n;
    }

    private static NetworkInterface interfaceByName(String name) {
        try {
            return NetworkInterface.getByName(name);
        } catch (SocketException e) {
            return null;
        }
    }

    private static InetAddress firstIpv4(NetworkInterface n) {
        for (InetAddress a : Collections.list(n.getInetAddresses())) {
            if (a instanceof Inet4Address) return a;
        }
        return null;
    }

    private static String interfaceList() {
        List<String> names = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp()) continue;
                InetAddress a = firstIpv4(n);
                names.add(n.getName() + (a == null ? "" : " (" + a.getHostAddress() + ")"));
            }
        } catch (SocketException e) {
            return "";
        }
        return names.isEmpty() ? "" : ". Interfaces: " + String.join(", ", names);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
