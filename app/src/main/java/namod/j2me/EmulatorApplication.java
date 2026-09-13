package namod.j2me;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.microedition.media.control.ToneControl;
import javax.microedition.util.ContextHolder;

import namod.j2me.util.ConsoleOutput;

public class EmulatorApplication extends Application {
	private static final String[] VALID_SIGNATURES = {
			"78EF7758720A9902F731ED706F72C669C39B765C",
			"289F84A32207DF89BE749481ED4BD07E15FC268F",
			"FA8AA497194847D5715BAA62C6344D75A936EBA6"
	};

	private String bytesToHex(byte[] bArr) {
		char[] cArr = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
		char[] cArr2 = new char[bArr.length * 2];
		for (int i5 = 0; i5 < bArr.length; i5++) {
			int i6 = bArr[i5] & ToneControl.SILENCE;
			int i7 = i5 * 2;
			cArr2[i7] = cArr[i6 >>> 4];
			cArr2[i7 + 1] = cArr[i6 & 15];
		}
		return new String(cArr2);
	}

	@SuppressLint({"PackageManagerGetSignatures"})
	private boolean isSignatureValid() {
		try {
			boolean z4 = true;
			for (Signature signature : Build.VERSION.SDK_INT >= 28 ? getPackageManager().getPackageInfo(getPackageName(), 134217728).signingInfo.getApkContentsSigners() : getPackageManager().getPackageInfo(getPackageName(), 64).signatures) {
				MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
				messageDigest.update(signature.toByteArray());
				if (!Arrays.asList(VALID_SIGNATURES).contains(bytesToHex(messageDigest.digest()))) {
					z4 = false;
				}
			}
			return z4;
		} catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e5) {
			e5.printStackTrace();
			return false;
		}
	}

	@Override
	public void attachBaseContext(Context context) {
		super.attachBaseContext(context);
		ConsoleOutput.init();
		ConsoleOutput.clear();
		ContextHolder.setApplication(this);
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		System.gc();
	}

	@Override
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);
		if (level >= 60) {
			System.gc();
		}
	}
}
