package com.simweaver.common.dis.cdis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Counters of a {@link CdisCodec}. Thread safe. */
public final class CdisStats {

    final AtomicLong encoded = new AtomicLong();
    final AtomicLong encodedDisOctets = new AtomicLong();
    final AtomicLong encodedCdisOctets = new AtomicLong();
    final AtomicLong fullUpdates = new AtomicLong();
    final AtomicLong partialUpdates = new AtomicLong();
    final AtomicLong notConvertible = new AtomicLong();
    final AtomicLong encodeErrors = new AtomicLong();
    final AtomicLong decoded = new AtomicLong();
    final AtomicLong decodedCdisOctets = new AtomicLong();
    final AtomicLong decodedDisOctets = new AtomicLong();
    final AtomicLong decodeErrors = new AtomicLong();
    final AtomicLong unknownPdus = new AtomicLong();
    final AtomicLong waitingForFull = new AtomicLong();
    final AtomicLong timedOut = new AtomicLong();
    private volatile String lastError;

    void error(String message) {
        lastError = message;
    }

    /** @return DIS PDUs encoded to C-DIS */
    public long getEncoded() {
        return encoded.get();
    }

    /** @return DIS PDUs of types C-DIS does not have (or DIS versions it does not take) */
    public long getNotConvertible() {
        return notConvertible.get();
    }

    /** @return DIS PDUs that could not be encoded (malformed, too large for C-DIS) */
    public long getEncodeErrors() {
        return encodeErrors.get();
    }

    /** @return C-DIS PDUs decoded to DIS */
    public long getDecoded() {
        return decoded.get();
    }

    /** @return C-DIS PDUs that could not be decoded */
    public long getDecodeErrors() {
        return decodeErrors.get();
    }

    /** @return C-DIS PDUs of other versions or unknown types (skipped by their length) */
    public long getUnknownPdus() {
        return unknownPdus.get();
    }

    /** @return partial updates dropped because no full update of the object had arrived yet */
    public long getWaitingForFull() {
        return waitingForFull.get();
    }

    /** @return the last encode or decode error, or null */
    public String getLastError() {
        return lastError;
    }

    /** @return C-DIS octets per DIS octet sent (0 when nothing was sent) */
    public double getEncodeRatio() {
        long d = encodedDisOctets.get();
        return d == 0 ? 0 : (double) encodedCdisOctets.get() / d;
    }

    /** @return the counters for a status report */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("encoded", encoded.get());
        m.put("encodeddisoctets", encodedDisOctets.get());
        m.put("encodedcdisoctets", encodedCdisOctets.get());
        m.put("encoderatio", Math.round(getEncodeRatio() * 1000) / 1000.0);
        m.put("fullupdates", fullUpdates.get());
        m.put("partialupdates", partialUpdates.get());
        m.put("notconvertible", notConvertible.get());
        m.put("encodeerrors", encodeErrors.get());
        m.put("decoded", decoded.get());
        m.put("decodedcdisoctets", decodedCdisOctets.get());
        m.put("decodeddisoctets", decodedDisOctets.get());
        m.put("decodeerrors", decodeErrors.get());
        m.put("unknownpdus", unknownPdus.get());
        m.put("waitingforfull", waitingForFull.get());
        m.put("timedout", timedOut.get());
        if (lastError != null) {
            m.put("lasterror", lastError);
        }
        return m;
    }
}
