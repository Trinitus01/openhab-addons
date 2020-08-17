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
public class KalmanFilter {
    
    private double processNoise;
    private double measurementNoise;
    private double estimatedRSSI;
    private double errorCovarianceRSSI;
    private boolean isInitialized = false;

    public KalmanFilter() {
        this.processNoise = 0.125;
        this.measurementNoise = 0.8;
    }

    public KalmanFilter(double processNoise, double measurementNoise) {
        this.processNoise = processNoise;
        this.measurementNoise = measurementNoise;
    }
    
    public double applyFilter(double rssi) {
        double priorRSSI;
        double kalmanGain;
        double priorErrorCovarianceRSSI;
        
        if (!isInitialized) {
            priorRSSI = rssi;
            priorErrorCovarianceRSSI = 1;
            isInitialized = true;
        } else {
            priorRSSI = estimatedRSSI;
            priorErrorCovarianceRSSI = errorCovarianceRSSI + processNoise;
        }

        kalmanGain = priorErrorCovarianceRSSI / (priorErrorCovarianceRSSI + measurementNoise);
        estimatedRSSI = priorRSSI + (kalmanGain * (rssi - priorRSSI));
        errorCovarianceRSSI = (1 - kalmanGain) * priorErrorCovarianceRSSI;

        return estimatedRSSI;
    }
    
    public void resetFilter() {
        estimatedRSSI = 0;
        errorCovarianceRSSI = 0;
        isInitialized = false;
    }
}
