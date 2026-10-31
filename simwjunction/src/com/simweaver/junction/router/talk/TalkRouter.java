package com.simweaver.junction.router.talk;

import com.simweaver.common.json.Json;
import com.simweaver.junction.config.ClassFilter;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.router.ConversionRules;
import com.simweaver.junction.router.IemRouter;
import com.simweaver.junction.router.ModelTools;
import com.simweaver.junction.router.Router;
import com.simweaver.junction.router.RouterStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Talk router ({@code router.kind=talk}): Talk federations bridged channel by channel,
 * optionally with Talk voice and radio channels on an RPR radio endpoint.
 *
 * <p>It is an {@link IemRouter} limited to the Talk classes that are bridged as objects
 * ({@code Client}, {@code User}, {@code Equipment}, with {@code talk.mirrorclients}) and to the
 * {@code PitchTalk.Message} interactions of {@code talk.messages}, with a {@link TalkBridge} that
 * keeps the channel tables, rewrites channel UUIDs and runs the radio bridge. With
 * {@code talk.mode=messages} only those interactions are exchanged: no Talk object is mirrored or
 * registered; the channel objects are only watched, read-only, for matching by name
 * ({@code talk.trackchannels}, default true). Instance renaming, late joiners, provide
 * requests and reconnects work as in the IEM router.
 */
public final class TalkRouter implements Router {

    private final IemRouter inner;
    private final TalkBridge bridge;

    /**
     * @param cfg       the router configuration (kind talk)
     * @param gatewayId the gateway id
     */
    public TalkRouter(RouterConfig cfg, String gatewayId) {
        this.bridge = new TalkBridge(cfg, gatewayId);
        this.inner = new IemRouter(cfg, gatewayId, ConversionRules.Provider.NONE, filter(cfg), bridge);
    }

    /**
     * What the inner router bridges: the mirrored client objects (full mode, talk.mirrorclients)
     * and the message kinds of talk.messages, nothing else.
     */
    static ClassFilter filter(RouterConfig cfg) {
        List<String> objects = !cfg.isTalkMessagesOnly() && cfg.isTalkMirrorClients()
                ? Arrays.asList("PitchTalk.Client", "PitchTalk.Client.*", "PitchTalk.User", "PitchTalk.User.*", "PitchTalk.Equipment")
                : Collections.<String>emptyList();
        List<String> messages = new ArrayList<String>();
        for (String kind : cfg.getTalkMessageKinds()) {
            switch (kind) {
                case "audio":
                    messages.addAll(Arrays.asList("PitchTalk.Message.Audio", "PitchTalk.Message.Audio.Audio_v2",
                            "PitchTalk.Message.Audio.Audio_v2.*"));
                    break;
                case "radio":
                    messages.addAll(Arrays.asList("PitchTalk.Message.Audio.Radio", "PitchTalk.Message.Audio.Radio.*"));
                    break;
                case "text":
                    messages.addAll(Arrays.asList("PitchTalk.Message.Text", "PitchTalk.Message.Text.*"));
                    break;
                default:
                    messages.addAll(Arrays.asList("PitchTalk.Message.Image", "PitchTalk.Message.Image.*"));
            }
        }
        return new ClassFilter(objects, Collections.singletonList("**"), messages, Collections.singletonList("**"));
    }

    @Override
    public EndpointListener inbound() {
        return inner.inbound();
    }

    @Override
    public void start(List<Endpoint> endpoints, Map<String, ModelTools> tools) throws EndpointException {
        inner.start(new ArrayList<Endpoint>(endpoints), tools);
    }

    @Override
    public void stop() {
        inner.stop();
    }

    @Override
    public RouterStats stats() {
        return inner.stats();
    }

    @Override
    public Json.Writable compatibility() {
        return inner.compatibility();
    }

    @Override
    public Json.Writable objects() {
        Json.Writable o = inner.objects();
        Json.Writable t = bridge.report();
        return () -> {
            Object base = o.toJson();
            if (base instanceof Json.JsonObject) {
                ((Json.JsonObject) base).put("talk", t.toJson());
            }
            return base;
        };
    }

    @Override
    public int queueDepth() {
        return inner.queueDepth();
    }
}
