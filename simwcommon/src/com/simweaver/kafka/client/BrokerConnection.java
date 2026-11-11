package com.simweaver.kafka.client;

import com.simweaver.kafka.KafkaException;
import com.simweaver.kafka.wire.Api;
import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;
import com.simweaver.kafka.wire.KafkaErrors;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * One connection to one broker: TCP or TLS, then ApiVersions (which versions to write), then SASL
 * when configured. Requests go one at a time ({@link #request} is synchronized): Kafka answers a
 * connection's requests in order, and each user of a connection (the producer's sender, the
 * consumer's thread) has its own.
 *
 * <p>Request header v1: api key int16, api version int16, correlation id int32, client id string.
 * Response header v0: correlation id int32.
 */
public final class BrokerConnection implements AutoCloseable {

    private static final int MAX_RESPONSE = 128 * 1024 * 1024;

    private final String host;
    private final int port;
    private final int nodeId;
    private final KafkaSettings settings;
    private final Map<Integer, int[]> versions = new HashMap<Integer, int[]>();
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int correlation;
    private int answered;
    private volatile boolean closed;

    private BrokerConnection(String host, int port, int nodeId, KafkaSettings settings) {
        this.host = host;
        this.port = port;
        this.nodeId = nodeId;
        this.settings = settings;
    }

    /**
     * Connects, asks the broker's API versions and authenticates.
     *
     * @param nodeId the broker's id, or -1 for a bootstrap server
     */
    public static BrokerConnection open(String host, int port, int nodeId, KafkaSettings settings) throws KafkaException {
        BrokerConnection c = new BrokerConnection(host, port, nodeId, settings);
        String stage = "connecting";
        try {
            c.connect();
            stage = "reading its API versions" + (settings.security.tls() ? "" : " (does it want TLS?)");
            c.apiVersions();
            if (settings.security.sasl()) {
                stage = "SASL " + settings.saslMechanism + " authentication (user or password wrong?)";
                c.authenticate();
            }
            return c;
        } catch (KafkaException e) {
            c.close();
            throw e;
        } catch (IOException | KReader.KafkaProtocolException e) {
            c.close();
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (e instanceof EOFException || e.getMessage() == null) {
                m = "the broker closed the connection during " + stage;
            } else if (e instanceof javax.net.ssl.SSLException) {
                m = "TLS: " + m;
            } else if (!"connecting".equals(stage)) {
                m = stage + ": " + m;
            }
            throw new KafkaException("Kafka broker " + host + ":" + port + ": " + m, e);
        }
    }

    private void connect() throws IOException, KafkaException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), settings.connectTimeoutMs);
        if (settings.security.tls()) {
            SSLContext ctx = sslContext(settings);
            SSLSocket t = (SSLSocket) ctx.getSocketFactory().createSocket(s, host, port, true);
            SSLParameters p = t.getSSLParameters();
            if (settings.hostnameVerification) {
                p.setEndpointIdentificationAlgorithm("HTTPS");
            }
            t.setSSLParameters(p);
            t.setSoTimeout(settings.connectTimeoutMs);
            t.startHandshake();
            s = t;
        }
        s.setSoTimeout(settings.requestTimeoutMs);
        socket = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
        out = s.getOutputStream();
    }

    /** @return a TLS context from the settings' trust store and key store (each optional) */
    static SSLContext sslContext(KafkaSettings st) throws KafkaException {
        try {
            TrustManager[] tm = null;
            if (st.trustStore != null && !st.trustStore.isEmpty()) {
                KeyStore ts = load(st.trustStore, st.trustStorePassword);
                TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                f.init(ts);
                tm = f.getTrustManagers();
            }
            KeyManager[] km = null;
            if (st.keyStore != null && !st.keyStore.isEmpty()) {
                KeyStore ks = load(st.keyStore, st.keyStorePassword);
                KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                f.init(ks, st.keyStorePassword == null ? new char[0] : st.keyStorePassword.toCharArray());
                km = f.getKeyManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(km, tm, null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new KafkaException("Kafka TLS: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(String path, String password) throws IOException, GeneralSecurityException {
        String type = path.toLowerCase(java.util.Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
        KeyStore k = KeyStore.getInstance(type);
        try (InputStream is = new FileInputStream(path)) {
            k.load(is, password == null ? new char[0] : password.toCharArray());
        }
        return k;
    }

    private void apiVersions() throws IOException, KafkaException {
        KReader r = exchange(Api.API_VERSIONS, 0, new KWriter(0));
        int err = r.int16();
        int n = r.count();
        for (int i = 0; i < n; i++) {
            int key = r.int16();
            int min = r.int16();
            int max = r.int16();
            versions.put(key, new int[] {min, max});
        }
        if (err != KafkaErrors.NONE) {
            throw new KafkaException(err, "Kafka broker " + this + ": ApiVersions: " + KafkaErrors.name(err));
        }
    }

    /** @return the version to write for an API: the highest both sides have */
    public int version(int api) throws KafkaException {
        int[] r = versions.get(api);
        int mine = Api.maxVersion(api);
        int v = r == null ? -1 : Math.min(mine, r[1]);
        if (r == null || v < Math.max(r[0], Api.minVersion(api))) {
            throw new KafkaException(KafkaErrors.UNSUPPORTED_VERSION, "Kafka broker " + this + " has no " + Api.name(api)
                    + " version this client writes (" + Api.minVersion(api) + "-" + mine
                    + (r == null ? ", broker: none" : ", broker: " + r[0] + "-" + r[1]) + ")");
        }
        return v;
    }

    private void authenticate() throws IOException, KafkaException {
        String mech = KafkaSettings.mechanism(settings.saslMechanism);
        if (mech == null) {
            throw new KafkaException("Kafka SASL: mechanism " + settings.saslMechanism + " is not supported");
        }
        KWriter hs = new KWriter();
        hs.string(mech);
        KReader r = exchange(Api.SASL_HANDSHAKE, 1, hs);
        int err = r.int16();
        if (err != KafkaErrors.NONE) {
            throw new KafkaException(err, "Kafka SASL: the broker does not take " + mech + " (it takes " + r.strings() + ")");
        }
        int av = version(Api.SASL_AUTHENTICATE);
        if ("PLAIN".equals(mech)) {
            byte[] u = settings.username.getBytes(StandardCharsets.UTF_8);
            byte[] p = (settings.password == null ? "" : settings.password).getBytes(StandardCharsets.UTF_8);
            byte[] msg = new byte[2 + u.length + p.length];
            System.arraycopy(u, 0, msg, 1, u.length);
            System.arraycopy(p, 0, msg, 2 + u.length, p.length);
            saslStep(av, msg);
            return;
        }
        Scram s = new Scram(mech, settings.username, settings.password);
        try {
            byte[] serverFirst = saslStep(av, s.first());
            byte[] serverFinal = saslStep(av, s.last(serverFirst));
            s.verify(serverFinal);
        } catch (GeneralSecurityException e) {
            throw new KafkaException(KafkaErrors.SASL_AUTHENTICATION_FAILED, "Kafka SASL " + mech + ": " + e.getMessage(), e);
        }
    }

    private byte[] saslStep(int version, byte[] msg) throws IOException, KafkaException {
        KWriter w = new KWriter(msg.length + 8);
        w.bytes(msg);
        KReader r = exchange(Api.SASL_AUTHENTICATE, version, w);
        int err = r.int16();
        String text = r.string();
        byte[] reply = r.bytes();
        if (err != KafkaErrors.NONE) {
            throw new KafkaException(err, "Kafka SASL: " + KafkaErrors.name(err) + (text == null ? "" : ": " + text));
        }
        return reply == null ? new byte[0] : reply;
    }

    /**
     * Sends a request and reads its response.
     *
     * @return the response body (after the correlation id)
     * @throws KafkaException when the connection fails or was closed
     */
    public synchronized KReader request(int api, int version, KWriter body) throws KafkaException {
        if (closed) {
            throw new KafkaException(KafkaErrors.NETWORK_EXCEPTION, "Kafka broker " + this + ": connection closed");
        }
        try {
            KReader r = exchange(api, version, body);
            answered++;
            return r;
        } catch (IOException | KReader.KafkaProtocolException e) {
            close();
            String m = e.getMessage();
            if (m == null || e instanceof EOFException) {
                // right after opening, a broker that wants SASL closes the connection on the first request
                m = "the broker closed the connection" + (answered == 0 && settings.security == KafkaSettings.Security.PLAINTEXT
                        ? " (does it want SASL?)" : "");
            }
            throw new KafkaException(KafkaErrors.NETWORK_EXCEPTION, "Kafka broker " + this + ": " + Api.name(api) + ": " + m, e);
        }
    }

    /** Sends a request that has no response (Produce with acks 0). */
    public synchronized void send(int api, int version, KWriter body) throws KafkaException {
        if (closed) {
            throw new KafkaException(KafkaErrors.NETWORK_EXCEPTION, "Kafka broker " + this + ": connection closed");
        }
        try {
            write(api, version, body, ++correlation);
        } catch (IOException e) {
            close();
            throw new KafkaException(KafkaErrors.NETWORK_EXCEPTION, "Kafka broker " + this + ": " + e.getMessage(), e);
        }
    }

    /** Request with a longer read timeout (a Fetch that waits on the broker). */
    public synchronized KReader request(int api, int version, KWriter body, int extraWaitMs) throws KafkaException {
        try {
            socket.setSoTimeout(settings.requestTimeoutMs + Math.max(0, extraWaitMs));
        } catch (IOException | NullPointerException e) {
            // closed: request() reports it
        }
        return request(api, version, body);
    }

    private KReader exchange(int api, int version, KWriter body) throws IOException {
        int id = ++correlation;
        write(api, version, body, id);
        while (true) {
            int size = in.readInt();
            if (size < 4 || size > MAX_RESPONSE) {
                throw new IOException("bad response size " + size);
            }
            byte[] b = new byte[size];
            in.readFully(b);
            KReader r = new KReader(b);
            int got = r.int32();
            if (got == id) {
                return r;
            }
            if (got > id || got < id - 1000) {                // an answer to an acks=0 request cannot come; anything else is wrong
                throw new IOException("response for correlation " + got + ", expected " + id);
            }
        }
    }

    private void write(int api, int version, KWriter body, int id) throws IOException {
        KWriter h = new KWriter(32 + settings.clientId.length() + body.position());
        h.int32(0);                                          // size, patched
        h.int16(api);
        h.int16(version);
        h.int32(id);
        h.string(settings.clientId);
        h.raw(body.buffer(), 0, body.position());
        h.patchInt32(0, h.position() - 4);
        out.write(h.buffer(), 0, h.position());
        out.flush();
    }

    /** @return the broker's id, -1 for a bootstrap connection */
    public int nodeId() {
        return nodeId;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // closing
            }
        }
    }

    @Override
    public String toString() {
        return host + ":" + port + (nodeId >= 0 ? " (node " + nodeId + ")" : "");
    }

    /** For tests: reads one int32-length-prefixed frame (the fake broker uses this framing too). */
    static byte[] readFrame(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_RESPONSE) {
            throw new EOFException("bad frame size " + size);
        }
        byte[] b = new byte[size];
        in.readFully(b);
        return b;
    }
}
