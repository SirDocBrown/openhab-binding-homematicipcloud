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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpRequest;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.binding.builder.ThingStatusInfoBuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises binding behaviour against synthetic cloud responses.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@org.eclipse.jdt.annotation.NonNullByDefault
class MappingTest {
    private static Points.Point point(Map<String, Points.Point> points, String id) {
        return Objects.requireNonNull(points.get(id), id);
    }

    static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject device() {
        return parse("""
                {"functionalChannels":{
                  "0":{"lowBat":true,"unreach":false,"firmware":"1.0","voltage":2.7},
                  "1":{"functionalChannelType":"SHUTTER_CONTACT_CHANNEL","windowState":"TILTED"},
                  "2":{"functionalChannelType":"BLIND_CHANNEL","shutterLevel":0.83,"slatsLevel":0.1},
                  "3":{"functionalChannelType":"SWITCH_MEASURING_CHANNEL","on":false,"currentPowerConsumption":54.2},
                  "4":{"functionalChannelType":"DIMMER_CHANNEL","dimLevel":0.87}}}
                """);
    }

    @Test
    void windowStateAndDetails() {
        var points = Points.read(device(), false);
        assertEquals(OpenClosedType.OPEN, Entity.state(point(points, "ch1_windowState")));
        assertEquals("TILTED", Entity.state(point(points, "ch1_windowStateDetail")).toString());
    }

    @Test
    void discoversDevicesAndOptionalGroups() {
        var device = device();
        var deviceKey = new Access.Key(false, "SENSOR");
        var groupKey = new Access.Key(true, "GROUP");
        assertTrue(Discovery.discoverable(deviceKey, device, "HAP", false));
        assertFalse(Discovery.discoverable(new Access.Key(false, "hap"), device, "HAP", true));
        assertFalse(Discovery.discoverable(deviceKey, parse("{\"type\":\"HOME_CONTROL_ACCESS_POINT_TWO\"}"), "", true));
        assertFalse(Discovery.discoverable(groupKey, device, "HAP", false));
        assertTrue(Discovery.discoverable(groupKey, device, "HAP", true));
    }

    @Test
    void preservesWindowLinks() {
        var points = Points.read(device(), false);
        var uid = new org.openhab.core.thing.ThingUID("homematicipcloud:device:bridge:sensor");
        var oldWindow = org.openhab.core.thing.binding.builder.ChannelBuilder
                .create(new org.openhab.core.thing.ChannelUID(uid, "ch1_windowState"), "Contact")
                .withType(point(points, "ch1_windowState").channelType()).withLabel("K1 · windowState").build();
        var builder = org.openhab.core.thing.binding.builder.ThingBuilder.create(Factory.DEVICE, uid);
        var oldThing = builder.withChannel(oldWindow).build();
        var channels = Entity.channels(oldThing, points);
        assertEquals(oldWindow.getUID(), channels.get(0).getUID());
        assertEquals("system:low-battery", Objects.requireNonNull(channels.get(1).getChannelTypeUID()).toString());
        assertEquals("homematicipcloud:unreachable",
                Objects.requireNonNull(channels.get(2).getChannelTypeUID()).toString());
        assertTrue(Objects.requireNonNull(channels.getLast().getChannelTypeUID()).getId().startsWith("diagnostic-"));
        var migrated = builder.withChannels(channels).build();
        assertEquals(channels, Entity.channels(migrated, points));
        assertEquals(channels, Entity.channels(migrated, java.util.Map.of()));
    }

    @Test
    void mapsActuatorCommands() {
        var points = Points.read(device(), false);
        var roller = point(points, "ch2_shutterLevel");
        assertEquals(1, Points.command("D", roller, UpDownType.DOWN).data().get("shutterLevel").getAsDouble());
        assertEquals(0, Points.command("D", roller, UpDownType.UP).data().get("shutterLevel").getAsDouble());
        assertEquals("device/control/stop", Points.command("D", roller, StopMoveType.STOP).path());
        var slats = Points.command("D", point(points, "ch2_slatsLevel"), new PercentType(50));
        assertEquals(0.83, slats.data().get("shutterLevel").getAsDouble());
        assertEquals(2, slats.data().get("channelIndex").getAsInt());
        assertTrue(Points.command("D", point(points, "ch3_on"), OnOffType.ON).data().get("on").getAsBoolean());
        assertEquals(0.75, Points.command("D", point(points, "ch4_dimLevel"), new PercentType(75)).data()
                .get("dimLevel").getAsDouble());
        assertEquals("number", point(points, "ch3_currentPowerConsumption").kind());
    }

    @Test
    void heatingCommands() {
        var group = Points.read(
                parse("""
                        {"type":"HEATING","setPointTemperature":20,"minTemperature":8,"maxTemperature":26,
                        "activeProfile":"PROFILE_1","profiles":{"PROFILE_1":{"index":"PROFILE_1","name":"Weekdays","enabled":true}},"boostMode":false,"controlMode":"ECO"}
                        """),
                true);
        assertTrue(Points.command("G", point(group, "group_setPointTemperature"), new DecimalType(21)).data()
                .has("groupId"));
        assertTrue(
                Points.command("G", point(group, "group_boostMode"), OnOffType.ON).data().get("boost").getAsBoolean());
        var profile = point(group, "group_activeProfile");
        assertEquals(new StringType("PROFILE_1"), Entity.state(profile));
        assertEquals("PROFILE_1",
                Points.command("G", profile, new StringType("PROFILE_1")).data().get("profileIndex").getAsString());
        assertThrows(IllegalArgumentException.class, () -> Points.command("G", profile, new StringType("PROFILE_2")));
        var mode = point(group, "group_controlMode");
        assertEquals(new StringType("ECO"), Entity.state(mode));
        assertThrows(IllegalArgumentException.class, () -> Points.command("G", mode, new StringType("ECO")));
        assertThrows(IllegalArgumentException.class,
                () -> Points.command("G", point(group, "group_setPointTemperature"), new DecimalType(40)));
        assertThrows(IllegalArgumentException.class,
                () -> Points.command("D", point(Points.read(device(), false), "ch0_lowBat"), OnOffType.OFF));
    }

    @Test
    void migratesExistingNumberProfileToStringWithSameChannelId() {
        var thingId = new org.openhab.core.thing.ThingUID("homematicipcloud:group:bridge:heating");
        var channelId = new org.openhab.core.thing.ChannelUID(thingId, "group_activeProfile");
        var mapped = Points.read(parse("""
                {"type":"HEATING","activeProfile":"PROFILE_1"}
                """), true);
        var profile = point(mapped, "group_activeProfile");
        var old = org.openhab.core.thing.binding.builder.ChannelBuilder.create(channelId, "Number")
                .withType(profile.channelType()).withLabel("activeProfile").build();
        var thing = org.openhab.core.thing.binding.builder.ThingBuilder.create(Factory.GROUP, thingId).withChannel(old)
                .build();
        var migrated = Entity.channels(thing, mapped).getFirst();
        assertEquals(channelId, migrated.getUID());
        assertEquals("String", migrated.getAcceptedItemType());
    }

    @Test
    void signalLightsAndSwitchGroups() {
        var lamp = Points.read(
                parse("""
                        {"functionalChannels":{"2":{"functionalChannelType":"SIGNAL_LIGHT_CHANNEL","dimLevel":0.6,"simpleRGBColorState":"BLUE"}}}
                        """),
                false);
        var color = Points.command("D", point(lamp, "ch2_simpleRGBColorState"), new StringType("RED"));
        assertEquals(0.6, color.data().get("dimLevel").getAsDouble());
        assertEquals("device/control/setSimpleRGBColorDimLevel", color.path());
        var sw = Points.read(parse("""
                {"type":"SWITCHING","on":false}
                """), true);
        assertEquals("group/switching/setState", Points.command("G", point(sw, "group_on"), OnOffType.ON).path());
    }

    @Test
    void pairingAndSnapshot() throws Exception {
        int[] poll = { 0 };
        try (var cloud = new Cloud("000000000000000000000001", "", req -> {
            return switch (req.uri().getPath()) {
                case "/getHost" -> new Cloud.Reply(200,
                        "{\"urlREST\":\"https://test.homematic.com\",\"urlWebSocket\":\"wss://test.homematic.com\"}");
                case "/hmip/auth/connectionRequest" -> {
                    assertEquals("1234", req.headers().firstValue("PIN").orElseThrow());
                    yield new Cloud.Reply(200, "");
                }
                case "/hmip/auth/isRequestAcknowledged" -> new Cloud.Reply(poll[0]++ == 0 ? 204 : 200, "");
                case "/hmip/auth/requestAuthToken" -> new Cloud.Reply(200, "{\"authToken\":\"test-token\"}");
                case "/hmip/auth/confirmAuthToken" -> new Cloud.Reply(200, "{\"clientId\":\"C\"}");
                case "/hmip/home/getCurrentState" -> {
                    assertEquals("test-token", req.headers().firstValue("AUTHTOKEN").orElseThrow());
                    yield new Cloud.Reply(200, "{\"devices\":{},\"groups\":{}}");
                }
                default -> throw new AssertionError(req.uri());
            };
        })) {
            cloud.lookup();
            var id = cloud.startPairing("1234");
            assertTrue(cloud.finishPairing(id, "1234").isEmpty());
            assertEquals("test-token", cloud.finishPairing(id, "1234"));
            assertTrue(cloud.snapshot().has("devices"));
        }
    }

    @Test
    void temperatureCommandsConvertCompatibleUnits() {
        var target = point(Points.read(parse("""
                {"type":"HEATING","setPointTemperature":20}
                """), true), "group_setPointTemperature");
        var result = Points.command("G", target, QuantityType.valueOf("68 °F"));
        assertEquals(20.0, result.data().get("setPointTemperature").getAsDouble(), 0.00001);
        assertThrows(IllegalArgumentException.class, () -> Points.command("G", target, QuantityType.valueOf("21 W")));
    }

    @Test
    void buttonPressDoesNotRequestSnapshot() {
        var inventory = new HashMap<Access.Key, JsonObject>();
        var changed = new HashSet<Access.Key>();
        Access.applyEvent(parse("""
                {"pushEventType":"DEVICE_CHANNEL_EVENT","channelIndex":1}
                """), inventory, changed);
        assertTrue(inventory.isEmpty());
        assertTrue(changed.isEmpty());
        Access.applyEvent(parse("""
                {"pushEventType":"DEVICE_CHANGED"}
                """), inventory, changed);
    }

    @Test
    void honoursRetryAfterWithFifteenMinuteMinimum() {
        long now = 1_000;
        assertEquals(901_000, new Cloud.Status(429, "").retryAt(now));
        assertEquals(901_000, new Cloud.Status(429, "bad").retryAt(now));
        assertEquals(901_000, new Cloud.Status(429, "30").retryAt(now));
        assertEquals(1_801_000, new Cloud.Status(429, "1800").retryAt(now));
        assertEquals(3_600_000, new Cloud.Status(429, "Thu, 01 Jan 1970 01:00:00 GMT").retryAt(now));
    }

    @Test
    void capsCloudAndPersistedDeadlines() {
        long now = 1000;
        long cap = now + TimeUnit.HOURS.toMillis(6);
        assertEquals(cap, new Cloud.Status(429, "999999999999999").retryAt(now));
        assertEquals(cap, new Cloud.Status(429, "Fri, 01 Jan 2100 00:00:00 GMT").retryAt(now));
        assertEquals(cap, Access.persistedDeadline(Long.toString(Long.MAX_VALUE), now));
        assertEquals(cap, Access.persistedDeadline("2100-01-01T00:00:00Z", now));
        assertEquals(2000, Access.persistedDeadline("2000", now));
        assertEquals(2000, Access.persistedDeadline("1970-01-01T00:00:02Z", now));
        assertEquals(0, Access.persistedDeadline("999", now));
    }

    @Test
    void normalizesLegacyDeadlineOnlyOnceAcrossRestarts() {
        var clock = new AtomicLong(1000);
        var bridge = BridgeBuilder.create(Factory.ACCESS, "test")
                .withConfiguration(new Configuration(Map.of("sgtin", "000000000000000000000001", "token", "test")))
                .withProperties(Map.of("cloudRetryAt", Long.toString(Long.MAX_VALUE))).build();
        var worker = mock(ScheduledExecutorService.class);
        var transport = mock(Cloud.Transport.class);
        var access = new Access(bridge, () -> worker, transport, clock::get, () -> TimeUnit.HOURS.toMillis(6));
        access.setCallback(mock(ThingHandlerCallback.class));
        access.initialize();
        try {
            var deadline = Instant.ofEpochMilli(clock.get() + TimeUnit.HOURS.toMillis(6)).toString();
            assertEquals(deadline, access.getThing().getProperties().get("cloudRetryAt"));
            access.dispose();
            clock.addAndGet(TimeUnit.HOURS.toMillis(1));
            access.initialize();
            assertEquals(deadline, access.getThing().getProperties().get("cloudRetryAt"));
            var pending = ArgumentCaptor.forClass(Runnable.class);
            verify(worker, times(2)).execute(pending.capture());
            pending.getValue().run();
            verify(worker).schedule(any(Runnable.class), eq(TimeUnit.HOURS.toMillis(5)), eq(TimeUnit.MILLISECONDS));
            verifyNoInteractions(transport);
        } finally {
            access.dispose();
        }
    }

    @Test
    void resyncsOnScheduleAndRestoresRejectedCommands() throws Exception {
        var clock = new AtomicLong(1000);
        var bridge = BridgeBuilder.create(Factory.ACCESS, "test")
                .withConfiguration(new Configuration(Map.of("sgtin", "000000000000000000000001", "token", "test")))
                .build();
        var child = new Entity(ThingBuilder.create(Factory.DEVICE, "D").withBridge(bridge.getUID()).build());
        var childCallback = mock(ThingHandlerCallback.class);
        child.setCallback(childCallback);
        when(childCallback.getBridge(bridge.getUID())).thenReturn(bridge);
        var worker = mock(ScheduledExecutorService.class);
        var tick = new AtomicReference<Runnable>();
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(worker).execute(any(Runnable.class));
        when(worker.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            tick.set(call.getArgument(0));
            return null;
        });
        var transport = mock(Cloud.Transport.class);
        var socket = mock(WebSocket.class);
        when(socket.sendPing(any())).thenReturn(CompletableFuture.completedFuture(socket));
        var listener = new AtomicReference<WebSocket.Listener>();
        when(transport.connect(any(), any(), any())).thenAnswer(call -> {
            listener.set(call.getArgument(2));
            return socket;
        });
        List<String> paths = new ArrayList<>();
        when(transport.send(any())).thenAnswer(call -> {
            String path = call.getArgument(0, HttpRequest.class).uri().getPath();
            paths.add(path);
            return switch (path) {
                case "/getHost" -> new Cloud.Reply(200,
                        "{\"urlREST\":\"https://test.homematic.com\",\"urlWebSocket\":\"wss://test.homematic.com\"}");
                case "/hmip/home/getCurrentState" -> new Cloud.Reply(200,
                        """
                                {"devices":{"D":{"functionalChannels":{"1":{"functionalChannelType":"SWITCH_CHANNEL","on":true}}}}}
                                """);
                case "/hmip/device/control/setSwitchState" -> new Cloud.Reply(429, "", "1800");
                default -> throw new AssertionError(path);
            };
        });
        var access = new Access(bridge, () -> worker, transport, clock::get, () -> TimeUnit.HOURS.toMillis(6));
        bridge.setHandler(access);
        access.setCallback(mock(ThingHandlerCallback.class));
        access.initialize();
        try {
            var initial = List.of("/getHost", "/hmip/home/getCurrentState");
            assertEquals(initial, paths);
            clock.addAndGet(TimeUnit.HOURS.toMillis(6) - 1);
            listener.get().onPong(socket, ByteBuffer.allocate(0));
            tick.get().run();
            assertEquals(initial, paths);
            clock.incrementAndGet();
            tick.get().run();
            tick.get().run();
            assertEquals(List.of("/hmip/home/getCurrentState"), paths.subList(2, paths.size()));
            paths.clear();

            listener.get().onText(socket, "{", true);
            listener.get().onText(socket, "{\"events\":{\"1\":{\"pushEventType\":\"DEVICE_CHANGED\"}}}", true);
            clock.addAndGet(TimeUnit.MINUTES.toMillis(15) - 1);
            listener.get().onPong(socket, ByteBuffer.allocate(0));
            tick.get().run();
            assertTrue(paths.isEmpty());
            clock.incrementAndGet();
            tick.get().run();
            tick.get().run();
            assertEquals(List.of("/hmip/home/getCurrentState"), paths);

            child.accept(access.inventory().get(child.key()));
            child.getThing().setStatusInfo(ThingStatusInfoBuilder.create(ThingStatus.ONLINE).build());
            var channel = new ChannelUID(child.getThing().getUID(), "ch1_on");
            clearInvocations(childCallback);
            child.handleCommand(channel, OnOffType.OFF);
            verify(childCallback).stateUpdated(channel, OnOffType.ON);
            assertEquals(List.of("/hmip/home/getCurrentState", "/hmip/device/control/setSwitchState"), paths);
            assertEquals(Instant.ofEpochMilli(clock.get() + 1_800_000).toString(),
                    access.getThing().getProperties().get("cloudRetryAt"));
            paths.clear();
            clock.addAndGet(1_799_999);
            child.bridgeOffline();
            child.handleCommand(channel, OnOffType.OFF);
            verify(childCallback, times(2)).stateUpdated(channel, OnOffType.ON);
            tick.get().run();
            assertTrue(paths.isEmpty());
            verify(transport, times(1)).connect(any(), any(), any());
            clock.incrementAndGet();
            tick.get().run();
            assertEquals(initial, paths);
            assertFalse(access.getThing().getProperties().containsKey("cloudRetryAt"));
            verify(transport, times(2)).connect(any(), any(), any());
        } finally {
            access.dispose();
        }
    }

    @Test
    void onlineBridgeRefreshesChildBeforeReportingStatus() {
        var bridge = BridgeBuilder.create(Factory.ACCESS, "test")
                .withConfiguration(new Configuration(Map.of("sgtin", "000000000000000000000001"))).build();
        var worker = mock(ScheduledExecutorService.class);
        var access = new Access(bridge, () -> worker, null);
        bridge.setHandler(access);
        var thing = ThingBuilder.create(Factory.DEVICE, "sensor").withBridge(bridge.getUID()).build();
        var child = new Entity(thing);
        var callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(bridge.getUID())).thenReturn(bridge);
        child.setCallback(callback);
        access.setCallback(mock(ThingHandlerCallback.class));
        access.initialize();
        try {
            clearInvocations(worker);
            var offline = ThingStatusInfoBuilder.create(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE).build();
            child.bridgeStatusChanged(ThingStatusInfoBuilder.create(ThingStatus.OFFLINE).build());
            verify(callback).statusUpdated(thing, offline);
            verifyNoInteractions(worker);

            for (var detail : List.of(ThingStatusDetail.CONFIGURATION_PENDING, ThingStatusDetail.NONE)) {
                clearInvocations(worker, callback);
                child.bridgeStatusChanged(ThingStatusInfoBuilder.create(ThingStatus.ONLINE, detail).build());
                var refresh = ArgumentCaptor.forClass(Runnable.class);
                verify(worker).execute(refresh.capture());
                refresh.getValue().run();
                verify(callback).statusUpdated(thing, offline);
                verify(callback, times(1)).statusUpdated(eq(thing), any());
            }
        } finally {
            access.dispose();
        }
    }

    @Test
    void reinitializesDisposedHandler() throws Exception {
        var bridge = BridgeBuilder.create(Factory.ACCESS, "test")
                .withConfiguration(new Configuration(Map.of("sgtin", "000000000000000000000001"))).build();
        var first = mock(ScheduledExecutorService.class);
        var second = mock(ScheduledExecutorService.class);
        var workers = List.of(first, second).iterator();
        var requests = new java.util.ArrayList<String>();
        var access = new Access(bridge, workers::next, request -> {
            String path = request.uri().getPath();
            requests.add(path);
            return switch (path) {
                case "/getHost" -> new Cloud.Reply(200,
                        "{\"urlREST\":\"https://test.homematic.com\",\"urlWebSocket\":\"wss://test.homematic.com\"}");
                case "/hmip/auth/connectionRequest" -> new Cloud.Reply(200, "");
                default -> throw new AssertionError(path);
            };
        });
        access.setCallback(mock(ThingHandlerCallback.class));
        access.initialize();
        var before = ArgumentCaptor.forClass(Runnable.class);
        verify(first).execute(before.capture());
        before.getValue().run();
        var expected = List.of("/getHost", "/hmip/auth/connectionRequest");
        assertEquals(expected, requests);
        access.dispose();
        verify(first).shutdownNow();

        requests.clear();
        access.initialize();
        try {
            var after = ArgumentCaptor.forClass(Runnable.class);
            verify(second).execute(after.capture());
            before.getValue().run();
            assertTrue(requests.isEmpty());
            after.getValue().run();
            assertEquals(expected, requests);
        } finally {
            access.dispose();
        }
        verify(second).shutdownNow();
    }
}
