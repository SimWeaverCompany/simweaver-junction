package com.simweaver.common.infra;

import com.simweaver.common.hla.session.RtiSession;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.function.IntFunction;

/**
 * {@link MomPort} on an {@link RtiSession} (Junction's HLA endpoints). Every RTI call is made under
 * the owner's lock. Subscriptions are the union with what the owner subscribes of the class
 * itself (and with what this port subscribed before), so an RTI that replaces a class's
 * subscription keeps the owner's attributes.
 */
public final class SessionMomPort implements MomPort {

    private final RtiSession session;
    private final Lock lock;
    private final IntFunction<int[]> ownAttributes;
    private final Map<Integer, Set<Integer>> subscribed = new ConcurrentHashMap<Integer, Set<Integer>>();

    /**
     * @param lock          the owner's RTI lock, or null
     * @param ownAttributes the attributes the owner subscribes of a class (empty when none), or null
     */
    public SessionMomPort(RtiSession session, Lock lock, IntFunction<int[]> ownAttributes) {
        this.session = session;
        this.lock = lock;
        this.ownAttributes = ownAttributes;
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private <T> T locked(Call<T> c) throws Exception {
        if (lock != null) {
            lock.lock();
        }
        try {
            return c.run();
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    @Override
    public int objectClass(String qualifiedName) throws Exception {
        return locked(() -> session.objectClass(qualifiedName));
    }

    @Override
    public int attribute(int objectClass, String name) throws Exception {
        return locked(() -> session.attribute(objectClass, name));
    }

    @Override
    public int interactionClass(String qualifiedName) throws Exception {
        return locked(() -> session.interactionClass(qualifiedName));
    }

    @Override
    public int parameter(int interactionClass, String name) throws Exception {
        return locked(() -> session.parameter(interactionClass, name));
    }

    @Override
    public void subscribeObjectClass(int objectClass, int[] attributes) throws Exception {
        Set<Integer> all = subscribed.computeIfAbsent(objectClass, k -> new LinkedHashSet<Integer>());
        synchronized (all) {
            for (int a : attributes) {
                all.add(a);
            }
            if (ownAttributes != null) {
                int[] own = ownAttributes.apply(objectClass);
                if (own != null) {
                    for (int a : own) {
                        all.add(a);
                    }
                }
            }
        }
        int[] arr;
        synchronized (all) {
            arr = all.stream().mapToInt(Integer::intValue).toArray();
        }
        locked(() -> {
            session.subscribeObjectClass(objectClass, arr);
            return null;
        });
    }

    @Override
    public void subscribeInteractionClass(int interactionClass) throws Exception {
        locked(() -> {
            session.subscribeInteractionClass(interactionClass);
            return null;
        });
    }

    @Override
    public void publishInteractionClass(int interactionClass) throws Exception {
        locked(() -> {
            session.publishInteractionClass(interactionClass);
            return null;
        });
    }

    @Override
    public void requestClassUpdate(int objectClass, int[] attributes) throws Exception {
        locked(() -> {
            session.requestClassAttributeUpdate(objectClass, attributes, null);
            return null;
        });
    }

    @Override
    public void requestUpdate(long instance, int[] attributes) throws Exception {
        locked(() -> {
            session.requestAttributeUpdate(instance, attributes, null);
            return null;
        });
    }

    @Override
    public void sendInteraction(int interactionClass, int[] parameters, byte[][] values) throws Exception {
        locked(() -> {
            session.sendInteraction(interactionClass, parameters, values, null);
            return null;
        });
    }

    @Override
    public String objectClassName(byte[] encodedHandle) {
        return session.objectClassName(encodedHandle);
    }

    @Override
    public String interactionClassName(byte[] encodedHandle) {
        return session.interactionClassName(encodedHandle);
    }

    @Override
    public String attributeName(byte[] encodedClass, byte[] encodedAttribute) {
        return session.attributeName(encodedClass, encodedAttribute);
    }
}
