package com.android.launcher3.moments;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Process;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.text.Collator;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MomentsActivity extends Activity {

    private static final int SCREEN_PICKER = 0;
    private static final int SCREEN_EDITOR = 1;
    private static final int SCREEN_APPS = 2;

    private MomentsStore mStore;
    private FrameLayout mRoot;
    private int mScreen;
    private Moment mEditing;
    private boolean mEditingIsNew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStore = MomentsStore.get(this);
        mRoot = new FrameLayout(this);
        mRoot.setFitsSystemWindows(true);
        setContentView(mRoot);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        showPicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mScreen == SCREEN_EDITOR) {
            saveEditing();
        }
    }

    private void goBack() {
        switch (mScreen) {
            case SCREEN_APPS:
                if (mEditingIsNew && mEditing.apps.isEmpty()) {
                    showPicker();
                } else {
                    showEditor();
                }
                break;
            case SCREEN_EDITOR:
                saveEditing();
                showPicker();
                break;
            default:
                finish();
        }
    }

    private View setScreen(int screen, int layout) {
        mScreen = screen;
        mRoot.removeAllViews();
        return LayoutInflater.from(this).inflate(layout, mRoot, true);
    }

    private void showPicker() {
        View view = setScreen(SCREEN_PICKER, R.layout.moments_picker);
        String activeId = mStore.getActiveId();
        Moment activeMoment = mStore.getActive();
        mRoot.setBackground(activeMoment == null ? null
                : MomentsUi.background(this, activeMoment.palette));
        String[] stats = MomentsStats.summary(this);
        if (stats != null) {
            view.findViewById(R.id.moments_stats).setVisibility(View.VISIBLE);
            ((TextView) view.findViewById(R.id.moments_stats_time)).setText(stats[0]);
            TextView unlocks = view.findViewById(R.id.moments_stats_unlocks);
            unlocks.setVisibility(stats.length > 1 ? View.VISIBLE : View.GONE);
            unlocks.setText(stats.length > 1 ? stats[1] : null);
        }
        LinearLayout cards = view.findViewById(R.id.moments_cards);
        for (Moment moment : mStore.getMoments()) {
            View card = getLayoutInflater().inflate(R.layout.moments_card, cards, false);
            boolean active = moment.id.equals(activeId);
            card.setActivated(active);
            int text = getColor(active ? R.color.moments_card_active_text : R.color.moments_text);
            ImageView icon = card.findViewById(R.id.moment_icon);
            icon.setImageResource(MomentsUi.iconRes(moment.icon));
            icon.setImageTintList(ColorStateList.valueOf(text));
            TextView name = card.findViewById(R.id.moment_name);
            name.setText(moment.name);
            name.setTextColor(text);
            ImageView edit = card.findViewById(R.id.moment_edit);
            edit.setImageTintList(ColorStateList.valueOf(text));
            edit.setOnClickListener(v -> {
                mEditing = moment;
                mEditingIsNew = false;
                showEditor();
            });
            card.setOnClickListener(v -> start(moment, 0));
            card.setOnLongClickListener(v -> {
                pickDuration(moment);
                return true;
            });
            cards.addView(card);
        }
        view.findViewById(R.id.moments_add).setOnClickListener(v -> {
            mEditing = mStore.newMoment();
            mEditingIsNew = true;
            showApps(false);
        });
        HoldToExitButton exit = view.findViewById(R.id.moments_exit);
        exit.setVisibility(activeId == null ? View.GONE : View.VISIBLE);
        exit.setOnExit(() -> {
            MomentsController.exit(this);
            finish();
        });
        View addTile = view.findViewById(R.id.moments_add_tile);
        addTile.setVisibility(activeId == null ? View.VISIBLE : View.GONE);
        addTile.setOnClickListener(v -> addTile());
    }

    private static final int[] DURATIONS_MIN = {0, 30, 60, 120, 240};

    private void pickDuration(Moment moment) {
        String[] labels = new String[DURATIONS_MIN.length];
        for (int i = 0; i < labels.length; i++) {
            int minutes = DURATIONS_MIN[i];
            labels[i] = minutes == 0 ? getString(R.string.moments_until_off)
                    : minutes >= 60 ? getResources().getQuantityString(
                            R.plurals.moments_for_hours, minutes / 60, minutes / 60)
                    : getResources().getQuantityString(R.plurals.moments_for_minutes, minutes,
                            minutes);
        }
        new AlertDialog.Builder(this)
                .setTitle(moment.name)
                .setItems(labels, (dialog, which) -> start(moment, DURATIONS_MIN[which] == 0 ? 0
                        : System.currentTimeMillis() + DURATIONS_MIN[which] * 60_000L))
                .show();
    }

    private void start(Moment moment, long endsAt) {
        if (moment.apps.isEmpty()) {
            Toast.makeText(this, R.string.moments_needs_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        MomentsController.enter(this, moment, MomentsStore.SOURCE_MANUAL, endsAt);
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void addTile() {
        getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, MomentsTileService.class),
                getString(R.string.moments_title),
                Icon.createWithResource(this, R.drawable.ic_moment_sparkle),
                getMainExecutor(), result -> { });
    }

    private void showEditor() {
        View view = setScreen(SCREEN_EDITOR, R.layout.moments_editor);
        Moment m = mEditing;
        mRoot.setBackground(MomentsUi.background(this, m.palette));
        view.findViewById(R.id.moments_back).setOnClickListener(v -> goBack());

        ImageView icon = view.findViewById(R.id.moment_icon);
        icon.setImageResource(MomentsUi.iconRes(m.icon));
        icon.setOnClickListener(v -> {
            m.icon = (m.icon + 1) % Moment.ICON_COUNT;
            icon.setImageResource(MomentsUi.iconRes(m.icon));
        });
        EditText name = view.findViewById(R.id.moment_name);
        name.setText(m.name);
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (!TextUtils.isEmpty(s.toString().trim())) {
                    m.name = s.toString().trim();
                }
            }
        });

        TextView apps = view.findViewById(R.id.moment_apps_summary);
        apps.setText(appsSummary(m.apps, R.string.moments_no_apps));
        view.findViewById(R.id.moment_apps).setOnClickListener(v -> showApps(false));
        TextView background = view.findViewById(R.id.moment_background_summary);
        background.setText(appsSummary(m.background, R.string.moments_background_none));
        view.findViewById(R.id.moment_background).setOnClickListener(v -> showApps(true));

        TextView schedule = view.findViewById(R.id.moment_schedule_summary);
        schedule.setText(scheduleSummary(m));
        view.findViewById(R.id.moment_schedule).setOnClickListener(
                v -> editSchedule(m, () -> schedule.setText(scheduleSummary(m))));
        bindSwitch(view, R.id.moment_car, m.carTrigger, on -> m.carTrigger = on);

        LinearLayout palettes = view.findViewById(R.id.moment_palettes);
        int size = getResources().getDimensionPixelSize(R.dimen.moments_swatch_size);
        int gap = getResources().getDimensionPixelSize(R.dimen.moments_swatch_gap);
        for (int i = 0; i < Moment.PALETTE_COUNT; i++) {
            int palette = i;
            View swatch = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(MomentsUi.swatch(i));
            dot.setStroke(gap / 3, getColor(i == m.palette ? R.color.moments_text
                    : R.color.moments_card_stroke));
            swatch.setBackground(dot);
            swatch.setContentDescription(getString(R.string.moments_colour));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            swatch.setOnClickListener(v -> {
                m.palette = palette;
                showEditor();
            });
            palettes.addView(swatch, lp);
        }

        bindPeople(view, R.id.moment_calls, R.id.moment_calls_summary, R.string.moments_calls,
                () -> m.calls, value -> m.calls = value);
        bindPeople(view, R.id.moment_messages, R.id.moment_messages_summary,
                R.string.moments_messages, () -> m.messages, value -> m.messages = value);

        bindSwitch(view, R.id.moment_app_notifications, m.appNotifications,
                on -> m.appNotifications = on);
        bindSwitch(view, R.id.moment_block, m.blockOtherApps, on -> m.blockOtherApps = on);
        bindSwitch(view, R.id.moment_grayscale, m.grayscale, on -> m.grayscale = on);
        bindSwitch(view, R.id.moment_dim, m.dimWallpaper, on -> m.dimWallpaper = on);

        View delete = view.findViewById(R.id.moment_delete);
        delete.setVisibility(mEditingIsNew ? View.GONE : View.VISIBLE);
        delete.setOnClickListener(v -> {
            MomentsController.delete(this, m);
            mEditing = null;
            showPicker();
        });
    }

    private void saveEditing() {
        if (mEditing == null) {
            return;
        }
        mStore.putMoment(mEditing);
        MomentsController.onMomentChanged(this, mEditing);
        mEditingIsNew = false;
    }

    private interface IntGetter {
        int get();
    }

    private interface IntSetter {
        void set(int value);
    }

    private void bindPeople(View root, int rowId, int summaryId, int title, IntGetter getter,
            IntSetter setter) {
        TextView summary = root.findViewById(summaryId);
        summary.setText(MomentsUi.peopleLabel(this, getter.get()));
        root.findViewById(rowId).setOnClickListener(v -> {
            String[] labels = new String[MomentsUi.PEOPLE_TYPES.length];
            int checked = 0;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = MomentsUi.peopleLabel(this, MomentsUi.PEOPLE_TYPES[i]);
                if (MomentsUi.PEOPLE_TYPES[i] == getter.get()) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        setter.set(MomentsUi.PEOPLE_TYPES[which]);
                        summary.setText(labels[which]);
                        dialog.dismiss();
                    })
                    .show();
        });
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private static void bindSwitch(View root, int id, boolean value, BoolSetter setter) {
        CompoundButton toggle = root.findViewById(id);
        toggle.setChecked(value);
        toggle.setOnCheckedChangeListener((button, checked) -> setter.set(checked));
    }

    private String appsSummary(List<String> apps, int emptyText) {
        List<String> labels = new ArrayList<>();
        for (String app : apps) {
            LauncherActivityInfo info = MomentsUi.resolve(this, app);
            if (info != null) {
                labels.add(info.getLabel().toString());
            }
        }
        return labels.isEmpty() ? getString(emptyText) : TextUtils.join(", ", labels);
    }

    private String scheduleSummary(Moment m) {
        if (!m.scheduleEnabled || m.scheduleDays == 0) {
            return getString(R.string.moments_schedule_off);
        }
        String[] names = new DateFormatSymbols().getShortWeekdays();
        List<String> days = new ArrayList<>();
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            if ((m.scheduleDays & (1 << (day - 1))) != 0) {
                days.add(names[day]);
            }
        }
        return getString(R.string.moments_schedule_summary, TextUtils.join(", ", days),
                formatMinutes(m.scheduleStart), formatMinutes(m.scheduleEnd));
    }

    private String formatMinutes(int minutes) {
        Calendar time = Calendar.getInstance();
        time.set(Calendar.HOUR_OF_DAY, minutes / 60);
        time.set(Calendar.MINUTE, minutes % 60);
        return DateFormat.getTimeFormat(this).format(time.getTime());
    }

    private void editSchedule(Moment m, Runnable onChanged) {
        View view = getLayoutInflater().inflate(R.layout.moments_schedule, null);
        CompoundButton enabled = view.findViewById(R.id.schedule_enabled);
        enabled.setChecked(m.scheduleEnabled);
        LinearLayout daysRow = view.findViewById(R.id.schedule_days);
        String[] names = new DateFormatSymbols().getShortWeekdays();
        int[] days = {m.scheduleDays};
        int[] times = {m.scheduleStart, m.scheduleEnd};
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            int bit = 1 << (day - 1);
            CheckBox chip = (CheckBox) getLayoutInflater().inflate(
                    R.layout.moments_day_chip, daysRow, false);
            chip.setText(names[day].substring(0, 1));
            chip.setContentDescription(names[day]);
            chip.setChecked((days[0] & bit) != 0);
            chip.setOnCheckedChangeListener((b, on) -> days[0] = on ? days[0] | bit
                    : days[0] & ~bit);
            daysRow.addView(chip);
        }
        TextView start = view.findViewById(R.id.schedule_start);
        TextView end = view.findViewById(R.id.schedule_end);
        Runnable refresh = () -> {
            start.setText(getString(R.string.moments_schedule_starts, formatMinutes(times[0])));
            end.setText(getString(R.string.moments_schedule_ends, formatMinutes(times[1])));
        };
        refresh.run();
        start.setOnClickListener(v -> new TimePickerDialog(this, (p, h, min) -> {
            times[0] = h * 60 + min;
            refresh.run();
        }, times[0] / 60, times[0] % 60, DateFormat.is24HourFormat(this)).show());
        end.setOnClickListener(v -> new TimePickerDialog(this, (p, h, min) -> {
            times[1] = h * 60 + min;
            refresh.run();
        }, times[1] / 60, times[1] % 60, DateFormat.is24HourFormat(this)).show());
        new AlertDialog.Builder(this)
                .setTitle(R.string.moments_schedule)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    m.scheduleEnabled = enabled.isChecked();
                    m.scheduleDays = days[0];
                    m.scheduleStart = times[0];
                    m.scheduleEnd = times[1];
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showApps(boolean background) {
        View view = setScreen(SCREEN_APPS, R.layout.moments_app_chooser);
        Moment m = mEditing;
        List<String> target = background ? m.background : m.apps;
        List<String> selected = new ArrayList<>(target);
        view.findViewById(R.id.moments_back).setOnClickListener(v -> goBack());
        if (background) {
            ((TextView) view.findViewById(R.id.moments_choose_title)).setText(
                    R.string.moments_background_title);
            ((TextView) view.findViewById(R.id.moments_choose_summary)).setText(
                    R.string.moments_background_summary);
        }

        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> all = new ArrayList<>(
                launcherApps.getActivityList(null, Process.myUserHandle()));
        all.removeIf(info -> info.getComponentName().getPackageName().equals(getPackageName()));
        Collator collator = Collator.getInstance();
        all.sort((a, b) -> collator.compare(a.getLabel().toString(), b.getLabel().toString()));

        LinearLayout chips = view.findViewById(R.id.moments_selected);
        View chipsScroll = view.findViewById(R.id.moments_selected_scroll);
        RecyclerView list = view.findViewById(R.id.moments_app_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        AppAdapter adapter = new AppAdapter(all, selected);
        Runnable refreshChips = new Runnable() {
            @Override
            public void run() {
                chips.removeAllViews();
                chipsScroll.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
                for (String app : selected) {
                    LauncherActivityInfo info = MomentsUi.resolve(MomentsActivity.this, app);
                    if (info == null) {
                        continue;
                    }
                    View chip = getLayoutInflater().inflate(R.layout.moments_app_chip, chips,
                            false);
                    ((ImageView) chip.findViewById(R.id.app_icon)).setImageDrawable(
                            info.getBadgedIcon(0));
                    ((TextView) chip.findViewById(R.id.app_label)).setText(info.getLabel());
                    chip.setOnClickListener(v -> {
                        selected.remove(app);
                        adapter.notifyDataSetChanged();
                        run();
                    });
                    chips.addView(chip);
                }
            }
        };
        adapter.mOnChanged = refreshChips;
        list.setAdapter(adapter);
        refreshChips.run();

        EditText search = view.findViewById(R.id.moments_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
            }
        });

        view.findViewById(R.id.moments_continue).setOnClickListener(v -> {
            if (selected.isEmpty() && !background) {
                Toast.makeText(this, R.string.moments_needs_apps, Toast.LENGTH_SHORT).show();
                return;
            }
            target.clear();
            target.addAll(selected);
            showEditor();
        });
    }

    private class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<LauncherActivityInfo> mAll;
        private final List<String> mSelected;
        private List<LauncherActivityInfo> mShown;
        Runnable mOnChanged;

        AppAdapter(List<LauncherActivityInfo> all, List<String> selected) {
            mAll = all;
            mShown = all;
            mSelected = selected;
        }

        void filter(String query) {
            String q = query.trim().toLowerCase(Locale.getDefault());
            if (q.isEmpty()) {
                mShown = mAll;
            } else {
                mShown = new ArrayList<>();
                for (LauncherActivityInfo info : mAll) {
                    if (info.getLabel().toString().toLowerCase(Locale.getDefault()).contains(q)) {
                        mShown.add(info);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(getLayoutInflater().inflate(
                    R.layout.moments_app_row, parent, false)) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LauncherActivityInfo info = mShown.get(position);
            String key = info.getComponentName().flattenToString();
            View row = holder.itemView;
            ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(info.getBadgedIcon(0));
            ((TextView) row.findViewById(R.id.app_label)).setText(info.getLabel());
            CheckBox check = row.findViewById(R.id.app_check);
            check.setChecked(mSelected.contains(key));
            row.setOnClickListener(v -> {
                if (mSelected.remove(key)) {
                    check.setChecked(false);
                } else if (mSelected.size() >= Moment.MAX_APPS) {
                    Toast.makeText(MomentsActivity.this, R.string.moments_max_apps,
                            Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    mSelected.add(key);
                    check.setChecked(true);
                }
                mOnChanged.run();
            });
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }
}
