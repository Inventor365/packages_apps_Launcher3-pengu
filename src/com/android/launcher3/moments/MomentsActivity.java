package com.android.launcher3.moments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.text.Collator;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MomentsActivity extends Activity {

    private static final int SCREEN_PICKER = 0;
    private static final int SCREEN_EDITOR = 1;
    private static final int SCREEN_APPS = 2;
    private static final int SCREEN_ORDER = 3;
    private static final int SCREEN_TEMPLATES = 4;
    private static final int SCREEN_BACKGROUND = 5;
    private static final int SCREEN_NAME = 6;

    private MomentsStore mStore;
    private MomentsBackgroundView mBackground;
    private FrameLayout mScreenHost;
    private int mScreen;
    private Moment mEditing;
    // True while going through the create steps, before the Moment is saved.
    private boolean mCreating;
    private boolean mAppsForBackground;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // The Moment's own light or dark choice, for as long as it is on.
        Moment active = MomentsStore.get(base).getActive();
        if (active != null && active.uiMode != Moment.UI_SYSTEM) {
            Configuration config = new Configuration();
            config.uiMode = (base.getResources().getConfiguration().uiMode
                    & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (active.uiMode == Moment.UI_DARK ? Configuration.UI_MODE_NIGHT_YES
                            : Configuration.UI_MODE_NIGHT_NO);
            applyOverrideConfiguration(config);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStore = MomentsStore.get(this);
        FrameLayout root = new FrameLayout(this);
        mBackground = new MomentsBackgroundView(this);
        root.addView(mBackground);
        mScreenHost = new FrameLayout(this);
        mScreenHost.setFitsSystemWindows(true);
        root.addView(mScreenHost);
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        showPicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!mCreating && (mScreen == SCREEN_EDITOR || mScreen == SCREEN_ORDER)) {
            saveEditing();
        }
    }

    private void goBack() {
        switch (mScreen) {
            case SCREEN_APPS:
                if (mCreating) {
                    showTemplates();
                } else if (mAppsForBackground) {
                    showEditor();
                } else {
                    showOrder();
                }
                break;
            case SCREEN_ORDER:
                showEditor();
                break;
            case SCREEN_BACKGROUND:
                if (mCreating) {
                    showApps(false);
                } else {
                    showEditor();
                }
                break;
            case SCREEN_NAME:
                showBackgrounds();
                break;
            case SCREEN_TEMPLATES:
                mCreating = false;
                mEditing = null;
                showPicker();
                break;
            case SCREEN_EDITOR:
                saveEditing();
                mEditing = null;
                showPicker();
                break;
            default:
                finish();
        }
    }

    private View setScreen(int screen, int layout) {
        mScreen = screen;
        mScreenHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(layout, mScreenHost, true);
        View back = view.findViewById(R.id.moments_back);
        if (back != null) {
            back.setOnClickListener(v -> goBack());
        }
        return view;
    }

    private void showBackgroundOf(Moment moment) {
        mBackground.setVisibility(moment == null ? View.GONE : View.VISIBLE);
        if (moment != null) {
            mBackground.setColors(moment.colors, MomentsUi.isNight(this, Moment.UI_SYSTEM));
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private boolean isNight() {
        return MomentsUi.isNight(this, Moment.UI_SYSTEM);
    }

    // Picker

    private void showPicker() {
        View view = setScreen(SCREEN_PICKER, R.layout.moments_picker);
        String activeId = mStore.getActiveId();
        showBackgroundOf(mStore.getActive());
        String[] stats = MomentsStats.summary(this);
        if (stats != null) {
            view.findViewById(R.id.moments_stats).setVisibility(View.VISIBLE);
            ((TextView) view.findViewById(R.id.moments_stats_time)).setText(stats[0]);
            TextView unlocks = view.findViewById(R.id.moments_stats_unlocks);
            unlocks.setVisibility(stats.length > 1 ? View.VISIBLE : View.GONE);
            unlocks.setText(stats.length > 1 ? stats[1] : null);
        }
        LinearLayout cards = view.findViewById(R.id.moments_cards);
        List<Moment> moments = mStore.getMoments();
        for (Moment moment : moments) {
            cards.addView(card(cards, moment, moment.id.equals(activeId)));
        }
        boolean canAdd = moments.size() < Moment.MAX_MOMENTS;
        View add = view.findViewById(R.id.moments_add);
        add.setVisibility(canAdd ? View.VISIBLE : View.GONE);
        add.setOnClickListener(v -> showTemplates());
        view.findViewById(R.id.moments_max).setVisibility(canAdd ? View.GONE : View.VISIBLE);

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

    private View card(ViewGroup parent, Moment moment, boolean active) {
        View card = getLayoutInflater().inflate(R.layout.moments_card, parent, false);
        boolean night = isNight();
        int text = getColor(active ? R.color.moments_card_active_text : R.color.moments_text);
        float radius = dp(16);
        Drawable background;
        if (active) {
            background = MomentsDrawables.animatedBorder(getColor(R.color.moments_card_active),
                    new int[]{MomentsUi.leftColor(moment.colors),
                            MomentsUi.rightColor(moment.colors)}, radius, dp(2));
        } else {
            int fill = night ? 0x1E3B3B3B : 0x55FFFFFF;
            background = MomentsDrawables.gradientBorder(fill, night ? 0x22CCCCCC : 0x55FFFFFF,
                    night ? 0x01999999 : 0x37FFFFFF, radius, dp(1));
        }
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), background,
                null));
        ImageView icon = card.findViewById(R.id.moment_icon);
        icon.setImageResource(MomentsUi.iconRes(moment.icon));
        icon.setImageTintList(ColorStateList.valueOf(text));
        TextView name = card.findViewById(R.id.moment_name);
        name.setText(moment.name);
        name.setTextColor(text);
        ImageView edit = card.findViewById(R.id.moment_edit);
        edit.setImageTintList(ColorStateList.valueOf(text));
        int editFill = active ? 0x32FFFFFF : night ? 0x323B3B3B : 0x1EFFFFFF;
        edit.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080),
                MomentsDrawables.gradientBorder(editFill,
                        active ? MomentsUi.rightColor(moment.colors) | 0xFF000000
                                : night ? 0x22CCCCCC : 0x55FFFFFF,
                        active ? MomentsUi.leftColor(moment.colors) | 0xFF000000
                                : night ? 0x01999999 : 0x37FFFFFF, dp(24), dp(1)), null));
        edit.setOnClickListener(v -> {
            mEditing = moment;
            showEditor();
        });
        card.setOnClickListener(v -> start(moment, 0));
        card.setOnLongClickListener(v -> {
            pickDuration(moment);
            return true;
        });
        return card;
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
            mEditing = moment;
            showApps(false);
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

    // Create steps: template, apps, background, name

    private static final int[][] TEMPLATE_TEXT = {
            {R.string.moments_preset_deep_focus, R.string.moments_template_deep_focus_summary},
            {R.string.moments_preset_journey, R.string.moments_template_journey_summary},
            {R.string.moments_preset_recharge, R.string.moments_template_recharge_summary},
            {R.string.moments_preset_quality_time,
                    R.string.moments_template_quality_time_summary},
            {R.string.moments_template_custom, R.string.moments_template_custom_summary},
    };
    private static final int[] TEMPLATE_ICONS = {Moment.ICON_FOCUS, Moment.ICON_JOURNEY,
            Moment.ICON_MOON, Moment.ICON_HEART, Moment.ICON_EXTRA + 5};

    private void showTemplates() {
        if (!mStore.canAddMoment()) {
            showPicker();
            return;
        }
        View view = setScreen(SCREEN_TEMPLATES, R.layout.moments_templates);
        mCreating = true;
        showBackgroundOf(null);
        LinearLayout list = view.findViewById(R.id.moments_templates);
        for (int i = 0; i < MomentsStore.TEMPLATES.length; i++) {
            String template = MomentsStore.TEMPLATES[i];
            View row = getLayoutInflater().inflate(R.layout.moments_template_row, list, false);
            ((ImageView) row.findViewById(R.id.template_icon)).setImageResource(
                    MomentsUi.iconRes(TEMPLATE_ICONS[i]));
            ((TextView) row.findViewById(R.id.template_title)).setText(TEMPLATE_TEXT[i][0]);
            ((TextView) row.findViewById(R.id.template_summary)).setText(TEMPLATE_TEXT[i][1]);
            int title = TEMPLATE_TEXT[i][0];
            row.setOnClickListener(v -> {
                mEditing = mStore.fromTemplate(template);
                mEditing.name = template.equals(MomentsStore.TEMPLATE_CUSTOM) ? ""
                        : getString(title);
                showApps(false);
            });
            list.addView(row);
        }
    }

    private void showBackgrounds() {
        View view = setScreen(SCREEN_BACKGROUND, R.layout.moments_background_picker);
        Moment m = mEditing;
        showBackgroundOf(null);
        boolean night = isNight();
        RecyclerView list = view.findViewById(R.id.moments_backgrounds);
        LinearLayoutManager layout = new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false);
        list.setLayoutManager(layout);
        int side = Math.max(0, (getResources().getDisplayMetrics().widthPixels
                - Math.round(dp(218 + 24))) / 2);
        list.setPadding(side, 0, side, 0);
        PagerSnapHelper snap = new PagerSnapHelper();
        snap.attachToRecyclerView(list);
        LinearLayout dots = view.findViewById(R.id.moments_background_dots);
        int[] selected = {m.colors};
        Runnable refreshDots = () -> {
            for (int i = 0; i < dots.getChildCount(); i++) {
                dots.getChildAt(i).setBackgroundResource(i == selected[0]
                        ? R.drawable.moments_dot_selected : R.drawable.moments_dot);
            }
        };
        for (int i = 0; i < Moment.COLORS_COUNT; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(dp(8)),
                    Math.round(dp(8)));
            lp.setMarginStart(i == 0 ? 0 : Math.round(dp(4)));
            dots.addView(dot, lp);
        }
        refreshDots.run();
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                    int type) {
                return new RecyclerView.ViewHolder(getLayoutInflater().inflate(
                        R.layout.moments_background_card, parent, false)) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                View card = holder.itemView;
                ((MomentsBackgroundView) card.findViewById(R.id.preview_background))
                        .setColors(position, night);
                int text = getColor(R.color.moments_text);
                int button = MomentsUi.isStatic(position) && night ? 0x2B979797
                        : getColor(R.color.moments_card);
                card.findViewById(R.id.preview_pill).setBackground(
                        MomentsDrawables.gradientBorder(button, button | 0xFF000000,
                                (button & 0xFFFFFF) | 0x1A000000, dp(6), dp(0.5f)));
                ImageView icon = card.findViewById(R.id.preview_icon);
                icon.setImageResource(MomentsUi.iconRes(m.icon));
                icon.setImageTintList(ColorStateList.valueOf(text));
                ((TextView) card.findViewById(R.id.preview_name)).setText(
                        TextUtils.isEmpty(m.name) ? getString(R.string.moments_new_name)
                                : m.name);
                card.setOnClickListener(v -> list.smoothScrollBy(
                        v.getLeft() + v.getWidth() / 2 - list.getWidth() / 2, 0));
            }

            @Override
            public int getItemCount() {
                return Moment.COLORS_COUNT;
            }
        });
        layout.scrollToPositionWithOffset(m.colors, 0);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int state) {
                View center = snap.findSnapView(layout);
                if (state == RecyclerView.SCROLL_STATE_IDLE && center != null) {
                    selected[0] = layout.getPosition(center);
                    refreshDots.run();
                }
            }
        });
        TextView next = view.findViewById(R.id.moments_continue);
        next.setText(mCreating ? R.string.moments_continue : R.string.moments_apply);
        next.setOnClickListener(v -> {
            m.colors = selected[0];
            if (mCreating) {
                showName();
            } else {
                showEditor();
            }
        });
    }

    private void showName() {
        View view = setScreen(SCREEN_NAME, R.layout.moments_name);
        Moment m = mEditing;
        showBackgroundOf(null);
        ImageView icon = view.findViewById(R.id.moment_icon);
        icon.setImageResource(MomentsUi.iconRes(m.icon));
        icon.setOnClickListener(v -> pickIcon(m, () ->
                icon.setImageResource(MomentsUi.iconRes(m.icon))));
        View create = view.findViewById(R.id.moments_continue);
        EditText input = bindNameField(view.findViewById(R.id.moment_name_editor), m.name,
                valid -> {
                    create.setEnabled(valid);
                    create.setAlpha(valid ? 1 : 0.4f);
                });
        create.setOnClickListener(v -> {
            m.name = input.getText().toString().trim();
            mCreating = false;
            saveEditing();
            showEditor();
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE && create.isEnabled()) {
                create.performClick();
            }
            return true;
        });
        input.requestFocus();
    }

    /** Wires the name field and its "under 15 characters" error; returns the input. */
    private EditText bindNameField(View field, String name, Consumer<Boolean> onValidChanged) {
        EditText input = field.findViewById(R.id.moment_name_input);
        TextView error = field.findViewById(R.id.moment_name_error);
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                boolean tooLong = s.length() >= Moment.MAX_NAME_LENGTH;
                error.setVisibility(tooLong ? View.VISIBLE : View.GONE);
                onValidChanged.accept(!tooLong && !TextUtils.isEmpty(s.toString().trim()));
            }
        };
        input.addTextChangedListener(watcher);
        input.setText(name);
        input.setSelection(input.length());
        return input;
    }

    private void pickIcon(Moment m, Runnable onPicked) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        int pad = Math.round(dp(16));
        grid.setPadding(0, pad, 0, 0);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.moments_change_icon)
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        int size = Math.round(dp(56));
        for (int i = 0; i < Moment.ICON_COUNT; i++) {
            int icon = i;
            ImageView view = new ImageView(this);
            view.setImageResource(MomentsUi.iconRes(i));
            view.setImageTintList(ColorStateList.valueOf(getColor(R.color.moments_text)));
            view.setPadding(pad, pad, pad, pad);
            view.setBackgroundResource(i == m.icon ? R.drawable.moments_icon_button_bg
                    : R.drawable.moments_circle_bg);
            if (i != m.icon) {
                view.getBackground().setAlpha(0);
            }
            view.setOnClickListener(v -> {
                m.icon = icon;
                onPicked.run();
                dialog.dismiss();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(pad / 4, pad / 4, pad / 4, pad / 4);
            grid.addView(view, lp);
        }
        dialog.show();
    }

    // Editor

    private void showEditor() {
        View view = setScreen(SCREEN_EDITOR, R.layout.moments_editor);
        Moment m = mEditing;
        showBackgroundOf(m);

        ImageView icon = view.findViewById(R.id.moment_icon);
        icon.setImageResource(MomentsUi.iconRes(m.icon));
        icon.setOnClickListener(v -> pickIcon(m, () ->
                icon.setImageResource(MomentsUi.iconRes(m.icon))));
        TextView name = view.findViewById(R.id.moment_name);
        name.setText(m.name);
        name.setOnClickListener(v -> rename(m, () -> name.setText(m.name)));
        boolean active = m.id.equals(mStore.getActiveId());
        view.findViewById(R.id.moment_active).setVisibility(active ? View.VISIBLE : View.GONE);
        View switchTo = view.findViewById(R.id.moment_switch_to);
        switchTo.setVisibility(active ? View.GONE : View.VISIBLE);
        switchTo.setOnClickListener(v -> {
            saveEditing();
            start(m, 0);
        });

        LinearLayout settings = view.findViewById(R.id.moment_settings);
        LinearLayout apps = group(settings, 0);
        row(apps, R.string.moments_visible_apps,
                () -> appsSummary(m.apps, R.string.moments_no_apps), v -> showOrder());

        LinearLayout people = group(settings, R.string.moments_customization);
        choice(people, R.string.moments_calls, () -> m.calls, value -> m.calls = value);
        choice(people, R.string.moments_messages, () -> m.messages, value -> m.messages = value);

        LinearLayout notifications = group(settings, R.string.moments_section_notifications);
        toggle(notifications, R.string.moments_app_notifications, 0, m.appNotifications,
                on -> m.appNotifications = on);
        toggle(notifications, R.string.moments_repeat_callers,
                R.string.moments_repeat_callers_summary, m.repeatCallers,
                on -> m.repeatCallers = on);

        LinearLayout appearance = group(settings, R.string.moments_section_appearance);
        row(appearance, R.string.moments_colour, null, v -> showBackgrounds());
        row(appearance, R.string.moments_ui_mode,
                () -> getString(MomentsUi.UI_MODE_TITLES[m.uiMode]), v -> pickUiMode(m));
        toggle(appearance, R.string.moments_grayscale, R.string.moments_grayscale_summary,
                m.grayscale, on -> m.grayscale = on);
        toggle(appearance, R.string.moments_blue_light, R.string.moments_blue_light_summary,
                m.blueLight, on -> m.blueLight = on);
        toggle(appearance, R.string.moments_dim_wallpaper, 0, m.dimWallpaper,
                on -> m.dimWallpaper = on);
        toggle(appearance, R.string.moments_calm_lock, R.string.moments_calm_lock_summary,
                m.calmLockWallpaper, on -> m.calmLockWallpaper = on);

        LinearLayout sound = group(settings, 0);
        row(sound, R.string.moments_sound,
                () -> getString(MomentsUi.SOUND_TITLES[m.sound]), v -> pickSound(m));

        LinearLayout power = group(settings, R.string.moments_section_power);
        toggle(power, R.string.moments_battery_saver, 0, m.batterySaver,
                on -> m.batterySaver = on);
        toggle(power, R.string.moments_extra_dim, 0, m.extraDim, on -> m.extraDim = on);
        toggle(power, R.string.moments_aod_off, 0, m.aodOff, on -> m.aodOff = on);
        toggle(power, R.string.moments_airplane, 0, m.airplane, on -> m.airplane = on);

        LinearLayout behaviour = group(settings, R.string.moments_section_behaviour);
        toggle(behaviour, R.string.moments_block, 0, m.blockOtherApps,
                on -> m.blockOtherApps = on);
        row(behaviour, R.string.moments_background,
                () -> appsSummary(m.background, R.string.moments_background_none),
                v -> showApps(true));
        TextView[] schedule = new TextView[1];
        schedule[0] = row(behaviour, R.string.moments_schedule, () -> scheduleSummary(m),
                v -> editSchedule(m, () -> schedule[0].setText(scheduleSummary(m))));
        toggle(behaviour, R.string.moments_car, 0, m.carTrigger, on -> m.carTrigger = on);

        boolean onlyOne = mStore.getMoments().size() <= 1;
        TextView delete = new TextView(this, null, 0, R.style.MomentsBodySmall);
        delete.setGravity(Gravity.CENTER);
        delete.setMinHeight(Math.round(dp(48)));
        int padding = Math.round(dp(16));
        delete.setPadding(padding, 0, padding, 0);
        if (onlyOne) {
            delete.setText(R.string.moments_delete_only);
        } else {
            delete.setText(R.string.moments_delete);
            delete.setTextColor(getColor(R.color.moments_error));
            delete.setBackgroundResource(android.R.drawable.list_selector_background);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(R.string.moments_delete)
                    .setMessage(R.string.moments_delete_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        MomentsController.delete(this, m);
                        mEditing = null;
                        showPicker();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.round(dp(24));
        settings.addView(delete, lp);
    }

    private LinearLayout group(LinearLayout parent, int title) {
        if (title != 0) {
            TextView header = new TextView(this, null, 0, R.style.MomentsSection);
            header.setText(title);
            parent.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout group = new LinearLayout(this, null, 0, R.style.MomentsGroup);
        group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (title == 0) {
            lp.topMargin = Math.round(dp(16));
        }
        parent.addView(group, lp);
        return group;
    }

    /** A row that opens something; returns its summary so it can be refreshed. */
    private TextView row(LinearLayout group, int title, Supplier<String> summary,
            View.OnClickListener onClick) {
        View row = getLayoutInflater().inflate(R.layout.moments_setting_row, group, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(title);
        TextView summaryView = row.findViewById(R.id.setting_summary);
        summaryView.setVisibility(summary == null ? View.GONE : View.VISIBLE);
        if (summary != null) {
            summaryView.setText(summary.get());
        }
        row.setOnClickListener(onClick);
        group.addView(row);
        return summaryView;
    }

    private void toggle(LinearLayout group, int title, int summary, boolean value,
            Consumer<Boolean> setter) {
        View row = getLayoutInflater().inflate(R.layout.moments_setting_row, group, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(title);
        TextView summaryView = row.findViewById(R.id.setting_summary);
        summaryView.setVisibility(summary == 0 ? View.GONE : View.VISIBLE);
        if (summary != 0) {
            summaryView.setText(summary);
        }
        CompoundButton toggle = row.findViewById(R.id.setting_switch);
        toggle.setVisibility(View.VISIBLE);
        toggle.setChecked(value);
        row.setOnClickListener(v -> {
            toggle.toggle();
            setter.accept(toggle.isChecked());
        });
        group.addView(row);
    }

    private void choice(LinearLayout group, int title, Supplier<Integer> getter,
            Consumer<Integer> setter) {
        TextView[] summary = new TextView[1];
        summary[0] = row(group, title, () -> MomentsUi.peopleLabel(this, getter.get()), v -> {
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
                        setter.accept(MomentsUi.PEOPLE_TYPES[which]);
                        summary[0].setText(labels[which]);
                        dialog.dismiss();
                    })
                    .show();
        });
    }

    private void pickSound(Moment m) {
        CharSequence[] items = new CharSequence[MomentsUi.SOUND_TITLES.length];
        for (int i = 0; i < items.length; i++) {
            SpannableStringBuilder item = new SpannableStringBuilder(
                    getString(MomentsUi.SOUND_TITLES[i]));
            int start = item.length();
            item.append('\n').append(getString(MomentsUi.SOUND_SUMMARIES[i]));
            item.setSpan(new RelativeSizeSpan(0.85f), start, item.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            item.setSpan(new ForegroundColorSpan(getColor(R.color.moments_text_secondary)),
                    start, item.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = item;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.moments_sound)
                .setSingleChoiceItems(items, m.sound, (dialog, which) -> {
                    m.sound = which;
                    dialog.dismiss();
                    showEditor();
                })
                .show();
    }

    private void pickUiMode(Moment m) {
        String[] items = new String[MomentsUi.UI_MODE_TITLES.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = getString(MomentsUi.UI_MODE_TITLES[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.moments_ui_mode)
                .setSingleChoiceItems(items, m.uiMode, (dialog, which) -> {
                    m.uiMode = which;
                    dialog.dismiss();
                    showEditor();
                })
                .show();
    }

    private void rename(Moment m, Runnable onRenamed) {
        View field = getLayoutInflater().inflate(R.layout.moments_name_field, null);
        int pad = Math.round(dp(24));
        field.setPadding(pad, Math.round(dp(8)), pad, 0);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.moments_rename)
                .setView(field)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        boolean[] valid = {true};
        EditText input = bindNameField(field, m.name, ok -> {
            valid[0] = ok;
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(ok);
            }
        });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(valid[0]);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                m.name = input.getText().toString().trim();
                onRenamed.run();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void saveEditing() {
        if (mEditing == null || mCreating) {
            return;
        }
        mStore.putMoment(mEditing);
        MomentsController.onMomentChanged(this, mEditing);
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

    // Visible apps, in the order the home shows them

    @SuppressLint("ClickableViewAccessibility")
    private void showOrder() {
        View view = setScreen(SCREEN_ORDER, R.layout.moments_order);
        Moment m = mEditing;
        showBackgroundOf(m);
        List<String> apps = m.apps;
        apps.removeIf(app -> MomentsUi.resolve(this, app) == null);
        RecyclerView list = view.findViewById(R.id.moments_order_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        ItemTouchHelper[] drag = new ItemTouchHelper[1];
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                            int type) {
                        return new RecyclerView.ViewHolder(getLayoutInflater().inflate(
                                R.layout.moments_order_row, parent, false)) { };
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
                            int position) {
                        String app = apps.get(position);
                        LauncherActivityInfo info = MomentsUi.resolve(MomentsActivity.this, app);
                        View row = holder.itemView;
                        ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(
                                info == null ? null : info.getBadgedIcon(0));
                        ((TextView) row.findViewById(R.id.app_label)).setText(
                                info == null ? app : info.getLabel());
                        row.findViewById(R.id.app_work_badge).setVisibility(
                                MomentsUi.isWork(app) ? View.VISIBLE : View.GONE);
                        row.findViewById(R.id.app_drag).setOnTouchListener((v, event) -> {
                            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                drag[0].startDrag(holder);
                            }
                            return false;
                        });
                    }

                    @Override
                    public int getItemCount() {
                        return apps.size();
                    }
                };
        drag[0] = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition();
                int b = to.getBindingAdapterPosition();
                Collections.swap(apps, a, b);
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) { }
        });
        drag[0].attachToRecyclerView(list);
        list.setAdapter(adapter);
        view.findViewById(R.id.moments_change_apps).setOnClickListener(v -> showApps(false));
    }

    // App chooser

    private void showApps(boolean background) {
        View view = setScreen(SCREEN_APPS, R.layout.moments_app_chooser);
        mAppsForBackground = background;
        Moment m = mEditing;
        showBackgroundOf(mCreating ? null : m);
        List<String> target = background ? m.background : m.apps;
        List<String> selected = new ArrayList<>(target);
        if (background) {
            ((TextView) view.findViewById(R.id.moments_choose_title)).setText(
                    R.string.moments_background_title);
            ((TextView) view.findViewById(R.id.moments_choose_summary)).setText(
                    R.string.moments_background_summary);
        }

        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> all = new ArrayList<>();
        for (UserHandle user : getSystemService(UserManager.class).getUserProfiles()) {
            all.addAll(launcherApps.getActivityList(null, user));
        }
        all.removeIf(info -> info.getComponentName().getPackageName().equals(getPackageName()));
        Collator collator = Collator.getInstance();
        all.sort((a, b) -> collator.compare(a.getLabel().toString(), b.getLabel().toString()));

        LinearLayout chips = view.findViewById(R.id.moments_selected);
        View chipsScroll = view.findViewById(R.id.moments_selected_scroll);
        TextView count = view.findViewById(R.id.moments_count);
        RecyclerView list = view.findViewById(R.id.moments_app_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        AppAdapter adapter = new AppAdapter(all, selected);
        Runnable refreshChips = new Runnable() {
            @Override
            public void run() {
                chips.removeAllViews();
                chipsScroll.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
                count.setText(getString(R.string.moments_count, selected.size(),
                        Moment.MAX_APPS));
                count.setTextColor(getColor(adapter.mFull ? R.color.moments_error
                        : R.color.moments_text_secondary));
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
                        adapter.mFull = false;
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
                count.setText(R.string.moments_needs_apps);
                count.setTextColor(getColor(R.color.moments_error));
                return;
            }
            target.clear();
            target.addAll(selected);
            if (mCreating) {
                showBackgrounds();
            } else if (background) {
                showEditor();
            } else {
                showOrder();
            }
        });
    }

    private class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<LauncherActivityInfo> mAll;
        private final List<String> mSelected;
        private List<LauncherActivityInfo> mShown;
        Runnable mOnChanged;
        // Set after trying to pick a sixth app, so the counter can say why nothing happened.
        boolean mFull;

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
            String key = MomentsUi.key(MomentsActivity.this, info);
            View row = holder.itemView;
            ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(info.getBadgedIcon(0));
            ((TextView) row.findViewById(R.id.app_label)).setText(info.getLabel());
            row.findViewById(R.id.app_work_badge).setVisibility(
                    MomentsUi.isWork(key) ? View.VISIBLE : View.GONE);
            CheckBox check = row.findViewById(R.id.app_check);
            check.setChecked(mSelected.contains(key));
            row.setOnClickListener(v -> {
                if (mSelected.remove(key)) {
                    check.setChecked(false);
                    mFull = false;
                } else if (mSelected.size() >= Moment.MAX_APPS) {
                    mFull = true;
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
