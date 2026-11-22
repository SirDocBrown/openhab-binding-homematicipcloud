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

import java.util.Map;
import java.util.Objects;

import org.openhab.core.library.types.OpenClosedType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises binding behaviour against synthetic cloud responses.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@org.eclipse.jdt.annotation.NonNullByDefault
public class MappingChecks {
    private static Points.Point point(Map<String, Points.Point> points, String id) {
        return Objects.requireNonNull(points.get(id), id);
    }

    static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public static void main(String[] args) throws Exception {
        var device = parse("""
                {"functionalChannels":{
                  "0":{"lowBat":true,"unreach":false,"firmware":"1.0","voltage":2.7},
                  "1":{"functionalChannelType":"SHUTTER_CONTACT_CHANNEL","windowState":"TILTED"},
                  "2":{"functionalChannelType":"BLIND_CHANNEL","shutterLevel":0.83,"slatsLevel":0.1},
                  "3":{"functionalChannelType":"SWITCH_MEASURING_CHANNEL","on":false,"currentPowerConsumption":54.2},
                  "4":{"functionalChannelType":"DIMMER_CHANNEL","dimLevel":0.87}}}
                """);
        var points = Points.read(device, false);
        assert Entity.state(point(points, "ch1_windowState")) == OpenClosedType.OPEN;
        assert Entity.state(point(points, "ch1_windowStateDetail")).toString().equals("TILTED");
        var deviceKey = new Access.Key(false, "SENSOR");
        var groupKey = new Access.Key(true, "GROUP");
        assert Discovery.discoverable(deviceKey, device, "HAP", false);
        assert !Discovery.discoverable(new Access.Key(false, "hap"), device, "HAP", true);
        assert !Discovery.discoverable(deviceKey, parse("{\"type\":\"HOME_CONTROL_ACCESS_POINT_TWO\"}"), "", true);
        assert !Discovery.discoverable(groupKey, device, "HAP", false);
        assert Discovery.discoverable(groupKey, device, "HAP", true);
        var uid = new org.openhab.core.thing.ThingUID("homematicipcloud:device:bridge:sensor");
        var oldWindow = org.openhab.core.thing.binding.builder.ChannelBuilder
                .create(new org.openhab.core.thing.ChannelUID(uid, "ch1_windowState"), "Contact")
                .withType(point(points, "ch1_windowState").channelType()).withLabel("K1 · windowState").build();
        var builder = org.openhab.core.thing.binding.builder.ThingBuilder.create(Factory.DEVICE, uid);
        var oldThing = builder.withChannel(oldWindow).build();
        var channels = Entity.channels(oldThing, points);
        assert channels.get(0).getUID().equals(oldWindow.getUID());
        assert Objects.requireNonNull(channels.get(1).getChannelTypeUID()).toString().equals("system:low-battery");
        assert Objects.requireNonNull(channels.get(2).getChannelTypeUID()).toString()
                .equals("homematicipcloud:unreachable");
        assert Objects.requireNonNull(channels.getLast().getChannelTypeUID()).getId().startsWith("diagnostic-");
        var migrated = builder.withChannels(channels).build();
        assert Entity.channels(migrated, points).equals(channels);
        assert Entity.channels(migrated, java.util.Map.of()).equals(channels);
        var roller = point(points, "ch2_shutterLevel");
        assert Points.command("D", roller, "DOWN").data().get("shutterLevel").getAsDouble() == 1;
        assert Points.command("D", roller, "UP").data().get("shutterLevel").getAsDouble() == 0;
        assert Points.command("D", roller, "STOP").path().equals("device/control/stop");
        var slats = Points.command("D", point(points, "ch2_slatsLevel"), "50");
        assert slats.data().get("shutterLevel").getAsDouble() == 0.83;
        assert slats.data().get("channelIndex").getAsInt() == 2;
        assert Points.command("D", point(points, "ch3_on"), "ON").data().get("on").getAsBoolean();
        assert Points.command("D", point(points, "ch4_dimLevel"), "75").data().get("dimLevel").getAsDouble() == 0.75;
        assert point(points, "ch3_currentPowerConsumption").kind().equals("number");
        var group = Points.read(parse("""
                {"type":"HEATING","setPointTemperature":20,"minTemperature":8,"maxTemperature":26,
                "activeProfile":1,"profiles":{"1":{}},"boostMode":false,"controlMode":"MANUAL"}
                """), true);
        assert Points.command("G", point(group, "group_setPointTemperature"), "21").data().has("groupId");
        assert Points.command("G", point(group, "group_boostMode"), "ON").data().get("boost").getAsBoolean();
        assert Points.command("G", point(group, "group_activeProfile"), "1").data().has("profileIndex");
        try {
            Points.command("G", point(group, "group_setPointTemperature"), "40");
            throw new AssertionError();
        } catch (IllegalArgumentException expected) {
        }
        try {
            Points.command("D", point(points, "ch0_lowBat"), "OFF");
            throw new AssertionError();
        } catch (IllegalArgumentException expected) {
        }
        var lamp = Points.read(
                parse("""
                        {"functionalChannels":{"2":{"functionalChannelType":"SIGNAL_LIGHT_CHANNEL","dimLevel":0.6,"simpleRGBColorState":"BLUE"}}}
                        """),
                false);
        var color = Points.command("D", point(lamp, "ch2_simpleRGBColorState"), "RED");
        assert color.data().get("dimLevel").getAsDouble() == 0.6;
        assert color.path().equals("device/control/setSimpleRGBColorDimLevel");
        var sw = Points.read(parse("""
                {"type":"SWITCHING","on":false}
                """), true);
        assert Points.command("G", point(sw, "group_on"), "ON").path().equals("group/switching/setState");
        int[] poll = { 0 };
        try (var cloud = new Cloud("000000000000000000000001", "", req -> {
            return switch (req.uri().getPath()) {
                case "/getHost" -> new Cloud.Reply(200,
                        "{\"urlREST\":\"https://test.homematic.com\",\"urlWebSocket\":\"wss://test.homematic.com\"}");
                case "/hmip/auth/connectionRequest" -> {
                    assert req.headers().firstValue("PIN").orElseThrow().equals("1234");
                    yield new Cloud.Reply(200, "");
                }
                case "/hmip/auth/isRequestAcknowledged" -> new Cloud.Reply(poll[0]++ == 0 ? 204 : 200, "");
                case "/hmip/auth/requestAuthToken" -> new Cloud.Reply(200, "{\"authToken\":\"test-token\"}");
                case "/hmip/auth/confirmAuthToken" -> new Cloud.Reply(200, "{\"clientId\":\"C\"}");
                case "/hmip/home/getCurrentState" -> {
                    assert req.headers().firstValue("AUTHTOKEN").orElseThrow().equals("test-token");
                    yield new Cloud.Reply(200, "{\"devices\":{},\"groups\":{}}");
                }
                default -> throw new AssertionError(req.uri());
            };
        })) {
            cloud.lookup();
            var id = cloud.startPairing("1234");
            assert cloud.finishPairing(id, "1234").isEmpty();
            assert cloud.finishPairing(id, "1234").equals("test-token");
            assert cloud.snapshot().has("devices");
        }
    }
}
