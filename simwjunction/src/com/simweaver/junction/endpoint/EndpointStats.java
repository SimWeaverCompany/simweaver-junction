package com.simweaver.junction.endpoint;

import com.simweaver.common.json.Json;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Traffic counters of one endpoint. "In" is what the endpoint received from its side and
 * handed to the router; "out" is what the router had it send. Thread safe; the rates are
 * computed by {@link #sample(long)} (the gateway's statistics tick).
 */
public final class EndpointStats implements Json.Writable {

    public final AtomicLong discoveredIn = new AtomicLong();
    public final AtomicLong updatesIn = new AtomicLong();
    public final AtomicLong removedIn = new AtomicLong();
    public final AtomicLong interactionsIn = new AtomicLong();
    public final AtomicLong bytesIn = new AtomicLong();
    public final AtomicLong registeredOut = new AtomicLong();
    public final AtomicLong updatesOut = new AtomicLong();
    public final AtomicLong deletedOut = new AtomicLong();
    public final AtomicLong interactionsOut = new AtomicLong();
    public final AtomicLong bytesOut = new AtomicLong();
    public final AtomicLong sendErrors = new AtomicLong();
    public final AtomicLong registrationFailures = new AtomicLong();
    public final AtomicLong ignoredIn = new AtomicLong();
    public final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong lastIn = new AtomicLong();
    private final AtomicLong lastOut = new AtomicLong();

    // rates (messages per second), from the last two samples
    private volatile double rateIn;
    private volatile double rateOut;
    private volatile double bytesRateIn;
    private volatile double bytesRateOut;
    private long sampleTime;
    private long sampleIn;
    private long sampleOut;
    private long sampleBytesIn;
    private long sampleBytesOut;

    /** Marks traffic received now. */
    public void markIn(int octets) {
        bytesIn.addAndGet(octets);
        lastIn.set(System.currentTimeMillis());
    }

    /** Marks traffic sent now. */
    public void markOut(int octets) {
        bytesOut.addAndGet(octets);
        lastOut.set(System.currentTimeMillis());
    }

    /** @return messages received */
    public long messagesIn() {
        return discoveredIn.get() + updatesIn.get() + removedIn.get() + interactionsIn.get();
    }

    /** @return messages sent */
    public long messagesOut() {
        return registeredOut.get() + updatesOut.get() + deletedOut.get() + interactionsOut.get();
    }

    /** @return epoch millis of the last received traffic, 0 = never */
    public long lastIn() {
        return lastIn.get();
    }

    /** @return epoch millis of the last sent traffic, 0 = never */
    public long lastOut() {
        return lastOut.get();
    }

    /** Computes the rates since the previous sample. */
    public synchronized void sample(long nowMillis) {
        long in = messagesIn();
        long out = messagesOut();
        long bi = bytesIn.get();
        long bo = bytesOut.get();
        if (sampleTime > 0 && nowMillis > sampleTime) {
            double dt = (nowMillis - sampleTime) / 1000.0;
            rateIn = (in - sampleIn) / dt;
            rateOut = (out - sampleOut) / dt;
            bytesRateIn = (bi - sampleBytesIn) / dt;
            bytesRateOut = (bo - sampleBytesOut) / dt;
        }
        sampleTime = nowMillis;
        sampleIn = in;
        sampleOut = out;
        sampleBytesIn = bi;
        sampleBytesOut = bo;
    }

    public double rateIn() {
        return rateIn;
    }

    public double rateOut() {
        return rateOut;
    }

    @Override
    public Object toJson() {
        Json.JsonObject o = Json.object();
        o.put("in", Json.object().put("messages", messagesIn()).put("discovered", discoveredIn.get())
                .put("updates", updatesIn.get()).put("removed", removedIn.get()).put("interactions", interactionsIn.get())
                .put("bytes", bytesIn.get()).put("ignored", ignoredIn.get())
                .put("rate", round(rateIn)).put("bytesrate", round(bytesRateIn)).put("last", lastIn.get()));
        o.put("out", Json.object().put("messages", messagesOut()).put("registered", registeredOut.get())
                .put("updates", updatesOut.get()).put("deleted", deletedOut.get()).put("interactions", interactionsOut.get())
                .put("bytes", bytesOut.get()).put("rate", round(rateOut)).put("bytesrate", round(bytesRateOut))
                .put("last", lastOut.get()));
        o.put("senderrors", sendErrors.get());
        o.put("registrationfailures", registrationFailures.get());
        o.put("reconnects", reconnects.get());
        return o;
    }

    private static double round(double d) {
        return Math.round(d * 10) / 10.0;
    }
}
