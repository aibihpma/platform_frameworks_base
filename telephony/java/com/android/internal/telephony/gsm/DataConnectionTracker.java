/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.INetStatService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Checkin;
import android.provider.Settings;
import android.provider.Telephony;
import android.provider.Settings.SettingNotFoundException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.gsm.PdpConnection.PdpFailCause;

import android.telephony.ServiceState;
import android.util.EventLog;
import android.util.Log;
import android.text.TextUtils;

import java.io.IOException;
import java.util.ArrayList;

/**
 * {@hide}
 */
final class DataConnectionTracker extends Handler
{
    private static final String LOG_TAG = "GSM";
    private static final boolean DBG = true;

    /**
     * IDLE: ready to start data connection setup, default state
     * INITING: state of issued setupDefaultPDP() but not finish yet
     * CONNECTING: state of issued startPppd() but not finish yet
     * SCANNING: data connection fails with one apn but other apns are available
     *           ready to start data connection on other apns (before INITING)
     * CONNECTED: IP connection is setup
     * FAILED: data connection fail for all apns settings
     *
     * getDataConnectionState() maps State to DataState
     *      FAILED or IDLE : DISCONNECTED
     *      INITING or CONNECTING or SCANNING: CONNECTING
     *      CONNECTED : CONNECTED
     */
    enum State {
        IDLE,
        INITING,
        CONNECTING,
        SCANNING,
        CONNECTED,
        FAILED
    }

    enum Activity {
        NONE,
        DATAIN,
        DATAOUT,
        DATAINANDOUT
    }

    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mDataConnectionTracker);
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean isConnected;

            isConnected = (state != State.IDLE && state != State.FAILED);
            // TODO: It'd be nice to only do this if the changed entrie(s)
            // match the current operator.
            cleanUpConnection(isConnected, Phone.REASON_APN_CHANGED);
            createAllApnList();
            sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
        }
    }

    //***** Instance Variables

    GSMPhone phone;
    INetStatService netstat;
    State state = State.IDLE;
    Activity activity = Activity.NONE;
    boolean netStatPollEnabled = false;
    // Indicates baseband will not auto-attach
    private boolean noAutoAttach = false;
    long nextReconnectDelay = RECONNECT_DELAY_INITIAL_MILLIS;
    Handler mDataConnectionTracker = null;
    private ContentResolver mResolver;

    int txPkts, rxPkts, sentSinceLastRecv, netStatPollPeriod;
    private int mNoRecvPollCount = 0;
    private boolean mPingTestActive = false;
    // Count of PDP reset attempts; reset when we see incoming,
    // call reRegisterNetwork, or pingTest succeeds.
    private int mPdpResetCount = 0;
    private boolean mIsScreenOn = true;

    //useful for debugging
    boolean failNextConnect = false;

    /**
     * allApns holds all apns for this sim spn, retrieved from
     * the Carrier DB.
     *
     * Create once after simcard info is loaded
     */
    private ArrayList<ApnSetting> allApns = null;

    /**
     * waitingApns holds all apns that are waiting to be connected
     *
     * It is a subset of allApns and has the same format
     */
    private ArrayList<ApnSetting> waitingApns = null;

    /**
     * pdpList holds all the PDP connection, i.e. IP Link in GPRS
     */
    private ArrayList<PdpConnection> pdpList;

    /** CID of active PDP */
    int cidActive;
    
    /** Currently requested APN type */
    private String mRequestedApnType = Phone.APN_TYPE_DEFAULT;

    /** Currently active APN */
    private ApnSetting mActiveApn;

    /** Currently active PdpConnection */
    private PdpConnection mActivePdp;

    private static int APN_DEFAULT_ID = 0;
    private static int APN_MMS_ID = 1;
    private static int APN_NUM_TYPES = 2;

    private boolean[] dataEnabled = new boolean[APN_NUM_TYPES];

    //***** Constants

    // TODO: Increase this to match the max number of simultaneous
    // PDP contexts we plan to support.
    /**
     * Pool size of PdpConnection objects.
     */
    private static final int PDP_CONNECTION_POOL_SIZE = 1;

    private static final int POLL_PDP_MILLIS = 5 * 1000;
    private static final int RECONNECT_DELAY_INITIAL_MILLIS = 5 * 1000;

    /** Slow poll when attempting connection recovery. */
    private static final int POLL_NETSTAT_SLOW_MILLIS = 5000;

    /** Default ping deadline, in seconds. */
    private final int DEFAULT_PING_DEADLINE = 5;
    /** Default max failure count before attempting to network re-registration. */
    private final int DEFAULT_MAX_PDP_RESET_FAIL = 3;

    /**
     * After detecting a potential connection problem, this is the max number
     * of subsequent polls before attempting a radio reset.  At this point,
     * poll interval is 5 seconds (POLL_NETSTAT_SLOW_MILLIS), so set this to
     * poll for about 2 more minutes.
     */
    private static final int NO_RECV_POLL_LIMIT = 24;

    // 1 sec. default polling interval when screen is on.
    private static final int POLL_NETSTAT_MILLIS = 1000;
    // 10 min. default polling interval when screen is off.
    private static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000*60*10;
    // 2 min for round trip time
    private static final int POLL_LONGEST_RTT = 120 * 1000;
    // 10 for packets without ack
    private static final int NUMBER_SENT_PACKETS_OF_HANG = 10;
    // how long to wait before switching back to default APN
    private static final int RESTORE_DEFAULT_APN_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String APN_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    // represents an invalid IP address
    private static final String NULL_IP = "0.0.0.0";

    static final String INTENT_RECONNECT_ALARM = "com.android.internal.telephony.gprs-reconnect";

    //***** Tag IDs for EventLog
    private static final int EVENT_LOG_RADIO_RESET_COUNTDOWN_TRIGGERED = 50101;
    private static final int EVENT_LOG_RADIO_RESET = 50102;
    private static final int EVENT_LOG_PDP_RESET = 50103;
    private static final int EVENT_LOG_REREGISTER_NETWORK = 50104;

    //***** Event Codes
    static final int EVENT_DATA_SETUP_COMPLETE = 1;
    static final int EVENT_RADIO_AVAILABLE = 3;
    static final int EVENT_RECORDS_LOADED = 4;
    static final int EVENT_TRY_SETUP_DATA = 5;
    static final int EVENT_PDP_STATE_CHANGED = 6;
    static final int EVENT_POLL_PDP = 7;
    static final int EVENT_GET_PDP_LIST_COMPLETE = 11;
    static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 12;
    static final int EVENT_VOICE_CALL_STARTED = 14;
    static final int EVENT_VOICE_CALL_ENDED = 15;
    static final int EVENT_GPRS_DETACHED = 19;
    static final int EVENT_LINK_STATE_CHANGED = 20;
    static final int EVENT_ROAMING_ON = 21;
    static final int EVENT_ROAMING_OFF = 22;
    static final int EVENT_ENABLE_NEW_APN = 23;
    static final int EVENT_RESTORE_DEFAULT_APN = 24;
    static final int EVENT_DISCONNECT_DONE = 25;
    static final int EVENT_GPRS_ATTACHED = 26;
    static final int EVENT_START_NETSTAT_POLL = 27;
    static final int EVENT_START_RECOVERY = 28;

    BroadcastReceiver screenOnOffReceiver = new BroadcastReceiver ()
    {
        public void onReceive(Context context, Intent intent)
        {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
            } else {
                Log.w(LOG_TAG, "DataConnectionTracker received unexpected Intent: " + intent.getAction());
            }
        }
    };

    BroadcastReceiver alarmReceiver
            = new BroadcastReceiver () {

        public void onReceive(Context context, Intent intent)
        {
            Log.d(LOG_TAG, "GPRS reconnect alarm. Previous state was " + state);

            if (state == State.FAILED) {
                cleanUpConnection(false, null);
            }

            trySetupData(null);
        }
    };

    /** Watches for changes to the APN db. */
    private ApnChangeObserver apnObserver;

    //***** Constructor

    DataConnectionTracker(GSMPhone phone)
    {
        this.phone = phone;
        phone.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        phone.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        phone.mSIMRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        phone.mCM.registerForPDPStateChanged (this, EVENT_PDP_STATE_CHANGED, null);
        phone.mCT.registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        phone.mCT.registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        phone.mSST.registerForGprsAttached(this, EVENT_GPRS_ATTACHED, null);
        phone.mSST.registerForGprsDetached(this, EVENT_GPRS_DETACHED, null);
        phone.mSST.registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        phone.mSST.registerForRoamingOff(this, EVENT_ROAMING_OFF, null);

        this.netstat = INetStatService.Stub.asInterface(ServiceManager.getService("netstat"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_RECONNECT_ALARM);
        phone.getContext().registerReceiver(
                alarmReceiver, filter, null, phone.h);

        IntentFilter filterS = new IntentFilter();
        filterS.addAction(Intent.ACTION_SCREEN_ON);
        filterS.addAction(Intent.ACTION_SCREEN_OFF);
        phone.getContext().registerReceiver(screenOnOffReceiver, filterS);

        mDataConnectionTracker = this;
        mResolver = phone.getContext().getContentResolver();

        apnObserver = new ApnChangeObserver();
        phone.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, apnObserver);

        createAllPdpList();

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS attach.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(phone.getContext());
        dataEnabled[APN_DEFAULT_ID] = !sp.getBoolean(GSMPhone.DATA_DISABLED_ON_BOOT_KEY, false);
        noAutoAttach = !dataEnabled[APN_DEFAULT_ID];
    }

    void setState(State s) {
        if (state != s) {
            if (s == State.INITING) { // request PDP context
                Checkin.updateStats(
                        phone.getContext().getContentResolver(),
                        Checkin.Stats.Tag.PHONE_GPRS_ATTEMPTED, 1, 0.0);
            }

            if (s == State.CONNECTED) { // pppd is up
                Checkin.updateStats(
                        phone.getContext().getContentResolver(),
                        Checkin.Stats.Tag.PHONE_GPRS_CONNECTED, 1, 0.0);
            }
        }

        state = s;

        if (state == State.FAILED) {
            if (waitingApns != null)
                waitingApns.clear(); // when teardown the connection and set to IDLE
        }
    }
    String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    String getActiveApnString() {
        String result = null;
        if (mActiveApn != null) {
            result = mActiveApn.apn;
        }
        return result;
    }

    /**
     * Ensure that we are connected to an APN of the specified type.
     * @param type the APN type (currently the only valid value
     * is {@link Phone#APN_TYPE_MMS})
     * @return the result of the operation. Success is indicated by
     * a return value of either {@code Phone.APN_ALREADY_ACTIVE} or
     * {@code Phone.APN_REQUEST_STARTED}. In the latter case, a broadcast
     * will be sent by the ConnectivityManager when a connection to
     * the APN has been established.
     */
    int enableApnType(String type) {
        if (!TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            return Phone.APN_REQUEST_FAILED;
        }
        // If already active, return
        Log.d(LOG_TAG, "enableApnType("+type+")");
        if (isApnTypeActive(type)) {
            setEnabled(type, true);
            removeMessages(EVENT_RESTORE_DEFAULT_APN);
            /**
             * We're being asked to enable a non-default APN that's already in use.
             * This means we should restart the timer that will automatically
             * switch back to the default APN and disable the non-default APN
             * when it expires.
             */
            sendMessageDelayed(
                    obtainMessage(EVENT_RESTORE_DEFAULT_APN),
                    getRestoreDefaultApnDelay());
            return Phone.APN_ALREADY_ACTIVE;
        }

        if (!isApnTypeAvailable(type)) {
            return Phone.APN_TYPE_NOT_AVAILABLE;
        }

        setEnabled(type, true);
        mRequestedApnType = type;
        sendMessage(obtainMessage(EVENT_ENABLE_NEW_APN));
        return Phone.APN_REQUEST_STARTED;
    }

    /**
     * The APN of the specified type is no longer needed. Ensure that if
     * use of the default APN has not been explicitly disabled, we are connected
     * to the default APN.
     * @param type the APN type. The only valid value currently is {@link Phone#APN_TYPE_MMS}.
     * @return
     */
    int disableApnType(String type) {
        Log.d(LOG_TAG, "disableApnType("+type+")");
        if (TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            removeMessages(EVENT_RESTORE_DEFAULT_APN);
            setEnabled(type, false);
            if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                if (dataEnabled[APN_DEFAULT_ID]) {
                    return Phone.APN_ALREADY_ACTIVE;
                } else {
                    cleanUpConnection(true, Phone.REASON_DATA_DISABLED);
                    return Phone.APN_REQUEST_STARTED;
                }
            } else {
                /*
                 * Note that if default data is disabled, the following
                 * has the effect of disabling the MMS APN, and then
                 * ignoring the request to enable the default APN.
                 * The net result is that data is completely disabled.
                 */
                sendMessage(obtainMessage(EVENT_RESTORE_DEFAULT_APN));
                return Phone.APN_REQUEST_STARTED;
            }
        } else {
            return Phone.APN_REQUEST_FAILED;
        }
    }
    
    private boolean isApnTypeActive(String type) {
        // TODO: to support simultaneous, mActiveApn can be a List instead.
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    private boolean isApnTypeAvailable(String type) {
        if (allApns != null) {
            for (ApnSetting apn : allApns) {
                if (apn.canHandleType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isEnabled(String apnType) {
        if (TextUtils.equals(apnType, Phone.APN_TYPE_DEFAULT)) {
            return dataEnabled[APN_DEFAULT_ID];
        } else if (TextUtils.equals(apnType, Phone.APN_TYPE_MMS)) {
            return dataEnabled[APN_MMS_ID];
        } else {
            return false;
        }
    }

    private void setEnabled(String apnType, boolean enable) {
        Log.d(LOG_TAG, "setEnabled(" + apnType + ", " + enable + ')');
        if (TextUtils.equals(apnType, Phone.APN_TYPE_DEFAULT)) {
            dataEnabled[APN_DEFAULT_ID] = enable;
        } else if (TextUtils.equals(apnType, Phone.APN_TYPE_MMS)) {
            dataEnabled[APN_MMS_ID] = enable;
        }
        Log.d(LOG_TAG, "dataEnabled[DEFAULT_APN]=" + dataEnabled[APN_DEFAULT_ID] +
                " dataEnabled[MMS_APN]=" + dataEnabled[APN_MMS_ID]);
    }
    
    /**
     * Prevent mobile data connections from being established,
     * or once again allow mobile data connections. If the state
     * toggles, then either tear down or set up data, as
     * appropriate to match the new state.
     * <p>This operation only affects the default APN, and if the same APN is
     * currently being used for MMS traffic, the teardown will not happen
     * even when {@code enable} is {@code false}.</p>
     * @param enable indicates whether to enable ({@code true}) or disable ({@code false}) data
     * @return {@code true} if the operation succeeded
     */
    public boolean setDataEnabled(boolean enable) {
        boolean isEnabled = isEnabled(Phone.APN_TYPE_DEFAULT);
        Log.d(LOG_TAG, "setDataEnabled("+enable+") isEnabled=" + isEnabled);
        if (!isEnabled && enable) {
            setEnabled(Phone.APN_TYPE_DEFAULT, true);
            // trySetupData() will be a no-op if we are currently
            // connected to the MMS APN
            return trySetupData(Phone.REASON_DATA_ENABLED);
        } else if (!enable) {
            setEnabled(Phone.APN_TYPE_DEFAULT, false);
            // Don't tear down if there is an active APN and it handles MMS.
            // TODO: This isn't very general.
            if (!isApnTypeActive(Phone.APN_TYPE_MMS) || !isEnabled(Phone.APN_TYPE_MMS)) {
                cleanUpConnection(true, Phone.REASON_DATA_DISABLED);
                return true;
            }
            return false;
        } else // isEnabled && enable
            return true;
    }

    /**
     * Report the current state of data connectivity (enabled or disabled) for
     * the default APN.
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    public boolean getDataEnabled() {
        return dataEnabled[APN_DEFAULT_ID];
    }

    /**
     * Report on whether data connectivity is enabled for any APN.
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    public boolean getAnyDataEnabled() {
        return dataEnabled[APN_DEFAULT_ID] || dataEnabled[APN_MMS_ID];
    }

    //The data roaming setting is now located in the shared preferences.
    //  See if the requested preference value is the same as that stored in
    //  the shared values.  If it is not, then update it.
    public void setDataOnRoamingEnabled(boolean enabled) {
        if (getDataOnRoamingEnabled() != enabled) {
            Settings.System.putInt(phone.getContext().getContentResolver(),
                    Settings.System.DATA_ROAMING, enabled ? 1 : 0);
        }
        Message roamingMsg = phone.getServiceState().getRoaming() ?
            obtainMessage(EVENT_ROAMING_ON) : obtainMessage(EVENT_ROAMING_OFF);
        sendMessage(roamingMsg);
    }

    //Retrieve the data roaming setting from the shared preferences.
    public boolean getDataOnRoamingEnabled() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(), 
                    Settings.System.DATA_ROAMING) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    public ArrayList<PdpConnection> getAllPdps() {
        ArrayList<PdpConnection> pdps = (ArrayList<PdpConnection>)pdpList.clone();
        return pdps;
    }

    private boolean isDataAllowed() {
        boolean roaming = phone.getServiceState().getRoaming();
        return getAnyDataEnabled() && (!roaming || getDataOnRoamingEnabled());
    }

    //****** Called from ServiceStateTracker
    /**
     * Invoked when ServiceStateTracker observes a transition from GPRS
     * attach to detach.
     */
    private void onGprsDetached()
    {
        /*
         * We presently believe it is unnecessary to tear down the PDP context
         * when GPRS detaches, but we should stop the network polling.
         */
        stopNetStatPoll();
        phone.notifyDataConnection(Phone.REASON_GPRS_DETACHED);
    }
    
    private void onGprsAttached() {
        if (state == State.CONNECTED) {
            startNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_GPRS_ATTACHED);
        } else {
            trySetupData(Phone.REASON_GPRS_ATTACHED);
        }
    }

    private boolean trySetupData(String reason)
    {
        if (DBG) log("***trySetupData due to " + (reason == null ? "(unspecified)" : reason));

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(reason);

            Log.i(LOG_TAG, "(fix?) We're on the simulator; assuming data is connected");
            return true;
        }

        int gprsState = phone.mSST.getCurrentGprsState();
        boolean roaming = phone.getServiceState().getRoaming();

        if ((state == State.IDLE || state == State.SCANNING)
                && (gprsState == ServiceState.STATE_IN_SERVICE || noAutoAttach)
                && phone.mSIMRecords.getRecordsLoaded()
                && ( phone.mSST.isConcurrentVoiceAndData() ||
                     phone.getState() == Phone.State.IDLE )
                && isDataAllowed()) {

            if (state == State.IDLE) {
                waitingApns = buildWaitingApns();
                if (waitingApns.isEmpty()) {
                    if (DBG) log("No APN found");
                    notifyNoData(PdpConnection.PdpFailCause.BAD_APN);
                    return false;
                } else {
                    log ("Create from allApns : " + apnListToString(allApns));
                }
            }

            if (DBG) {
                log ("Setup watingApns : " + apnListToString(waitingApns));
            }
            return setupData(reason);
        } else {
            if (DBG)
                log("trySetupData: Not ready for data: " +
                    " dataState=" + state +
                    " gprsState=" + gprsState +
                    " sim=" + phone.mSIMRecords.getRecordsLoaded() +
                    " UMTS=" + phone.mSST.isConcurrentVoiceAndData() +
                    " phoneState=" + phone.getState() +
                    " dataEnabled=" + getAnyDataEnabled() +
                    " roaming=" + roaming +
                    " dataOnRoamingEnable=" + getDataOnRoamingEnabled());
            return false;
        }
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an INITING/CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     * @param tearDown true if the underlying PdpConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     */
    private void cleanUpConnection(boolean tearDown, String reason) {
        if (DBG) log("Clean up connection due to " + reason);
        for (PdpConnection pdp : pdpList) {
            if (tearDown) {
                Message msg = obtainMessage(EVENT_DISCONNECT_DONE);
                pdp.disconnect(msg);
            } else {
                pdp.clearSettings();
            }
        }
        stopNetStatPoll();
        setState(State.IDLE);
        phone.notifyDataConnection(reason);
        mActiveApn = null;
    }

    /**
     * @param types comma delimited list of APN types
     * @return array of APN types
     */
    private String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = Phone.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

    private ArrayList<ApnSetting> createApnList(Cursor cursor) {
        ArrayList<ApnSetting> result = new ArrayList<ApnSetting>();
        if (cursor.moveToFirst()) {
            do {
                String[] types = parseTypes(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                ApnSetting apn = new ApnSetting(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        types);
                result.add(apn);
            } while (cursor.moveToNext());
        }
        return result;
    }

    private PdpConnection findFreePdp() {
        for (PdpConnection pdp : pdpList) {
            if (pdp.getState() == PdpConnection.PdpState.INACTIVE) {
                return pdp;
            }
        }
        return null;
    }

    private boolean setupData(String reason) {
        ApnSetting apn;
        PdpConnection pdp;

        apn = getNextApn();
        if (apn == null) return false;
        pdp = findFreePdp();
        if (pdp == null) {
            if (DBG) log("setupData: No free PdpConnection found!");
            return false;
        }
        mActiveApn = apn;
        mActivePdp = pdp;

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        pdp.connect(apn, msg);

        setState(State.INITING);
        phone.notifyDataConnection(reason);
        return true;
    }

    String getInterfaceName(String apnType) {
        if (mActivePdp != null
                && (apnType == null || mActiveApn.canHandleType(apnType))) {
            return mActivePdp.getInterface();
        }
        return null;
    }

    String getIpAddress(String apnType) {
        if (mActivePdp != null
                && (apnType == null || mActiveApn.canHandleType(apnType))) {
            return mActivePdp.getIpAddress();
        }
        return null;
    }

    String getGateway(String apnType) {
        if (mActivePdp != null
                && (apnType == null || mActiveApn.canHandleType(apnType))) {
            return mActivePdp.getGatewayAddress();
        }
        return null;
    }

    String[] getDnsServers(String apnType) {
        if (mActivePdp != null
                && (apnType == null || mActiveApn.canHandleType(apnType))) {
            return mActivePdp.getDnsServers();
        }
        return null;
    }

    private boolean
    pdpStatesHasCID (ArrayList<PDPContextState> states, int cid)
    {
        for (int i = 0, s = states.size() ; i < s ; i++) {
            if (states.get(i).cid == cid) return true;
        }

        return false;
    }

    private boolean
    pdpStatesHasActiveCID (ArrayList<PDPContextState> states, int cid)
    {
        for (int i = 0, s = states.size() ; i < s ; i++) {
            if (states.get(i).cid == cid) return states.get(i).active;
        }

        return false;
    }

    /**
     * @param explicitPoll if true, indicates that *we* polled for this
     * update while state == CONNECTED rather than having it delivered
     * via an unsolicited response (which could have happened at any
     * previous state
     */
    private void
    onPdpStateChanged (AsyncResult ar, boolean explicitPoll)
    {
        ArrayList<PDPContextState> pdpStates;

        pdpStates = (ArrayList<PDPContextState>)(ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }


        // This is how things are supposed to work:
        // The PDP list is supposed to be empty of the CID
        // when it disconnects

        if (state == State.CONNECTED
                && !pdpStatesHasCID(pdpStates, cidActive)) {

            // It looks like the PDP context has deactivated
            // Tear everything down and try to reconnect

            Log.i(LOG_TAG, "PDP connection has dropped. Reconnecting");

            cleanUpConnection(true, null);
            trySetupData(null);

            return;
        }

        if (true) {
            //
            // Workaround for issue #655426
            //

            // --------------------------

            // This is how some things work now: the PDP context is still
            // listed with active = false, which makes it hard to
            // distinguish an activating context from an activated-and-then
            // deactivated one.
            //
            // Here, we only consider this authoritative if we asked for the
            // PDP list. If it was an unsolicited response, we poll again
            // to make sure everyone agrees on the initial state

            if (state == State.CONNECTED
                    && !pdpStatesHasActiveCID(pdpStates, cidActive)) {

                if (!explicitPoll) {
                    // We think it disconnected but aren't sure...poll from our side
                    phone.mCM.getPDPContextList(
                        this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
                } else {
                    Log.i(LOG_TAG, "PDP connection has dropped (active=false case). "
                                    + " Reconnecting");

                    cleanUpConnection(true, null);
                    trySetupData(null);
                }
            }
        }
    }

    private void notifyDefaultData() {
        setupDnsProperties();
        setState(State.CONNECTED);
        phone.notifyDataConnection(null);
        startNetStatPoll();
        // reset reconnect timer
        nextReconnectDelay = RECONNECT_DELAY_INITIAL_MILLIS;
    }

    private void setupDnsProperties() {
        int mypid = android.os.Process.myPid();
        String[] servers = getDnsServers(null);
        String propName;
        String propVal;
        int count;

        count = 0;
        for (int i = 0; i < servers.length; i++) {
            String serverAddr = servers[i];
            if (!TextUtils.equals(serverAddr, "0.0.0.0")) {
                SystemProperties.set("net.dns" + (i+1) + "." + mypid, serverAddr);
                count++;
            }
        }
        for (int i = count+1; i <= 4; i++) {
            propName = "net.dns" + i + "." + mypid;
            propVal = SystemProperties.get(propName);
            if (propVal.length() != 0) {
                SystemProperties.set(propName, "");
            }
        }
        /*
         * Bump the property that tells the name resolver library
         * to reread the DNS server list from the properties.
         */
        propVal = SystemProperties.get("net.dnschange");
        if (propVal.length() != 0) {
            try {
                int n = Integer.parseInt(propVal);
                SystemProperties.set("net.dnschange", "" + (n+1));
            } catch (NumberFormatException e) {
            }
        }
    }

    /**
     * This is a kludge to deal with the fact that
     * the PDP state change notification doesn't always work
     * with certain RIL impl's/basebands
     *
     */
    private void
    startPeriodicPdpPoll()
    {
        removeMessages(EVENT_POLL_PDP);

        sendMessageDelayed(obtainMessage(EVENT_POLL_PDP), POLL_PDP_MILLIS);
    }

    private void resetPollStats() {
        txPkts = -1;
        rxPkts = -1;
        sentSinceLastRecv = 0;
        mNoRecvPollCount = 0;
    }

    private void doRecovery() {
        if (state == State.CONNECTED) {
            int maxPdpReset = Settings.Gservices.getInt(mResolver,
                    Settings.Gservices.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                    DEFAULT_MAX_PDP_RESET_FAIL); 
            if (mPdpResetCount < maxPdpReset) {
                mPdpResetCount++;
                EventLog.writeEvent(EVENT_LOG_PDP_RESET, sentSinceLastRecv);
                cleanUpConnection(true, Phone.REASON_PDP_RESET);
            } else {
                mPdpResetCount = 0;
                EventLog.writeEvent(EVENT_LOG_REREGISTER_NETWORK, sentSinceLastRecv);
                phone.mSST.reRegisterNetwork(null);
            }
            // TODO: Add increasingly drastic recovery steps, eg,
            // reset the radio, reset the device.
        }
    }

    private void
    startNetStatPoll()
    {
        if (state == State.CONNECTED && mPingTestActive == false) {
            Log.d(LOG_TAG, "[DataConnection] Start poll NetStat");
            resetPollStats();
            netStatPollEnabled = true;
            mPollNetStat.run();
        }
    }

    private void
    stopNetStatPoll()
    {
        netStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        Log.d(LOG_TAG, "[DataConnection] Stop poll NetStat");
    }

    private void
    restartRadio()
    {
        Log.d(LOG_TAG, "************TURN OFF RADIO**************");
        cleanUpConnection(true, Phone.REASON_RADIO_TURNED_OFF);
        phone.mCM.setRadioPower(false, null);
        /* Note: no need to call setRadioPower(true).  Assuming the desired
         * radio power state is still ON (as tracked by ServiceStateTracker),
         * ServiceStateTracker will call setRadioPower when it receives the
         * RADIO_STATE_CHANGED notification for the power off.  And if the
         * desired power state has changed in the interim, we don't want to
         * override it with an unconditional power on.
         */

        int reset = Integer.parseInt(SystemProperties.get("net.ppp.reset-by-timeout", "0"));
        SystemProperties.set("net.ppp.reset-by-timeout", String.valueOf(reset+1));
    }

    Runnable mPollNetStat = new Runnable()
    {

        public void run() {
            int sent, received;
            int preTxPkts = -1, preRxPkts = -1;

            Activity newActivity;

            preTxPkts = txPkts;
            preRxPkts = rxPkts;

            try {
                txPkts = netstat.getTxPackets();
                rxPkts = netstat.getRxPackets();
            } catch (RemoteException e) {
                txPkts = 0;
                rxPkts = 0;
            }

            //Log.d(LOG_TAG, "rx " + String.valueOf(rxPkts) + " tx " + String.valueOf(txPkts));

            if (netStatPollEnabled && (preTxPkts > 0 || preRxPkts > 0)) {
                sent = txPkts - preTxPkts;
                received = rxPkts - preRxPkts;

                if ( sent > 0 && received > 0 ) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAINANDOUT;
                    mPdpResetCount = 0;
                } else if (sent > 0 && received == 0) {
                    if (phone.mCT.state == Phone.State.IDLE) {
                        sentSinceLastRecv += sent;
                    } else {
                        sentSinceLastRecv = 0;
                    }
                    newActivity = Activity.DATAOUT;
                } else if (sent == 0 && received > 0) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAIN;
                    mPdpResetCount = 0;
                } else if (sent == 0 && received == 0) {
                    newActivity = Activity.NONE;
                } else {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.NONE;
                }

                if (activity != newActivity && mIsScreenOn) {
                    activity = newActivity;
                    phone.notifyDataActivity();
                }
            }

            int watchdogTrigger = Settings.Gservices.getInt(mResolver,
                    Settings.Gservices.PDP_WATCHDOG_TRIGGER_PACKET_COUNT, NUMBER_SENT_PACKETS_OF_HANG);

            if (sentSinceLastRecv >= watchdogTrigger) {
                // we already have NUMBER_SENT_PACKETS sent without ack
                if (mNoRecvPollCount == 0) {
                    EventLog.writeEvent(EVENT_LOG_RADIO_RESET_COUNTDOWN_TRIGGERED,
                            sentSinceLastRecv);
                }

                int noRecvPollLimit = Settings.Gservices.getInt(mResolver,
                        Settings.Gservices.PDP_WATCHDOG_ERROR_POLL_COUNT, NO_RECV_POLL_LIMIT);

                if (mNoRecvPollCount < noRecvPollLimit) {
                    // It's possible the PDP context went down and we weren't notified.
                    // Start polling the context list in an attempt to recover.
                    if (DBG) log("no DATAIN in a while; polling PDP");
                    phone.mCM.getPDPContextList(obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));

                    mNoRecvPollCount++;

                    // Slow down the poll interval to let things happen
                    netStatPollPeriod = Settings.Gservices.getInt(mResolver,
                            Settings.Gservices.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS, POLL_NETSTAT_SLOW_MILLIS);
                } else {
                    if (DBG) log("Sent " + String.valueOf(sentSinceLastRecv) +
                                        " pkts since last received");
                    // We've exceeded the threshold.  Run ping test as a final check;
                    // it will proceed with recovery if ping fails.
                    netStatPollEnabled = false;
                    stopNetStatPoll();
                    Thread pingTest = new Thread() {
                        public void run() {
                            mPingTestActive = true;
                            runPingTest();
                        }
                    };
                    pingTest.start();
                }
            } else {
                mNoRecvPollCount = 0;
                if (mIsScreenOn) {
                    netStatPollPeriod = Settings.Gservices.getInt(mResolver,
                            Settings.Gservices.PDP_WATCHDOG_POLL_INTERVAL_MS, POLL_NETSTAT_MILLIS);
                } else {
                    netStatPollPeriod = Settings.Gservices.getInt(mResolver,
                            Settings.Gservices.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                            POLL_NETSTAT_SCREEN_OFF_MILLIS);
                }
            }

            if (netStatPollEnabled) {
                mDataConnectionTracker.postDelayed(this, netStatPollPeriod);
            }
        }
    };

    private void runPingTest () {
        int status = -1;
        try {
            String address = Settings.Gservices.getString(mResolver,
                    Settings.Gservices.PDP_WATCHDOG_PING_ADDRESS);
            int deadline = Settings.Gservices.getInt(mResolver,
                        Settings.Gservices.PDP_WATCHDOG_PING_DEADLINE, DEFAULT_PING_DEADLINE);
            if (DBG) log("pinging " + address + " for " + deadline + "s");
            if (address != null && !NULL_IP.equals(address)) {
                Process p = Runtime.getRuntime()
                                .exec("ping -c 1 -i 1 -w "+ deadline + " " + address);
                status = p.waitFor();
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "ping failed: IOException");
        } catch (Exception e) {
            Log.w(LOG_TAG, "exception trying to ping");
        }
        
        if (status == 0) {
            // ping succeeded.  False alarm.  Reset netStatPoll.
            // ("-1" for this event indicates a false alarm)
            EventLog.writeEvent(EVENT_LOG_PDP_RESET, -1);
            mPdpResetCount = 0;
            sendMessage(obtainMessage(EVENT_START_NETSTAT_POLL));
        } else {
            // ping failed.  Proceed with recovery.
            sendMessage(obtainMessage(EVENT_START_RECOVERY));
        }
    }

    /**
     * Returns true if the last fail cause is something that
     * seems like it deserves an error notification.
     * Transient errors are ignored
     */
    private boolean
    shouldPostNotification(PdpConnection.PdpFailCause  cause)
    {
        boolean shouldPost = true;
        // TODO CHECK
        // if (dataLink != null) {
        //    shouldPost = dataLink.getLastLinkExitCode() != DataLink.EXIT_OPEN_FAILED;
        //}
        return (shouldPost && cause != PdpConnection.PdpFailCause.UNKNOWN);
    }

    private void
    reconnectAfterFail(PdpConnection.PdpFailCause lastFailCauseCode)
    {
        if (state == State.FAILED) {
            Log.d(LOG_TAG, "PDP activate failed. Scheduling next attempt for "
                    + (nextReconnectDelay / 1000) + "s");

            try {
                IAlarmManager am = IAlarmManager.Stub.asInterface(
                        ServiceManager.getService(Context.ALARM_SERVICE));
                PendingIntent sender = PendingIntent.getBroadcast(
                        phone.getContext(), 0,
                        new Intent(INTENT_RECONNECT_ALARM), 0);
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + nextReconnectDelay,
                        sender);
            } catch (RemoteException ex) {
            }

            // double it for next time
            nextReconnectDelay *= 2;

            if (!shouldPostNotification(lastFailCauseCode)) {
                Log.d(LOG_TAG,"NOT Posting GPRS Unavailable notification "
                                + "-- likely transient error");
            } else {
                notifyNoData(lastFailCauseCode);
            }
        }
    }

    private void notifyNoData(PdpConnection.PdpFailCause lastFailCauseCode) {
        setState(State.FAILED);
    }


    private void log(String s) {
        Log.d(LOG_TAG, "[DataConnectionTracker] " + s);
    }

    //***** Overridden from Handler
    public void
    handleMessage (Message msg)
    {
        AsyncResult ar;
        String reason = null;

        switch (msg.what) {
            case EVENT_RECORDS_LOADED:
                createAllApnList();
                if (state == State.FAILED) {
                    cleanUpConnection(false, null);
                }
                sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
                break;

            case EVENT_ENABLE_NEW_APN:
                // TODO:  To support simultaneous PDP contexts, this should really only call
                // cleanUpConnection if it needs to free up a PdpConnection.
                reason = Phone.REASON_APN_SWITCHED;
                cleanUpConnection(true, reason);
                // Fall through to EVENT_TRY_SETUP_DATA.
            case EVENT_TRY_SETUP_DATA:
                trySetupData(reason);
                break;

            case EVENT_RESTORE_DEFAULT_APN:
                if (DBG) Log.d(LOG_TAG, "Restore default APN");
                setEnabled(Phone.APN_TYPE_MMS, false);
                if (!isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                    cleanUpConnection(true, Phone.REASON_RESTORE_DEFAULT_APN);
                    mRequestedApnType = Phone.APN_TYPE_DEFAULT;
                    trySetupData(Phone.REASON_RESTORE_DEFAULT_APN );
                }
                break;

            case EVENT_ROAMING_OFF:
                trySetupData(Phone.REASON_ROAMING_OFF);
                break;

            case EVENT_GPRS_DETACHED:
                onGprsDetached();
                break;

            case EVENT_GPRS_ATTACHED:
                onGprsAttached();
                break;
                
            case EVENT_ROAMING_ON:
                if (getDataOnRoamingEnabled()) {
                    trySetupData(Phone.REASON_ROAMING_ON);
                } else {
                    if (DBG) log("Tear down data connection on roaming.");
                    cleanUpConnection(true, Phone.REASON_ROAMING_ON);
                }
                break;

            case EVENT_RADIO_AVAILABLE:
                if (phone.getSimulatedRadioControl() != null) {
                    // Assume data is connected on the simulator
                    // FIXME  this can be improved
                    setState(State.CONNECTED);
                    phone.notifyDataConnection(null);


                    Log.i(LOG_TAG, "We're on the simulator; assuming data is connected");
                }

                if (state != State.IDLE) {
                    cleanUpConnection(true, null);
                }
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                // Make sure our reconnect delay starts at the initial value
                // next time the radio comes on
                nextReconnectDelay = RECONNECT_DELAY_INITIAL_MILLIS;

                if (phone.getSimulatedRadioControl() != null) {
                    // Assume data is connected on the simulator
                    // FIXME  this can be improved
                    Log.i(LOG_TAG, "We're on the simulator; assuming radio off is meaningless");
                } else {
                    if (DBG) log("Radio is off and clean up all connection");
                    // TODO: Should we reset mRequestedApnType to "default"?
                    cleanUpConnection(false, Phone.REASON_RADIO_TURNED_OFF);
                }
            break;

            case EVENT_DATA_SETUP_COMPLETE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    // everything is setup

                    // arg1 contains CID for this PDP context
                    cidActive = msg.arg1;
                    /*
                     * We may have switched away from the default PDP context
                     * in order to enable a "special" APN (e.g., for MMS
                     * traffic). Set a timer to switch back and/or disable the
                     * special APN, so that a negligient application doesn't
                     * permanently prevent data connectivity. What we are
                     * protecting against here is not malicious apps, but
                     * rather an app that inadvertantly fails to reset to the
                     * default APN, or that dies before doing so.
                     */
                    if (dataEnabled[APN_MMS_ID]) {
                        removeMessages(EVENT_RESTORE_DEFAULT_APN);
                        sendMessageDelayed(
                                obtainMessage(EVENT_RESTORE_DEFAULT_APN),
                                getRestoreDefaultApnDelay());
                    }
                    if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                        SystemProperties.set("gsm.defaultpdpcontext.active", "true");
                    } else {
                        SystemProperties.set("gsm.defaultpdpcontext.active", "false");
                    }
                    notifyDefaultData();
                    
                    // TODO: For simultaneous PDP support, we need to build another
                    // trigger another TRY_SETUP_DATA for the next APN type.  (Note
                    // that the existing connection may service that type, in which
                    // case we should try the next type, etc.
                } else {
                    PdpConnection.PdpFailCause cause;
                    cause = (PdpConnection.PdpFailCause) (ar.result);
                    if(DBG)
                        log("PDP setup failed " + cause);

                    // No try for permanent failure
                    if (cause.isPermanentFail()) {
                        notifyNoData(cause);
                    }

                    if (tryNextApn(cause)) {
                        waitingApns.remove(0);
                        if (waitingApns.isEmpty()) {
                            // No more to try, start delayed retry
                            notifyNoData(cause);
                            reconnectAfterFail(cause);
                        } else {
                            // we still have more apns to try
                            setState(State.SCANNING);
                            trySetupData(null);
                        }
                    } else {
                        notifyNoData(cause);
                        reconnectAfterFail(cause);
                    }
                }
            break;

            case EVENT_DISCONNECT_DONE:
                if(DBG) log("EVENT_DISCONNECT_DONE");
                trySetupData(null);
                break;

            case EVENT_PDP_STATE_CHANGED:
                ar = (AsyncResult) msg.obj;

                onPdpStateChanged(ar, false);
            break;

            case EVENT_GET_PDP_LIST_COMPLETE:
                ar = (AsyncResult) msg.obj;

                onPdpStateChanged(ar, true);
            break;

            case EVENT_POLL_PDP:
                /* See comment in startPeriodicPdpPoll */
                ar = (AsyncResult) msg.obj;

                if (!(state == State.CONNECTED)) {
                    // not connected; don't poll anymore
                    break;
                }

                phone.mCM.getPDPContextList(this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));

                sendMessageDelayed(obtainMessage(EVENT_POLL_PDP),
                                   POLL_PDP_MILLIS);
            break;

            case EVENT_VOICE_CALL_STARTED:
                if (state == State.CONNECTED &&
                        !phone.mSST.isConcurrentVoiceAndData()) {
                    stopNetStatPoll();
                    phone.notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
                }
            break;

            case EVENT_VOICE_CALL_ENDED:
                 // in case data setup was attempted when we were on a voice call
                trySetupData(Phone.REASON_VOICE_CALL_ENDED);
                if (state == State.CONNECTED &&
                        !phone.mSST.isConcurrentVoiceAndData()) {
                    startNetStatPoll();
                    phone.notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
                } else {
                    // clean slate after call end.
                    resetPollStats();
                }
            break;

            case EVENT_START_NETSTAT_POLL:
                mPingTestActive = false;
                startNetStatPoll();
                break;

            case EVENT_START_RECOVERY:
                mPingTestActive = false;
                doRecovery();
                break;
        }
    }

    private boolean tryNextApn(PdpFailCause cause) {
        return (cause != PdpFailCause.RADIO_NOT_AVIALABLE)
            && (cause != PdpFailCause.RADIO_OFF)
            && (cause != PdpFailCause.RADIO_ERROR_RETRY)
            && (cause != PdpFailCause.NO_SIGNAL)
            && (cause != PdpFailCause.SIM_LOCKED);
    }

    private int getRestoreDefaultApnDelay() {
        String restoreApnDelayStr = SystemProperties.get(APN_RESTORE_DELAY_PROP_NAME);

        if (restoreApnDelayStr != null && restoreApnDelayStr.length() != 0) {
            try {
                return Integer.valueOf(restoreApnDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        return RESTORE_DEFAULT_APN_DELAY;
   }

    /**
     * Based on the sim operator numeric, create a list for all possible pdps
     * with all apns associated with that pdp
     *
     *
     */
    private void createAllApnList() {
        allApns = new ArrayList<ApnSetting>();
        String operator = phone.mSIMRecords.getSIMOperatorNumeric();

        if (operator != null) {
            String selection = "numeric = '" + operator + "'";

            Cursor cursor = phone.getContext().getContentResolver().query(
                    Telephony.Carriers.CONTENT_URI, null, selection, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    allApns = createApnList(cursor);
                    // TODO: Figure out where this fits in.  This basically just 
                    // writes the pap-secrets file.  No longer tied to PdpConnection
                    // object.  Not used on current platform (no ppp).
                    //PdpConnection pdp = pdpList.get(pdp_name);
                    //if (pdp != null && pdp.dataLink != null) {
                    //    pdp.dataLink.setPasswordInfo(cursor);
                    //}
                }
                cursor.close();
            }
        }

        if (allApns.isEmpty()) {
            if (DBG) log("No APN found for carrier: " + operator);
            notifyNoData(PdpConnection.PdpFailCause.BAD_APN);
        }
    }

    private void createAllPdpList() {
        pdpList = new ArrayList<PdpConnection>();
        PdpConnection pdp;

        for (int i = 0; i < PDP_CONNECTION_POOL_SIZE; i++) {
            pdp = new PdpConnection(phone);
            pdpList.add(pdp);
         }
    }

    /**
     *
     * @return waitingApns list to be used to create PDP
     *          error when waitingApns.isEmpty()
     */
    private ArrayList<ApnSetting> buildWaitingApns() {
        ArrayList<ApnSetting> apnList = new ArrayList<ApnSetting>();

        if (allApns != null) {
            for (ApnSetting apn : allApns) {
                if (apn.canHandleType(mRequestedApnType)) {
                    apnList.add(apn);
                }
            }
        }
        return apnList;
    }

    /**
     * Get next apn in waitingApns 
     * @return the first apn found in waitingApns, null if none
     */
    private ApnSetting getNextApn() {
        ArrayList<ApnSetting> list = waitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    private String apnListToString (ArrayList<ApnSetting> apns) {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = apns.size(); i < size; i++) {
            result.append('[')
                  .append(apns.get(i).toString())
                  .append(']');
        }
        return result.toString();
    }
}
