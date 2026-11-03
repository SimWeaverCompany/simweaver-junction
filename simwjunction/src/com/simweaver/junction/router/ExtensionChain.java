package com.simweaver.junction.router;

import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.Interest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Several {@link IemRouter.Extension}s as one (0.12.0: every IEM gateway has the IFF bridge and
 * the NETN enrichment). Each takes part only when its {@code prepare} says so.
 *
 * <ul>
 *   <li>Watching hooks ({@code declare}, {@code received}, {@code bridged}, {@code observed},
 *       {@code copied}, {@code tick}, …) go to every member, in order.</li>
 *   <li>Taking hooks ({@code discovered}, {@code reflected}, {@code removed}, {@code provide}): the
 *       first member that takes the object wins; the others are not asked.</li>
 *   <li>{@code registered} / {@code registrationFailed} go to every member (each ignores ids it did
 *       not make) and are the router's when no member claims them.</li>
 *   <li>{@code rewrite} is applied member after member; null from one drops the interaction.</li>
 * </ul>
 */
public final class ExtensionChain implements IemRouter.Extension {

    private final List<IemRouter.Extension> all;
    private List<IemRouter.Extension> active;

    /** @param members the extensions, in the order they are asked */
    public ExtensionChain(IemRouter.Extension... members) {
        this.all = new ArrayList<IemRouter.Extension>(Arrays.asList(members));
        this.active = all;
    }

    /** @return the members that take part (after {@link #prepare}) */
    public List<IemRouter.Extension> active() {
        return active;
    }

    @Override
    public boolean prepare(List<Endpoint> endpoints, Map<String, ModelTools> tools) {
        List<IemRouter.Extension> on = new ArrayList<IemRouter.Extension>();
        for (IemRouter.Extension x : all) {
            if (x.prepare(endpoints, tools)) {
                on.add(x);
            }
        }
        active = on;
        return !on.isEmpty();
    }

    @Override
    public void declare(Endpoint e, ModelTools tools, Interest publish, Interest subscribe) {
        for (IemRouter.Extension x : active) x.declare(e, tools, publish, subscribe);
    }

    @Override
    public void started(IemRouter router, List<Endpoint> endpoints, Map<String, ModelTools> tools) {
        for (IemRouter.Extension x : active) x.started(router, endpoints, tools);
    }

    @Override
    public boolean discovered(Endpoint s, long key, int objectClass, String name) {
        for (IemRouter.Extension x : active) {
            if (x.discovered(s, key, objectClass, name)) return true;
        }
        return false;
    }

    @Override
    public boolean reflected(Endpoint s, long key, int[] attributes, byte[][] values) {
        for (IemRouter.Extension x : active) {
            if (x.reflected(s, key, attributes, values)) return true;
        }
        return false;
    }

    @Override
    public boolean removed(Endpoint s, long key) {
        for (IemRouter.Extension x : active) {
            if (x.removed(s, key)) return true;
        }
        return false;
    }

    @Override
    public void received(Endpoint s, int interactionClass, int[] parameters, byte[][] values) {
        for (IemRouter.Extension x : active) x.received(s, interactionClass, parameters, values);
    }

    @Override
    public byte[][] rewrite(Endpoint s, Endpoint d, int destinationClass, int[] parameters, byte[][] values) {
        byte[][] v = values;
        for (IemRouter.Extension x : active) {
            v = x.rewrite(s, d, destinationClass, parameters, v);
            if (v == null) return null;
        }
        return v;
    }

    @Override
    public boolean registered(Endpoint d, long gwId, long key, String name) {
        boolean mine = false;
        for (IemRouter.Extension x : active) {
            mine |= x.registered(d, gwId, key, name);
        }
        return mine;
    }

    @Override
    public boolean registrationFailed(Endpoint d, long gwId, String reason) {
        boolean mine = false;
        for (IemRouter.Extension x : active) {
            mine |= x.registrationFailed(d, gwId, reason);
        }
        return mine;
    }

    @Override
    public boolean provide(Endpoint d, long key, int[] attributes) {
        for (IemRouter.Extension x : active) {
            if (x.provide(d, key, attributes)) return true;
        }
        return false;
    }

    @Override
    public void stateChanged(Endpoint e, EndpointState state) {
        for (IemRouter.Extension x : active) x.stateChanged(e, state);
    }

    @Override
    public void tick(long now) {
        for (IemRouter.Extension x : active) x.tick(now);
    }

    @Override
    public void bridged(Endpoint s, long key, int objectClass, String name) {
        for (IemRouter.Extension x : active) x.bridged(s, key, objectClass, name);
    }

    @Override
    public void observed(Endpoint s, long key, int[] attributes, byte[][] values) {
        for (IemRouter.Extension x : active) x.observed(s, key, attributes, values);
    }

    @Override
    public void unbridged(Endpoint s, long key) {
        for (IemRouter.Extension x : active) x.unbridged(s, key);
    }

    @Override
    public void copied(Endpoint origin, long originKey, Endpoint d, String name) {
        for (IemRouter.Extension x : active) x.copied(origin, originKey, d, name);
    }

    @Override
    public void stopping() {
        for (IemRouter.Extension x : active) x.stopping();
    }

    @Override
    public void describe(Map<String, Object> into) {
        for (IemRouter.Extension x : active) x.describe(into);
    }
}
