package com.android.launcher3.moments;

import static com.android.launcher3.LauncherState.NORMAL;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.os.Process;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Insettable;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;

/**
 * The home screen while a Moment is on. It sits above the drag layer so no workspace, drawer
 * or search gesture reaches the launcher underneath.
 */
public class MomentsHomeView extends FrameLayout implements Insettable {

    private static final long TICK_MS = 30_000;

    private final Runnable mOnChanged = this::update;
    private final Runnable mTick = this::updateRemaining;
    private Launcher mLauncher;
    private MomentsBackgroundView mBackground;
    private View mContent;
    private View mPill;
    private ImageView mPillIcon;
    private TextView mPillName;
    private LinearLayout mApps;
    private View mTooltip;

    public MomentsHomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = findViewById(R.id.moments_background);
        mContent = findViewById(R.id.moments_content);
        mPill = findViewById(R.id.moments_pill);
        mPillIcon = findViewById(R.id.moments_pill_icon);
        mPillName = findViewById(R.id.moments_pill_name);
        mApps = findViewById(R.id.moments_apps);
        mTooltip = findViewById(R.id.moments_tooltip);
        mPill.setOnClickListener(v -> {
            finishOnboardingStep();
            getContext().startActivity(new Intent(getContext(), MomentsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        findViewById(R.id.moments_tooltip_close).setOnClickListener(
                v -> finishOnboardingStep());
    }

    private void finishOnboardingStep() {
        MomentsStore store = MomentsStore.get(getContext());
        int step = store.getOnboardingStep();
        if (step < 2 && mTooltip.getVisibility() == VISIBLE) {
            store.setOnboardingStep(step + 1);
            mTooltip.setVisibility(GONE);
        }
    }

    public void setup(Launcher launcher) {
        mLauncher = launcher;
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        MomentsStore.get(getContext()).addListener(mOnChanged);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mTick);
        MomentsStore.get(getContext()).removeListener(mOnChanged);
        super.onDetachedFromWindow();
    }

    @Override
    public void setInsets(Rect insets) {
        mContent.setPadding(insets.left, insets.top, insets.right, insets.bottom);
    }

    private void update() {
        Moment moment = MomentsStore.get(getContext()).getActive();
        boolean show = moment != null;
        if (show && getVisibility() != VISIBLE && mLauncher != null) {
            AbstractFloatingView.closeAllOpenViews(mLauncher, false);
            mLauncher.getStateManager().goToState(NORMAL, false);
        }
        setVisibility(show ? VISIBLE : GONE);
        removeCallbacks(mTick);
        if (!show) {
            return;
        }
        Context themed = MomentsUi.themed(getContext(), moment.uiMode);
        boolean night = MomentsUi.isNight(getContext(), moment.uiMode);
        int text = themed.getColor(R.color.moments_text);
        setBackgroundColor(themed.getColor(R.color.moments_bg));
        mBackground.setColors(moment.colors, night);
        ((TextView) findViewById(R.id.moments_time)).setTextColor(text);
        ((TextView) findViewById(R.id.moments_date)).setTextColor(text);

        float density = getResources().getDisplayMetrics().density;
        int button = MomentsUi.isStatic(moment.colors) && night ? 0x2B979797
                : themed.getColor(R.color.moments_card);
        mPill.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080),
                MomentsDrawables.gradientBorder(button,
                        button | 0xFF000000, (button & 0xFFFFFF) | 0x1A000000, 12 * density,
                        density), null));
        mPillIcon.setImageResource(MomentsUi.iconRes(moment.icon));
        mPillIcon.setImageTintList(ColorStateList.valueOf(text));
        mPillName.setTextColor(text);
        updateRemaining();

        int step = MomentsStore.get(getContext()).getOnboardingStep();
        mTooltip.setVisibility(step < 2 ? VISIBLE : GONE);
        mTooltip.setBackground(MomentsDrawables.tooltip(themed.getColor(R.color.moments_tooltip),
                12 * density, 8 * density));
        TextView tooltipText = findViewById(R.id.moments_tooltip_text);
        tooltipText.setText(step == 0 ? R.string.moments_onboarding_customize
                : R.string.moments_onboarding_switch);
        tooltipText.setTextColor(text);
        ((ImageView) findViewById(R.id.moments_tooltip_close)).setImageTintList(
                ColorStateList.valueOf(text));

        mApps.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (String app : moment.apps) {
            LauncherActivityInfo info = MomentsUi.resolve(getContext(), app);
            if (info == null) {
                continue;
            }
            View row = inflater.inflate(R.layout.moments_home_app, mApps, false);
            TextView label = row.findViewById(R.id.app_label);
            label.setText(info.getLabel());
            label.setTextColor(text);
            row.findViewById(R.id.app_work_badge).setVisibility(
                    MomentsUi.isWork(app) ? VISIBLE : GONE);
            row.setOnClickListener(v -> launch(v, info));
            mApps.addView(row);
        }
    }

    private void updateRemaining() {
        MomentsStore store = MomentsStore.get(getContext());
        Moment moment = store.getActive();
        if (moment == null) {
            return;
        }
        long left = store.getEndsAt() - System.currentTimeMillis();
        if (store.getEndsAt() <= 0 || left <= 0) {
            mPillName.setText(moment.name);
            return;
        }
        long minutes = (left + 59_999) / 60_000;
        String remaining = minutes >= 60
                ? getContext().getString(R.string.moments_duration_hm, minutes / 60, minutes % 60)
                : getContext().getString(R.string.moments_duration_m, minutes);
        mPillName.setText(getContext().getString(R.string.moments_pill_remaining, moment.name,
                remaining));
        postDelayed(mTick, TICK_MS);
    }

    private void launch(View view, LauncherActivityInfo info) {
        finishOnboardingStep();
        if (!info.getUser().equals(Process.myUserHandle())) {
            getContext().getSystemService(LauncherApps.class).startMainActivity(
                    info.getComponentName(), info.getUser(), null, null);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(info.getComponentName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (mLauncher != null) {
            mLauncher.startActivitySafely(view, intent, null);
        } else {
            getContext().startActivity(intent);
        }
    }
}
