/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.android_scripting.facade.wifi;

import android.app.Service;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.BssidInfo;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanSettings;
import android.os.Bundle;
import android.os.SystemClock;

import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.MainThread;
import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcParameter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * WifiScanner functions.
 *
 */
public class WifiScannerFacade extends RpcReceiver {
  private final Service mService;
  private final EventFacade mEventFacade;
  private final WifiScanner mScan;
  //These counters are just for indexing;
  //they do not represent the total number of listeners
  private static int WifiScanListenerCnt;
  private static int WifiChangeListenerCnt;
  private static int WifiBssidListenerCnt;
  private final ConcurrentHashMap<Integer, WifiScanListener> wifiScannerListenerList;
  private final ConcurrentHashMap<Integer, ChangeListener> wifiChangeListenerList;
  private final ConcurrentHashMap<Integer, WifiBssidListener> wifiBssidListenerList;
  private static ConcurrentHashMap<Integer, ScanResult[]> wifiScannerResultList;

  public WifiScannerFacade(FacadeManager manager) {
    super(manager);
    mService = manager.getService();
    mScan = (WifiScanner) mService.getSystemService(Context.WIFI_SCANNING_SERVICE);
    mEventFacade = manager.getReceiver(EventFacade.class);
    wifiScannerListenerList = new ConcurrentHashMap<Integer, WifiScanListener>();
    wifiChangeListenerList = new ConcurrentHashMap<Integer, ChangeListener>();
    wifiBssidListenerList = new ConcurrentHashMap<Integer, WifiBssidListener>();
    wifiScannerResultList = new ConcurrentHashMap<Integer, ScanResult[]>();
  }

  public static List<ScanResult> getWifiScanResult(Integer listener_index,
                                                   List<ScanResult> scanResults){
    synchronized (wifiScannerResultList) {
      ScanResult[] scanArray = wifiScannerResultList.get(listener_index);
      if (scanArray != null){
        for(ScanResult scanresult :  scanArray)
          scanResults.add(scanresult);
      }
      return scanResults;
    }
  }

  private class WifiActionListener implements WifiScanner.ActionListener {
    private final Bundle mResults;
    public int mIndex;
    protected String mEventType;

    public WifiActionListener(String type, int idx, Bundle resultBundle) {
      this.mIndex = idx;
      this.mEventType = type;
      this.mResults = resultBundle;
    }

    @Override
    public void onSuccess() {
      Log.d("onSuccess " + mEventType + " " + mIndex);
      mResults.putString("Type", "onSuccess");
      mResults.putLong("Realtime", SystemClock.elapsedRealtime());
      mEventFacade.postEvent(mEventType + mIndex + "onSuccess", mResults.clone());
      mResults.clear();
    }

    @Override
    public void onFailure(int reason, String description) {
      Log.d("onFailure " + mEventType + " " + mIndex);
      mResults.putString("Type", "onFailure");
      mResults.putInt("Reason", reason);
      mResults.putString("Description", description);
      mEventFacade.postEvent(mEventType + mIndex + "onFailure", mResults.clone());
      mResults.clear();
    }

    public void reportResult(ScanResult[] results, String type) {
      Log.d("reportResult "+ mEventType + " "+ mIndex);
      mResults.putLong("Timestamp", System.currentTimeMillis()/1000);
      mResults.putString("Type", type);
      mResults.putParcelableArray("Results", results);
      mEventFacade.postEvent(mEventType + mIndex + type, mResults.clone());
      mResults.clear();
    }
  }

  /**
   * Constructs a wifiScanListener obj and returns it
   * @return WifiScanListener
   */
  private WifiScanListener genWifiScanListener() {
    WifiScanListener mWifiScannerListener = MainThread.run(mService,
      new Callable<WifiScanListener>() {
        @Override
        public WifiScanListener call() throws Exception {
          return new WifiScanListener();
        }
      });
    wifiScannerListenerList.put(mWifiScannerListener.mIndex, mWifiScannerListener);
    return mWifiScannerListener;
  }

  private class WifiScanListener implements WifiScanner.ScanListener {
    private static final String mEventType =  "WifiScannerScan";
    protected final Bundle mScanResults;
    private final WifiActionListener mWAL;
    public int mIndex;

    public WifiScanListener() {
      mScanResults = new Bundle();
      WifiScanListenerCnt += 1;
      mIndex = WifiScanListenerCnt;
      mWAL = new WifiActionListener(mEventType, mIndex, mScanResults);
    }

    @Override
    public void onSuccess() {
      mWAL.onSuccess();
    }

    @Override
    public void onFailure(int reason, String description) {
      wifiScannerListenerList.remove(mIndex);
      mWAL.onFailure(reason, description);
    }

    @Override
    public void onPeriodChanged(int periodInMs) {
      Log.d("onPeriodChanged " + mEventType + " " + mIndex);
      mScanResults.putString("Type", "onPeriodChanged");
      mScanResults.putInt("NewPeriod", periodInMs);
      mEventFacade.postEvent(mEventType + mIndex, mScanResults.clone());
      mScanResults.clear();
    }

    @Override
    public void onResults(ScanResult[] results) {
      wifiScannerResultList.put(mIndex, results);
      mWAL.reportResult(results, "onResults");
    }

    @Override
    public void onFullResult(ScanResult fullScanResult) {
      Log.d("onFullResult WifiScanListener " + mIndex);
      mWAL.reportResult(new ScanResult[]{fullScanResult}, "onFullResult");
    }
  }

  /**
   * Constructs a ChangeListener obj and returns it
   * @return ChangeListener
   */
  public ChangeListener genWifiChangeListener() {
    ChangeListener mWifiChangeListener = MainThread.run(mService,
                                                        new Callable<ChangeListener>() {
      @Override
      public ChangeListener call() throws Exception {
        return new ChangeListener();
      }
    });
    wifiChangeListenerList.put(mWifiChangeListener.mIndex, mWifiChangeListener);
    return mWifiChangeListener;
  }

  private class ChangeListener implements WifiScanner.WifiChangeListener {
    private static final String mEventType =  "WifiScannerChange";
    protected final Bundle mResults;
    private final WifiActionListener mWAL;
    public int mIndex;

    public ChangeListener() {
      mResults = new Bundle();
      WifiChangeListenerCnt += 1;
      mIndex = WifiChangeListenerCnt;
      mWAL = new WifiActionListener(mEventType, mIndex, mResults);
    }

    @Override
    public void onSuccess() {
      mWAL.onSuccess();
    }

    @Override
    public void onFailure(int reason, String description) {
      wifiChangeListenerList.remove(mIndex);
      mWAL.onFailure(reason, description);
    }
    /** indicates that changes were detected in wifi environment
     * @param results indicate the access points that exhibited change
     */
    @Override
    public void onChanging(ScanResult[] results) {           /* changes are found */
      mWAL.reportResult(results, "onChanging");
    }
    /** indicates that no wifi changes are being detected for a while
     * @param results indicate the access points that are bing monitored for change
     */
    @Override
    public void onQuiescence(ScanResult[] results) {         /* changes settled down */
      mWAL.reportResult(results, "onQuiescence");
    }
  }

  public WifiBssidListener genWifiBssidListener() {
    WifiBssidListener mWifiBssidListener = MainThread.run(mService,
                                                          new Callable<WifiBssidListener>() {
      @Override
      public WifiBssidListener call() throws Exception {
        return new WifiBssidListener();
      }
    });
    wifiBssidListenerList.put(mWifiBssidListener.mIndex, mWifiBssidListener);
    return mWifiBssidListener;
  }

  private class WifiBssidListener implements WifiScanner.BssidListener {
    private static final String mEventType =  "WifiScannerBssid";
    protected final Bundle mResults;
    private final WifiActionListener mWAL;
    public int mIndex;

    public WifiBssidListener() {
      mResults = new Bundle();
      WifiBssidListenerCnt += 1;
      mIndex = WifiBssidListenerCnt;
      mWAL = new WifiActionListener(mEventType, mIndex, mResults);
    }

    @Override
    public void onSuccess() {
      mWAL.onSuccess();
    }

    @Override
    public void onFailure(int reason, String description) {
      wifiBssidListenerList.remove(mIndex);
      mWAL.onFailure(reason, description);
    }

    @Override
    public void onFound(ScanResult[] results) {
      mWAL.reportResult(results, "onBssidFound");
    }
  }

  private ScanSettings parseScanSettings(String scanSettings) throws JSONException {
      JSONObject j = new JSONObject(scanSettings);
      ScanSettings result = new ScanSettings();
      if (j.has("band")) {
          result.band = j.optInt("band");
      }
      if (j.has("channels")) {
          JSONArray chs = j.getJSONArray("channels");
          ChannelSpec[] channels = new ChannelSpec[chs.length()];
          for (int i = 0; i < channels.length; i++) {
              channels[i] = new ChannelSpec(chs.getInt(i));
          }
          result.channels = channels;
      }
      /* periodInMs and reportEvents are required */
      result.periodInMs = j.getInt("periodInMs");
      result.reportEvents = j.getInt("reportEvents");
      if (j.has("numBssidsPerScan")) {
          result.numBssidsPerScan = j.getInt("numBssidsPerScan");
      }
      return result;
  }

  /** RPC Methods */

  /**
   * Starts periodic WifiScanner scan
   * @param periodInMs
   * @param channel_freqs frequencies of channels to scan
   * @return the id of the scan listener associated with this scan
   * @throws JSONException
   */
  @Rpc(description = "Starts a periodic WifiScanner scan")
  public Integer startWifiScannerScan(@RpcParameter(name = "scanSettings") String scanSettings)
          throws JSONException {
    ScanSettings ss = parseScanSettings(scanSettings);
    Log.d("startWifiScannerScan with " + ss.channels);
    WifiScanListener mListener = genWifiScanListener();
    mScan.startBackgroundScan(ss, mListener);
    return mListener.mIndex;
  }

  /**
   * Stops a WifiScanner scan
   * @param listener_mIndex the id of the scan listener whose scan to stop
   * @throws Exception
   */
  @Rpc(description = "Stops an ongoing periodic WifiScanner scan")
  public void stopWifiScannerScan(@RpcParameter(name = "listener") Integer listener_index)
          throws Exception {
    if(!wifiScannerListenerList.containsKey(listener_index)) {
      throw new Exception("Background scan session " + listener_index + " does not exist");
    }
    WifiScanListener mListener = wifiScannerListenerList.get(listener_index);
    Log.d("stopWifiScannerScan mListener "+ mListener.mIndex );
    mScan.stopBackgroundScan(mListener);
    wifiScannerResultList.remove(listener_index);
    wifiScannerListenerList.remove(listener_index);
  }

  @Rpc(description = "Returns a list of mIndexes of existing listeners")
  public Integer[] showWifiScanListeners() {
    Integer[] result = new Integer[wifiScannerListenerList.size()];
    int j = 0;
    for(int i : wifiScannerListenerList.keySet()) {
      result[j] = wifiScannerListenerList.get(i).mIndex;
      j += 1;
    }
    return result;
  }

  /**
   * Starts tracking wifi changes
   * @return the id of the change listener associated with this track
   * @throws Exception
   */
  @Rpc(description = "Starts tracking wifi changes")
  public Integer startTrackingChange(
      @RpcParameter(name = "bssidInfos") String[] bssidInfos,
      @RpcParameter(name = "rssiSS") Integer rssiSS,
      @RpcParameter(name = "lostApSS") Integer lostApSS,
      @RpcParameter(name = "unchangedSS") Integer unchangedSS,
      @RpcParameter(name = "minApsBreachingThreshold") Integer minApsBreachingThreshold,
      @RpcParameter(name = "periodInMs") Integer periodInMs ) throws Exception{
    Log.d("starting change track");
    BssidInfo[] mBssidInfos = new BssidInfo[bssidInfos.length];
    for(int i=0; i<bssidInfos.length; i++) {
      Log.d("android_scripting " + bssidInfos[i]);
      String[] tokens = bssidInfos[i].split(" ");
      if(tokens.length != 3) {
        throw new Exception("Invalid bssid info: "+bssidInfos[i]);

      }
      int rssiHI = Integer.parseInt(tokens[1]);
      BssidInfo mBI = new BssidInfo();
      mBI.bssid = tokens[0];
      mBI.low = rssiHI - unchangedSS;
      mBI.high = rssiHI + unchangedSS;
      mBI.frequencyHint = Integer.parseInt(tokens[2]);
      mBssidInfos[i] = mBI;
    }
    ChangeListener mListener = genWifiChangeListener();
    mScan.configureWifiChange(rssiSS, lostApSS, unchangedSS, minApsBreachingThreshold,
                              periodInMs, mBssidInfos);
    mScan.startTrackingWifiChange(mListener);
    return mListener.mIndex;
  }

  /**
   * Stops tracking wifi changes
   * @param listener_index the id of the change listener whose track to stop
   * @throws Exception
   */
  @Rpc(description = "Stops tracking wifi changes")
  public void stopTrackingChange(@RpcParameter(name = "listener") Integer listener_index)
          throws Exception {
    if(!wifiChangeListenerList.containsKey(listener_index)) {
      throw new Exception("Wifi change tracking session " + listener_index + " does not exist");
    }
    ChangeListener mListener = wifiChangeListenerList.get(listener_index);
    mScan.stopTrackingWifiChange(mListener);
    wifiChangeListenerList.remove(listener_index);
  }

  /**
   * Starts tracking changes of the wifi networks specified in a list of bssid
   * @param bssidInfos a list specifying which wifi networks to track
   * @param apLostThreshold signal strength below which an AP is considered lost
   * @return the id of the bssid listener associated with this track
   * @throws Exception
   */
  @Rpc(description = "Starts tracking changes in the APs specified by the list")
  public Integer startTrackingBssid(String[] bssidInfos, Integer apLostThreshold)
          throws Exception {
    //Instantiates BssidInfo objs
    BssidInfo[] mBssidInfos = new BssidInfo[bssidInfos.length];
    for(int i=0; i<bssidInfos.length; i++) {
      Log.d("android_scripting " + bssidInfos[i]);
      String[] tokens = bssidInfos[i].split(" ");
      if(tokens.length!=3) {
        throw new Exception("Invalid bssid info: "+bssidInfos[i]);

      }
      int a = Integer.parseInt(tokens[1]);
      int b = Integer.parseInt(tokens[2]);
      BssidInfo mBI = new BssidInfo();
      mBI.bssid = tokens[0];
      mBI.low = a<b ? a:b;
      mBI.high = a<b ? b:a;
      mBssidInfos[i] = mBI;
    }
    WifiBssidListener mWHL = genWifiBssidListener();
    mScan.startTrackingBssids(mBssidInfos, apLostThreshold, mWHL);
    return mWHL.mIndex;
  }

  /**
   * Stops tracking the list of APs associated with the input listener
   * @param listener_index the id of the bssid listener whose track to stop
   * @throws Exception
   */
  @Rpc(description = "Stops tracking changes in the APs on the list")
  public void stopTrackingBssids(@RpcParameter(name = "listener") Integer listener_index)
          throws Exception {
    if(!wifiBssidListenerList.containsKey(listener_index)) {
      throw new Exception("Bssid tracking session " + listener_index + " does not exist");
    }
    WifiBssidListener mListener = wifiBssidListenerList.get(listener_index);
    mScan.stopTrackingBssids(mListener);
    wifiBssidListenerList.remove(listener_index);
  }

  /**
   * Shuts down all activities associated with WifiScanner
   */
  @Rpc(description = "Shuts down all WifiScanner activities")
  public void wifiScannerShutdown() {
    this.shutdown();
  }

  /**
   * Stops all activity
   */
  @Override
  public void shutdown() {
    try {
      if(!wifiScannerListenerList.isEmpty()) {
        for(int i : wifiScannerListenerList.keySet()) {
          this.stopWifiScannerScan(i);
        }
      }
      if(!wifiChangeListenerList.isEmpty()) {
        for(int i : wifiChangeListenerList.keySet()) {
          this.stopTrackingChange(i);
        }
      }
      if(!wifiBssidListenerList.isEmpty()) {
        for(int i : wifiBssidListenerList.keySet()) {
          this.stopTrackingBssids(i);
        }
      }
    } catch (Exception e) {
      Log.e("Shutdown failed: " + e.toString());
    }
  }
}