package com.cleanhome.tv;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Renders the 4-column app grid. Focus/highlight is handled by MainActivity. */
public class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {

    private final List<AppInfo> apps;

    public GridAdapter(List<AppInfo> apps) {
        this.apps = apps;
    }

    static class VH extends RecyclerView.ViewHolder {
        final View tileArt;
        final ImageView art;
        final ImageView iconOnly;
        final TextView label;
        VH(View v) {
            super(v);
            tileArt = v.findViewById(R.id.tileArt);
            art = v.findViewById(R.id.art);
            iconOnly = v.findViewById(R.id.iconOnly);
            label = v.findViewById(R.id.tileLabel);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_grid, parent, false);
        v.findViewById(R.id.tileArt).setClipToOutline(true); // round the banner to the tile
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppInfo a = apps.get(position);
        h.label.setText(a.label);
        // Uniform icon tiles in the grid (the dock carries the featured banners).
        Drawable d = a.icon != null ? a.icon : a.banner;
        h.iconOnly.setImageDrawable(d);
        h.iconOnly.setVisibility(View.VISIBLE);
        h.art.setVisibility(View.GONE);
        // Reset transient focus visuals (views are recycled)
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        h.label.setAlpha(0f);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }
}
