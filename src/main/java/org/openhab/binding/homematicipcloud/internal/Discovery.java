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

import java.time.Instant;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingUID;

import com.google.gson.JsonObject;

/**
 * Discovers devices and optional groups on the bridge.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public final class Discovery extends AbstractThingHandlerDiscoveryService<Access> {
    public Discovery() {
        super(Access.class, Set.of(Factory.DEVICE, Factory.GROUP), 10, false);
    }

    static boolean discoverable(Access.Key key, JsonObject entity, String serial, boolean discoverGroups) {
        if (key.group()) {
            return discoverGroups;
        }
        String type = Points.text(entity, "type", "");
        return !key.id().equalsIgnoreCase(serial) && !type.startsWith("HOME_CONTROL_ACCESS_POINT");
    }

    @Override
    protected void startScan() {
        Access access = thingHandler;
        if (access.getThing().getStatus() != ThingStatus.ONLINE
                || access.getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.CONFIGURATION_PENDING) {
            return;
        }
        Instant started = Instant.now();
        ThingUID bridge = access.getThing().getUID();
        boolean groups = Boolean.parseBoolean(access.setting("discoverGroups"));
        String serial = access.setting("sgtin").replace("-", "").replace(" ", "");
        access.inventory().forEach((key, value) -> {
            if (discoverable(key, value, serial, groups)) {
                ThingUID uid = new ThingUID(key.group() ? Factory.GROUP : Factory.DEVICE, bridge, key.id());
                thingDiscovered(DiscoveryResultBuilder.create(uid).withBridge(bridge)
                        .withLabel(Points.text(value, "label", key.id())).withProperty("cloudId", key.id())
                        .withRepresentationProperty("cloudId").build());
            }
        });
        removeOlderResults(started, bridge);
    }
}
