/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package namod.j2me.applist;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import namod.j2me.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import namod.j2me.R;

public class AppsListAdapter extends BaseAdapter implements Filterable {

	private List<AppItem> list;
	private List<AppItem> filteredList;
	private final LayoutInflater layoutInflater;
	private Context context;
	private AppFilter appFilter;
	private boolean isGrid;

	public AppsListAdapter(Context context) {
		this.list = new ArrayList<>();
		this.filteredList = new ArrayList<>();
		this.layoutInflater = LayoutInflater.from(context);
		this.context = context;
		this.appFilter = new AppFilter();
	}

	@Override
	public int getCount() {
		return filteredList.size();
	}

	@Override
	public AppItem getItem(int position) {
		return filteredList.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public int getItemViewType(int position) {
		return isGrid ? 1 : 0;
	}

	@Override
	public int getViewTypeCount() {
		return 2;
	}

	public void setGridMode(boolean grid) {
		this.isGrid = grid;
		notifyDataSetChanged();
	}

	@Override
	public View getView(int position, View view, ViewGroup viewGroup) {
		ViewHolder holder;
		if (view == null) {
			view = layoutInflater.inflate(isGrid ? R.layout.grid_item_jar : R.layout.list_row_jar, viewGroup, false);
			holder = new ViewHolder();
			holder.icon = view.findViewById(R.id.list_image);
			holder.name = view.findViewById(R.id.list_title);
			holder.author = view.findViewById(R.id.list_author);
			holder.version = view.findViewById(R.id.list_version);
			holder.more = view.findViewById(R.id.list_more);
			view.setTag(holder);
		} else {
			holder = (ViewHolder) view.getTag();
		}
		AppItem item = filteredList.get(position);

		File iconFile = new File(item.getImagePathExt());
		if (iconFile.isFile()) {
			Bitmap iconBitmap = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
			holder.icon.setImageBitmap(iconBitmap);
		} else {
			holder.icon.setImageResource(R.mipmap.ic_launcher);
		}
		holder.name.setText(item.getTitle());
		if (holder.author != null) {
			String author = item.getAuthor();
			String title = item.getTitle();
			if (author != null && !author.trim().isEmpty()) {
				String cleanAuthor = author.trim();
				if (title != null && cleanAuthor.equalsIgnoreCase(title.trim())) {
					holder.author.setText("J2ME");
				} else {
					holder.author.setText(cleanAuthor);
				}
				holder.author.setVisibility(View.VISIBLE);
			} else {
				holder.author.setText("J2ME");
				holder.author.setVisibility(View.VISIBLE);
			}
		}
		int accent = namod.j2me.util.AppUtils.getAccentColor(context);
		View cardContainer = view.findViewById(R.id.card_container);
		if (cardContainer != null) {
			GradientDrawable cardShape = new GradientDrawable();
			cardShape.setCornerRadius(namod.j2me.util.AppUtils.dpToPx(14, context));
			int cardBg = ContextCompat.getColor(context, R.color.card_bg);
			int customBg = namod.j2me.util.AppUtils.getCustomBgColor(context, -1);
			if (customBg != -1) {
				cardShape.setColor(customBg);
			} else {
				cardShape.setColor(cardBg);
			}
			cardShape.setStroke(namod.j2me.util.AppUtils.dpToPx(1, context),
					Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent)));
			if (Build.VERSION.SDK_INT >= 21) {
				cardContainer.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20FFFFFF), cardShape, null));
			} else {
				cardContainer.setBackground(cardShape);
			}

			double bgLuminance = (0.299 * Color.red(cardBg) + 0.587 * Color.green(cardBg) + 0.114 * Color.blue(cardBg)) / 255.0;
			int defaultTextColor = ContextCompat.getColor(context, R.color.text_primary);
			int customTextColor = namod.j2me.util.AppUtils.getCustomTextColor(context, -1);
			if (customTextColor != -1) {
				double textLuminance = (0.299 * Color.red(customTextColor) + 0.587 * Color.green(customTextColor) + 0.114 * Color.blue(customTextColor)) / 255.0;
				if (Math.abs(textLuminance - bgLuminance) < 0.35) {
					holder.name.setTextColor(bgLuminance > 0.5 ? 0xFF111827 : 0xFFFFFFFF);
					if (holder.author != null) holder.author.setTextColor(bgLuminance > 0.5 ? 0xFF4B5563 : 0xFF9CA3AF);
				} else {
					holder.name.setTextColor(customTextColor);
				}
			} else {
				holder.name.setTextColor(bgLuminance > 0.5 ? 0xFF111827 : defaultTextColor);
				if (holder.author != null) holder.author.setTextColor(bgLuminance > 0.5 ? 0xFF4B5563 : ContextCompat.getColor(context, R.color.text_secondary));
			}
		}
		if (holder.version != null) {
			String ver = item.getVersion();
			if (ver != null && !ver.trim().isEmpty()) {
				String vText = ver.trim();
				if (!vText.toLowerCase().startsWith("v")) {
					vText = "v" + vText;
				}
				holder.version.setVisibility(View.VISIBLE);
				holder.version.setText(vText);
				holder.version.setTextColor(accent);
				GradientDrawable pill = new GradientDrawable();
				pill.setCornerRadius(namod.j2me.util.AppUtils.dpToPx(8, context));
				pill.setColor(Color.argb(35, Color.red(accent), Color.green(accent), Color.blue(accent)));
				pill.setStroke(namod.j2me.util.AppUtils.dpToPx(1, context),
						Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)));
				holder.version.setBackground(pill);
			} else {
				holder.version.setVisibility(View.GONE);
			}
		}

		if (holder.more != null) {
			final View finalItemView = view;
			holder.more.setOnClickListener(v -> {
				if (context instanceof android.app.Activity) {
					((android.app.Activity) context).openContextMenu(finalItemView);
				}
			});
		}

		return view;
	}

	public void setItems(List<AppItem> items) {
		list = items;
		filteredList = items;
		notifyDataSetChanged();
	}

	private static class ViewHolder {
		ImageView icon;
		TextView name;
		TextView author;
		TextView version;
		View more;
	}

	@Override
	public Filter getFilter() {
		return appFilter;
	}

	private class AppFilter extends Filter {
		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			FilterResults results = new FilterResults();
			if (constraint.equals("")) {
				results.values = list;
				results.count = list.size();
			} else {
				ArrayList<AppItem> filtered = new ArrayList<>();
				for (AppItem appItem : list) {
					if (appItem.getTitle().toLowerCase().contains(constraint)
							|| appItem.getAuthor().toLowerCase().contains(constraint)) {
						filtered.add(appItem);
					}
				}
				results.values = filtered;
				results.count = filtered.size();
			}
			return results;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected void publishResults(CharSequence constraint, FilterResults results) {
			if (results.values == null) {
				notifyDataSetInvalidated();
				return;
			}
			filteredList = (List<AppItem>) results.values;
			notifyDataSetChanged();
		}
	}
}
