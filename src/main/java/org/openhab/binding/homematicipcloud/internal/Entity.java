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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Handles devices and groups reported by the cloud.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class Entity extends BaseThingHandler {
    private volatile Map<String, Points.Point> points = Map.of();

    Entity(Thing thing) {
        super(thing);
    }

    private @Nullable Access access() {
        Bridge parent = getBridge();
        return parent != null && parent.getHandler() instanceof Access a ? a : null;
    }

    Access.Key key() {
        Object cloudId = getConfig().get("cloudId");
        return new Access.Key(Factory.GROUP.equals(getThing().getThingTypeUID()),
                cloudId == null ? getThing().getUID().getId() : cloudId.toString());
    }

    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        Access bridge = access();
        if (bridge != null) {
            bridge.refreshChild(this);
        }
    }

    static String item(String kind) {
        return switch (kind) {
            case "flag", "power", "boost" -> "Switch";
            case "target", "profile", "number" -> "Number";
            case "dimmer", "slats", "paletteDim" -> "Dimmer";
            case "roller" -> "Rollershutter";
            case "window" -> "Contact";
            default -> "String";
        };
    }

    static State state(Points.Point p) {
        JsonPrimitive v = p.value();
        return switch (item(p.kind())) {
            case "Switch" -> v.getAsBoolean() ? OnOffType.ON : OnOffType.OFF;
            case "Number" -> new DecimalType(v.getAsBigDecimal());
            case "Rollershutter", "Dimmer" -> new PercentType(v.getAsBigDecimal().movePointRight(2));
            case "Contact" -> switch (v.getAsString()) {
                case "OPEN", "TILTED" -> OpenClosedType.OPEN;
                case "CLOSED" -> OpenClosedType.CLOSED;
                default -> UnDefType.UNDEF;
            };
            default -> new StringType(v.getAsString());
        };
    }

    void accept(@Nullable JsonObject data) {
        if (data == null) {
            points = Map.of();
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.missing-entity");
            return;
        }
        var mapped = Points.read(data, key().group());
        List<Channel> channels = channels(getThing(), mapped);
        if (!channels.equals(getThing().getChannels())) {
            updateThing(editThing().withChannels(channels).build());
        }
        for (String old : points.keySet()) {
            if (!mapped.containsKey(old)) {
                updateState(old, UnDefType.UNDEF);
            }
        }
        points = Map.copyOf(mapped);
        for (var p : mapped.values()) {
            publish(p);
        }
        boolean unreachable = mapped.values().stream()
                .anyMatch(p -> "unreach".equals(p.field()) && p.value().getAsBoolean());
        updateStatus(unreachable ? ThingStatus.OFFLINE : ThingStatus.ONLINE);
    }

    static List<Channel> channels(Thing thing, Map<String, Points.Point> mapped) {
        List<Channel> channels = new ArrayList<>();
        for (Points.Point point : mapped.values().stream().sorted(Comparator.comparingInt(Points.Point::priority))
                .toList()) {
            ChannelTypeUID type = point.channelType();
            Channel existing = thing.getChannel(point.id());
            String label = point.priority() < 3 ? null
                    : (point.index() < 0 ? "" : "K" + point.index() + " · ") + point.field();
            if (existing != null && type.equals(existing.getChannelTypeUID())
                    && Objects.equals(label, existing.getLabel())) {
                channels.add(existing);
            } else {
                ChannelBuilder builder = existing == null || label == null
                        ? ChannelBuilder.create(new ChannelUID(thing.getUID(), point.id()))
                        : ChannelBuilder.create(existing);
                if (label != null) {
                    builder.withLabel(label);
                } else if (existing != null) {
                    builder.withConfiguration(existing.getConfiguration()).withProperties(existing.getProperties())
                            .withDefaultTags(existing.getDefaultTags());
                }
                channels.add(builder.withType(type).withAcceptedItemType(item(point.kind())).build());
            }
        }
        // A partial cloud report can omit a previously linked channel.
        for (Channel channel : thing.getChannels()) {
            if (!mapped.containsKey(channel.getUID().getId())) {
                channels.add(channel);
            }
        }
        return channels;
    }

    private void publish(Points.Point p) {
        try {
            updateState(p.id(), state(p));
        } catch (IllegalArgumentException e) {
            updateState(p.id(), UnDefType.UNDEF);
        }
    }

    public void handleCommand(ChannelUID uid, Command command) {
        Points.Point p = points.get(uid.getId());
        if (p == null) {
            return;
        }
        if (command == RefreshType.REFRESH) {
            publish(p);
            return;
        }
        Access parent = access();
        if (parent != null && getThing().getStatus() == ThingStatus.ONLINE) {
            parent.command(this, uid.getId(), command.toString());
        }
    }
}
