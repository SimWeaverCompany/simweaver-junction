package com.simweaver.common.logging;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Sends log records to a syslog server (0.9.22): UDP (RFC 5426), TCP (RFC 6587) or TLS
 * (RFC 5425), messages in RFC 5424 or RFC 3164 format ({@link SyslogFormat}).
 *
 * <p>Logging never waits for the network: {@link #publish} formats the record and puts it in a
 * bounded queue; a daemon thread sends. While the server cannot be reached the thread keeps the
 * queue and retries (1 s, doubling to 60 s); when the queue is full new messages are dropped and
 * counted, and after the next successful connection a NOTICE tells the server how many were lost.
 *
 * <p>The handler's own trouble (cannot connect, connection lost, connected again) goes straight
 * to {@code System.err} / {@code System.out} — the local log — never through java.util.logging,
 * so it cannot feed itself.
 */
public final class SyslogHandler extends Handler {

    /** Where messages go (UDP, TCP, TLS). */
    interface Transport extends AutoCloseable {
        void connect() throws IOException;

        void send(byte[] message) throws IOException;

        @Override
        void close();
    }

    private static final long MAX_BACKOFF_MS = 60_000;

    private final SyslogSettings settings;
    private final SyslogFormat format;
    private final BlockingQueue<byte[]> queue;
    private final Transport transport;
    private final Thread sender;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedUnreported = new AtomicLong();
    private volatile boolean connected;
    private volatile boolean closing;
    private volatile String lastError;
    private volatile long lastErrorMillis;
    private volatile long connectedSinceMillis;
    private volatile boolean holding;          // the sender has a message it has not sent yet

    public SyslogHandler(SyslogSettings settings, String swVersion) {
        this(settings, swVersion, ZoneId.systemDefault(), null);
    }

    /** For tests: a fixed time zone and, if not null, a transport of their own. */
    SyslogHandler(SyslogSettings settings, String swVersion, ZoneId zone, Transport transport) {
        if (!settings.isEnabled()) {
            throw new IllegalArgumentException("syslog is not enabled: " + settings.problems());
        }
        this.settings = settings;
        this.format = new SyslogFormat(settings, swVersion, zone);
        this.queue = new ArrayBlockingQueue<byte[]>(settings.queueSize());
        this.transport = transport != null ? transport : transportFor(settings);
        setLevel(settings.level());
        setErrorManager(new ErrorManager());
        this.sender = new Thread(this::run, "syslog-sender");
        sender.setDaemon(true);
        sender.start();
    }

    private static Transport transportFor(SyslogSettings s) {
        switch (s.protocol()) {
            case TCP:
                return new StreamTransport(s, false);
            case TLS:
                return new StreamTransport(s, true);
            default:
                return new UdpTransport(s);
        }
    }

    @Override
    public void publish(LogRecord r) {
        if (r == null || closing || !isLoggable(r)) {
            return;
        }
        byte[] msg;
        try {
            msg = format.format(r);
        } catch (RuntimeException e) {
            reportError("syslog: could not format a log record", e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        offer(msg);
    }

    private void offer(byte[] msg) {
        if (!queue.offer(msg)) {
            dropped.incrementAndGet();
            droppedUnreported.incrementAndGet();
        }
    }

    private void run() {
        long backoff = 1000;
        byte[] pending = null;
        while (true) {
            try {
                if (pending == null) {
                    pending = closing ? queue.poll() : queue.poll(500, TimeUnit.MILLISECONDS);
                    holding = pending != null;
                    if (pending == null) {
                        if (closing) {
                            return;
                        }
                        continue;
                    }
                }
                if (!connected) {
                    transport.connect();
                    connected = true;
                    connectedSinceMillis = System.currentTimeMillis();
                    backoff = 1000;
                    if (lastError != null) {
                        System.out.println("INFO: syslog: connected to " + settings.describe());
                    }
                    long lost = droppedUnreported.getAndSet(0);
                    if (lost > 0) {
                        transport.send(format.format(Events.NOTICE, Instant.now(), null,
                                "syslog: " + lost + " message(s) were dropped while the syslog server could not be reached"));
                        sent.incrementAndGet();
                    }
                }
                transport.send(pending);
                sent.incrementAndGet();
                pending = null;
                holding = false;
            } catch (InterruptedException e) {
                if (closing) {
                    continue;          // drain what is left without waiting
                }
                return;
            } catch (IOException | RuntimeException e) {
                boolean wasConnected = connected;
                connected = false;
                transport.close();
                String err = String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (wasConnected || lastError == null || !err.equals(lastError)) {
                    System.err.println("WARNING: syslog: " + (wasConnected ? "lost the connection to " : "cannot reach ")
                            + settings.host() + ":" + settings.port() + " (" + err + "); messages wait in a queue of "
                            + settings.queueSize() + " and sending is retried");
                }
                lastError = err;
                lastErrorMillis = System.currentTimeMillis();
                if (closing) {
                    return;            // no retries while shutting down
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    if (!closing) {
                        return;
                    }
                }
                backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
            }
        }
    }

    /** Waits (at most {@code millis}) until the queue is empty. */
    public boolean drain(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (queue.isEmpty() && !holding) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty() && !holding;
    }

    @Override
    public void flush() {
        // the sender thread sends as fast as it can; close() waits for it
    }

    /** Sends what is queued (waiting at most 3 s), then stops. */
    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        sender.interrupt();
        try {
            sender.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        transport.close();
        connected = false;
    }

    /** @return the state for /api/serverinfo */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", Boolean.TRUE);
        m.put("target", settings.host() + ":" + settings.port());
        m.put("protocol", settings.protocol().name().toLowerCase());
        m.put("format", settings.format() == SyslogSettings.Format.RFC5424 ? "rfc5424" : "rfc3164");
        m.put("level", settings.level().getName());
        m.put("connected", connected);
        if (connected) {
            m.put("connectedsince", Instant.ofEpochMilli(connectedSinceMillis).toString());
        }
        m.put("sent", sent.get());
        m.put("queued", queue.size() + (holding ? 1 : 0));
        m.put("dropped", dropped.get());
        if (lastError != null) {
            m.put("lasterror", lastError);
            m.put("lasterrortime", Instant.ofEpochMilli(lastErrorMillis).toString());
        }
        return m;
    }

    public SyslogSettings settings() {
        return settings;
    }

    long sentCount() {
        return sent.get();
    }

    long droppedCount() {
        return dropped.get();
    }

    boolean isConnected() {
        return connected;
    }

    // --- transports ----------------------------------------------------------

    /** RFC 5426: one message per datagram, no connection. */
    static final class UdpTransport implements Transport {
        private final SyslogSettings s;
        private DatagramSocket socket;
        private InetAddress address;

        UdpTransport(SyslogSettings s) {
            this.s = s;
        }

        @Override
        public void connect() throws IOException {
            address = InetAddress.getByName(s.host());       // again after a failure: DNS may have changed
            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket();
            }
        }

        @Override
        public void send(byte[] message) throws IOException {
            socket.send(new DatagramPacket(message, message.length, address, s.port()));
        }

        @Override
        public void close() {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
    }

    /** RFC 6587 (TCP) and RFC 5425 (TLS): octet counting ("LEN SP MSG") or a newline after each message. */
    static final class StreamTransport implements Transport {
        private static final int CONNECT_TIMEOUT_MS = 5000;
        private final SyslogSettings s;
        private final boolean tls;
        private SSLContext sslContext;
        private Socket socket;
        private OutputStream out;

        StreamTransport(SyslogSettings s, boolean tls) {
            this.s = s;
            this.tls = tls;
        }

        @Override
        public void connect() throws IOException {
            close();
            Socket plain = new Socket();
            try {
                plain.connect(new InetSocketAddress(s.host(), s.port()), CONNECT_TIMEOUT_MS);
                plain.setKeepAlive(true);
                plain.setTcpNoDelay(true);
                if (tls) {
                    SSLSocket ssl = (SSLSocket) context().getSocketFactory().createSocket(plain, s.host(), s.port(), true);
                    SSLParameters p = ssl.getSSLParameters();
                    if (s.verifyHostname()) {
                        p.setEndpointIdentificationAlgorithm("HTTPS");    // the certificate must name the host
                    }
                    ssl.setSSLParameters(p);
                    ssl.setSoTimeout(CONNECT_TIMEOUT_MS);
                    ssl.startHandshake();
                    ssl.setSoTimeout(0);
                    socket = ssl;
                } else {
                    socket = plain;
                }
                out = new BufferedOutputStream(socket.getOutputStream(), 16384);
            } catch (IOException | RuntimeException e) {
                try {
                    plain.close();
                } catch (IOException ignored) {
                    // failed anyway
                }
                throw e;
            }
        }

        private SSLContext context() throws IOException {
            if (sslContext != null) {
                return sslContext;
            }
            try {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                if (s.trustStore().isEmpty()) {
                    tmf.init((KeyStore) null);               // Java's own CA certificates
                } else {
                    KeyStore ks = KeyStore.getInstance(s.trustStore().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12");
                    try (InputStream in = new FileInputStream(s.trustStore())) {
                        ks.load(in, s.trustStorePassword().isEmpty() ? null : s.trustStorePassword().toCharArray());
                    }
                    tmf.init(ks);
                }
                SSLContext c = SSLContext.getInstance("TLS");
                c.init(null, tmf.getTrustManagers(), null);
                sslContext = c;
                return c;
            } catch (IOException e) {
                throw new IOException("syslog.tls.truststore " + s.trustStore() + ": " + e.getMessage(), e);
            } catch (Exception e) {
                throw new IOException("TLS set-up failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void send(byte[] message) throws IOException {
            if (s.octetFraming()) {
                out.write((message.length + " ").getBytes(StandardCharsets.US_ASCII));
                out.write(message);
            } else {
                out.write(message);
                out.write('\n');
            }
            out.flush();
        }

        @Override
        public void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
                socket = null;
                out = null;
            }
        }
    }
}
