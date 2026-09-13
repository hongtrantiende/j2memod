/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2018 Nikita Shakarun
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

package javax.microedition.lcdui.list;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Image;

import namod.j2me.R;
import namod.j2me.network.TabStatusManager;

public class CompoundListAdapter extends CompoundAdapter implements ListAdapter {
	protected int listType;
	protected ItemSelector selector;
	private String listTitle;

	public CompoundListAdapter(Context context, ItemSelector selector, int type) {
		super(context);

		this.listType = type;
		this.selector = selector;

		if (type != Choice.IMPLICIT && selector == null) {
			throw new IllegalArgumentException("ItemSelector is required for this list type");
		}
	}

	public void setListTitle(String title) {
		this.listTitle = title;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder holder;
		if (convertView == null || !(convertView.getTag() instanceof ViewHolder)) {
			convertView = LayoutInflater.from(context).inflate(R.layout.item_compound_list, parent, false);
			holder = new ViewHolder();
			holder.iconView = convertView.findViewById(R.id.compound_item_icon);
			holder.titleView = convertView.findViewById(R.id.compound_item_title);
			holder.badgeView = convertView.findViewById(R.id.compound_item_badge);
			holder.subtitleView = convertView.findViewById(R.id.compound_item_subtitle);
			holder.checkView = convertView.findViewById(R.id.compound_item_check);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}

		CompoundItem item = getItem(position);
		if (item == null) return convertView;

		String originalTitle = item.getString();
		Image img = item.getImage();

		if (img != null && img.getBitmap() != null) {
			holder.iconView.setVisibility(View.VISIBLE);
			holder.iconView.setImageBitmap(img.getBitmap());
		} else {
			holder.iconView.setVisibility(View.GONE);
		}

		boolean isTab = TabStatusManager.isTabMenu(listTitle, originalTitle);
		if (isTab) {
			String displayTitle = originalTitle;
			if (displayTitle != null && displayTitle.trim().matches("\\d+")) {
				displayTitle = "Tab " + displayTitle.trim();
			}
			holder.titleView.setText(displayTitle);

			TabStatusManager.TabStatus status = TabStatusManager.getStatus(position, originalTitle, img);
			if (status.isOnline) {
				holder.badgeView.setVisibility(View.VISIBLE);
				holder.badgeView.setText("● ONLINE");
				holder.badgeView.setTextColor(0xFF4CAF50);
				holder.badgeView.setBackgroundResource(R.drawable.badge_bg_online);

				holder.subtitleView.setVisibility(View.VISIBLE);
				holder.subtitleView.setText("💾 RAM: ~" + status.ramUsedMB + "MB  |  ⏱ " +
						TabStatusManager.formatDuration(status.uptimeMs) + "  |  ⚡ ↑" +
						TabStatusManager.formatBytes(status.bytesOut) + " ↓" +
						TabStatusManager.formatBytes(status.bytesIn));
			} else if (status.isRunning) {
				holder.badgeView.setVisibility(View.VISIBLE);
				if (status.wasConnected) {
					holder.badgeView.setText("● MẤT KẾT NỐI");
					holder.badgeView.setTextColor(0xFFEF5350);
					holder.badgeView.setBackgroundResource(R.drawable.badge_bg_offline);

					holder.subtitleView.setVisibility(View.VISIBLE);
					holder.subtitleView.setText("💾 RAM: ~" + status.ramUsedMB + "MB  |  ⚠ Đã ngắt kết nối máy chủ");
				} else {
					holder.badgeView.setText("● ĐANG CHẠY");
					holder.badgeView.setTextColor(0xFFFFA000);
					holder.badgeView.setBackgroundResource(R.drawable.badge_bg_running);

					holder.subtitleView.setVisibility(View.VISIBLE);
					holder.subtitleView.setText("💾 RAM: ~" + status.ramUsedMB + "MB  |  ⏳ Đang mở / Chờ đăng nhập");
				}
			} else {
				holder.badgeView.setVisibility(View.VISIBLE);
				holder.badgeView.setText("○ Chưa chạy");
				holder.badgeView.setTextColor(0xFF9E9E9E);
				holder.badgeView.setBackgroundResource(R.drawable.badge_bg_stopped);

				holder.subtitleView.setVisibility(View.VISIBLE);
				holder.subtitleView.setText("💾 RAM: 0MB  |  Nhấn để chạy tab");
			}
		} else {
			holder.titleView.setText(originalTitle != null ? originalTitle : "");
			holder.badgeView.setVisibility(View.GONE);
			holder.subtitleView.setVisibility(View.GONE);
		}

		if (listType == Choice.EXCLUSIVE) {
			holder.checkView.setVisibility(View.VISIBLE);
			holder.checkView.setCheckMarkDrawable(android.R.drawable.btn_radio);
			if (selector != null) {
				holder.checkView.setChecked(selector.isSelected(position));
			}
		} else if (listType == Choice.MULTIPLE) {
			holder.checkView.setVisibility(View.VISIBLE);
			holder.checkView.setCheckMarkDrawable(android.R.drawable.checkbox_on_background);
			if (selector != null) {
				holder.checkView.setChecked(selector.isSelected(position));
			}
		} else {
			holder.checkView.setVisibility(View.GONE);
		}

		return convertView;
	}

	private static class ViewHolder {
		ImageView iconView;
		TextView titleView;
		TextView badgeView;
		TextView subtitleView;
		CheckedTextView checkView;
	}

	@Override
	public boolean areAllItemsEnabled() {
		return true;
	}

	@Override
	public boolean isEnabled(int position) {
		return true;
	}
}