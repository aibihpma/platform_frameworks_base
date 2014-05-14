/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.content.Intent;
import android.provider.Settings;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BluetoothController;

/** Quick settings tile: Bluetooth **/
public class BluetoothTile extends QSTile<QSTile.BooleanState>  {
    private static final Intent BLUETOOTH_SETTINGS = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);

    private final BluetoothController mController;

    public BluetoothTile(Host host) {
        super(host);
        mController = host.getBluetoothController();
        mController.addStateChangedCallback(mCallback);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void dispose() {
        mController.removeStateChangedCallback(mCallback);
    }

    @Override
    protected void handleClick() {
        final boolean isEnabled = (Boolean)mState.value;
        mController.setBluetoothEnabled(!isEnabled);
    }

    @Override
    protected void handleSecondaryClick() {
        mHost.startSettingsActivity(BLUETOOTH_SETTINGS);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean supported = mController.isBluetoothSupported();
        final boolean enabled = mController.isBluetoothEnabled();
        final boolean connected = mController.isBluetoothConnected();
        state.visible = supported;
        state.value = enabled;
        state.icon = mHost.getVectorDrawable(R.drawable.ic_qs_bluetooth);
        final String stateContentDescription;
        if (enabled) {
            if (connected) {
                state.iconId = R.drawable.ic_qs_bluetooth_on;
                stateContentDescription = mContext.getString(R.string.accessibility_desc_connected);
            } else {
                state.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                stateContentDescription = mContext.getString(R.string.accessibility_desc_on);
            }
            state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
        } else {
            state.iconId = R.drawable.ic_qs_bluetooth_off;
            state.label = mContext.getString(R.string.quick_settings_bluetooth_off_label);
            stateContentDescription = mContext.getString(R.string.accessibility_desc_off);
        }
        state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_bluetooth, stateContentDescription);
    }

    private final BluetoothStateChangeCallback mCallback = new BluetoothStateChangeCallback() {
        @Override
        public void onBluetoothStateChange(boolean on) {
            refreshState();
        }
    };
}