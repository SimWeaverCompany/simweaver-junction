package com.simweaver.rift.comms;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One listener's audio (0.12.0): the channels it asked for and a bounded queue of binary frames,
 * filled by the tracker (database worker thread) and drained by the HTTP stream's thread.
 *
 * <h2>Frame format</h2>
 * Little-endian; a 32-octet header, then {@code length} octets of payload:
 * <pre>
 *  0  u8   version (1)
 *  1  u8   type: 0 keep-alive (no payload), 1 audio
 *  2  u8   encoding of the payload: 1 = 16-bit linear PCM, little endian, mono;
 *           2 = one Opus packet (RFC 6716) as received, decoded by the listener (0.12.1)
 *  3  u8   reserved (0)
 *  4  u16  channel: its position in the stream's channels= list (0 based)
 *  6  u16  reserved (0)
 *  8  u32  source: the sender (transmitter / Talk client) number, as in the channel listing
 * 12  u32  sample rate (Hz)
 * 16  u32  sequence number of the frame in this stream (keep-alives included)
 * 20  u32  payload length in octets
 * 24  f64  server receive time, ms since 1970
 * 32  ...  payload
 * </pre>
 * When the listener falls behind, the oldest frames are dropped (a radio net is better late than
 * never, but not seconds late).
 */
public final class CommsAudioStream {

    public static final int VERSION = 1;
    public static final int HEADER = 32;
    public static final int TYPE_KEEPALIVE = 0;
    public static final int TYPE_AUDIO = 1;
    public static final int ENC_PCM16LE = 1;
    /** 0.12.1: one Opus packet, passed through; the browser decodes it (WebCodecs). */
    public static final int ENC_OPUS = 2;
    /** About 5 s of 20 ms frames on one channel. */
    static final int QUEUE = 256;

    private final Map<String, Integer> index;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(QUEUE);
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final String client;
    private final long opened = System.currentTimeMillis();
    private volatile boolean closed;

    /**
     * @param channels the channel ids, in the order of their numbers in the frames
     * @param client   who listens (for the status), e.g. the remote address
     */
    CommsAudioStream(List<String> channels, String client) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (String c : channels) {
            if (!m.containsKey(c)) m.put(c, m.size());
        }
        this.index = Collections.unmodifiableMap(m);
        this.client = client;
    }

    /** @return the channel's number in this stream, or -1 when it is not listened to */
    int channelIndex(String channelId) {
        Integer i = index.get(channelId);
        return i == null ? -1 : i;
    }

    Map<String, Integer> channels() {
        return index;
    }

    /** Queues a PCM16 LE audio frame (worker thread); drops the oldest when full. */
    void audio(int channel, int source, int sampleRate, long timeMillis, byte[] pcm16le) {
        audio(ENC_PCM16LE, channel, source, sampleRate, timeMillis, pcm16le);
    }

    /** Queues an audio frame of an encoding ({@link #ENC_PCM16LE}, {@link #ENC_OPUS}); drops the oldest when full. */
    void audio(int encoding, int channel, int source, int sampleRate, long timeMillis, byte[] payload) {
        if (closed) {
            return;
        }
        byte[] f = frame(TYPE_AUDIO, encoding, channel, source, sampleRate, timeMillis, payload);
        while (!queue.offer(f)) {
            if (queue.poll() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    /** The next frame to write, waiting at most {@code millis}; a keep-alive when there was none. */
    byte[] next(long millis) throws InterruptedException {
        byte[] f = queue.poll(millis, TimeUnit.MILLISECONDS);
        if (f == null) {
            f = frame(TYPE_KEEPALIVE, 0, 0, 0, 0, System.currentTimeMillis(), new byte[0]);
        }
        sent.incrementAndGet();
        return f;
    }

    /** More frames waiting (write them before flushing). */
    byte[] poll() {
        byte[] f = queue.poll();
        if (f != null) sent.incrementAndGet();
        return f;
    }

    private byte[] frame(int type, int encoding, int channel, int source, int rate, long time, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) VERSION).put((byte) type).put((byte) (type == TYPE_AUDIO ? encoding : 0)).put((byte) 0);
        b.putShort((short) channel).putShort((short) 0);
        b.putInt(source).putInt(rate);
        b.putInt((int) seq.getAndIncrement());
        b.putInt(payload.length);
        b.putDouble(time);
        b.put(payload);
        return b.array();
    }

    void close() {
        closed = true;
    }

    boolean isClosed() {
        return closed;
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("client", client);
        m.put("channels", index.keySet());
        m.put("opened", java.time.Instant.ofEpochMilli(opened).toString());
        m.put("frames", sent.get());
        m.put("dropped", dropped.get());
        return m;
    }
}
