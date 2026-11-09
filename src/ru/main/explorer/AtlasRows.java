package ru.main.explorer;

import android.database.DataSetObserver;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;

/** Retains native single-choice, item positions, enabled state and dialog callbacks. */
final class AtlasRows extends BaseAdapter implements WrapperListAdapter {
    final ListAdapter source;

    static void install(ListView list) {
        android.util.SparseBooleanArray checked = list.getCheckedItemPositions();
        checked = checked == null ? new android.util.SparseBooleanArray() : checked.clone();
        int selected = list.getSelectedItemPosition(), first = list.getFirstVisiblePosition();
        list.setAdapter(new AtlasRows(list.getAdapter()));
        for (int i = 0; i < checked.size(); i++)
            list.setItemChecked(checked.keyAt(i), checked.valueAt(i));
        if (selected >= 0) list.setSelection(selected);
        else if (first > 0) list.setSelection(first);
    }

    AtlasRows(ListAdapter source) {
        this.source = source;
        source.registerDataSetObserver(
                new DataSetObserver() {
                    public void onChanged() {
                        notifyDataSetChanged();
                    }

                    public void onInvalidated() {
                        notifyDataSetInvalidated();
                    }
                });
    }

    public ListAdapter getWrappedAdapter() {
        return source;
    }

    public int getCount() {
        return source.getCount();
    }

    public Object getItem(int i) {
        return source.getItem(i);
    }

    public long getItemId(int i) {
        return source.getItemId(i);
    }

    public boolean hasStableIds() {
        return source.hasStableIds();
    }

    public int getItemViewType(int i) {
        return source.getItemViewType(i);
    }

    public int getViewTypeCount() {
        return source.getViewTypeCount();
    }

    public boolean areAllItemsEnabled() {
        return source.areAllItemsEnabled();
    }

    public boolean isEnabled(int i) {
        return source.isEnabled(i);
    }

    public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        row.setBackground(AtlasDecor.background(row, AtlasDecor.BUTTON));
        row.setMinimumHeight(AtlasUi.dp(row, 60));
        row.setPadding(
                AtlasUi.dp(row, 23), AtlasUi.dp(row, 12), AtlasUi.dp(row, 23), AtlasUi.dp(row, 12));
        if (row instanceof TextView) {
            TextView text = (TextView) row;
            text.setTextColor(AtlasUi.CREAM);
            text.setTextSize(16);
            text.setTypeface(Typeface.create("serif", 0));
            text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
        return row;
    }
}
