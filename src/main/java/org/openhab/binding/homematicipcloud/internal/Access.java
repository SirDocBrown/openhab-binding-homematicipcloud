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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
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

    private static final String RETRY_AT = "cloudRetryAt";
    private final LongSupplier clock;
    private final LongSupplier resyncInterval;
    private long loggedThrottle;
    private volatile long blockedUntil;
    private final Supplier<ScheduledExecutorService> workers;
    private final Cloud.@Nullable Transport transport;
    private volatile @Nullable Session session;

    Access(Bridge bridge) {
        this(bridge, () -> ThreadPoolManager.getPoolBasedSequentialScheduledExecutorService(Factory.ID,
                "homematicipcloud-bridge"), null);
    }

    Access(Bridge bridge, Supplier<ScheduledExecutorService> workers, Cloud.@Nullable Transport transport) {
        this(bridge, workers, transport, System::currentTimeMillis, () -> TimeUnit.HOURS.toMillis(6) + ThreadLocalRandom
                .current().nextLong(-TimeUnit.MINUTES.toMillis(30), TimeUnit.MINUTES.toMillis(30) + 1));
    }

    Access(Bridge bridge, Supplier<ScheduledExecutorService> workers, Cloud.@Nullable Transport transport,
            LongSupplier clock, LongSupplier resyncInterval) {
        super(bridge);
        this.clock = clock;
        this.resyncInterval = resyncInterval;
        this.workers = workers;
        this.transport = transport;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(Discovery.class);
    }

    static long persistedDeadline(@Nullable String value, long now) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        Instant deadline;
        try {
            deadline = Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            try {
                deadline = Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                return 0;
            }
        }
        Instant current = Instant.ofEpochMilli(now);
        if (!deadline.isAfter(current)) {
            return 0;
        }
        long maximum = now + Cloud.MAX_THROTTLE.toMillis();
        return deadline.isAfter(Instant.ofEpochMilli(maximum)) ? maximum : deadline.toEpochMilli();
    }

    @Override
    public void initialize() {
        String stored = getThing().getProperties().get(RETRY_AT);
        blockedUntil = persistedDeadline(stored, clock.getAsLong());
        String normalized = blockedUntil == 0 ? null : Instant.ofEpochMilli(blockedUntil).toString();
        if (!Objects.equals(normalized, stored)) {
            updateProperty(RETRY_AT, normalized);
        }
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
        } else {
            sender.restore(id);
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

    static boolean applyEvent(JsonObject e, Map<Key, JsonObject> fresh, Set<Key> changed) {
        String type = Points.text(e, "pushEventType", "");
        if ("DEVICE_CHANNEL_EVENT".equals(type)) {
            return true;
        }
        boolean group = type.startsWith("GROUP_");
        if (!group && !type.startsWith("DEVICE_")) {
            return !type.isEmpty();
        }
        if (type.endsWith("_REMOVED")) {
            String id = Points.text(e, "id", "");
            if (id.isEmpty()) {
                return false;
            }
            Key key = new Key(group, id);
            fresh.remove(key);
            changed.add(key);
        } else if (e.get(group ? "group" : "device") instanceof JsonObject object) {
            String id = Points.text(object, "id", "");
            if (id.isEmpty()) {
                return false;
            }
            Key key = new Key(group, id);
            fresh.put(key, object);
            changed.add(key);
        } else {
            return false;
        }
        return true;
    }

    // Each initialization owns its inventory, callbacks, timers, client and executor.
    private final class Session {
        private volatile Map<Key, JsonObject> entities = Map.of();
        private final ScheduledExecutorService worker;
        private final Cloud cloud;
        private volatile @Nullable WebSocket socket;
        private @Nullable JsonObject pairing;
        private long pairDeadline, nextPing, reconnectAfter;
        private long lastSnapshot, nextSnapshot;
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

        private boolean submit(Runnable action) {
            if (this.equals(session)) {
                try {
                    worker.execute(action);
                    return true;
                } catch (RejectedExecutionException ignored) {
                }
            }
            return false;
        }

        private void tick() {
            if (!this.equals(session)) {
                return;
            }
            long remaining = Math.max(blockedUntil, reconnectAfter) - clock.getAsLong();
            if (remaining > 0) {
                if (blockedUntil > clock.getAsLong()) {
                    ifCurrent(() -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "@text/offline.throttled"));
                }
                ifCurrent(() -> worker.schedule(this::tick, remaining, TimeUnit.MILLISECONDS));
                return;
            }
            if (blockedUntil != 0) {
                ifCurrent(() -> {
                    blockedUntil = 0;
                    updateProperty(RETRY_AT, null);
                });
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
                        pairDeadline = clock.getAsLong() + 120_000;
                        ifCurrent(() -> updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                                "@text/offline.press-button"));
                    } else {
                        if (clock.getAsLong() > pairDeadline) {
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
                        lastSignal = clock.getAsLong();
                        nextPing = lastSignal + 40_000;
                        snapshot();
                        ifCurrent(() -> updateStatus(ThingStatus.ONLINE));
                    }
                    long now = clock.getAsLong();
                    if (now - lastSignal > 100_000) {
                        throw new java.io.IOException("WebSocket-Timeout");
                    }
                    if (now >= nextSnapshot) {
                        snapshot();
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
                if (status.code == 429) {
                    throttle(status);
                } else {
                    delay = retryDelay(status.getMessage());
                }
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

        private void throttle(Cloud.Status error) {
            ifCurrent(() -> {
                blockedUntil = error.retryAt(clock.getAsLong());
                pairing = null;
                disconnected();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.throttled");
                updateProperty(RETRY_AT, Instant.ofEpochMilli(blockedUntil).toString());
            });
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

        private void snapshot() throws Exception {
            replace(cloud.snapshot());
            lastSnapshot = clock.getAsLong();
            nextSnapshot = lastSnapshot + resyncInterval.getAsLong();
        }

        private void discarded(String type) {
            LoggerFactory.getLogger(Access.class).debug("Discarded cloud event (pushEventType={})", type);
            nextSnapshot = Math.min(nextSnapshot, lastSnapshot + TimeUnit.MINUTES.toMillis(15));
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
                    discarded("unknown");
                    return;
                }
                var fresh = new HashMap<>(entities);
                Set<Key> changed = new HashSet<>();
                for (var entry : list.entrySet()) {
                    if (entry.getValue() instanceof JsonObject e) {
                        if (!applyEvent(e, fresh, changed)) {
                            discarded(Points.text(e, "pushEventType", "unknown"));
                        }
                    } else {
                        discarded("unknown");
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
                discarded("unknown");
            }
        }

        private void rejected(Entity sender, String id) {
            synchronized (Access.this) {
                long deadline = blockedUntil;
                if (deadline > clock.getAsLong() && loggedThrottle != deadline) {
                    loggedThrottle = deadline;
                    LoggerFactory.getLogger(Access.class).info("Discarding commands during cloud throttle until {}",
                            Instant.ofEpochMilli(deadline));
                }
            }
            sender.restore(id);
        }

        void command(Entity sender, String id, Command value) {
            WebSocket source = socket;
            if (source == null || clock.getAsLong() < blockedUntil) {
                rejected(sender, id);
                return;
            }
            if (!submit(() -> {
                if (!source.equals(socket)) {
                    rejected(sender, id);
                    return;
                }
                var key = sender.key();
                var entity = entities.get(key);
                if (!connected || entity == null || sender.getThing().getStatus() != ThingStatus.ONLINE) {
                    rejected(sender, id);
                    return;
                }
                var p = Points.read(entity, key.group()).get(id);
                if (p == null) {
                    rejected(sender, id);
                    return;
                }
                try {
                    var request = Points.command(key.id(), p, value);
                    cloud.call(request.path(), request.data());
                    return;
                } catch (IllegalArgumentException e) {
                    LoggerFactory.getLogger(Access.class).debug("Ignoring invalid command {} for {}", value, id);
                } catch (Cloud.Status e) {
                    if (e.code == 429) {
                        throttle(e);
                    } else {
                        LoggerFactory.getLogger(Access.class).warn("Command for {} rejected (HTTP {})", id, e.code);
                    }
                } catch (Exception e) {
                    LoggerFactory.getLogger(Access.class).warn("Command for {} rejected ({})", id,
                            e.getClass().getSimpleName());
                }
                rejected(sender, id);
            })) {
                rejected(sender, id);
            }
        }

        @NonNullByDefault({}) // WebSocket.Listener has no nullness annotations in the JDK.
        private final class Events implements WebSocket.Listener {
            private final StringBuilder text = new StringBuilder();
            private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

            @Override
            public @Nullable CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
                lastSignal = clock.getAsLong();
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
                lastSignal = clock.getAsLong();
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
                lastSignal = clock.getAsLong();
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
                        reconnectAfter = clock.getAsLong() + 60_000;
                        disconnected();
                        ifCurrent(() -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "@text/offline.disconnected"));
                    }
                });
            }
        }
    }
}
