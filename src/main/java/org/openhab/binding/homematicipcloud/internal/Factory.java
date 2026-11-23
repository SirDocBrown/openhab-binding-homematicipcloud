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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;

/**
 * Creates handlers for supported thing types.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
@Component(service = ThingHandlerFactory.class)
public final class Factory extends BaseThingHandlerFactory {
    static final String ID = "homematicipcloud";
    static final ThingTypeUID ACCESS = new ThingTypeUID(ID, "accesspoint");
    static final ThingTypeUID DEVICE = new ThingTypeUID(ID, "device");
    static final ThingTypeUID GROUP = new ThingTypeUID(ID, "group");

    @Override
    public boolean supportsThingType(ThingTypeUID type) {
        return ACCESS.equals(type) || DEVICE.equals(type) || GROUP.equals(type);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        if (ACCESS.equals(thing.getThingTypeUID()) && thing instanceof Bridge bridge) {
            return new Access(bridge);
        }
        return supportsThingType(thing.getThingTypeUID()) ? new Entity(thing) : null;
    }
}
