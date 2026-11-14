/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Miku-Picked UI: drives the status bar lyric bar for media players that don't push their own
 * lyric lines through notifications — Spotify above all. It follows the active media session,
 * fetches timed lyrics from lrclib.net (a free, no-key LRC service) keyed on the track, and
 * scrolls the current line into {@link LyricViewController} in time with playback.
 *
 * Only runs while the lyric feature is enabled. Notification-driven lyrics (the Meizu-style
 * music apps) always win: this controller only ever calls setExternalLyric/clearExternalLyric,
 * and LyricViewController ignores external lyrics while a notification is driving the bar.
 */
public class MediaLyricController {

    private static final String TAG = "MediaLyricController";

    // Players handled here. Spotify never broadcasts lyric lines of its own.
    private static final String[] SUPPORTED_PACKAGES = {
            "com.spotify.music",
    };

    private static final String LRCLIB_GET = "https://lrclib.net/api/get";
    // lrclib asks clients to identify themselves; no personal data, just the project.
    private static final String USER_AGENT =
            "MikuPickedUI-StatusBarLyrics (https://github.com/Miku-Picked-UI)";
    private static final int HTTP_TIMEOUT_MS = 8000;
    private static final long TICK_MS = 300;

    private static final Pattern LRC_LINE =
            Pattern.compile("((?:\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\])+)(.*)");
    private static final Pattern LRC_TAG =
            Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private final Context mContext;
    private final LyricViewController mLyric;
    private final MediaSessionManager mSessionManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mNetwork = Executors.newSingleThreadExecutor();

    private boolean mEnabled;
    private MediaController mController;
    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onTrackChanged(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            scheduleTick();
        }

        @Override
        public void onSessionDestroyed() {
            mHandler.post(() -> pickSession());
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsListener =
            controllers -> pickSession();

    // The current track and its parsed lyrics.
    private String mTrackKey;
    private long mTrackFetchId;
    private List<LyricLine> mLines;
    private int mLastLineIndex = -1;
    private Drawable mIcon;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(this);
            if (!mEnabled) return;
            updateCurrentLine();
            if (isPlaying()) {
                mHandler.postDelayed(this, TICK_MS);
            }
        }
    };

    private static final class LyricLine {
        final long timeMs;
        final String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public MediaLyricController(Context context, LyricViewController lyric) {
        mContext = context;
        mLyric = lyric;
        mSessionManager = context.getSystemService(MediaSessionManager.class);
    }

    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        if (enabled) {
            try {
                // SystemUI holds MEDIA_CONTENT_CONTROL, so no listener component is needed.
                mSessionManager.addOnActiveSessionsChangedListener(mSessionsListener, null,
                        mHandler);
            } catch (Exception e) {
                Log.w(TAG, "Can't watch media sessions", e);
            }
            pickSession();
        } else {
            try {
                mSessionManager.removeOnActiveSessionsChangedListener(mSessionsListener);
            } catch (Exception ignored) {
            }
            detachController();
            clearLyric();
        }
    }

    public void destroy() {
        setEnabled(false);
        mNetwork.shutdownNow();
    }

    private void pickSession() {
        if (!mEnabled) return;
        MediaController chosen = null;
        try {
            final List<MediaController> controllers = mSessionManager.getActiveSessions(null);
            for (MediaController c : controllers) {
                if (isSupported(c.getPackageName())) {
                    chosen = c;
                    if (isActive(c)) break; // prefer one that is actually playing
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No access to active media sessions", e);
            return;
        }
        if (chosen == null) {
            detachController();
            clearLyric();
            return;
        }
        if (mController != null && mController.getSessionToken().equals(chosen.getSessionToken())) {
            return; // same session
        }
        detachController();
        mController = chosen;
        mController.registerCallback(mControllerCallback, mHandler);
        mIcon = loadAppIcon(chosen.getPackageName());
        onTrackChanged(mController.getMetadata());
    }

    private void detachController() {
        if (mController != null) {
            try {
                mController.unregisterCallback(mControllerCallback);
            } catch (Exception ignored) {
            }
            mController = null;
        }
    }

    private static boolean isSupported(String pkg) {
        if (pkg == null) return false;
        for (String p : SUPPORTED_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean isActive(MediaController c) {
        final PlaybackState state = c.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private boolean isPlaying() {
        return mController != null && isActive(mController);
    }

    private Drawable loadAppIcon(String pkg) {
        try {
            return mContext.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private void onTrackChanged(MediaMetadata metadata) {
        mHandler.removeCallbacks(mTick);
        if (metadata == null) {
            clearTrack();
            return;
        }
        final String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String artist = firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        final String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        final long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(artist)) {
            clearTrack();
            return;
        }
        final String key = title + "\u0000" + artist + "\u0000" + (durationMs / 1000);
        if (key.equals(mTrackKey)) {
            scheduleTick();
            return;
        }
        clearTrack();
        mTrackKey = key;
        final long fetchId = ++mTrackFetchId;
        fetchLyrics(title, artist, album, durationMs, fetchId);
    }

    private void fetchLyrics(String title, String artist, String album, long durationMs,
            long fetchId) {
        mNetwork.execute(() -> {
            final List<LyricLine> lines = requestLrclib(title, artist, album, durationMs);
            mHandler.post(() -> {
                if (fetchId != mTrackFetchId || !mEnabled) return; // track moved on
                mLines = lines;
                mLastLineIndex = -1;
                if (lines == null || lines.isEmpty()) {
                    clearLyric();
                } else {
                    scheduleTick();
                }
            });
        });
    }

    private List<LyricLine> requestLrclib(String title, String artist, String album,
            long durationMs) {
        HttpURLConnection conn = null;
        try {
            final StringBuilder url = new StringBuilder(LRCLIB_GET)
                    .append("?track_name=").append(enc(title))
                    .append("&artist_name=").append(enc(artist));
            if (!TextUtils.isEmpty(album)) url.append("&album_name=").append(enc(album));
            if (durationMs > 0) url.append("&duration=").append(durationMs / 1000);
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null; // 404 = lrclib has no lyrics for this track
            }
            final String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final JSONObject json = new JSONObject(body);
            final String synced = json.optString("syncedLyrics", null);
            if (TextUtils.isEmpty(synced)) return null; // plain lyrics can't be timed
            return parseLrc(synced);
        } catch (Exception e) {
            Log.w(TAG, "lrclib request failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static List<LyricLine> parseLrc(String lrc) {
        final List<LyricLine> lines = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            final Matcher m = LRC_LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            final String text = m.group(2).trim();
            final Matcher tags = LRC_TAG.matcher(m.group(1));
            while (tags.find()) {
                final long min = Long.parseLong(tags.group(1));
                final long sec = Long.parseLong(tags.group(2));
                long frac = 0;
                if (tags.group(3) != null) {
                    final String f = tags.group(3);
                    frac = Long.parseLong(f) * (f.length() == 2 ? 10 : (f.length() == 1 ? 100 : 1));
                }
                lines.add(new LyricLine(min * 60_000 + sec * 1000 + frac, text));
            }
        }
        lines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return lines;
    }

    private void scheduleTick() {
        if (!mEnabled) return;
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    private long currentPositionMs() {
        if (mController == null) return 0;
        final PlaybackState state = mController.getPlaybackState();
        if (state == null) return 0;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            final long since = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            position += (long) (since * state.getPlaybackSpeed());
        }
        return position;
    }

    private void updateCurrentLine() {
        final List<LyricLine> lines = mLines;
        if (lines == null || lines.isEmpty()) {
            return;
        }
        final long pos = currentPositionMs();
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= pos) {
                index = i;
            } else {
                break;
            }
        }
        if (index == mLastLineIndex) return;
        mLastLineIndex = index;
        if (index < 0) {
            // Before the first timed line (intro): leave the bar empty, not stale.
            mLyric.clearExternalLyric();
            return;
        }
        final String text = lines.get(index).text;
        if (TextUtils.isEmpty(text)) {
            mLyric.clearExternalLyric();
        } else {
            mLyric.setExternalLyric(text, mIcon);
        }
    }

    private void clearTrack() {
        mTrackKey = null;
        mTrackFetchId++;
        mLines = null;
        mLastLineIndex = -1;
        clearLyric();
    }

    private void clearLyric() {
        mHandler.removeCallbacks(mTick);
        mLyric.clearExternalLyric();
    }

    private static String firstNonEmpty(String a, String b) {
        return !TextUtils.isEmpty(a) ? a : b;
    }
}
