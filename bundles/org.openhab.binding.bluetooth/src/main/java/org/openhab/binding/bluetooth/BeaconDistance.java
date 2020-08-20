/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.bluetooth;

/**
 *
 * @author Tom Blum - Initial contribution
 */
public class BeaconDistance {

    public BeaconDistance() {
        // DO NOTHING
    }

    public double calculateDistanceFromRssi(double rssi, int txPower, int environmentalfactor) {
        double distance = Math.pow(10, ((txPower - rssi) / (10 * environmentalfactor)));

        return distance;
    }
}
