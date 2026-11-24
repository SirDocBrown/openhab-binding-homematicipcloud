/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homematicipcloud.internal;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Manages the access point cloud session.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public final class Access extends BaseBridgeHandler {
    record Key(boolean group, String id) {
    }

    private final Supplier<ScheduledExecutorService> workers;
    private final Cloud.@Nullable Transport transport;
    private volatile @Nullable Session session;

    Access(Bridge bridge) {
        this(bridge, () -> ThreadPoolManager.getPoolBasedSequentialScheduledExecutorService(Factory.ID,
                "homematicipcloud-bridge"), null);
    }

    Access(Bridge bridge, Supplier<ScheduledExecutorService> workers, Cloud.@Nullable Transport transport) {
        super(bridge);
        this.workers = workers;
        this.transport = transport;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(Discovery.class);
    }

    @Override
    public void initialize() {
        String token = setting("token");
        Cloud cloud;
        try {
            cloud = new Cloud(setting("sgtin"), token, transport);
        } catch (IllegalArgumentException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.invalid-sgtin");
            return;
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getClass().getSimpleName());
            return;
        }
        try {
            Session current = new Session(cloud, workers.get(), !token.isBlank());
            session = current;
            updateStatus(ThingStatus.UNKNOWN);
            current.worker.execute(current::tick);
        } catch (RuntimeException e) {
            if (session == null) {
                cloud.close();
            } else {
                stopSession();
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getClass().getSimpleName());
        }
    }

    String setting(String name) {
        Object value = getConfig().get(name);
        return value == null ? "" : value.toString().strip();
    }

    Map<Key, JsonObject> inventory() {
        Session current = session;
        return current == null ? Map.of() : current.entities;
    }

    void refreshChild(Entity entity) {
        Session current = session;
        if (current == null) {
            entity.bridgeOffline();
        } else {
            current.submit(() -> current.refresh(entity));
        }
    }

    void command(Entity sender, String id, Command value) {
        Session current = session;
        if (current != null) {
            current.command(sender, id, value);
        }
    }

    @Override
    public void handleCommand(ChannelUID channel, Command command) {
    }

    private void stopSession() {
        Session previous;
        synchronized (this) {
            previous = session;
            session = null;
        }
        if (previous != null) {
            previous.worker.shutdownNow();
            WebSocket socket = previous.socket;
            if (socket != null) {
                socket.abort();
            }
            previous.cloud.close();
        }
    }

    @Override
    public void dispose() {
        stopSession();
        super.dispose();
    }

    /** Applies an inventory event and returns whether the payload requires reconciliation. */
    static boolean applyEvent(JsonObject e, Map<Key, JsonObject> fresh, Set<Key> changed) {
        String type = Points.text(e, "pushEventType", "");
        if ("DEVICE_CHANNEL_EVENT".equals(type)) {
            return false;
        }
        boolean group = type.startsWith("GROUP_");
        if (!group && !type.startsWith("DEVICE_")) {
            return false;
        }
        if (type.endsWith("_REMOVED")) {
            String id = Points.text(e, "id", "");
            if (id.isEmpty()) {
                return true;
            }
            Key key = new Key(group, id);
            fresh.remove(key);
            changed.add(key);
        } else if (e.get(group ? "group" : "device") instanceof JsonObject object) {
            Key key = new Key(group, object.get("id").getAsString());
            fresh.put(key, object);
            changed.add(key);
        } else {
            return true;
        }
        return false;
    }

    // Each initialization owns its inventory, callbacks, timers, client and executor.
    private final class Session {
        private volatile Map<Key, JsonObject> entities = Map.of();
        private final ScheduledExecutorService worker;
        private final Cloud cloud;
        private volatile @Nullable WebSocket socket;
        private @Nullable JsonObject pairing;
        private long pairDeadline, nextSnapshot, nextPing, earliestEventSnapshot;
        private volatile long lastSignal;
        private boolean located, authenticated, connected;
        private int failures;

        Session(Cloud cloud, ScheduledExecutorService worker, boolean authenticated) {
            this.cloud = cloud;
            this.worker = worker;
            this.authenticated = authenticated;
        }

        private void ifCurrent(Runnable action) {
            synchronized (Access.this) {
                if (this.equals(session)) {
                    action.run();
                }
            }
        }

        private void refresh(Entity entity) {
            ifCurrent(() -> {
                if (connected) {
                    entity.accept(entities.get(entity.key()));
                } else {
                    entity.bridgeOffline();
                }
            });
        }

        private void submit(Runnable action) {
            if (this.equals(session)) {
                try {
                    worker.execute(action);
                } catch (RejectedExecutionException ignored) {
                }
            }
        }

        private void tick() {
            if (!this.equals(session)) {
                return;
            }
            int delay = 5;
            try {
                if (!located) {
                    cloud.lookup();
                    located = true;
                }
                if (!authenticated) {
                    JsonObject pending = pairing;
                    if (pending == null) {
                        pairing = cloud.startPairing(setting("pin"));
                        pairDeadline = System.currentTimeMillis() + 120_000;
                        ifCurrent(() -> updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                                "@text/offline.press-button"));
                    } else {
                        if (System.currentTimeMillis() > pairDeadline) {
                            ifCurrent(() -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                    "@text/offline.pairing-expired"));
                            return;
                        }
                        String token = cloud.finishPairing(pending, setting("pin"));
                        if (!token.isEmpty()) {
                            var config = editConfiguration();
                            config.put("token", token);
                            config.remove("pin");
                            ifCurrent(() -> updateConfiguration(config));
                            authenticated = true;
                        }
                    }
                    delay = 3;
                } else {
                    WebSocket active = socket;
                    if (active == null) {
                        active = cloud.listen(new Events());
                        synchronized (Access.this) {
                            if (!this.equals(session)) {
                                active.abort();
                                return;
                            }
                            socket = active;
                        }
                        nextSnapshot = 0;
                        lastSignal = System.currentTimeMillis();
                        nextPing = lastSignal + 40_000;
                    }
                    long now = System.currentTimeMillis();
                    if (now >= nextSnapshot) {
                        replace(cloud.snapshot());
                        earliestEventSnapshot = System.currentTimeMillis() + 30_000;
                        nextSnapshot = now + 300_000;
                        ifCurrent(() -> updateStatus(ThingStatus.ONLINE));
                    }
                    if (now - lastSignal > 100_000) {
                        throw new java.io.IOException("WebSocket-Timeout");
                    }
                    if (now >= nextPing) {
                        active.sendPing(ByteBuffer.wrap(new byte[] { 1 })).orTimeout(5, TimeUnit.SECONDS).join();
                        nextPing = now + 40_000;
                    }
                }
                failures = 0;
            } catch (Cloud.Status status) {
                if (!this.equals(session)) {
                    return;
                }
                if (status.code == 401 || status.code == 403) {
                    disconnected();
                    ifCurrent(() -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            status.getMessage()));
                    return;
                }
                delay = retryDelay(status.getMessage());
            } catch (Exception e) {
                if (!this.equals(session)) {
                    return;
                }
                delay = retryDelay(e.getClass().getSimpleName());
            }
            int nextDelay = delay;
            ifCurrent(() -> worker.schedule(this::tick, nextDelay, TimeUnit.SECONDS));
        }

        private int retryDelay(@Nullable String message) {
            disconnected();
            ifCurrent(() -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message));
            return Math.min(300, 5 << Math.min(++failures, 6));
        }

        private void disconnected() {
            WebSocket socket = this.socket;
            if (socket != null) {
                socket.abort();
            }
            this.socket = null;
            located = false;
            connected = false;
            notifyChildren();
        }

        private void replace(JsonObject snapshot) {
            Map<Key, JsonObject> fresh = new HashMap<>();
            for (String section : List.of("devices", "groups")) {
                if (!(snapshot.get(section) instanceof JsonObject list)) {
                    continue;
                }
                list.entrySet().forEach(e -> {
                    if (e.getValue().isJsonObject()) {
                        fresh.put(new Key("groups".equals(section), e.getKey()), e.getValue().getAsJsonObject());
                    }
                });
            }
            entities = Map.copyOf(fresh);
            connected = true;
            notifyChildren();
        }

        private void notifyChildren() {
            for (Thing child : getThing().getThings()) {
                if (child.getHandler() instanceof Entity entity) {
                    refresh(entity);
                }
            }
        }

        private void event(WebSocket source, String json) {
            if (!connected || !source.equals(socket)) {
                return;
            }
            try {
                JsonObject list = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("events");
                if (list == null) {
                    requestSnapshot();
                    return;
                }
                var fresh = new HashMap<>(entities);
                Set<Key> changed = new HashSet<>();
                for (var entry : list.entrySet()) {
                    JsonObject e = entry.getValue().getAsJsonObject();
                    if (applyEvent(e, fresh, changed)) {
                        requestSnapshot();
                    }
                }
                if (changed.isEmpty()) {
                    return;
                }
                entities = Map.copyOf(fresh);
                for (Thing child : getThing().getThings()) {
                    if (child.getHandler() instanceof Entity entity && changed.contains(entity.key())) {
                        refresh(entity);
                    }
                }
            } catch (RuntimeException e) {
                requestSnapshot();
            }
        }

        private void requestSnapshot() {
            nextSnapshot = Math.min(nextSnapshot, Math.max(System.currentTimeMillis(), earliestEventSnapshot));
        }

        void command(Entity sender, String id, Command value) {
            submit(() -> {
                var key = sender.key();
                var entity = entities.get(key);
                if (!connected || entity == null) {
                    return;
                }
                var p = Points.read(entity, key.group()).get(id);
                if (p == null) {
                    return;
                }
                try {
                    var request = Points.command(key.id(), p, value);
                    cloud.call(request.path(), request.data());
                } catch (IllegalArgumentException e) {
                    LoggerFactory.getLogger(Access.class).debug("Ignoring invalid command {} for {}", value, id);
                } catch (Exception e) {
                    LoggerFactory.getLogger(Access.class).warn("Command for {} rejected ({})", id,
                            e.getClass().getSimpleName());
                }
            });
        }

        @NonNullByDefault({}) // WebSocket.Listener has no nullness annotations in the JDK.
        private final class Events implements WebSocket.Listener {
            private final StringBuilder text = new StringBuilder();
            private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

            @Override
            public @Nullable CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
                lastSignal = System.currentTimeMillis();
                text.append(part);
                if (last) {
                    String message = text.toString();
                    text.setLength(0);
                    submit(() -> event(ws, message));
                }
                ws.request(1);
                return null;
            }

            @Override
            public @Nullable CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                lastSignal = System.currentTimeMillis();
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                binary.writeBytes(chunk);
                if (last) {
                    String message = binary.toString(StandardCharsets.UTF_8);
                    binary.reset();
                    submit(() -> event(ws, message));
                }
                ws.request(1);
                return null;
            }

            @Override
            public @Nullable CompletionStage<?> onPong(WebSocket ws, ByteBuffer data) {
                lastSignal = System.currentTimeMillis();
                ws.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                lost(ws);
            }

            @Override
            public @Nullable CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                lost(ws);
                return null;
            }

            private void lost(WebSocket ws) {
                submit(() -> {
                    if (ws.equals(socket)) {
                        disconnected();
                        ifCurrent(() -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "@text/offline.disconnected"));
                    }
                });
            }
        }
    }
}
