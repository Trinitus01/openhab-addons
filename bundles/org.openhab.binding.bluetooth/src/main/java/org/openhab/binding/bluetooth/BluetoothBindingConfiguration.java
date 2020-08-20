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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 *
 * @author Tom Blum - Initial contribution
 */
@NonNullByDefault
public class BluetoothBindingConfiguration {
    public String kalmanfiltertype = KalmanFilter.FilterType.MODERATE.name();
    public int environmentalfactor = 3;
    public int onlinetimeout = 30;
    public boolean synchronizemac = true;

    public void update(BluetoothBindingConfiguration bluetoothBindingConfiguration) {
        this.kalmanfiltertype = bluetoothBindingConfiguration.kalmanfiltertype;
        this.environmentalfactor = bluetoothBindingConfiguration.environmentalfactor;
        this.onlinetimeout = bluetoothBindingConfiguration.onlinetimeout;
        this.synchronizemac = bluetoothBindingConfiguration.synchronizemac;
    }
}
