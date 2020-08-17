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
    
    private double coefficientA = 0.42093;
    private double coefficientB = 6.9476;
    private double coefficientC = 0.54992;
    
    public BeaconDistance() {
        // DO NOTHING
    }
    
    public double calculateDistanceFromRssi(double rssi, int txPower) {
        double distance;
        double ratio = rssi / txPower;
        
        if (ratio < 1.0) {
            distance = Math.pow(ratio, 10);
        } else {
            distance =  coefficientA * Math.pow(ratio, coefficientB) + coefficientC;
        }
        
        return distance;
    }
}
