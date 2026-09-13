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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

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
			holder.author.setText(item.getAuthorExt(context));
		}
		if (holder.version != null) {
			holder.version.setText(item.getVersionExt(context));
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
