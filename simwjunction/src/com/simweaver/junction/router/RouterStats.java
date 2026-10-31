package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.json.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Counters of a router. Thread safe. Also collects the codec's conversion warnings. */
public final class RouterStats implements HlaCodec.Issues, Json.Writable {

    public final AtomicLong queueDrops = new AtomicLong();
    public final AtomicLong queueHighWater = new AtomicLong();
    public final AtomicLong unknownObjects = new AtomicLong();
    public final AtomicLong conversionFailures = new AtomicLong();
    public final AtomicLong conversionWarnings = new AtomicLong();
    public final AtomicLong transcoded = new AtomicLong();
    public final AtomicLong referencesRewritten = new AtomicLong();
    public final AtomicLong unresolvedReferences = new AtomicLong();
    public final AtomicLong interactionsRouted = new AtomicLong();
    public final AtomicLong updatesRouted = new AtomicLong();
    public final AtomicLong providesAnswered = new AtomicLong();
    /** 0.9.19: attribute requests passed on to an object's origin (provide requests the cache could not answer) */
    public final AtomicLong providesForwarded = new AtomicLong();
    public final AtomicLong renamed = new AtomicLong();
    /** Values replaced by the router's value rules. */
    public final AtomicLong valuesReplaced = new AtomicLong();
    /** Number of value rules of the router (set at start; 0 = none). */
    public volatile int valueRules;
    private final Deque<String> recentProblems = new ArrayDeque<String>();

    /** Records a conversion failure (the member was left out). */
    public void conversionFailure(String message) {
        conversionFailures.incrementAndGet();
        problem(message);
    }

    @Override
    public void warn(String message) {
        conversionWarnings.incrementAndGet();
        problem(message);
    }

    /** Keeps the last few distinct problems for the UI. */
    public void problem(String message) {
        synchronized (recentProblems) {
            if (recentProblems.contains(message)) {
                return;
            }
            recentProblems.addFirst(message);
            while (recentProblems.size() > 20) {
                recentProblems.removeLast();
            }
        }
    }

    /** @return the latest distinct problems, newest first */
    public List<String> recentProblems() {
        synchronized (recentProblems) {
            return new ArrayList<String>(recentProblems);
        }
    }

    @Override
    public Object toJson() {
        return Json.object()
                .put("updatesrouted", updatesRouted.get())
                .put("interactionsrouted", interactionsRouted.get())
                .put("providesanswered", providesAnswered.get())
                .put("providesforwarded", providesForwarded.get())
                .put("transcoded", transcoded.get())
                .put("referencesrewritten", referencesRewritten.get())
                .put("unresolvedreferences", unresolvedReferences.get())
                .put("renamed", renamed.get())
                .put("valuerules", valueRules)
                .put("valuesreplaced", valuesReplaced.get())
                .put("queuedrops", queueDrops.get())
                .put("queuehighwater", queueHighWater.get())
                .put("unknownobjects", unknownObjects.get())
                .put("conversionfailures", conversionFailures.get())
                .put("conversionwarnings", conversionWarnings.get())
                .put("problems", recentProblems());
    }
}
