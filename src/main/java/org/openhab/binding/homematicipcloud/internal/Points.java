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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.type.ChannelTypeUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Maps reported capabilities rather than maintaining a whitelist of product numbers.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class Points {
    record Point(int index, String field, String kind, JsonPrimitive value, JsonObject siblings) {
        String id() {
            return (index < 0 ? "group" : "ch" + index) + "_" + field;
        }

        int priority() {
            return switch (field) {
                case "windowState" -> 0;
                case "lowBat" -> 1;
                case "unreach" -> 2;
                default -> diagnostic() ? 4 : 3;
            };
        }

        private boolean diagnostic() {
            return (index == 0 && "DEVICE_BASE".equals(text(siblings, "functionalChannelType", "")))
                    || DIAGNOSTICS.contains(field);
        }

        ChannelTypeUID channelType() {
            if ("lowBat".equals(field) && "flag".equals(kind)) {
                return new ChannelTypeUID("system", "low-battery");
            }
            if ("unreach".equals(field) && "flag".equals(kind)) {
                return new ChannelTypeUID(Factory.ID, "unreachable");
            }
            return new ChannelTypeUID(Factory.ID,
                    diagnostic() && Set.of("flag", "text", "number").contains(kind) ? "diagnostic-" + kind : kind);
        }
    }

    record Request(String path, JsonObject data) {
    }

    private static final Set<String> DIAGNOSTICS = Set.of("channelRole", "windowStateDetail", "eventDelay",
            "routerModuleEnabled", "multicastRoutingEnabled", "routerModuleSupported", "rssiDeviceValue",
            "rssiPeerValue", "configPending", "dutyCycle", "deviceOverloaded", "coProUpdateFailure", "coProFaulty",
            "coProRestartNeeded", "deviceUndervoltage", "deviceOverheated", "temperatureOutOfRange",
            "devicePowerFailureDetected", "bootedRecently", "lastBootTimestamp");
    static final Set<String> INTERNAL = Set.of("id", "deviceId", "groupId", "homeId", "label", "type", "index",
            "groupIndex", "functionalChannelType", "channelIndex", "lastStatusUpdate", "modelType");

    static String text(JsonObject json, String key, String fallback) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    static Map<String, Point> read(JsonObject entity, boolean group) {
        Map<String, Point> points = new LinkedHashMap<>();
        if (group) {
            fields(points, -1, entity, text(entity, "type", ""));
        } else if (entity.get("functionalChannels") instanceof JsonObject channels) {
            for (var entry : channels.entrySet()) {
                if (!entry.getKey().matches("[0-9]{1,5}") || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject channel = entry.getValue().getAsJsonObject();
                fields(points, Integer.parseInt(entry.getKey()), channel, text(channel, "functionalChannelType", ""));
            }
        }
        return points;
    }

    private static void fields(Map<String, Point> out, int index, JsonObject values, String type) {
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonPrimitive() || !key.matches("[A-Za-z][A-Za-z0-9_]*")
                    || INTERNAL.contains(key)) {
                continue;
            }
            JsonPrimitive raw = entry.getValue().getAsJsonPrimitive();
            String kind = raw.isBoolean() ? "flag" : raw.isNumber() ? "number" : "text";
            boolean device = index >= 0;
            boolean switchingGroup = !device && Set.of("SWITCHING", "EXTENDED_LINKED_SWITCHING").contains(type);
            boolean shade = device ? type.contains("SHUTTER") || type.contains("BLIND")
                    : type.contains("SHUTTER") || switchingGroup;
            if ("windowState".equals(key)) {
                kind = "window";
            }
            if (shade && "shutterLevel".equals(key) && raw.isNumber()) {
                kind = "roller";
            }
            if (shade && "slatsLevel".equals(key) && raw.isNumber()
                    && values.get("shutterLevel") instanceof JsonPrimitive) {
                kind = "slats";
            }
            if ((switchingGroup || device && type.contains("SWITCH")) && "on".equals(key) && raw.isBoolean()) {
                kind = "power";
            }
            if (device && (type.contains("DIMMER") || type.contains("DIMMING")) && "dimLevel".equals(key)
                    && raw.isNumber()) {
                kind = "dimmer";
            }
            if (device && values.get("dimLevel") instanceof JsonPrimitive level && level.isNumber()
                    && values.get("simpleRGBColorState") instanceof JsonPrimitive color && color.isString()) {
                if ("simpleRGBColorState".equals(key)) {
                    kind = "palette";
                }
                if ("dimLevel".equals(key)) {
                    kind = "paletteDim";
                }
            }
            if (!device && "HEATING".equals(type)) {
                kind = switch (key) {
                    case "setPointTemperature" -> "target";
                    case "boostMode" -> "boost";
                    case "controlMode" -> "mode";
                    case "activeProfile" -> "profile";
                    default -> kind;
                };
            }
            var point = new Point(index, key, kind, raw, values);
            out.put(point.id(), point);
            if ("window".equals(kind)) {
                var detail = new Point(index, "windowStateDetail", "text", raw, values);
                out.put(detail.id(), detail);
            }
        }
    }

    static Request command(String entityId, Point p, String command) {
        boolean group = p.index < 0;
        JsonObject data = new JsonObject();
        data.addProperty(group ? "groupId" : "deviceId", entityId);
        if (!group) {
            data.addProperty("channelIndex", p.index);
        }
        String endpoint;
        switch (p.kind) {
            case "power", "boost" -> {
                if (!Set.of("ON", "OFF").contains(command)) {
                    throw new IllegalArgumentException("ON/OFF erwartet");
                }
                boolean boost = "boost".equals(p.kind);
                data.addProperty(boost ? "boost" : "on", "ON".equals(command));
                endpoint = boost ? "group/heating/setBoost"
                        : group ? "group/switching/setState" : "device/control/setSwitchState";
            }
            case "roller", "slats", "dimmer" -> {
                String prefix = group ? "group/switching/" : "device/control/";
                if ("roller".equals(p.kind) && "STOP".equals(command)) {
                    return new Request(prefix + "stop", data);
                }
                String level = switch (p.kind) {
                    case "roller" -> switch (command) {
                        case "UP" -> "0";
                        case "DOWN" -> "100";
                        default -> command;
                    };
                    case "dimmer" -> switch (command) {
                        case "OFF" -> "0";
                        case "ON" -> "100";
                        default -> command;
                    };
                    default -> command;
                };
                data.addProperty(p.field, range(level, 0, 100).movePointLeft(2));
                if ("slats".equals(p.kind)) {
                    data.add("shutterLevel", p.siblings.get("shutterLevel"));
                }
                endpoint = prefix + switch (p.kind) {
                    case "roller" -> "setShutterLevel";
                    case "slats" -> "setSlatsLevel";
                    default -> "setDimLevel";
                };
            }
            case "palette", "paletteDim" -> {
                String color = "palette".equals(p.kind) ? command : p.siblings.get("simpleRGBColorState").getAsString();
                if (!Set.of("BLACK", "BLUE", "GREEN", "TURQUOISE", "RED", "PURPLE", "YELLOW", "WHITE")
                        .contains(color)) {
                    throw new IllegalArgumentException("Unbekannte Lichtfarbe");
                }
                String level = switch (command) {
                    case "ON" -> "100";
                    case "OFF" -> "0";
                    default -> command;
                };
                data.addProperty("simpleRGBColorState", color);
                data.addProperty("dimLevel", "paletteDim".equals(p.kind) ? range(level, 0, 100).movePointLeft(2)
                        : p.siblings.get("dimLevel").getAsBigDecimal());
                endpoint = "device/control/setSimpleRGBColorDimLevel";
            }
            case "target" -> {
                data.addProperty("setPointTemperature",
                        range(command, Double.parseDouble(text(p.siblings, "minTemperature", "5")),
                                Double.parseDouble(text(p.siblings, "maxTemperature", "30"))));
                endpoint = "group/heating/setSetPointTemperature";
            }
            case "mode" -> {
                if (!Set.of("AUTOMATIC", "MANUAL").contains(command)) {
                    throw new IllegalArgumentException("Ungültiger Modus");
                }
                data.addProperty("controlMode", command);
                endpoint = "group/heating/setControlMode";
            }
            case "profile" -> {
                int profile = new BigDecimal(command).intValueExact();
                if (!(p.siblings.get("profiles") instanceof JsonObject profiles)
                        || !profiles.has(Integer.toString(profile))) {
                    throw new IllegalArgumentException("Profil nicht vorhanden");
                }
                data.addProperty("profileIndex", profile);
                endpoint = "group/heating/setActiveProfile";
            }
            default -> throw new IllegalArgumentException("Nur lesbar");
        }
        return new Request(endpoint, data);
    }

    private static BigDecimal range(String value, double min, double max) {
        var number = new BigDecimal(value);
        if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException("Wert außerhalb des Bereichs");
        }
        return number;
    }
}
