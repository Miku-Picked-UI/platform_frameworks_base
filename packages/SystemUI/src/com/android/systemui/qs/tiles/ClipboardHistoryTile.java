/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.picked.clipboard.ClipboardHistory;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Miku-Picked UI: Clipboard history tile. Opens the last copied texts (see ClipboardHistory):
 * tap to copy one again, long press to pin or delete it.
 */
public class ClipboardHistoryTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "clipboard_history";

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_clipboard_history);
    private final UserTracker mUserTracker;
    private final ShadeController mShadeController;

    @Inject
    public ClipboardHistoryTile(
            QsEventLogger uiEventLogger,
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            UserTracker userTracker,
            ShadeController shadeController) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mUserTracker = userTracker;
        mShadeController = shadeController;
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mUiHandler.post(() -> {
            mShadeController.animateCollapseShade();
            showHistory();
        });
    }

    private void showHistory() {
        final Context context = mContext;
        final int userId = mUserTracker.getUserId();
        final List<ClipboardHistory.Entry> entries =
                new ArrayList<>(ClipboardHistory.INSTANCE.entries(context, userId));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context,
                R.style.Theme_SystemUI_Dialog)
                .setTitle(R.string.picked_clipboard_title)
                .setNegativeButton(R.string.picked_clipboard_close, null);
        if (!ClipboardHistory.INSTANCE.isEnabled(context, userId)) {
            builder.setMessage(R.string.picked_clipboard_off);
        } else if (entries.isEmpty()) {
            builder.setMessage(R.string.picked_clipboard_empty);
        } else {
            final ListView list = new ListView(context);
            final EntryAdapter adapter = new EntryAdapter(context, entries);
            list.setAdapter(adapter);
            builder.setView(list);
            builder.setNeutralButton(R.string.picked_clipboard_clear, (d, w) ->
                    ClipboardHistory.INSTANCE.clear(context, userId, false));
            final AlertDialog[] holder = new AlertDialog[1];
            list.setOnItemClickListener((parent, view, position, id) -> {
                final ClipboardManager cm = context.getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText(null, entries.get(position).getText()));
                Toast.makeText(context, R.string.picked_clipboard_copied, Toast.LENGTH_SHORT)
                        .show();
                if (holder[0] != null) holder[0].dismiss();
            });
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                final ClipboardHistory.Entry e = entries.get(position);
                final AlertDialog actions = new AlertDialog.Builder(context,
                        R.style.Theme_SystemUI_Dialog)
                        .setItems(new CharSequence[] {
                                context.getString(e.getPinned() ? R.string.picked_clipboard_unpin
                                        : R.string.picked_clipboard_pin),
                                context.getString(R.string.picked_clipboard_delete)},
                                (d, which) -> {
                                    if (which == 0) {
                                        ClipboardHistory.INSTANCE.setPinned(context, userId,
                                                e.getText(), !e.getPinned());
                                    } else {
                                        ClipboardHistory.INSTANCE.remove(context, userId,
                                                e.getText());
                                    }
                                    entries.clear();
                                    entries.addAll(ClipboardHistory.INSTANCE.entries(context,
                                            userId));
                                    adapter.notifyDataSetChanged();
                                })
                        .create();
                SystemUIDialog.applyFlags(actions);
                SystemUIDialog.setShowForAllUsers(actions, true);
                actions.show();
                return true;
            });
            final AlertDialog dialog = builder.create();
            holder[0] = dialog;
            show(dialog);
            return;
        }
        show(builder.create());
    }

    private static void show(AlertDialog dialog) {
        SystemUIDialog.applyFlags(dialog);
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.registerDismissListener(dialog);
        dialog.show();
    }

    private static final class EntryAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<ClipboardHistory.Entry> mEntries;

        EntryAdapter(Context context, List<ClipboardHistory.Entry> entries) {
            mContext = context;
            mEntries = entries;
        }

        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return mEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final LinearLayout row;
            final TextView text, meta;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                text = (TextView) row.getChildAt(0);
                meta = (TextView) row.getChildAt(1);
            } else {
                final float d = mContext.getResources().getDisplayMetrics().density;
                row = new LinearLayout(mContext);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding((int) (24 * d), (int) (10 * d), (int) (24 * d), (int) (10 * d));
                text = new TextView(mContext);
                text.setMaxLines(3);
                text.setEllipsize(TextUtils.TruncateAt.END);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                meta = new TextView(mContext);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                meta.setAlpha(0.6f);
                row.addView(text);
                row.addView(meta);
            }
            final ClipboardHistory.Entry e = mEntries.get(position);
            text.setText(e.getText());
            text.setTypeface(null, e.getPinned() ? Typeface.BOLD : Typeface.NORMAL);
            final CharSequence when = DateUtils.getRelativeTimeSpanString(e.getTime(),
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            meta.setText(e.getPinned()
                    ? TextUtils.concat(mContext.getString(R.string.picked_clipboard_pinned),
                            " · ", when)
                    : when);
            return row;
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.label = mContext.getString(R.string.picked_clipboard_title);
        state.icon = mIcon;
        state.state = Tile.STATE_INACTIVE;
        state.contentDescription = state.label;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.picked_clipboard_title);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VIEW_UNKNOWN;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
