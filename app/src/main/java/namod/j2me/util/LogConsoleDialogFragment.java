package namod.j2me.util;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import namod.j2me.R;

public class LogConsoleDialogFragment extends DialogFragment implements ConsoleOutput.LogListener {
	private ScrollView scrollView;
	private TextView tvLog;
	private boolean updatePending;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable updateRunnable = this::updateLog;

	private boolean isAtBottom() {
		if (this.scrollView == null || this.scrollView.getChildCount() == 0) {
			return true;
		}
		return this.scrollView.getScrollY() + this.scrollView.getHeight() >= this.scrollView.getChildAt(0).getHeight() - 50;
	}

	private void scrollToBottom() {
		if (this.scrollView != null) {
			this.scrollView.post(() -> this.scrollView.fullScroll(ScrollView.FOCUS_DOWN));
		}
	}

	private void updateLog() {
		if (this.tvLog != null) {
			boolean atBottom = isAtBottom();
			this.tvLog.setText(ConsoleOutput.getLog());
			if (atBottom) {
				scrollToBottom();
			}
		}
		this.updatePending = false;
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		View view = LayoutInflater.from(requireActivity()).inflate(R.layout.dialog_log_console, null);
		this.tvLog = view.findViewById(R.id.tvLog);
		this.scrollView = view.findViewById(R.id.scrollLog);
		if (this.tvLog != null) {
			this.tvLog.setText(ConsoleOutput.getLog());
		}

		AlertDialog dialog = new AlertDialog.Builder(requireActivity())
				.setTitle(R.string.log_console)
				.setView(view)
				.setPositiveButton(android.R.string.ok, null)
				.setNeutralButton(R.string.clear, null)
				.create();

		dialog.setOnShowListener(d -> {
			Button clearBtn = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
			if (clearBtn != null) {
				clearBtn.setOnClickListener(v -> ConsoleOutput.clear());
			}
		});

		return dialog;
	}

	@Override
	public void onLogClear() {
		this.handler.post(() -> {
			if (this.tvLog != null) {
				this.tvLog.setText("");
			}
		});
	}

	@Override
	public void onLogUpdate(String str) {
		if (this.updatePending) {
			return;
		}
		this.updatePending = true;
		this.handler.postDelayed(this.updateRunnable, 200L);
	}

	@Override
	public void onPause() {
		ConsoleOutput.setListener(null);
		this.handler.removeCallbacks(this.updateRunnable);
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		ConsoleOutput.setListener(this);
		scrollToBottom();
	}
}
