# Hướng Dẫn Phát Triển & Kinh Nghiệm Modding J2ME-Loader (Namod)

Tài liệu này tổng hợp toàn bộ kiến trúc, hướng dẫn build, quy trình chỉnh sửa mã nguồn, và các kinh nghiệm quan trọng nhằm tối ưu CPU/RAM, chạy ngầm ổn định và chống văng game (crash/OOM) cho dự án J2ME-Loader Namod.

---

## 1. Cấu Trúc Workspace & Các Thư Mục Chính

* **`J2MELoader-namod/`**: Thư mục mã nguồn chính (đang hoạt động và đồng bộ với Git `origin/master`). Mọi sửa đổi, tính năng mới đều thực hiện tại đây.
* **`J2MELoader-project/` & `J2MELoader-original/`**: Thư mục mã nguồn tham khảo gốc để đối chiếu logic nguyên bản khi cần.
* **`tools/`**: Chứa các script và công cụ hỗ trợ chuyển đổi hoặc giải mã.

---

## 2. Hướng Dẫn Build & Cài Đặt (Build & Deploy)

### 2.1. Yêu cầu môi trường
* **Hệ điều hành:** Windows (PowerShell)
* **JDK:** Java 17 hoặc Java 21
* **Android SDK:** Đường dẫn `$env:LOCALAPPDATA\Android\Sdk` (bao gồm `platform-tools/adb.exe`, `ndk`, `build-tools`).

### 2.2. Lệnh Build
Di chuyển vào thư mục `J2MELoader-namod`:
```powershell
# Build bản Release (Open variant không có Google Play Services phụ thuộc)
.\gradlew assembleOpenRelease

# Hoặc build bản Debug (để test logcat nhanh)
.\gradlew assembleOpenDebug
```
*Output APK Release:* `app/build/outputs/apk/open/release/J2ME_Loader-1.6.7-open-release.apk`

### 2.3. Cài đặt trực tiếp lên điện thoại qua ADB
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\open\release\J2ME_Loader-1.6.7-open-release.apk
```

### 2.4. Quy trình Git Commit & Push
Nhánh cục bộ `main` được đẩy lên nhánh từ xa `master`:
```powershell
git add .
git commit -m "Nội dung cập nhật"
git push origin main:master
```

---

## 3. Kiến Trúc Mã Nguồn & Cách Chỉnh Sửa

### 3.1. Đồ Họa & Vòng Lặp Game (Canvas & CPU Throttling)
* **Tệp:** [`app/src/main/java/javax/microedition/lcdui/Canvas.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/lcdui/Canvas.java)
* **Hàm `limitFps()`:**
  * **Khi tab ẩn / màn hình tắt / ở danh sách tab (`!isShown()`):** Bắt buộc giới hạn FPS về **5 FPS** (`Thread.sleep(200ms)`) hoặc **3 FPS** nếu bật `sleepTab`. Lần đầu ẩn tab (`!wasHidden`), lập tức gọi `Image.clearCache()`, `Font.clearCache()` và `System.gc()`.
  * **Khi tab hiển thị (`isShown() == true`):** Reset `wasHidden = false` và chạy ở FPS cài đặt (mặc định 60 FPS) cho trải nghiệm mượt mà.
  * **Khi máy rảnh rỗi (`autoSleep` sau 3 phút không tương tác):** Tự động đưa về 10 FPS và xả bộ nhớ cache để tiết kiệm pin nếu để máy trên bàn.
* **Hàm `flushBuffer()`:**
  * Luôn gọi `limitFps()` trước khi kiểm tra `if (!isShown()) return;` để tránh hiện tượng vòng lặp game chạy không độ trễ khi ẩn màn hình.

### 3.2. Quản Lý Bộ Nhớ & Phục Hồi RAM (Memory Management)
* **Tệp:** [`app/src/main/java/javax/microedition/lcdui/Image.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/lcdui/Image.java)
  * Sử dụng `LruCache<String, Bitmap> CACHE`.
  * Hàm `clearCache()`: Chỉ gọi `CACHE.evictAll()`, **tuyệt đối không gọi `bitmap.recycle()`** để tránh làm lỗi các bitmap đang được Canvas tham chiếu.
  * **Cơ chế Fallback OOM:** Các hàm `createTransparentImage`, `createImage`, `createRGBImage` đều được bọc `try-catch (OutOfMemoryError)`. Khi bắt được lỗi tràn bộ nhớ, lập tức gọi `clearCache()` + `System.gc()` rồi tạo lại ảnh.
* **Tệp:** [`app/src/main/java/javax/microedition/lcdui/Font.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/lcdui/Font.java)
  * Mảng `fonts[72]` quản lý các phông chữ.
  * Phương thức `getFont()` và `clearCache()` được gán `synchronized` trên `Font.class` để loại bỏ hoàn toàn race condition gây `NullPointerException`.
* **Tệp:** [`app/src/main/java/namod/j2me/EmulatorApplication.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/namod/j2me/EmulatorApplication.java)
  * Lắng nghe sự kiện hệ thống `onLowMemory()` và `onTrimMemory(level)`: Tự động dọn sạch cache Image và Font trước khi yêu cầu `System.gc()`.
* **Tệp:** [`app/src/main/java/javax/microedition/shell/ForegroundService.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/shell/ForegroundService.java)
  * Luồng dọn RAM `performOverkillClean()` được kiểm soát bằng `AtomicBoolean isCleaning` để tránh việc tạo luồng dọn dẹp chồng chéo làm nghẽn CPU.

### 3.3. Chạy Ngầm & Chống Ngắt Kết Nối (Background Hanging)
* **Tệp:** `ForegroundService.java`
  * **`PARTIAL_WAKE_LOCK`:** Giữ cho CPU luôn chạy ở mức tiết kiệm điện khi tắt màn hình, bot tự động đánh và nhặt đồ không bị dừng.
  * **`WifiLock (WIFI_MODE_FULL_HIGH_PERF)`:** Khóa Wi-Fi không cho chuyển sang chế độ ngủ, đảm bảo socket TCP duy trì kết nối tới server game liên tục.
* **Tệp:** [`app/src/main/java/javax/microedition/shell/MicroActivity.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/shell/MicroActivity.java)
  * Trong `onPause()`: Khi `pref_background_run = true`, không gọi `MidletThread.pauseApp()`, game vẫn chạy ngầm bình thường.
  * Trong `onDestroy()`: Chỉ kill process khi `isFinishing() == true` (người dùng chủ động thoát game hoàn toàn).

### 3.4. Chuyển Hướng IP (IP Redirection)
* **Cấu hình Profile:** `ProfileModel.java` lưu trữ `proxyAddr` và `proxyPort`.
* **Khởi tạo:** `MicroLoader.java` nạp vào System Property:
  * `microedition.proxy.addr`
  * `microedition.proxy.port`
* **Xử lý URL:**
  * `javax/microedition/io/Connector.java` & `org/microemu/microedition/io/ConnectorImpl.java`: Hàm `redirectUrl(String url)` tự động thay thế Host & Port theo cài đặt cấu hình trước khi mở kết nối Socket/HTTP.
  * `org/microemu/cldc/socket/SocketConnection.java`: Kiểm tra fallback redirect nếu MIDlet tạo socket thông qua lớp nội bộ.

### 3.5. Cửa Sổ Nổi & Phím Tab
* **Tệp:** [`app/src/main/java/namod/j2me/FloatingBubbleService.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/namod/j2me/FloatingBubbleService.java)
  * Điều khiển bong bóng nổi (Floating Bubble) và cửa sổ game mini.
  * Nút `btn_tab_menu`: Tự động gửi phím `*` (KEY_STAR) để mở Menu danh sách Tab trong game mod Ninja School / J2ME bots.

---

## 4. Kinh Nghiệm Thực Chiến & Lưu Ý Quan Trọng (Best Practices)

1. **Lỗi 100% CPU trên các tab ẩn:**
   * *Nguyên nhân:* Khi ẩn tab, `flushBuffer` bỏ qua lệnh vẽ (`if (!isShown()) return;`). Nếu không bọc lệnh giảm tốc `Thread.sleep()`, vòng lặp `while(running)` của game sẽ quay hàng trăm nghìn lần/giây, đốt 100% CPU core.
   * *Giải pháp:* Luôn kiểm tra `!isShown()` trong `limitFps()` và ép xung về 5 FPS (200ms mỗi nhịp).
2. **Lỗi văng game (NullPointerException) khi dọn Font:**
   * *Nguyên nhân:* Luồng dọn dẹp chạy `Arrays.fill(fonts, null)` đồng thời với luồng game đang gọi `Font.getFont()`. Nếu `getFont()` lấy mảng `fonts[index]` lúc vừa bị gán `null`, game sẽ văng ngay lập tức.
   * *Giải pháp:* Dùng biến cục bộ `Font font = fonts[index]` và khóa `synchronized (Font.class)`.
3. **Lỗi `Canvas: trying to use a recycled bitmap`:**
   * *Tuyệt đối không:* Gọi `.recycle()` trên các bitmap trong bộ nhớ đệm `Image.CACHE`. Hãy để Garbage Collector (GC) tự thu hồi bộ nhớ tự nhiên.
4. **Tràn bộ nhớ ngoài (OutOfMemoryError - OOM):**
   * Trong `AndroidManifest.xml`, luôn phải có `android:largeHeap="true"`.
   * Mọi hàm khởi tạo `Bitmap.createBitmap` lớn đều phải có khối `try-catch (OutOfMemoryError)` để giải phóng bộ nhớ tạm thời trước khi thử lại lần 2.
5. **Chế độ Treo Máy (AFK Mode):**
   * Người dùng treo máy trực tiếp thông qua màn hình danh sách Tab của game mod hoặc ẩn ứng dụng vào nền. **Không thêm lại màn hình đen phủ overlay (AFK Mode cũ)** vì gây khó chịu và dư thừa.
