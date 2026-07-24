package com.qandil.opencodego.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

public final class Ui {
    public static final int BG = Color.rgb(8, 17, 15);
    public static final int SURFACE = Color.rgb(18, 31, 27);
    public static final int SURFACE_2 = Color.rgb(26, 42, 36);
    public static final int BORDER = Color.rgb(42, 72, 61);
    public static final int TEXT = Color.rgb(240, 248, 244);
    public static final int MUTED = Color.rgb(157, 177, 168);
    public static final int ACCENT = Color.rgb(61, 220, 132);
    public static final int WARNING = Color.rgb(255, 181, 71);
    public static final int DANGER = Color.rgb(255, 108, 108);
    public static final int INFO = Color.rgb(104, 184, 255);

    private Ui() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable bg(int color, int radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(int color, int borderColor, int radiusDp, Context context) {
        GradientDrawable drawable = bg(color, radiusDp, context);
        drawable.setStroke(dp(context, 1), borderColor);
        return drawable;
    }

    public static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(0, 1.1f);
        return view;
    }

    public static TextView button(Context context, String label, boolean primary) {
        TextView view = text(context, label, 14, primary ? BG : TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 15), dp(context, 12), dp(context, 15), dp(context, 12));
        view.setBackground(primary
                ? bg(ACCENT, 14, context)
                : outlined(SURFACE_2, BORDER, 14, context));
        view.setClickable(true);
        view.setFocusable(true);
        view.setMinHeight(dp(context, 44));
        return view;
    }

    public static TextView dangerButton(Context context, String label) {
        TextView view = text(context, label, 14, TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 15), dp(context, 12), dp(context, 15), dp(context, 12));
        view.setBackground(outlined(Color.rgb(55, 29, 29), DANGER, 14, context));
        view.setClickable(true);
        return view;
    }

    public static TextView chip(Context context, String label, int color) {
        TextView view = text(context, label, 11, color, true);
        view.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        view.setBackground(bg(SURFACE_2, 99, context));
        return view;
    }

    public static LinearLayout card(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        layout.setBackground(outlined(SURFACE, BORDER, 20, context));
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static Space space(Context context, int heightDp) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return space;
    }

    public static Space horizontalSpace(Context context, int widthDp) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(context, widthDp), 1));
        return space;
    }

    public static EditText input(Context context, String hint, boolean multiline) {
        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setHintTextColor(MUTED);
        editText.setTextColor(TEXT);
        editText.setTextSize(15);
        editText.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        editText.setBackground(outlined(SURFACE_2, BORDER, 14, context));
        editText.setSelectAllOnFocus(false);
        if (multiline) {
            editText.setMinLines(3);
            editText.setGravity(Gravity.TOP | Gravity.START);
        } else editText.setSingleLine(true);
        return editText;
    }

    public static LinearLayout labeledInput(Context context, String label, EditText input) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(text(context, label, 12, MUTED, true));
        layout.addView(space(context, 5));
        layout.addView(input);
        return layout;
    }

    public static LinearLayout.LayoutParams weight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static void margins(View view, Context context, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        LinearLayout.LayoutParams params = raw instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) raw
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
        view.setLayoutParams(params);
    }

    public static void toast(Context context, String value) {
        Toast.makeText(context, value, Toast.LENGTH_SHORT).show();
    }

    public static void error(Context context, String title, Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "Неизвестная ошибка" : error.getMessage();
        new AlertDialog.Builder(context).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }
}
