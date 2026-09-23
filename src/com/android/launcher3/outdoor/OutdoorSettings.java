package com.android.launcher3.outdoor;

import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.widget.ListView;
import android.widget.Toast;

import androidx.preference.Preference;

import com.android.launcher3.R;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

public class OutdoorSettings extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new OutdoorFragment())
                    .commit();
        }
    }

    public static class OutdoorFragment extends SettingsBasePreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setSharedPreferencesName(OutdoorState.PREFS_NAME);
            setPreferencesFromResource(R.xml.outdoor_preferences, rootKey);
            findPreference("widget").setOnPreferenceClickListener(p -> {
                Context context = requireContext();
                AppWidgetManager.getInstance(context).requestPinAppWidget(
                        new ComponentName(context, OutdoorWidgetProvider.class), null, null);
                return true;
            });
            findPreference("apps").setOnPreferenceClickListener(p -> {
                pickApps();
                return true;
            });
            findPreference("about").setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.outdoor_about)
                        .setMessage(R.string.outdoor_about_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return true;
            });
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
            refreshSummaries();
        }

        @Override
        public void onPause() {
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (key != null && !key.startsWith("saved_")) {
                OutdoorController.apply(requireContext());
                refreshSummaries();
            }
        }

        private void refreshSummaries() {
            Context context = requireContext();
            findPreference("widget").setSummary(OutdoorWidgetProvider.isAdded(context)
                    ? R.string.outdoor_widget_added : R.string.outdoor_widget_not_added);
            List<String> labels = new ArrayList<>();
            for (String app : OutdoorState.get(context).getApps()) {
                LauncherActivityInfo info = resolve(context, app);
                if (info != null) {
                    labels.add(info.getLabel().toString());
                }
            }
            Preference apps = findPreference("apps");
            apps.setSummary(labels.isEmpty() ? getString(R.string.outdoor_apps_none)
                    : TextUtils.join(", ", labels));
        }

        private void pickApps() {
            Context context = requireContext();
            List<LauncherActivityInfo> all = new ArrayList<>(context.getSystemService(
                    LauncherApps.class).getActivityList(null, Process.myUserHandle()));
            all.removeIf(i -> i.getComponentName().getPackageName()
                    .equals(context.getPackageName()));
            Collator collator = Collator.getInstance();
            all.sort((a, b) -> collator.compare(a.getLabel().toString(),
                    b.getLabel().toString()));
            List<String> selected = OutdoorState.get(context).getApps();
            String[] labels = new String[all.size()];
            boolean[] checked = new boolean[all.size()];
            for (int i = 0; i < all.size(); i++) {
                labels[i] = all.get(i).getLabel().toString();
                checked[i] = selected.contains(all.get(i).getComponentName().flattenToString());
            }
            new AlertDialog.Builder(context)
                    .setTitle(R.string.outdoor_apps_title)
                    .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                        String key = all.get(which).getComponentName().flattenToString();
                        if (!isChecked) {
                            selected.remove(key);
                        } else if (selected.size() >= OutdoorState.MAX_APPS) {
                            ListView list = ((AlertDialog) dialog).getListView();
                            list.setItemChecked(which, false);
                            Toast.makeText(context, R.string.outdoor_apps_max,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            selected.add(key);
                        }
                    })
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> OutdoorState.get(context).setApps(selected))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private static LauncherActivityInfo resolve(Context context, String app) {
            ComponentName component = ComponentName.unflattenFromString(app);
            if (component == null) {
                return null;
            }
            List<LauncherActivityInfo> found = context.getSystemService(LauncherApps.class)
                    .getActivityList(component.getPackageName(), Process.myUserHandle());
            for (LauncherActivityInfo info : found) {
                if (info.getComponentName().equals(component)) {
                    return info;
                }
            }
            return null;
        }
    }
}
