/*
 * Copyright (C) 2025-2026 AxionOS
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.picked.spoof;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.pif.PlayIntegritySpoofService;
import android.security.trickystore.TrickyStoreService;
import android.util.Log;

import com.android.internal.util.picked.PixelDeviceRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Miku-Picked UI: system_server side of the Play Integrity and TrickyStore spoofs, after
 * AxionOS' AxSpoofManager (as shipped in Evolution X).
 *
 * Caches the spoof settings and hands them to the processes that spoof (through
 * IActivityManager), keeps the Pixel fingerprint current and stages the PIF prop overrides
 * that bionic serves to DroidGuard. Unlike AxSpoofManager it never downloads a keybox: the
 * user imports their own, and it is only returned to TrickyStore target packages.
 */
public final class PickedSpoofManager {
    private static final String TAG = "PickedSpoofManager";

    private static final String[] WATCHED_SECURE_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    /**
     * Settings.Secure: Settings bumps this to ask for an immediate fingerprint fetch, since it
     * can't call this manager across processes. The value is ignored, only the change matters.
     */
    public static final String REFRESH_REQUEST_KEY = "spoof_pif_refresh_request";

    /** Settings.System switches (on by default) for the two halves. */
    public static final String PIF_ENABLED_KEY = "spoof_pif_enabled";
    public static final String TRICKYSTORE_ENABLED_KEY = "spoof_trickystore_enabled";

    /**
     * Settings.Secure: when the fingerprint was last fetched. Settings' "Update fingerprint"
     * resets it to 0 and clears SPOOF_PIF_CONFIG, which makes a fetch run within seconds.
     */
    public static final String LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch";

    /**
     * Settings.Secure: the Pixel device profile the user picked (a codename from
     * PixelDeviceRepository.DEVICE_MODEL_MAP), or empty for the automatic default. The next
     * fingerprint fetch uses this device instead of the default.
     */
    public static final String PIF_PROFILE_KEY = "spoof_pif_profile";
    private static final long REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static final long BOOTSTRAP_INITIAL_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long BOOTSTRAP_RETRY_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int BOOTSTRAP_MAX_RETRIES = 6;
    private static final long REFRESH_STEP_DEADLINE_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long REFRESH_AFTER_CLEAR_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long AUTO_FETCH_COOLDOWN_MS = TimeUnit.DAYS.toMillis(1);
    private static final long REFETCH_WINDOW_DAYS = 15L;
    private static final Pattern PLAIN_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String[] GMS_FAMILY = {
            "com.android.vending",
            "com.google.android.gms.unstable",
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.google.android.rkpdapp",
            "com.google.android.gsf",
            "com.google.android.contactkeys",
            "com.google.android.safetycore",
            "com.google.android.googlequicksearchbox",
    };

    // Staging props for bionic's prop override (libc/bionic/picked_prop_spoof.cpp), which
    // serves them to DroidGuard (com.google.android.gms.unstable) in place of the matching
    // read-only props. ro.* can't change after boot, so these are the handoff point.
    private static final String PIF_PRODUCT_PROP_PREFIX = "persist.sys.pif.product.";
    private static final String[] PIF_PRODUCT_PROP_NAMES = {
            "manufacturer", "brand", "model", "device", "name",
    };
    private static final String PIF_BUILD_ID_PROP = "persist.sys.pif.build.id";
    private static final String PIF_SECURITY_PATCH_PROP = "persist.sys.pif.security_patch";
    private static final String PIF_API_LEVEL_PROP = "persist.sys.pif.api_level";
    private static final String PIF_BUILD_TYPE_PROP = "persist.sys.pif.build.type";
    private static final String PIF_BUILD_TAGS_PROP = "persist.sys.pif.build.tags";
    private static final String PIF_BUILD_DESCRIPTION_PROP = "persist.sys.pif.build.description";
    private static final String PIF_BUILD_FLAVOR_PROP = "persist.sys.pif.build.flavor";
    private static final String PIF_CUSTOM_COUNT_PROP = "persist.sys.pif.x.count";
    private static final String PIF_CUSTOM_SLOT_PREFIX = "persist.sys.pif.x.";
    private static final int PIF_CUSTOM_SLOT_MAX = 32;
    private static final int PROP_VALUE_MAX_CHARS = 91;
    private static final String PIF_DEVICE_INITIAL_SDK = "32";

    private static volatile PickedSpoofManager sInstance;

    private final ExecutorService mFingerprintExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Runnable mRefreshAfterClear = this::refreshNow;

    private Context mContext;
    private ContentResolver mResolver;
    private volatile boolean mPifEnabled = true;
    private volatile boolean mTrickyStoreEnabled = true;
    // Nothing runs before setup is complete, so Setup Wizard's GMS is never disturbed.
    private volatile boolean mActivated;
    private int mBootstrapRetries;

    public static PickedSpoofManager getInstance() {
        if (sInstance == null) {
            synchronized (PickedSpoofManager.class) {
                if (sInstance == null) sInstance = new PickedSpoofManager();
            }
        }
        return sInstance;
    }

    private PickedSpoofManager() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    /** Called from ActivityManagerService.systemReady(). */
    public void systemReady(Context context) {
        mHandler.post(() -> init(context));
    }

    private void init(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        for (String key : WATCHED_SECURE_KEYS) refreshKey(key);
        refreshSwitches();

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                final String last = uri != null ? uri.getLastPathSegment() : null;
                if (last == null) return;
                if (PIF_ENABLED_KEY.equals(last) || TRICKYSTORE_ENABLED_KEY.equals(last)) {
                    onSwitchesChanged();
                    return;
                }
                if (Settings.Secure.USER_SETUP_COMPLETE.equals(last)) {
                    if (!mActivated && isDeviceProvisioned()) activate();
                    return;
                }
                if (REFRESH_REQUEST_KEY.equals(last)) {
                    forceFingerprintRefresh();
                    return;
                }
                refreshKey(last);
                if (Settings.Secure.SPOOF_PIF_CONFIG.equals(last)) onPifConfigChanged();
                Log.i(TAG, "Spoof config refreshed: " + last);
            }
        };
        for (String key : WATCHED_SECURE_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL);
        }
        mResolver.registerContentObserver(Settings.System.getUriFor(PIF_ENABLED_KEY), false,
                observer, UserHandle.USER_ALL);
        mResolver.registerContentObserver(Settings.System.getUriFor(TRICKYSTORE_ENABLED_KEY),
                false, observer, UserHandle.USER_ALL);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(REFRESH_REQUEST_KEY), false,
                observer, UserHandle.USER_ALL);
        mResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), false, observer,
                UserHandle.USER_ALL);

        final BroadcastReceiver packageAdded = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // Updates broadcast PACKAGE_ADDED too; only new installs get targeted.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                final Uri data = intent.getData();
                final String pkg = data != null ? data.getSchemeSpecificPart() : null;
                if (pkg != null) mHandler.post(() -> onPackageAdded(pkg));
            }
        };
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(packageAdded, UserHandle.ALL, filter, null, mHandler);

        if (isDeviceProvisioned()) {
            mActivated = true;
            seedDefaultTargetsIfUnset();
            stagePifPropOverrides(pifConfig());
            scheduleFirstRefresh();
        } else {
            Log.i(TAG, "Spoofing off until setup is complete");
        }
        Log.i(TAG, "PickedSpoofManager ready");
    }

    private void activate() {
        mActivated = true;
        Log.i(TAG, "Setup complete, enabling spoofing");
        PlayIntegritySpoofService.getInstance().loadConfig();
        TrickyStoreService.getInstance().initialize();
        seedDefaultTargetsIfUnset();
        stagePifPropOverrides(pifConfig());
        scheduleFirstRefresh();
        killGmsFamily();
    }

    private void refreshSwitches() {
        mPifEnabled = Settings.System.getIntForUser(
                mResolver, PIF_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) != 0;
        mTrickyStoreEnabled = Settings.System.getIntForUser(
                mResolver, TRICKYSTORE_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) != 0;
    }

    private void onSwitchesChanged() {
        refreshSwitches();
        Log.i(TAG, "Spoof switches: pif=" + mPifEnabled + " trickystore=" + mTrickyStoreEnabled);
        if (!mActivated) return;
        stagePifPropOverrides(pifConfig());
        if (mPifEnabled) refreshSoon();
        killGmsFamily();
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(mResolver, key,
                UserHandle.USER_SYSTEM);
        if (value == null) mCache.remove(key);
        else mCache.put(key, value);
    }

    /** The PIF config the processes should use: null while off. */
    private String pifConfig() {
        return mPifEnabled ? mCache.get(Settings.Secure.SPOOF_PIF_CONFIG) : null;
    }

    private void onPifConfigChanged() {
        final String config = pifConfig();
        if (config != null && !config.trim().isEmpty()) {
            if (mActivated) stagePifPropOverrides(config);
            return;
        }
        clearPifPropOverrides();
        killGmsFamily();
        if (mPifEnabled) refreshSoon();
    }

    private void refreshSoon() {
        mHandler.removeCallbacks(mRefreshAfterClear);
        mHandler.postDelayed(mRefreshAfterClear, REFRESH_AFTER_CLEAR_DELAY_MS);
    }

    /**
     * Settings' "Fetch latest": drop the cooldown and any auto-fetched fingerprint (never a
     * config the user imported) so a fresh Pixel fingerprint is fetched right away.
     */
    private void forceFingerprintRefresh() {
        if (!mActivated || !mPifEnabled) return;
        Settings.Secure.putLongForUser(mResolver, LAST_AUTO_FETCH_KEY, 0L, UserHandle.USER_SYSTEM);
        final String config = mCache.get(Settings.Secure.SPOOF_PIF_CONFIG);
        boolean manual = false;
        if (config != null && config.trim().startsWith("{")) {
            try {
                manual = new JSONObject(config).optBoolean("manually_imported", false);
            } catch (Exception ignored) {
            }
        } else if (config != null && !config.trim().isEmpty()) {
            manual = true; // a key=value pif.prop the user imported
        }
        if (!manual) {
            Settings.Secure.putStringForUser(mResolver, Settings.Secure.SPOOF_PIF_CONFIG, "",
                    UserHandle.USER_SYSTEM);
            refreshKey(Settings.Secure.SPOOF_PIF_CONFIG);
        }
        refreshNow();
    }

    private void seedDefaultTargetsIfUnset() {
        if (Settings.Secure.getStringForUser(mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                UserHandle.USER_SYSTEM) != null) {
            return;
        }
        Settings.Secure.putStringForUser(mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                TrickyStoreService.DEFAULT_TARGET_LIST, UserHandle.USER_SYSTEM);
        Log.i(TAG, "Seeded default TrickyStore targets");
    }

    /**
     * Adds a newly installed app to the TrickyStore targets (auto mode), unless the list is
     * empty or JSON, the app is already listed, or it is an Xposed manager.
     */
    private void onPackageAdded(String pkg) {
        if (!mActivated || mResolver == null || TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) {
            return;
        }
        final String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, UserHandle.USER_SYSTEM);
        final String existing = current != null ? current : "";
        final String trimmed = existing.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("[") || trimmed.startsWith("{")) return;
        for (String raw : existing.split("\n")) {
            if (pkg.equals(barePackage(raw.trim()))) return;
        }
        final String updated = existing.endsWith("\n") ? existing + pkg : existing + "\n" + pkg;
        Settings.Secure.putStringForUser(mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                updated, UserHandle.USER_SYSTEM);
        Log.i(TAG, "Auto-targeted newly installed package: " + pkg);
    }

    private static String barePackage(String line) {
        return line.endsWith("!") || line.endsWith("?") || line.endsWith("-")
                ? line.substring(0, line.length() - 1).trim() : line;
    }

    private void scheduleFirstRefresh() {
        mHandler.postDelayed(this::performScheduledRefresh,
                isBootstrapPending() ? BOOTSTRAP_INITIAL_DELAY_MS : REFRESH_INTERVAL_MS);
    }

    private void performScheduledRefresh() {
        if (!mActivated) return;
        refreshNow();
        long delay = REFRESH_INTERVAL_MS;
        if (isBootstrapPending() && mBootstrapRetries < BOOTSTRAP_MAX_RETRIES) {
            mBootstrapRetries++;
            delay = BOOTSTRAP_RETRY_MS;
        }
        mHandler.postDelayed(this::performScheduledRefresh, delay);
    }

    private boolean isBootstrapPending() {
        if (mResolver == null || !mPifEnabled) return false;
        final String config = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_PIF_CONFIG, UserHandle.USER_SYSTEM);
        return config == null || config.trim().isEmpty();
    }

    private void refreshNow() {
        if (!mActivated || !mPifEnabled) return;
        try {
            refreshPixelFingerprintIfStale();
        } catch (Exception e) {
            Log.e(TAG, "Fingerprint refresh failed", e);
        }
    }

    private <T> T runBounded(ExecutorService pool, Callable<T> task) throws Exception {
        final Future<T> future = pool.submit(task);
        try {
            return future.get(REFRESH_STEP_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private void refreshPixelFingerprintIfStale() throws Exception {
        final long lastFetch = Settings.Secure.getLongForUser(
                mResolver, LAST_AUTO_FETCH_KEY, 0L, UserHandle.USER_SYSTEM);
        if (lastFetch > 0L && System.currentTimeMillis() - lastFetch < AUTO_FETCH_COOLDOWN_MS) {
            return;
        }

        final String content = mCache.get(Settings.Secure.SPOOF_PIF_CONFIG);
        final JSONObject existing = (content != null && content.trim().startsWith("{"))
                ? new JSONObject(content) : new JSONObject();
        // A config the user imported is theirs: never replace it.
        if (existing.optBoolean("manually_imported", false)
                || (content != null && !content.trim().isEmpty()
                        && !content.trim().startsWith("{"))) {
            return;
        }

        final String canaryMonth = existing.optString("_canary_month", "");
        final String releaseDate = existing.has("_canary_release_date")
                ? existing.optString("_canary_release_date") : null;
        final Long daysLeft = canaryMonth.isEmpty() ? null
                : PixelDeviceRepository.getDaysUntilExpiry(canaryMonth, releaseDate);
        if (daysLeft != null && daysLeft > REFETCH_WINDOW_DAYS) return;

        final String chosenProfile = Settings.Secure.getStringForUser(
                mResolver, PIF_PROFILE_KEY, UserHandle.USER_SYSTEM);
        final PixelDeviceRepository.PixelProfile matched = runBounded(mFingerprintExecutor, () -> {
            final List<PixelDeviceRepository.PixelProfile> profiles =
                    PixelDeviceRepository.getProfiles(mContext, true);
            // Miku-Picked UI: the user's chosen device profile, or the automatic default.
            final String codename = (chosenProfile != null
                    && PixelDeviceRepository.DEVICE_MODEL_MAP.containsKey(chosenProfile))
                    ? chosenProfile
                    : PixelDeviceRepository.getDefaultPifCodename(profiles);
            return PixelDeviceRepository.getProfileByCodename(mContext, codename, false);
        });
        if (matched == null || !PixelDeviceRepository.isValidFingerprint(matched.fingerprint)) {
            return;
        }
        if (matched.fingerprint.equals(existing.optString("FINGERPRINT", ""))) {
            Settings.Secure.putLongForUser(mResolver, LAST_AUTO_FETCH_KEY,
                    System.currentTimeMillis(), UserHandle.USER_SYSTEM);
            return;
        }

        // Keep flags set by hand (spoofVendingFinger, spoofProps, ...); drop what described
        // the old fingerprint so it can't disagree with the new one.
        final JSONObject toSave = new JSONObject(existing.toString());
        for (String stale : new String[] {"ID", "INCREMENTAL", "TYPE", "TAGS", "RELEASE",
                "_canary_month", "_canary_release_date"}) {
            toSave.remove(stale);
        }
        toSave.put("MANUFACTURER", capitalize(matched.brand));
        toSave.put("BRAND", matched.brand);
        toSave.put("MODEL", matched.model);
        toSave.put("PRODUCT", matched.product);
        toSave.put("DEVICE", matched.device);
        toSave.put("FINGERPRINT", matched.fingerprint);
        toSave.put("SECURITY_PATCH", matched.securityPatch);
        toSave.put("DEVICE_INITIAL_SDK_INT", PIF_DEVICE_INITIAL_SDK);
        toSave.put("manually_imported", false);
        if (matched.isCanary) {
            final String month = matched.securityPatch != null
                    && matched.securityPatch.length() >= 7
                    ? matched.securityPatch.substring(0, 7) : null;
            if (month != null) toSave.put("_canary_month", month);
            if (matched.releaseDate != null) toSave.put("_canary_release_date", matched.releaseDate);
        }

        Settings.Secure.putStringForUser(mResolver, Settings.Secure.SPOOF_PIF_CONFIG,
                toSave.toString(2), UserHandle.USER_SYSTEM);
        Settings.Secure.putLongForUser(mResolver, LAST_AUTO_FETCH_KEY,
                System.currentTimeMillis(), UserHandle.USER_SYSTEM);
        updatePatchIfSimple(matched.securityPatch);
        stagePifPropOverrides(toSave.toString(2));
        killGmsFamily();
        Log.i(TAG, "Auto-refreshed Pixel fingerprint: " + matched.model);
    }

    /** Keeps the TrickyStore patch level in step with the fingerprint, unless set by hand. */
    private void updatePatchIfSimple(String patch) {
        if (patch == null || !PLAIN_DATE.matcher(patch).matches()) return;
        final String existing = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, UserHandle.USER_SYSTEM);
        final String trimmed = existing != null ? existing.trim() : "";
        if (!trimmed.isEmpty() && !PLAIN_DATE.matcher(trimmed).matches()) return;
        if (patch.equals(trimmed)) return;
        Settings.Secure.putStringForUser(mResolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
                patch, UserHandle.USER_SYSTEM);
    }

    private void killGmsFamily() {
        if (!mActivated) return;
        try {
            final ActivityManager am = mContext.getSystemService(ActivityManager.class);
            for (String pkg : GMS_FAMILY) am.forceStopPackage(pkg);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restart GMS after a spoof change", e);
        }
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0
                && Settings.Secure.getIntForUser(mResolver, Settings.Secure.USER_SETUP_COMPLETE,
                        0, UserHandle.USER_SYSTEM) != 0;
    }

    // ---- PIF prop staging for bionic -------------------------------------------------------

    private void clearPifPropOverrides() {
        for (String name : PIF_PRODUCT_PROP_NAMES) setStagedProp(PIF_PRODUCT_PROP_PREFIX + name, null);
        setStagedProp(PIF_BUILD_ID_PROP, null);
        setStagedProp(PIF_SECURITY_PATCH_PROP, null);
        setStagedProp(PIF_API_LEVEL_PROP, null);
        setStagedProp(PIF_BUILD_TYPE_PROP, null);
        setStagedProp(PIF_BUILD_TAGS_PROP, null);
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP, null);
        stageCustomProps(null);
    }

    /**
     * Stages the PIF identity as persist props for bionic to serve to DroidGuard in place of
     * the real read-only values. A field the config doesn't carry is staged empty, which
     * bionic treats as unset; spoofProps=0 stages nothing, as in PlayIntegrityFork.
     */
    private void stagePifPropOverrides(String config) {
        final Map<String, String> fields = parsePifConfig(config);
        if (fields.isEmpty() || !isSpoofPropsEnabled(fields)) {
            clearPifPropOverrides();
            return;
        }
        final String fp = fields.get("FINGERPRINT");
        final String[] head = fingerprintHead(fp);
        final String[] tail = fingerprintTail(fp);
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "manufacturer", fields.get("MANUFACTURER"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "brand",
                firstNonEmpty(fields.get("BRAND"), head != null ? head[0] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "model", fields.get("MODEL"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "device",
                firstNonEmpty(fields.get("DEVICE"), head != null ? head[2] : null));
        final String product = firstNonEmpty(fields.get("PRODUCT"), head != null ? head[1] : null);
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "name", product);
        final String buildId = firstNonEmpty(fields.get("*.build.id"), fields.get("ID"),
                tail != null ? tail[1] : null);
        setStagedProp(PIF_BUILD_ID_PROP, buildId);
        final String type = firstNonEmpty(fields.get("*.build.type"), fields.get("TYPE"),
                tail != null ? tail[3] : null);
        final String tags = firstNonEmpty(fields.get("*.build.tags"), fields.get("TAGS"),
                tail != null ? tail[4] : null);
        setStagedProp(PIF_BUILD_TYPE_PROP, type);
        setStagedProp(PIF_BUILD_TAGS_PROP, tags);
        final String release = firstNonEmpty(fields.get("RELEASE"), tail != null ? tail[0] : null);
        final String incremental = firstNonEmpty(fields.get("INCREMENTAL"),
                tail != null ? tail[2] : null);
        final boolean haveDescription = product != null && type != null && release != null
                && buildId != null && incremental != null && tags != null;
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, haveDescription
                ? product + "-" + type + " " + release + " " + buildId + " " + incremental + " "
                        + tags
                : null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP,
                product != null && type != null ? product + "-" + type : null);
        setStagedProp(PIF_SECURITY_PATCH_PROP,
                firstNonEmpty(fields.get("*.security_patch"), fields.get("SECURITY_PATCH")));
        setStagedProp(PIF_API_LEVEL_PROP,
                firstNonEmpty(fields.get("*api_level"), fields.get("DEVICE_INITIAL_SDK_INT")));
        stageCustomProps(fields);
    }

    /** Other property entries of the config (keys with '.' or a leading '*'), sorted. */
    private static void stageCustomProps(Map<String, String> fields) {
        final TreeMap<String, String> entries = new TreeMap<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                if (key.indexOf('.') < 0 && key.indexOf('*') < 0) continue;
                if (key.equals("*") || key.startsWith("persist.sys.pif.")) continue;
                if (key.equals("*.build.id") || key.equals("*.security_patch")
                        || key.equals("*api_level") || key.equals("*.build.type")
                        || key.equals("*.build.tags")) {
                    continue;
                }
                if (value == null || value.isEmpty()) continue;
                final String entry = key + "=" + value;
                if (entry.length() > PROP_VALUE_MAX_CHARS) {
                    Log.w(TAG, "PIF property " + key + " is too long to stage, skipping");
                    continue;
                }
                entries.put(key, entry);
            }
        }
        final int previous = Math.min(SystemProperties.getInt(PIF_CUSTOM_COUNT_PROP, 0),
                PIF_CUSTOM_SLOT_MAX);
        int count = 0;
        for (String entry : entries.values()) {
            if (count >= PIF_CUSTOM_SLOT_MAX) break;
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + count, entry);
            count++;
        }
        // Slots before the count when growing, the count first when shrinking.
        setStagedProp(PIF_CUSTOM_COUNT_PROP, count > 0 ? Integer.toString(count) : null);
        for (int i = count; i < previous; i++) setStagedProp(PIF_CUSTOM_SLOT_PREFIX + i, null);
    }

    private static boolean isSpoofPropsEnabled(Map<String, String> fields) {
        final String value = fields.get("spoofProps");
        return value == null || "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static void setStagedProp(String name, String value) {
        final String target = value != null ? value : "";
        try {
            if (target.equals(SystemProperties.get(name, ""))) return;
            SystemProperties.set(name, target);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stage " + name, e);
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** {brand, product, device} of brand/product/device:release/id/incremental:type/tags. */
    private static String[] fingerprintHead(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] head = fingerprint.substring(0, colon).split("/");
        return head.length == 3 ? head : null;
    }

    /** {release, id, incremental, type, tags} of the same format. */
    private static String[] fingerprintTail(String fingerprint) {
        if (fingerprint == null) return null;
        final int first = fingerprint.indexOf(':');
        final int second = first < 0 ? -1 : fingerprint.indexOf(':', first + 1);
        if (second < 0) return null;
        final String[] mid = fingerprint.substring(first + 1, second).split("/");
        final String[] end = fingerprint.substring(second + 1).split("/");
        if (mid.length != 3 || end.length != 2) return null;
        return new String[] {mid[0], mid[1], mid[2], end[0], end[1]};
    }

    /** The flat key/value view of a PIF config, JSON or key=value lines. */
    private static Map<String, String> parsePifConfig(String config) {
        final Map<String, String> out = new HashMap<>();
        if (config == null || config.trim().isEmpty()) return out;
        try {
            if (config.trim().startsWith("{")) {
                final JSONObject json = new JSONObject(config);
                final Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!json.isNull(key)) out.put(key, json.get(key).toString());
                }
            } else {
                for (String line : config.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    final int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String value = line.substring(eq + 1);
                    final int comment = value.indexOf('#');
                    if (comment >= 0) value = value.substring(0, comment);
                    value = value.trim();
                    if (!value.isEmpty()) out.put(line.substring(0, eq).trim(), value);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read PIF config for staging", e);
        }
        return out;
    }

    private static String capitalize(String s) {
        return (s == null || s.isEmpty()) ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- What IActivityManager hands to app processes ---------------------------------------

    public String getPifConfig() {
        return mActivated ? pifConfig() : null;
    }

    public String getTrickyStoreTarget() {
        return mActivated && mTrickyStoreEnabled
                ? mCache.get(Settings.Secure.SPOOF_TRICKYSTORE_TARGET) : null;
    }

    public String getTrickyStorePatch() {
        return mActivated && mTrickyStoreEnabled
                ? mCache.get(Settings.Secure.SPOOF_TRICKYSTORE_PATCH) : null;
    }

    /**
     * The keybox holds private keys, so only system uids and TrickyStore targets (any mode
     * but skip) get it: those are the processes that sign attestation chains with it.
     */
    public String getTrickyStoreKeyBox() {
        if (!mActivated || !mTrickyStoreEnabled) return null;
        final int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID && !isTarget(uid)) {
            return null;
        }
        return mCache.get(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    private boolean isTarget(int uid) {
        final String targets = mCache.get(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        if (targets == null || targets.trim().isEmpty() || mContext == null) return false;
        final String[] packages;
        final long token = Binder.clearCallingIdentity();
        try {
            packages = mContext.getPackageManager().getPackagesForUid(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (packages == null) return false;
        final Map<String, Boolean> listed = parseTargets(targets.trim());
        for (String pkg : packages) {
            if (Boolean.TRUE.equals(listed.get(pkg))
                    && !TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** Package -> whether it is targeted (false for skip entries). */
    private static Map<String, Boolean> parseTargets(String targets) {
        final Map<String, Boolean> out = new HashMap<>();
        try {
            if (targets.startsWith("[")) {
                final JSONArray array = new JSONArray(targets);
                for (int i = 0; i < array.length(); i++) {
                    final JSONObject o = array.optJSONObject(i);
                    if (o == null) continue;
                    final String pkg = o.optString("package", null);
                    if (pkg != null) {
                        out.put(pkg, !"SKIP".equalsIgnoreCase(o.optString("mode", "AUTO")));
                    }
                }
                return out;
            }
        } catch (Exception e) {
            return out;
        }
        for (String raw : targets.split("\n")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.put(barePackage(line), !line.endsWith("-"));
        }
        return out;
    }
}
