package com.cardash.display;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cardash.link.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A plain grid of the apps installed on the display, so Car Dash can also be used as the home screen. */
final class AppsDialog {
    private AppsDialog() {}

    private static final class Entry {
        String label;
        ComponentName component;
        ResolveInfo info;
        Drawable icon;
    }

    static void show(Activity a) {
        PackageManager pm = a.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        List<Entry> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || a.getPackageName().equals(ri.activityInfo.packageName)) continue;
            Entry e = new Entry();
            e.label = String.valueOf(ri.loadLabel(pm));
            e.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            e.info = ri;
            apps.add(e);
        }
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));

        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        float u = Math.min(dm.widthPixels, dm.heightPixels) / 100f;
        int pad = Math.round(3f * u);
        int iconSize = Math.round(11f * u);
        int cellWidth = Math.round(20f * u);

        Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);

        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = Ui.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(a, "Apps", 5f * u, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f));
        ImageView close = new ImageView(a);
        close.setImageDrawable(new Icons(Icons.CLOSE, Ui.TEXT));
        int cp = Math.round(2.6f * u);
        close.setPadding(cp, cp, cp, cp);
        close.setBackground(Ui.pressable(Ui.CARD, 2.5f * u));
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close, Ui.lp(Math.round(10f * u), Math.round(10f * u)));
        root.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, pad));

        GridView grid = new GridView(a);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(cellWidth);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Math.round(2f * u));
        grid.setSelector(Ui.pressable(0x00000000, 2.5f * u));
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int position) {
                return apps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) convertView;
                if (cell == null) {
                    cell = Ui.col(a);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, Math.round(1.5f * u), 0, Math.round(1.5f * u));
                    cell.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP));
                    ImageView icon = new ImageView(a);
                    cell.addView(icon, Ui.lp(iconSize, iconSize));
                    TextView label = Ui.text(a, "", 2.7f * u, Ui.TEXT, false);
                    label.setGravity(Gravity.CENTER);
                    Ui.lines(label, 2);
                    label.setPadding(Math.round(u), Math.round(1.2f * u), Math.round(u), 0);
                    cell.addView(label, Ui.lp(Ui.MATCH, Ui.WRAP));
                }
                Entry e = apps.get(position);
                if (e.icon == null) {
                    try {
                        e.icon = e.info.loadIcon(pm);
                    } catch (Exception ignored) {
                    }
                }
                ((ImageView) cell.getChildAt(0)).setImageDrawable(e.icon);
                ((TextView) cell.getChildAt(1)).setText(e.label);
                return cell;
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Entry e = apps.get(position);
            try {
                a.startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(e.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                d.dismiss();
            } catch (Exception ex) {
                Toast.makeText(a, "Could not open " + e.label, Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(grid, Ui.lp(Ui.MATCH, 0, 1f));

        d.setContentView(root);
        d.show();
    }
}
