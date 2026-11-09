package ru.main.explorer;

import android.os.Build;
import android.view.*;

final class UiInsets {
    static void apply(View view, int left, int top, int right, int bottom) {
        if (Build.VERSION.SDK_INT >= 35)
            view.setOnApplyWindowInsetsListener(
                    (v, insets) -> {
                        android.graphics.Insets bars =
                                insets.getInsets(WindowInsets.Type.systemBars());
                        v.setPadding(
                                left + bars.left,
                                top + bars.top,
                                right + bars.right,
                                bottom + bars.bottom);
                        return insets;
                    });
    }
}
