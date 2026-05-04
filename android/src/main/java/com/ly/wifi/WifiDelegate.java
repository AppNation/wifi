package com.ly.wifi;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class
WifiDelegate implements PluginRegistry.RequestPermissionsResultListener {
    private Activity activity;
    private WifiManager wifiManager;
    private PermissionManager permissionManager;
    private static final int REQUEST_ACCESS_FINE_LOCATION_PERMISSION = 1;
    private static final int REQUEST_CHANGE_WIFI_STATE_PERMISSION = 2;
    private static final int REQUEST_SSID_PERMISSION = 3;
    NetworkChangeReceiver networkReceiver;

    interface PermissionManager {
        boolean isPermissionGranted(String permissionName);

        void askForPermission(String permissionName, int requestCode);
    }

    public WifiDelegate(final Activity activity, final WifiManager wifiManager) {
        this(activity, wifiManager, null, null, new PermissionManager() {

            @Override
            public boolean isPermissionGranted(String permissionName) {
                return ActivityCompat.checkSelfPermission(activity, permissionName) == PackageManager.PERMISSION_GRANTED;
            }

            @Override
            public void askForPermission(String permissionName, int requestCode) {
                ActivityCompat.requestPermissions(activity, new String[]{permissionName}, requestCode);
            }
        });
    }

    private MethodChannel.Result result;
    private MethodCall methodCall;

    WifiDelegate(
            Activity activity,
            WifiManager wifiManager,
            MethodChannel.Result result,
            MethodCall methodCall,
            PermissionManager permissionManager) {
        this.networkReceiver = new NetworkChangeReceiver();
        this.activity = activity;
        this.wifiManager = wifiManager;
        this.result = result;
        this.methodCall = methodCall;
        this.permissionManager = permissionManager;
    }

    public void getSSID(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        
        // No permission checks - Flutter handles all permission logic
        // Native code just calls Android API and returns result
        launchSSID();
    }

    public void getLevel(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        launchLevel();
    }

    private void launchSSID() {
        android.util.Log.d("WifiDelegate", "launchSSID() started - Android API: " + Build.VERSION.SDK_INT);
        
        // Use modern ConnectivityManager API for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSSIDModern();
        } else {
            // Fallback to legacy WifiManager for Android 9 and below
            getSSIDLegacy();
        }
    }

    private void getSSIDModern() {
    Context context = activity.getApplicationContext();

    // 1. Permission check
    if (!hasLocationPermission()) {
        finishWithError("permission_denied", "Location permission not granted");
        return;
    }

    // 2. Location service check
    if (!isLocationEnabled(context)) {
        finishWithError("location_disabled", "Location services are disabled");
        return;
    }

    // 3. Try to get SSID with retry (timing fix)
    getSSIDWithRetry(3, 300);
}

private void getSSIDWithRetry(int retryCount, long delayMs) {
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {

        String ssid = getSSIDInternal();

        if (ssid == null || ssid.equals("<unknown ssid>")) {
            if (retryCount > 0) {
                android.util.Log.w("WifiDelegate", "SSID unknown, retrying... attempts left: " + retryCount);
                getSSIDWithRetry(retryCount - 1, delayMs);
            } else {
                finishWithError("unavailable", "Unable to fetch SSID after retries");
            }
        } else {
            result.success(ssid);
            clearMethodCallAndResult();
        }

    }, delayMs);
}

private String getSSIDInternal() {
    Context context = activity.getApplicationContext();

    // --- 1. Modern API ---
    try {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();

            if (network != null) {
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(network);

                if (capabilities != null &&
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {

                    Object transportInfo = capabilities.getTransportInfo();

                    if (transportInfo instanceof WifiInfo) {
                        WifiInfo wifiInfo = (WifiInfo) transportInfo;

                        String ssid = sanitizeSSID(wifiInfo.getSSID());

                        android.util.Log.d("WifiDelegate", "Modern API SSID: " + ssid);

                        if (isValidSSID(ssid)) {
                            return ssid;
                        }
                    } else {
                        android.util.Log.w("WifiDelegate", "TransportInfo is not WifiInfo");
                    }
                }
            }
        }
    } catch (Exception e) {
        android.util.Log.e("WifiDelegate", "Modern API failed", e);
    }

    // --- 2. Fallback: WifiManager ---
    try {
        WifiManager wifiManager =
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();

            if (wifiInfo != null) {
                String ssid = sanitizeSSID(wifiInfo.getSSID());

                android.util.Log.d("WifiDelegate", "Fallback SSID: " + ssid);

                if (isValidSSID(ssid)) {
                    return ssid;
                }
            }
        }
    } catch (Exception e) {
        android.util.Log.e("WifiDelegate", "WifiManager fallback failed", e);
    }

    return null;
}

private String sanitizeSSID(String ssid) {
    if (ssid == null) return null;

    ssid = ssid.replace("\"", "");

    if (ssid.equalsIgnoreCase("unknown ssid") ||
            ssid.equalsIgnoreCase("<unknown ssid>") ||
            ssid.equalsIgnoreCase(WifiManager.UNKNOWN_SSID)) {
        return null;
    }

    return ssid;
}

private boolean isValidSSID(String ssid) {
    return ssid != null && !ssid.trim().isEmpty();
}

private boolean hasLocationPermission() {

    if (activity == null) return false;

    int coarse = ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_COARSE_LOCATION
    );

    int fine = ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
    );

    android.util.Log.d("WifiDelegate", "COARSE: " + coarse);
    android.util.Log.d("WifiDelegate", "FINE: " + fine);

    boolean coarseGranted = coarse == PackageManager.PERMISSION_GRANTED;
    boolean fineGranted = fine == PackageManager.PERMISSION_GRANTED;

    // 🔥 önemli değişiklik: COARSE yeterli kabul ediyoruz
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        return coarseGranted || fineGranted;
    }

    return coarseGranted;
}

private boolean isLocationEnabled(Context context) {
    LocationManager locationManager =
            (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

    if (locationManager == null) return false;

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        return locationManager.isLocationEnabled();
    } else {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
}
    
    /**
     * Legacy method to get SSID using WifiManager
     * Works on Android 9 and below (API 28 and below)
     */
    private void getSSIDLegacy() {
        android.util.Log.d("WifiDelegate", "Using legacy WifiManager API");
        
        if (wifiManager == null) {
            finishWithError("unavailable", "WifiManager is not available");
            return;
        }
        
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            finishWithError("unavailable", "Wi-Fi connection info not available");
            return;
        }
        
        String wifiName = wifiInfo.getSSID();
        
        // Detailed logging
        android.util.Log.d("WifiDelegate", "Legacy API - Raw SSID: '" + wifiName + "'");
        android.util.Log.d("WifiDelegate", "Legacy API - Network ID: " + wifiInfo.getNetworkId());
        android.util.Log.d("WifiDelegate", "Legacy API - IP Address: " + wifiInfo.getIpAddress());
        android.util.Log.d("WifiDelegate", "Legacy API - BSSID: " + wifiInfo.getBSSID());
        android.util.Log.d("WifiDelegate", "Legacy API - Link Speed: " + wifiInfo.getLinkSpeed());
        
        // Check for unknown SSID
        if (wifiName == null || wifiName.isEmpty() || 
            wifiName.equals(WifiManager.UNKNOWN_SSID) || 
            wifiName.equals("<unknown ssid>")) {
            
            String detailedError = "Wi-Fi SSID not available via legacy API. ";
            if (wifiName == null) {
                detailedError += "SSID is null. ";
            } else if (wifiName.isEmpty()) {
                detailedError += "SSID is empty. ";
            } else if (wifiName.equals(WifiManager.UNKNOWN_SSID)) {
                detailedError += "SSID is UNKNOWN (permission issue). ";
            }
            
            android.util.Log.e("WifiDelegate", detailedError);
            finishWithError("unavailable", detailedError);
            return;
        }
        
        // Remove quotes from SSID
        wifiName = wifiName.replace("\"", "");
        android.util.Log.d("WifiDelegate", "Legacy API - Final SSID: '" + wifiName + "'");
        result.success(wifiName);
        clearMethodCallAndResult();
    }

    private void launchLevel() {
        android.util.Log.d("WifiDelegate", "launchLevel() started - Android API: " + Build.VERSION.SDK_INT);
        
        int rssi = 0;
        
        // Use modern ConnectivityManager API for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManager connectivityManager = 
                (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            
            if (connectivityManager != null) {
                Network network = connectivityManager.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        WifiInfo wifiInfo = (WifiInfo) capabilities.getTransportInfo();
                        if (wifiInfo != null) {
                            rssi = wifiInfo.getRssi();
                            android.util.Log.d("WifiDelegate", "Modern API - RSSI: " + rssi);
                        }
                    }
                }
            }
        } else {
            // Fallback to legacy WifiManager for Android 9 and below
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    rssi = wifiInfo.getRssi();
                    android.util.Log.d("WifiDelegate", "Legacy API - RSSI: " + rssi);
                }
            }
        }
        
        if (rssi != 0) {
            int level;
            if (rssi <= 0 && rssi >= -55) {
                level = 3;
            } else if (rssi < -55 && rssi >= -80) {
                level = 2;
            } else if (rssi < -80 && rssi >= -100) {
                level = 1;
            } else {
                level = 0;
            }
            android.util.Log.d("WifiDelegate", "Wi-Fi level: " + level + " (RSSI: " + rssi + ")");
            result.success(level);
            clearMethodCallAndResult();
        } else {
            finishWithError("unavailable", "wifi level not available.");
        }
    }

    public void getIP(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        launchIP();
    }

    private void launchIP() {
        android.util.Log.d("WifiDelegate", "launchIP() started - Android API: " + Build.VERSION.SDK_INT);
        
        ConnectivityManager connectivityManager = 
            (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) {
            finishWithError("unavailable", "ConnectivityManager not available");
            return;
        }
        
        // Use modern API for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                finishWithError("unavailable", "No active network");
                return;
            }
            
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null) {
                finishWithError("unavailable", "Network capabilities not available");
                return;
            }
            
            // Check if connected to WiFi
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiInfo wifiInfo = (WifiInfo) capabilities.getTransportInfo();
                if (wifiInfo != null) {
                    String ipAddress = intIP2StringIP(wifiInfo.getIpAddress());
                    android.util.Log.d("WifiDelegate", "Modern API - WiFi IP: " + ipAddress);
                    result.success(ipAddress);
                    clearMethodCallAndResult();
                    return;
                }
            }
            // Check if connected to Mobile
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                android.util.Log.d("WifiDelegate", "Modern API - Mobile network detected");
                getIPFromNetworkInterface();
                return;
            }
            
            finishWithError("unavailable", "Not connected to WiFi or Mobile network");
        } else {
            // Fallback to legacy API for Android 9 and below
            NetworkInfo info = connectivityManager.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                    android.util.Log.d("WifiDelegate", "Legacy API - Mobile network detected");
                    getIPFromNetworkInterface();
                } else if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                    if (wifiManager != null) {
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        if (wifiInfo != null) {
                            String ipAddress = intIP2StringIP(wifiInfo.getIpAddress());
                            android.util.Log.d("WifiDelegate", "Legacy API - WiFi IP: " + ipAddress);
                            result.success(ipAddress);
                            clearMethodCallAndResult();
                            return;
                        }
                    }
                }
            }
            finishWithError("unavailable", "ip not available.");
        }
    }
    
    /**
     * Helper method to get IP address from NetworkInterface (for mobile networks)
     */
    private void getIPFromNetworkInterface() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ipAddress = inetAddress.getHostAddress();
                        android.util.Log.d("WifiDelegate", "Mobile IP from NetworkInterface: " + ipAddress);
                        result.success(ipAddress);
                        clearMethodCallAndResult();
                        return;
                    }
                }
            }
            finishWithError("unavailable", "Could not find IP address from network interfaces");
        } catch (SocketException e) {
            android.util.Log.e("WifiDelegate", "SocketException while getting IP: " + e.getMessage());
            finishWithError("unavailable", "Error getting IP address: " + e.getMessage());
        }
    }

    private static String intIP2StringIP(int ip) {
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                (ip >> 24 & 0xFF);
    }

    public void getWifiList(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        
        // No permission checks - Flutter handles all permission logic
        launchWifiList();
    }

    private void launchWifiList() {
        String key = methodCall.argument("key");
        List<HashMap> list = new ArrayList<>();
        if (wifiManager != null) {
            List<ScanResult> scanResultList = wifiManager.getScanResults();
            for (ScanResult scanResult : scanResultList) {
                int level;
                if (scanResult.level <= 0 && scanResult.level >= -55) {
                    level = 3;
                } else if (scanResult.level < -55 && scanResult.level >= -80) {
                    level = 2;
                } else if (scanResult.level < -80 && scanResult.level >= -100) {
                    level = 1;
                } else {
                    level = 0;
                }
                HashMap<String, Object> maps = new HashMap<>();
                if (key.isEmpty()) {
                    maps.put("ssid", scanResult.SSID);
                    maps.put("level", level);
                    list.add(maps);
                } else {
                    if (scanResult.SSID.contains(key)) {
                        maps.put("ssid", scanResult.SSID);
                        maps.put("level", level);
                        list.add(maps);
                    }
                }
            }
        }
        result.success(list);
        clearMethodCallAndResult();
    }

    public void connection(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        
        // No permission checks - manifest handles CHANGE_WIFI_STATE (normal permission)
        connection();
    }

    private void connection() {
        String ssid = methodCall.argument("ssid");
        String password = methodCall.argument("password");
        WifiConfiguration wifiConfig = createWifiConfig(ssid, password);
        if (wifiConfig == null) {
            finishWithError("unavailable", "wifi config is null!");
            return;
        }
        int netId = wifiManager.addNetwork(wifiConfig);
        if (netId == -1) {
            result.success(0);
            clearMethodCallAndResult();
        } else {
            // support Android O
            // https://stackoverflow.com/questions/50462987/android-o-wifimanager-enablenetwork-cannot-work
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                wifiManager.enableNetwork(netId, true);
                wifiManager.reconnect();
                result.success(1);
                clearMethodCallAndResult();
            } else {
                networkReceiver.connect(netId);
            }
        }
    }

    private WifiConfiguration createWifiConfig(String ssid, String Password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        config.allowedAuthAlgorithms.clear();
        config.allowedGroupCiphers.clear();
        config.allowedKeyManagement.clear();
        config.allowedPairwiseCiphers.clear();
        config.allowedProtocols.clear();
        WifiConfiguration tempConfig = isExist(wifiManager, ssid);
        if (tempConfig != null) {
            wifiManager.removeNetwork(tempConfig.networkId);
        }
        config.preSharedKey = "\"" + Password + "\"";
        config.hiddenSSID = true;
        config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        config.status = WifiConfiguration.Status.ENABLED;
        return config;
    }

    private WifiConfiguration isExist(WifiManager wifiManager, String ssid) {
        List<WifiConfiguration> existingConfigs = wifiManager.getConfiguredNetworks();
        if(existingConfigs != null) {
            for (WifiConfiguration existingConfig : existingConfigs) {
                if (existingConfig.SSID.equals("\"" + ssid + "\"")) {
                    return existingConfig;
                }
            }
        }
        return null;
    }
    
    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            return locationManager != null && locationManager.isLocationEnabled();
        } else {
            int locationMode = 0;
            try {
                locationMode = Settings.Secure.getInt(activity.getContentResolver(), Settings.Secure.LOCATION_MODE);
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }
            return locationMode != Settings.Secure.LOCATION_MODE_OFF;
        }
    }

    private boolean setPendingMethodCallAndResult(MethodCall methodCall, MethodChannel.Result result) {
        if (this.result != null) {
            return false;
        }
        this.methodCall = methodCall;
        this.result = result;
        return true;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // NOTE: This callback is no longer used since permission requests are handled in Flutter
        // using permission_handler package. Native code only checks permissions, doesn't request them.
        // Keeping this method for backward compatibility, but it should not be triggered.
        
        android.util.Log.w("WifiDelegate", "onRequestPermissionsResult called - this should be handled in Flutter layer");
        return false;
    }

    private void finishWithAlreadyActiveError() {
        finishWithError("already_active", "wifi is already active");
    }

    private void finishWithError(String errorCode, String errorMessage) {
        result.error(errorCode, errorMessage, null);
        clearMethodCallAndResult();
    }

    private void clearMethodCallAndResult() {
        methodCall = null;
        result = null;
    }

    // support Android O
    // https://stackoverflow.com/questions/50462987/android-o-wifimanager-enablenetwork-cannot-work
    public class NetworkChangeReceiver extends BroadcastReceiver {
        private int netId;
        private boolean willLink = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            NetworkInfo info = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (info.getState() == NetworkInfo.State.DISCONNECTED && willLink) {
                wifiManager.enableNetwork(netId, true);
                wifiManager.reconnect();
                result.success(1);
                willLink = false;
                clearMethodCallAndResult();
            }
        }

        public void connect(int netId) {
            this.netId = netId;
            willLink = true;
            wifiManager.disconnect();
        }
    }
}
