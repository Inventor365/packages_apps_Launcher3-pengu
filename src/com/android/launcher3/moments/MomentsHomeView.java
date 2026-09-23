package com.android.launcher3.moments;

import static com.android.launcher3.LauncherState.NORMAL;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private final Runnable mOnChanged = this::update;
    private Launcher mLauncher;
    private ImageView mPillIcon;
    private TextView mPillName;
    private LinearLayout mApps;

    public MomentsHomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPillIcon = findViewById(R.id.moments_pill_icon);
        mPillName = findViewById(R.id.moments_pill_name);
        mApps = findViewById(R.id.moments_apps);
        findViewById(R.id.moments_pill).setOnClickListener(v -> getContext().startActivity(
                new Intent(getContext(), MomentsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
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
        MomentsStore.get(getContext()).removeListener(mOnChanged);
        super.onDetachedFromWindow();
    }

    @Override
    public void setInsets(Rect insets) {
        setPadding(insets.left, insets.top, insets.right, insets.bottom);
    }

    private void update() {
        Moment moment = MomentsStore.get(getContext()).getActive();
        boolean show = moment != null;
        if (show && getVisibility() != VISIBLE && mLauncher != null) {
            AbstractFloatingView.closeAllOpenViews(mLauncher, false);
            mLauncher.getStateManager().goToState(NORMAL, false);
        }
        setVisibility(show ? VISIBLE : GONE);
        if (!show) {
            return;
        }
        mPillIcon.setImageResource(MomentsUi.iconRes(moment.icon));
        mPillName.setText(moment.name);
        mApps.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (String app : moment.apps) {
            LauncherActivityInfo info = MomentsUi.resolve(getContext(), app);
            if (info == null) {
                continue;
            }
            TextView label = (TextView) inflater.inflate(R.layout.moments_home_app, mApps, false);
            label.setText(info.getLabel());
            label.setOnClickListener(v -> launch(v, info));
            mApps.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void launch(View view, LauncherActivityInfo info) {
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
