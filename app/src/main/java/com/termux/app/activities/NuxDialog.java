package com.termux.app.activities;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import com.termux.R;

/**
 * The shell's only dialog: a dark rounded surface with a title, a body and two
 * quiet text actions.
 *
 * <p/>
 * The platform {@code AlertDialog} is intentionally not used here. It keeps its
 * own type scale and button treatment no matter how the overlay is themed, so
 * the first-run and desktop notes looked like a stock template next to the
 * editorial pages. This helper inflates one layout, so every note in the shell
 * has the same shape.
 */
public final class NuxDialog {

    private NuxDialog() {
    }

    /**
     * Show a two-action dialog.
     *
     * @param activity    screen that owns the dialog, also used for its density
     * @param title       dialog title
     * @param message     dialog body
     * @param positive    label of the confirming action
     * @param onPositive  run after the dialog is dismissed, may be null
     * @param negative    label of the dismissing action, may be null to hide it
     * @return the shown dialog
     */
    public static Dialog show(Activity activity, CharSequence title, CharSequence message,
                              CharSequence positive, Runnable onPositive, CharSequence negative) {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_nux, null);

        ((TextView) content.findViewById(R.id.dialog_title)).setText(title);
        ((TextView) content.findViewById(R.id.dialog_message)).setText(message);

        Dialog dialog = new Dialog(activity, R.style.Theme_NuxDroid_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        TextView positiveView = content.findViewById(R.id.dialog_positive);
        positiveView.setText(positive);
        positiveView.setOnClickListener(view -> {
            dialog.dismiss();
            if (onPositive != null) onPositive.run();
        });

        TextView negativeView = content.findViewById(R.id.dialog_negative);
        if (negative == null) {
            negativeView.setVisibility(View.GONE);
        } else {
            negativeView.setText(negative);
            negativeView.setOnClickListener(view -> dialog.dismiss());
        }

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.6f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // Keep the card from stretching on tablets: 48dp side margins, 400dp cap.
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int margin = Math.round(48 * metrics.density);
            int max = Math.round(400 * metrics.density);
            window.setLayout(Math.min(metrics.widthPixels - margin, max),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        return dialog;
    }

}
