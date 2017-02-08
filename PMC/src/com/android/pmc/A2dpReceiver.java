/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.pmc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;

/**
 * Bluetooth A2DP Receiver functions for codec power testing.
 */
public class A2dpReceiver extends BroadcastReceiver {
    public static final String TAG = "A2DPPOWER";
    public static final String A2DP_INTENT = "com.android.pmc.A2DP";
    public static final String A2DP_ALARM = "com.android.pmc.A2DP.Alarm";
    public static final String A2DP_ACTION = "com.android.pmc.A2DP.Action";
    public static final String CURRENT_ALARM = "com.android.pmc.A2DP.CurrentAlarm";
    public static final int ALARM_MESSAGE = 1;
    public static final int THOUSAND = 1000;
    public static final int WAIT_SECONDS = 10;

    public static final int START_PLAY = 1;
    public static final int PAUSE_PLAY = 2;
    public static final int STOP_PLAY = 3;
    public static final float NORMAL_VOLUME = 0.5f;
    public static final float ZERO_VOLUME = 0.0f;

    private final Context mContext;
    private final AlarmManager mAlarmManager;

    private int mPlayTime;
    private int mIdleTime;
    private int mNumAlarms;
    private MediaPlayer mPlayer;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothA2dp mBluetoothA2dp;
    // For a baseline case when music is not play but BT is enabled.
    private boolean mNotPlay = false;
    // For a baseline case when BT is off but music is playing with speaker is muted
    private boolean mMute = false;

    /**
     * BroadcastReceiver() to get status after calling setCodecConfigPreference()
     *
     */
    private BroadcastReceiver mBluetoothA2dpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "mBluetoothA2dpReceiver.onReceive() intent=" + intent);
            String action = intent.getAction();

            if (BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
                getCodecValue(true);
            }
        }
    };

    /**
     * ServiceListener for A2DP connection/disconnection event
     *
     */
    private BluetoothProfile.ServiceListener mBluetoothA2dpServiceListener =
            new BluetoothProfile.ServiceListener() {
            public void onServiceConnected(int profile,
                                           BluetoothProfile proxy) {
                Log.d(TAG, "BluetoothA2dpServiceListener.onServiceConnected");
                mBluetoothA2dp = (BluetoothA2dp) proxy;
                getCodecValue(true);
            }

            public void onServiceDisconnected(int profile) {
                Log.d(TAG, "BluetoothA2dpServiceListener.onServiceDisconnected");
                mBluetoothA2dp = null;
            }
        };

    /**
     * Constructor to be called by PMC
     *
     * @param context - PMC will provide a context
     * @param alarmManager - PMC will provide alarmManager
     */
    public A2dpReceiver(Context context, AlarmManager alarmManager) {
        // Prepare for setting alarm service
        mContext = context;
        mAlarmManager = alarmManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter is Null");
            return;
        } else {
            if (!mBluetoothAdapter.isEnabled()) {
                Log.d(TAG, "BluetoothAdapter is NOT enabled, enable now");
                mBluetoothAdapter.enable();
                if (!mBluetoothAdapter.isEnabled()) {
                    Log.e(TAG, "Can't enable Bluetooth");
                    return;
                }
            }
        }
        // Setup BroadcastReceiver for ACTION_CODEC_CONFIG_CHANGED
        IntentFilter filter = new IntentFilter();
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(context,
                                    mBluetoothA2dpServiceListener,
                                    BluetoothProfile.A2DP);
        }
        filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        context.registerReceiver(mBluetoothA2dpReceiver, filter);
    }

    /**
     * Method to receive the broadcast from Python client or AlarmManager
     *
     * @param context - system will provide a context to this function
     * @param intent - system will provide an intent to this function
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.getAction().equals(A2DP_INTENT)) return;
        boolean alarm = intent.hasExtra(A2DP_ALARM);
        if (alarm) {
            Log.d(TAG, "Received Alarm broadcast message");
            handleAlarm(intent);
        } else {
            Log.d(TAG, "Received PMC command message");
            processParameters(intent);
        }
    }

    /**
     * Method to process parameters from Python client
     *
     * @param intent - system will provide an intent to this function
     */
    private void processParameters(Intent intent) {
        int codecType = BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID;
        int sampleRate = BluetoothCodecConfig.SAMPLE_RATE_NONE;
        int bitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_NONE;
        int channelMode = BluetoothCodecConfig.CHANNEL_MODE_STEREO;
        // codecSpecific1 is for LDAC quality so far
        // Other code specific values are not used now
        long codecSpecific1 = 0, codecSpecific2 = 0, codecSpecific3 = 0,
                codecSpecific4 = 0;
        int startTime = 0, playTime = 0, idleTime = 0, repetitions = 1;
        String musicUrl;
        String tmpStr;
        // Reset these values for each test cases
        // For a baseline case when music is not play but BT is enabled.
        mNotPlay = false;
        // For a baseline case when BT is off but music is playing with speaker is muted
        mMute = false;

        Bundle extras = intent.getExtras();

        if (extras == null) {
            Log.e(TAG, "No parameters specified");
            return;
        }

        if (!extras.containsKey("CodecType")) {
            Log.e(TAG, "No Codec Type specified");
            return;
        }
        tmpStr = extras.getString("CodecType");
        Log.d(TAG, "Codec Type= " + tmpStr);
        codecType = Integer.valueOf(tmpStr);

        if (!extras.containsKey("SampleRate")) {
            Log.e(TAG, "No Sample Rate specified");
            return;
        }
        tmpStr = extras.getString("SampleRate");
        Log.d(TAG, "Sample Rate = " + tmpStr);
        sampleRate = Integer.valueOf(tmpStr);

        if (!extras.containsKey("BitsPerSample")) {
            Log.e(TAG, "No BitsPerSample specified");
            return;
        }
        tmpStr = extras.getString("BitsPerSample");
        Log.d(TAG, "BitsPerSample = " + tmpStr);
        bitsPerSample = Integer.valueOf(tmpStr);

        if (extras.containsKey("ChannelMode")) {
            tmpStr = extras.getString("ChannelMode");
            Log.d(TAG, "ChannelMode = " + tmpStr);
            channelMode = Integer.valueOf(tmpStr);
        }

        if (extras.containsKey("LdacPlaybackQuality")) {
            tmpStr = extras.getString("LdacPlaybackQuality");
            Log.d(TAG, "LdacPlaybackQuality = " + tmpStr);
            codecSpecific1 = Integer.valueOf(tmpStr);
        }

        if (extras.containsKey("CodecSpecific2")) {
            tmpStr = extras.getString("CodecSpecific2");
            Log.d(TAG, "CodecSpecific2 = " + tmpStr);
            codecSpecific1 = Integer.valueOf(tmpStr);
        }

        if (extras.containsKey("CodecSpecific3")) {
            tmpStr = extras.getString("CodecSpecific3");
            Log.d(TAG, "CodecSpecific3 = " + tmpStr);
            codecSpecific1 = Integer.valueOf(tmpStr);
        }

        if (extras.containsKey("CodecSpecific4")) {
            tmpStr = extras.getString("CodecSpecific4");
            Log.d(TAG, "CodecSpecific4 = " + tmpStr);
            codecSpecific1 = Integer.valueOf(tmpStr);
        }

        if (!extras.containsKey("StartTime")) {
            Log.e(TAG, "No Start Time specified");
            return;
        }
        tmpStr = extras.getString("StartTime");
        Log.d(TAG, "Start Time = " + tmpStr);
        startTime = Integer.valueOf(tmpStr);

        if (!extras.containsKey("PlayTime")) {
            Log.e(TAG, "No Play Time specified");
            return;
        }
        tmpStr = extras.getString("PlayTime");
        Log.d(TAG, "Play Time = " + tmpStr);
        playTime = Integer.valueOf(tmpStr);

        if (extras.containsKey("IdleTime")) {
            tmpStr = extras.getString("IdleTime");
            Log.d(TAG, "Idle Time = " + tmpStr);
            idleTime = Integer.valueOf(tmpStr);
        }

        if (extras.containsKey("Repetitions")) {
            tmpStr = extras.getString("Repetitions");
            Log.d(TAG, "Repetitions = " + tmpStr);
            repetitions = Integer.valueOf(tmpStr);
        }

        if (!extras.containsKey("MusicURL")) {
            Log.e(TAG, "No Music URL specified");
            return;
        }
        musicUrl = extras.getString("MusicURL");
        Log.d(TAG, "Music URL = " + musicUrl);

        if (extras.containsKey("NotPlay")) {
            Log.v(TAG, "NotPlay is specified for baseline case of only BT on");
            mNotPlay = true;
        }

        if (extras.containsKey("Mute")) {
            Log.v(TAG, "Mute is specified for BT off baseline case");
            mMute = true;
        }

        if (playTime == 0 || startTime == 0 || musicUrl.isEmpty() || musicUrl == null
                || codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID
                || sampleRate == BluetoothCodecConfig.SAMPLE_RATE_NONE
                || bitsPerSample == BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
            Log.d(TAG, "Invalid paramters");
            return;
        }

        if (prepare(codecType, sampleRate, bitsPerSample, channelMode, codecSpecific1,
                    codecSpecific2, codecSpecific3, codecSpecific4,
                    playTime, idleTime, repetitions, musicUrl)) {
            startAlarm(startTime, null);
        }
    }

    /**
     * Method to start or stop playing music when Alarm is triggered
     *
     * @param intent - system will provide an intent to this function
     */
    private void handleAlarm(Intent intent) {

        int action = intent.getIntExtra(A2DP_ACTION, 0);
        Log.d(TAG, "handleAlarm() Action: " + action);
        if (action == -1) {
            Log.e(TAG, "Received Alarm with no Action");
            return;
        }
        if (mPlayer == null) {
            Log.e(TAG, "Media Player is null");
            return;
        }

        if (mNotPlay) {
            notPlayCase(intent, action);
            return;
        }

        if (action == START_PLAY) {
            Log.v(TAG, "Before Start Play");
            mPlayer.start();
            mPlayer.setLooping(true);
            startAlarm(mPlayTime, intent);
        } else if (action == PAUSE_PLAY) {
            Log.v(TAG, "Before Pause play");
            mPlayer.pause();
            startAlarm(mIdleTime, intent);
        } else if (action == STOP_PLAY) {
            Log.v(TAG, "Before Stop play");
            mPlayer.stop();
            // Release the Media Player
            mPlayer.release();

        } else {
            Log.e(TAG, "Unknown Action");
        }
    }

    /**
     * Method for baseline case "Not Play" when Alarm is triggered
     *
     * @param intent - system will provide an intent to this function
     */
    private void notPlayCase(Intent intent, int action) {

        if (action == START_PLAY) {
            Log.v(TAG, "NotPlay case for PlAY action");
            startAlarm(mPlayTime, intent);
        } else if (action == PAUSE_PLAY) {
            Log.v(TAG, "NotPlay case for PAUSE");
            startAlarm(mIdleTime, intent);
        } else if (action == STOP_PLAY) {
            Log.v(TAG, "NotPlay case for Stop play");
            // Release the Media Player
            mPlayer.release();
        } else {
            Log.e(TAG, "Unknown Action");
        }
    }

    /**
     * Method to verify if the codec config values are changed
     *
     * @param codecType - Codec Type
     * @param sampleRate - Sample Rate
     * @param bitsPerSample - Bit Per Sample
     * @param codecSpecific1 - LDAC playback quality
     */
    private boolean verifyCodeConfig(int codecType, int sampleRate, int bitsPerSample,
                                     int channelMode, long codecSpecific1) {
        BluetoothCodecConfig codecConfig = null;
        codecConfig = getCodecValue(false);
        if (codecConfig == null) return false;

        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            if (codecConfig.getCodecType() == codecType
                    && codecConfig.getSampleRate() == sampleRate
                    && codecConfig.getBitsPerSample() == bitsPerSample
                    && codecConfig.getChannelMode() == channelMode
                    && codecConfig.getCodecSpecific1() == codecSpecific1) return true;
        } else {
            if (codecConfig.getCodecType() == codecType
                    && codecConfig.getSampleRate() == sampleRate
                    && codecConfig.getBitsPerSample() == bitsPerSample
                    && codecConfig.getChannelMode() == channelMode) return true;
        }

        return false;
    }

    /**
     * Function to setup MediaPlayer
     *
     * @param codecType - Codec Type
     * @param sampleRate - Sample Rate
     * @param bitsPerSample - Bit Per Sample
     * @param codecSpecific1 - LDAC playback quality
     * @param codecSpecific2 - codecSpecific2
     * @param codecSpecific3 - codecSpecific3
     * @param codecSpecific4 - codecSpecific4
     * @param playTime - Music playing duration
     * @param idleTime - Pause time for music
     * @param repetitions - number of cycles of play/pause
     * @param musicURL - Music URL
     *
     */
    private boolean prepare(int codecType, int sampleRate, int bitsPerSample, int channelMode,
                             long codecSpecific1, long codecSpecific2, long codecSpecific3,
                             long codecSpecific4,
                             int playTime, int idleTime, int repetitions, String musicURL) {
        ArrayList<BluetoothDevice> results = new ArrayList<BluetoothDevice>();
        // When mMute is specific Bluetooth will be disabled by Python script
        if (!mMute) {
            for (BluetoothDevice bd : mBluetoothAdapter.getBondedDevices()) {
                if (bd.isConnected()) {
                    results.add(bd);
                }
            }

            if (results.isEmpty()) {
                Log.e(TAG, "No device is connected");
                return false;
            }

            // Set the requested Codecs on the device
            setCodecValue(codecType, sampleRate, bitsPerSample, channelMode, codecSpecific1,
                    codecSpecific2, codecSpecific3, codecSpecific4);
            // Wait here to see if the codec is changed to new value
            for (int i = 0; i < WAIT_SECONDS; i++) {
                if (verifyCodeConfig(codecType, sampleRate,
                        bitsPerSample, channelMode, codecSpecific1))  {
                    break;
                }
                try {
                    Thread.sleep(THOUSAND);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Sleep is interrupted");
                }
            }
            if (!verifyCodeConfig(codecType, sampleRate,
                    bitsPerSample, channelMode, codecSpecific1)) {
                Log.e(TAG, "Codec config is NOT set correctly");
                return false;
            }
        }
        mPlayTime = playTime;
        mIdleTime = idleTime;
        mNumAlarms = repetitions * 2;
        mPlayer = MediaPlayer.create(mContext, Uri.parse(musicURL));
        if (mPlayer == null) {
            Log.e(TAG, "Failed to create Media Player");
            return false;
        }
        Log.d(TAG, "Media Player created: " + musicURL);

        if (mMute) {
            Log.v(TAG, "Mute Speakers for BT off baseline case");
            mPlayer.setVolume(ZERO_VOLUME, ZERO_VOLUME);
        } else {
            Log.d(TAG, "Set Normal Volume for speakers");
            mPlayer.setVolume(NORMAL_VOLUME, NORMAL_VOLUME);
        }

        return true;
    }

    /**
     * Function to be called to start alarm
     *
     * @param alarmStartTime - time when the music needs to be started or stopped
     * @param intent - intent to content alarm number and current action
     */
    public void startAlarm(int alarmStartTime, Intent intent) {
        int currentAlarm = 0;
        int nextAction = START_PLAY;
        int currentAction = 0;

        if (intent != null) {
            if (currentAlarm >= mNumAlarms) {
                Log.d(TAG, "All alarms are done");
                return;
            }
            // Get alarm number inside the intent
            currentAlarm = intent.getIntExtra(CURRENT_ALARM, 0);
            currentAction =  intent.getIntExtra(A2DP_ACTION, 0);
            if (currentAlarm == mNumAlarms - 1) {
                // last alarm
                nextAction = STOP_PLAY;
            } else if (currentAction == START_PLAY) {
                nextAction = PAUSE_PLAY;
            } else {
                nextAction = START_PLAY;
            }
        }

        Log.d(TAG, "Current Alarm: " + currentAlarm + " Action: " + currentAction
                + " trigger time: " + alarmStartTime);

        Intent alarmIntent = new Intent(A2DP_INTENT);
        alarmIntent.putExtra(A2DP_ALARM, ALARM_MESSAGE);
        alarmIntent.putExtra(A2DP_ACTION, nextAction);
        alarmIntent.putExtra(CURRENT_ALARM, ++currentAlarm);

        long triggerTime = SystemClock.elapsedRealtime()
                               + alarmStartTime * THOUSAND;
        mAlarmManager.setExactAndAllowWhileIdle(
                          AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime,
                          PendingIntent.getBroadcast(mContext, 0,
                                        alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /**
     * Function to get current codec config
     * @param printCapabilities - Flag to indicate if to print local and selectable capabilities
     */
    private BluetoothCodecConfig getCodecValue(boolean printCapabilities) {
        BluetoothCodecStatus codecStatus = null;
        BluetoothCodecConfig codecConfig = null;
        BluetoothCodecConfig[] codecsLocalCapabilities = null;
        BluetoothCodecConfig[] codecsSelectableCapabilities = null;
        // In the mute case BT is off there will be not Codec Info available
        if (mMute) return null;

        if (mBluetoothA2dp != null) {
            codecStatus = mBluetoothA2dp.getCodecStatus();
            if (codecStatus != null) {
                codecConfig = codecStatus.getCodecConfig();
                codecsLocalCapabilities = codecStatus.getCodecsLocalCapabilities();
                codecsSelectableCapabilities = codecStatus.getCodecsSelectableCapabilities();
            }
        }
        if (codecConfig == null) return null;

        Log.d(TAG, "GetCodecValue: " + codecConfig.toString());

        if (printCapabilities) {
            Log.d(TAG, "Local Codec Capabilities ");
            for (BluetoothCodecConfig config : codecsLocalCapabilities) {
                Log.d(TAG, config.toString());
            }
            Log.d(TAG, "Codec Selectable Capabilities: ");
            for (BluetoothCodecConfig config : codecsSelectableCapabilities) {
                Log.d(TAG, config.toString());
            }
        }
        return codecConfig;
    }

    /**
     * Function to set new codec config
     *
     * @param codecType - Codec Type
     * @param sampleRate - Sample Rate
     * @param bitsPerSample - Bit Per Sample
     * @param codecSpecific1 - LDAC playback quality
     * @param codecSpecific2 - codecSpecific2
     * @param codecSpecific3 - codecSpecific3
     * @param codecSpecific4 - codecSpecific4
     */
    private void setCodecValue(int codecType, int sampleRate, int bitsPerSample,
                int channelMode, long codecSpecific1, long codecSpecific2,
                long codecSpecific3, long codecSpecific4) {
        Log.d(TAG, "SetCodecValue: Codec Type: " + codecType + " sampleRate: " + sampleRate
                + " bitsPerSample: " + bitsPerSample + " Channel Mode: " + channelMode
                + " LDAC quality: " + codecSpecific1);

        // In the mute case BT is off there will be not Codec Info available
        if (mMute) return;

        BluetoothCodecConfig codecConfig =
                new BluetoothCodecConfig(codecType, BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
                sampleRate, bitsPerSample, channelMode,
                codecSpecific1, codecSpecific2, codecSpecific3, codecSpecific4);

        if (mBluetoothA2dp != null) {
            Log.d(TAG, "setCodecConfigPreference()");
            mBluetoothA2dp.setCodecConfigPreference(codecConfig);
        } else {
            Log.e(TAG, "mBluetoothA2dp is null. Codec is not set");
        }
    }
}
