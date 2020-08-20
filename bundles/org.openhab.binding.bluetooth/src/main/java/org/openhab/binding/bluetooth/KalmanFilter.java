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

    public enum FilterType {
        NONE(0, 0),
        FAST(0.125, 0.8),
        MODERATE(0.125, 5),
        SLOW(0.125, 15),
        VERY_SLOW(0.125, 120);

        private final double processNoise;
        private final double measurmentNoise;

        FilterType(double processNoise, double measurmentNoise) {
            this.processNoise = processNoise;
            this.measurmentNoise = measurmentNoise;
        }
    }

    private double processNoise;
    private double measurementNoise;
    private double estimatedRSSI;
    private double errorCovarianceRSSI;
    private boolean isInitialized = false;

    public KalmanFilter() {
        // DO NOTHING
    }

    public double applyFilter(double rssi, FilterType filterType) {
        double priorRSSI;
        double kalmanGain;
        double priorErrorCovarianceRSSI;

        if (processNoise != filterType.processNoise) {
            processNoise = filterType.processNoise;
            isInitialized = false;
        }
        if (measurementNoise != filterType.measurmentNoise) {
            measurementNoise = filterType.measurmentNoise;
            isInitialized = false;
        }

        if (!isInitialized) {
            priorRSSI = rssi;
            priorErrorCovarianceRSSI = 1;
            estimatedRSSI = 0;
            errorCovarianceRSSI = 0;
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
        isInitialized = false;
    }
}
