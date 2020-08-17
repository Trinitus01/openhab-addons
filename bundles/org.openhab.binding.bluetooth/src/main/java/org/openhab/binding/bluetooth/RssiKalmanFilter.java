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
public class RssiKalmanFilter {
    private double processNoise;
    private double measurementNoise;
    private double estimatedRssi;
    private double covarianceRssi;
    private boolean init = false;

    public RssiKalmanFilter() {
        this.processNoise = 0.125;
        this.measurementNoise = 0.8;
    }

    public RssiKalmanFilter(double processNoise, double measurementNoise) {
        this.processNoise = processNoise;
        this.measurementNoise = measurementNoise;
    }
    
    public double applyRssiKalmanFilter(double rssi) {
        double priorRssi;
        double kalmanGain;
        double priorCovarianceRSSI;
        
        if (!init) {
            priorRssi = rssi;
            priorCovarianceRSSI = 1;
            init = true;
        } else {
            priorRssi = estimatedRssi;
            priorCovarianceRSSI = covarianceRssi + processNoise;
        }

        kalmanGain = priorCovarianceRSSI / (priorCovarianceRSSI + measurementNoise);
        estimatedRssi = priorRssi + (kalmanGain * (rssi - priorRssi));
        covarianceRssi = (1 - kalmanGain) * priorCovarianceRSSI;

        return estimatedRssi;
    }
    
    public void resetRssiKalmanFilter() {
        covarianceRssi = 0;
        covarianceRssi = 0;
        init = false;
    }
}
