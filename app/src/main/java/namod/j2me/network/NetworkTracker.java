package namod.j2me.network;

import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworkTracker {
	public static class SocketInfo {
		public final Socket socket;
		public final String host;
		public final int port;
		public final int tabIndex; // 0 for Tab 1, 1 for Tab 2, etc. Or -1 if unknown
		public final long connectTime;
		public final AtomicLong bytesRead = new AtomicLong(0);
		public final AtomicLong bytesWritten = new AtomicLong(0);
		public volatile long lastActiveTime;
		public volatile boolean closed = false;

		public SocketInfo(Socket socket, String host, int port, int tabIndex) {
			this.socket = socket;
			this.host = host;
			this.port = port;
			this.tabIndex = tabIndex;
			this.connectTime = System.currentTimeMillis();
			this.lastActiveTime = connectTime;
		}

		public boolean isOnline() {
			return !closed && socket != null && socket.isConnected() && !socket.isClosed();
		}
	}

	private static final Map<Object, SocketInfo> socketMap = new ConcurrentHashMap<>();
	private static final List<SocketInfo> allSockets = new CopyOnWriteArrayList<>();

	public static void registerSocket(Object connection, Socket socket, String host, int port) {
		int tabIndex = detectTabIndexFromStack();
		SocketInfo info = new SocketInfo(socket, host, port, tabIndex);
		socketMap.put(connection, info);
		allSockets.add(info);
	}

	public static void unregisterSocket(Object connection) {
		SocketInfo info = socketMap.remove(connection);
		if (info != null) {
			info.closed = true;
		}
	}

	public static void onBytesRead(Object connection, long bytes) {
		SocketInfo info = socketMap.get(connection);
		if (info != null) {
			info.bytesRead.addAndGet(bytes);
			info.lastActiveTime = System.currentTimeMillis();
		}
	}

	public static void onBytesWritten(Object connection, long bytes) {
		SocketInfo info = socketMap.get(connection);
		if (info != null) {
			info.bytesWritten.addAndGet(bytes);
			info.lastActiveTime = System.currentTimeMillis();
		}
	}

	private static int detectTabIndexFromStack() {
		try {
			StackTraceElement[] stack = Thread.currentThread().getStackTrace();
			Pattern pattern = Pattern.compile("package(\\d+)");
			for (StackTraceElement elem : stack) {
				String className = elem.getClassName();
				Matcher m = pattern.matcher(className);
				if (m.find()) {
					int pkgNum = Integer.parseInt(m.group(1));
					return pkgNum - 1; // package1 -> index 0 (Tab 1)
				}
			}
		} catch (Throwable ignored) {
		}
		return -1;
	}

	public static SocketInfo getSocketInfoForTab(int tabIndex) {
		// First try to find socket matching exact tabIndex that is currently online
		for (int i = allSockets.size() - 1; i >= 0; i--) {
			SocketInfo info = allSockets.get(i);
			if (info.tabIndex == tabIndex && info.isOnline()) {
				return info;
			}
		}
		// Then find any socket matching exact tabIndex
		for (int i = allSockets.size() - 1; i >= 0; i--) {
			SocketInfo info = allSockets.get(i);
			if (info.tabIndex == tabIndex) {
				return info;
			}
		}
		// Fallback: if tabIndex matches online socket order
		int onlineCount = 0;
		for (SocketInfo info : allSockets) {
			if (info.isOnline()) {
				if (onlineCount == tabIndex) return info;
				onlineCount++;
			}
		}
		return null;
	}

	public static int getActiveSocketCount() {
		int count = 0;
		for (SocketInfo info : allSockets) {
			if (info.isOnline()) count++;
		}
		return count;
	}
}
