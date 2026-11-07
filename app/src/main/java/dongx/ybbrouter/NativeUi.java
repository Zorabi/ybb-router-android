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
    final int bg, surface, text, muted, accent, tint, green, danger, line;
    NativeUi(Context context) {
        this.context = context; dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = c(dark ? "#101318" : "#F3F5F9"); surface = c(dark ? "#1B2029" : "#FFFFFF");
        text = c(dark ? "#ECF0F7" : "#182237"); muted = c(dark ? "#A5B1C3" : "#606D82");
        accent = c(dark ? "#A5BEFF" : "#2D5DE0"); tint = c(dark ? "#293B5D" : "#E7EEFF");
        green = c(dark ? "#79DCB3" : "#137D5B"); danger = c(dark ? "#FFA59D" : "#BD3535");
        line = c(dark ? "#343E4D" : "#DCE2ED");
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
        v.setIncludeFontPadding(false); v.setLineSpacing(dp(3), 1); return v;
    }
    Button button(String value, boolean primary, View.OnClickListener action) {
        Button b = new Button(context); b.setText(value); b.setTextSize(14); b.setAllCaps(false);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0);
        int fill = primary ? c(dark ? "#406EDB" : "#2D5DE0") : surface;
        int pressed = primary ? c(dark ? "#527CE0" : "#204AB9") : tint;
        b.setBackground(states(fill, pressed)); b.setStateListAnimator(null); b.setPadding(dp(16), dp(8), dp(16), dp(8));
        int color = primary ? Color.WHITE : accent;
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{muted, color}));
        b.setOnClickListener(action); return b;
    }
    EditText search(String hint) {
        EditText v = new EditText(context); v.setSingleLine(true); v.setTextSize(16); v.setTextColor(text); v.setHintTextColor(muted); v.setHint(hint);
        GradientDrawable background = shape(surface, 12); background.setStroke(dp(1), line);
        GradientDrawable focused = shape(tint, 12); focused.setStroke(dp(1), line);
        StateListDrawable field = new StateListDrawable(); field.addState(new int[]{android.R.attr.state_focused}, focused); field.addState(new int[]{}, background); v.setBackground(field);
        v.setPadding(dp(16), dp(12), dp(16), dp(12)); v.setMinimumHeight(dp(48));
        v.setInputType(android.text.InputType.TYPE_CLASS_TEXT); v.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); return v;
    }
    LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(18), dp(18), dp(18), dp(18)); v.setBackground(shape(surface, 18)); return v; }
    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p);
    }
    View gap(int height) { View v = new View(context); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return v; }
}
