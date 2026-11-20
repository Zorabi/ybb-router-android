// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class NativeUi {
    final Context context;
    final boolean dark;
    final int bg, surface, text, muted, accent, tint, green, danger, line, soft, primary, hero;
    NativeUi(Context context) {
        this.context = context; dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = c(dark ? "#10141D" : "#F4F6FA"); surface = c(dark ? "#1B2230" : "#FFFFFF");
        text = c(dark ? "#ECF0F7" : "#182237"); muted = c(dark ? "#A5B1C3" : "#606D82");
        accent = c(dark ? "#AEC8FF" : "#285CE4"); tint = c(dark ? "#293D62" : "#EAF0FF");
        green = c(dark ? "#79DCB3" : "#137D5B"); danger = c(dark ? "#FFA59D" : "#BD3535");
        line = c(dark ? "#343E4D" : "#DCE2ED");
        soft = c(dark ? "#232C3B" : "#F3F5F9");
        primary = c(dark ? "#3968D9" : "#285CE4");
        hero = c(dark ? "#233F7B" : "#244EC3");
    }
    static int c(String hex) { return Color.parseColor(hex); }
    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    StateListDrawable states(int normal, int active) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, shape(active, 12));
        d.addState(new int[]{android.R.attr.state_focused}, shape(active, 12));
        d.addState(new int[]{android.R.attr.state_hovered}, shape(active, 12));
        d.addState(new int[]{android.R.attr.state_selected}, shape(active, 12));
        d.addState(new int[]{}, shape(normal, 12)); return d;
    }
    TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(context); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setIncludeFontPadding(false); v.setLineSpacing(dp(2), 1); return v;
    }
    Button button(String value, boolean primary, View.OnClickListener action) {
        Button b = new Button(context); b.setText(value); b.setTextSize(14); b.setAllCaps(false);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0);
        int fill = primary ? this.primary : Color.TRANSPARENT;
        int pressed = primary ? c(dark ? "#527CE0" : "#204AB9") : tint;
        b.setBackground(states(fill, pressed)); b.setStateListAnimator(null); b.setPadding(dp(16), dp(8), dp(16), dp(8));
        int color = primary ? Color.WHITE : accent;
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{muted, color}));
        b.setOnClickListener(action); return b;
    }
    android.graphics.drawable.Drawable icon(int resource, int color, int size) {
        android.graphics.drawable.Drawable drawable = context.getDrawable(resource).mutate();
        drawable.setTint(color); drawable.setBounds(0, 0, dp(size), dp(size)); return drawable;
    }
    void trailingIcon(TextView view, int resource, int color, int size) {
        view.setIncludeFontPadding(false);view.setLineSpacing(0,1);
        view.setGravity((view.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable[] icons=view.getCompoundDrawablesRelative();
        view.setCompoundDrawablesRelative(icons[0],icons[1],resource==0?null:icon(resource,color,size),icons[3]);
        view.setCompoundDrawablePadding(dp(4));
    }
    Button iconButton(int resource, String description, View.OnClickListener action) {
        Button button = button("", false, action);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setCompoundDrawables(icon(resource, muted, 24), null, null, null);
        button.setContentDescription(description); return button;
    }
    android.widget.ImageView symbol(int resource, int color, int background) {
        android.widget.ImageView image = new android.widget.ImageView(context);
        image.setImageDrawable(icon(resource, color, 24)); image.setPadding(dp(10), dp(10), dp(10), dp(10));
        image.setBackground(shape(background, 14));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return image;
    }
    Button navigation(String title, int resource, boolean selected, View.OnClickListener action) {
        Button button = button(title, false, action); button.setTextSize(12);
        button.setCompoundDrawables(null, icon(resource, selected ? accent : muted, 22), null, null);
        button.setCompoundDrawablePadding(dp(4)); button.setPadding(dp(12),dp(8),dp(12),dp(8));
        button.setTextColor(selected ? accent : muted); button.setSelected(selected); return button;
    }
    LinearLayout selection(String caption, String value, int resource, View.OnClickListener action) {
        LinearLayout row = row(); row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(60)); row.setBackground(states(soft, tint));
        row.setClickable(true); row.setFocusable(true); row.setOnClickListener(action);
        row.addView(symbol(resource, accent, Color.TRANSPARENT), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout labels = column(); labels.setPadding(dp(8),0,dp(8),0);
        add(labels,label(caption,11,muted,false),0); add(labels,label(value,15,text,true),4);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.ImageView arrow = symbol(R.drawable.ic_arrow, muted, Color.TRANSPARENT);
        arrow.setPadding(dp(2),dp(2),dp(2),dp(2)); row.addView(arrow,new LinearLayout.LayoutParams(dp(18),dp(18)));
        row.setContentDescription(caption + "，" + value);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
            }
        });
        return row;
    }
    EditText search(String hint) {
        EditText v = new EditText(context); v.setSingleLine(true); v.setTextSize(16); v.setTextColor(text); v.setHintTextColor(muted); v.setHint(hint);
        GradientDrawable background = shape(surface, 12); background.setStroke(dp(1), line);
        GradientDrawable focused = shape(tint, 12); focused.setStroke(dp(1), line);
        StateListDrawable field = new StateListDrawable(); field.addState(new int[]{android.R.attr.state_focused}, focused); field.addState(new int[]{}, background); v.setBackground(field);
        v.setPadding(dp(16), dp(12), dp(16), dp(12)); v.setMinimumHeight(dp(48));
        v.setInputType(android.text.InputType.TYPE_CLASS_TEXT); v.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); return v;
    }
    LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(18), dp(18), dp(18), dp(18)); v.setBackground(shape(surface, 20)); return v; }
    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p);
    }
    View gap(int height) { View v = new View(context); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return v; }
}
