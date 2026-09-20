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
  * Thiết kế: Header gradient Dark Glassmorphism, nút Tab dạng viên thuốc (Pill) xanh ngọc, nút kéo grip handle hiện đại.

### 3.6. Bảng Điều Khiển Log (Log Console & Tự Động Dọn Dẹp)
* **Tệp:** [`app/src/main/java/namod/j2me/util/ConsoleOutput.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/namod/j2me/util/ConsoleOutput.java)
  * **Cơ chế tự động dọn (Rolling Trim):** Giới hạn cứng `MAX_LOG_SIZE = 30000` (khoảng 30KB ký tự). Khi log vượt quá 30.000 ký tự, hệ thống **tự động cắt bỏ các dòng log cũ nhất** (`sb.delete(0, sb.length() - MAX_LOG_SIZE)`), bảo đảm bộ đệm log không bao giờ phình to gây tràn RAM.
  * **Tự động làm sạch khi thoát/mở:** Được gọi `ConsoleOutput.clear()` tại `EmulatorApplication.attachBaseContext()` và `MicroActivity.onDestroy()`.
* **Tệp:** [`app/src/main/java/namod/j2me/util/LogConsoleDialogFragment.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/namod/j2me/util/LogConsoleDialogFragment.java)
  * Cung cấp nút "Xóa" (Clear) để người dùng chủ động xóa trắng log bất cứ lúc nào.

### 3.7. Thiết Kế Giao Diện & Trải Nghiệm Mượt Mà (UI/UX)
* **Danh sách game:** Dạng thẻ Card bo góc 12dp (`bg_card_jar.xml`), icon game bo viền 10dp (`bg_game_icon.xml`), hiệu ứng phản hồi chạm Ripple mượt mà.
* **Cửa sổ nổi & Tab:** Header 38dp tông màu xám kim loại sang trọng (`bg_floating_header.xml`), nút TAB dạng viên thuốc Gradient (`bg_tab_pill.xml`), icon bong bóng tròn viền phát sáng (`bubble_background.xml`).
* **Hiệu năng giao diện:** Không sử dụng divider thô cứng, padding tối ưu để không bị che khuất bởi nút FAB, loại bỏ overdraw thừa.

### 3.8. Multi-Tab (Multi-Slot Architecture — Theo Kiến Trúc NST)
* **Tham chiếu:** [`J2ME_NST_src/sources/`](file:///c:/Users/Admin/Documents/j2me/J2ME_NST_src/sources/) — mã nguồn decompile của J2ME NST làm tham khảo kiến trúc multi-slot.
* **Kiến trúc tổng quan:**
  * **`SlotSession`:** Mỗi tab game = 1 `SlotSession` chứa: `Display` riêng, `EventQueue` riêng, `MicroLoader` riêng, `MidletThread` riêng, `Displayable current`, `container (SlotCell)`, `dataDir`.
  * **`SlotRegistry`:** Quản lý tất cả `SlotSession` qua `InheritableThreadLocal`. Mỗi thread (game thread, networking thread) kế thừa session binding từ thread cha.
  * **`SlotCell`:** `FrameLayout` riêng cho mỗi slot 1+, chứa Canvas/View của game slot đó.

#### 3.8.1. Khởi tạo Slot (launchSlot)
* **Tệp:** [`MicroActivity.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/shell/MicroActivity.java)
* **Slot 0:** Dùng `microLoader` đã tạo sẵn trong `onCreate()` (đã `init()` + `applyConfiguration()` rồi). **KHÔNG tạo MicroLoader mới** — tránh double init gây màn đen.
* **Slot 1+:** Tạo `MicroLoader` MỚI → `init()` → `applyConfiguration()` → set `VirtualKeyboard.setView(overlayView)` → `MidletThread.create()`.
* **Data Directory:**
  * Slot 0: `/J2ME-Loader/data/` (mặc định)
  * Slot 1: `/J2ME-Loader/data2/`
  * Slot 2: `/J2ME-Loader/data3/`
  * **Lần đầu tạo:** Copy RMS data từ slot 0 (`copyRmsData`) để có config cơ bản (ngôn ngữ, server...). **Lần sau:** Giữ nguyên data riêng của slot.

#### 3.8.2. MidletThread Per-Slot
* **Tệp:** [`MidletThread.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/shell/MidletThread.java)
* Mỗi slot có 1 `MidletThread` (`HandlerThread`) riêng, tên `MidletMain-{slot}`.
* **Quan trọng:** Trong `handleMessage()`, luôn gọi `SlotRegistry.bind(session)` ĐẦU TIÊN trước khi xử lý message.
* Child threads (networking, game logic) kế thừa session binding qua `InheritableThreadLocal`.

#### 3.8.3. Display Per-Slot
* **Tệp:** [`Display.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/lcdui/Display.java)
* Mỗi `SlotSession` sở hữu 1 `Display` instance riêng.
* `Display.getDisplay()` lấy Display từ `SlotRegistry.current().getDisplay()` thay vì static singleton.
* `Display.postEvent()` gửi event vào `session.eventQueue()` thay vì global EventQueue.

#### 3.8.4. Canvas FPS Throttling (Multi-Slot)
* **Tệp:** [`Canvas.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/lcdui/Canvas.java) — hàm `limitFps()`
* **Focused slot:** Luôn chạy **full FPS** (60 FPS), bất kể `isShown()` trả gì.
* **Background slot (tab nền):** Giới hạn **2 FPS** — game loop chạy 2 lần/giây (đủ bot + network), xóa cache Image/Font ngay khi ẩn, không render gì cả (`flushBuffer` return early).
* **App bị ẩn hoàn toàn (tắt màn hình):** Giới hạn **3-5 FPS** + xóa cache Image/Font.
* **Thứ tự ưu tiên:** `isBackgroundSlot` → `!isAppVisible` → autoSleep → bình thường.
* **AngelChip tabs:** Tất cả chạy trong 1 slot → game JAR tự quản lý nội bộ, emulator không can thiệp từng tab riêng.

#### 3.8.5. VirtualKeyboard Null Safety
* **Tệp:** [`VirtualKeyboard.java`](file:///c:/Users/Admin/Documents/j2me/J2MELoader-namod/app/src/main/java/javax/microedition/lcdui/pointer/VirtualKeyboard.java)
* `repaint()` phải kiểm tra `if (overlayView != null)` trước khi gọi `postInvalidate()`.
* Khi tạo slot mới, sau `applyConfiguration()` phải gọi `vk.setView(overlayView)` để gán OverlayView cho VK.

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
6. **Double Init gây màn đen (Multi-Tab):**
   * *Nguyên nhân:* Gọi `MicroLoader.init()` + `applyConfiguration()` 2 lần cho slot 0 (1 lần trong `onCreate()`, 1 lần trong `launchSlot()`). `init()` reset Display/Graphics3D → Canvas bị mất reference → màn đen.
   * *Giải pháp:* Slot 0 dùng `microLoader` đã tạo trong `onCreate()`. Chỉ slot 1+ mới tạo MicroLoader mới.
7. **Focused Slot bị throttle FPS (Multi-Tab):**
   * *Nguyên nhân:* Slot mới vừa tạo, Canvas chưa attach vào View hierarchy → `View.isShown()` trả `false` → `limitFps()` throttle về 3 FPS → game lag đơ, network timeout, đăng nhập thất bại.
   * *Giải pháp:* Trong `limitFps()`, kiểm tra `isFocusedSlot` — nếu đúng thì KHÔNG throttle, luôn chạy full FPS.
8. **VirtualKeyboard NPE khi chạm màn hình (Multi-Tab):**
   * *Nguyên nhân:* `applyConfiguration()` tạo VK mới nhưng chưa gọi `vk.setView(overlayView)`. Khi user chạm → `VK.repaint()` → `overlayView.postInvalidate()` → NPE crash.
   * *Giải pháp:* Sau `applyConfiguration()` cho slot mới, gọi `vk.setView(findViewById(R.id.vOverlay))`. Thêm null check trong `repaint()`.
9. **Tab mới = dữ liệu mới hoàn toàn (Multi-Tab):**
   * Tab mới **KHÔNG copy RMS từ tab 1**. Mỗi tab bắt đầu với data trống, game tự hiện setup lần đầu (chọn server, ngôn ngữ, đăng nhập/đăng ký).
   * Thư mục data: slot 0 → `/data/`, slot 1 → `/data2/`, slot 2 → `/data3/`...
   * **Quan trọng:** `ContextHolder.getFileByName()` phải tự tạo thư mục parent (`parent.mkdirs()`) khi chưa tồn tại, nếu không game sẽ crash `FileNotFoundException` khi cố lưu RMS lần đầu.
10. **Event routing sai khi click nút trên UI thread (Floating Bubble):**
    * *Nguyên nhân:* `Displayable.menuItemSelected()` gọi `Display.postEvent()` → dùng `SlotRegistry.current()` (ThreadLocal). Nút OK/Cancel trong FloatingBubbleService click trên **UI thread** không có slot binding → event đi vào **fallback static queue** → game không bao giờ nhận command → TextBox/List kẹt vĩnh viễn.
    * *Giải pháp:* Trong `menuItemSelected()`, fallback sang `SlotRegistry.focused()` khi `SlotRegistry.current()` trả `null`.
11. **TextBox/Form nền trong suốt nhìn xuyên thấy slot khác (Multi-Tab):**
    * *Nguyên nhân:* Slot 1+ dùng `SlotCell` (FrameLayout) nằm trên `layout` chứa slot 0's Canvas. TextBox/Form có background trong suốt → nhìn xuyên qua → thấy game tab 1 phía dưới.
    * *Giải pháp:* Trong `applySetCurrent()`, khi displayable KHÔNG phải Canvas: `targetContainer.setBackgroundColor(0xFF000000)`. Khi Canvas: reset về `0x00000000`.
    * *Floating mode:* Tương tự trong `performUpdateDisplayable()`: set `0xFF263238` cho non-Canvas.
12. **Floating Bubble bị throttle FPS (limitFps):**
    * *Nguyên nhân:* `MicroActivity.onPause()` → `visible = false`. `limitFps()` kiểm tra `act.isVisible()` → `false` → throttle tất cả slot xuống 3-5 FPS → lag trong floating bubble.
    * *Giải pháp:* `isAppVisible = act.isVisible() || Displayable.isFloatingMode`. Floating mode = app vẫn "hiển thị".
13. **Floating Bubble chuyển slot cần ACTION_UPDATE_DISPLAYABLE:**
    * *Nguyên nhân:* `selectSlot()` chỉ thay đổi visibility SlotCell trong Activity layout. Floating window có `game_container` riêng, không tự cập nhật.
    * *Giải pháp:* Sau `selectSlot()`, gửi Intent `ACTION_UPDATE_DISPLAYABLE` với `slot_index` extra để FloatingBubbleService lấy đúng displayable từ đúng session.
    * `performUpdateDisplayable(slotIndex)`: nếu `slotIndex >= 0`, lấy displayable từ `SlotRegistry.get(slotIndex).getCurrent()` thay vì `MidletThread.getCurrentDisplayable()`.
14. **Đơ khi chuyển tab sau khi quay về từ Floating Bubble (Multi-Tab):**
    * *Nguyên nhân 1:* `hideFloatingWindow()` gọi `clearDisplayableView()` → destroy + recreate SurfaceView → đơ.
    * *Giải pháp:* **KHÔNG gọi `clearDisplayableView()`** trong `hideFloatingWindow()`. Chỉ di chuyển view hiện có về đúng slot container.
    * *Nguyên nhân 2:* `MicroActivity.onResume()` gửi `ACTION_HIDE_WINDOW` → tạo service MỚI (cái cũ đã `stopSelf()`) → `hideFloatingWindow()` lần 2 trên service không có context → lỗi view.
    * *Giải pháp:* Chỉ gửi `ACTION_HIDE_WINDOW` khi `FloatingBubbleService.getInstance() != null`.
    * *Nguyên nhân 3:* `applySetCurrent()` luôn `clearDisplayableView()` + `getDisplayableView()` → recreate SurfaceView mỗi lần.
    * *Giải pháp:* Chỉ clear + recreate khi `displayableView.getParent() != targetContainer`.
15. **`hideFloatingWindow()` phải trả view về đúng slot (Multi-Tab):**
    * Sử dụng `session.getContainer()` thay vì `displayable_container` cố định.
    * Trả view cho TẤT CẢ slots (loop `SlotRegistry.all()`), không chỉ focused slot.
    * Skip view nếu `displayableView.getParent() == targetContainer` (đã đúng chỗ).
16. **Chuyển tab mượt mà (Crossfade Animation):**
    * `showFocusedSlot()` sử dụng crossfade 150ms: `cell.setAlpha(0f)` → `cell.animate().alpha(1f).setDuration(150).start()`.
    * Defer `canvas.repaint()` bằng `canvasView.post(() -> canvas.repaint())` — không block UI thread.
    * Gán `OverlayView` cho Canvas mới khi chuyển tab: `canvas.setOverlayView(overlayView)`.
17. **Ẩn thanh tab bar khi game hiển thị List/TextBox/Form:**
    * Trong `applySetCurrent()`: Canvas → `slotTabBar.setVisibility(View.VISIBLE)`, non-Canvas → `slotTabBar.setVisibility(View.GONE)`.
18. **Xóa dữ liệu game phải xóa cả multi-tab:**
    * `showClearDataDialog()` trong `ConfigActivity`: ngoài `FileUtils.clearDirectory(dataDir)` (slot 0), loop xóa thêm `/data2/` → `/data20/` + `dirName`.
19. **`performUpdateDisplayable()` skip reattach nếu view đã đúng container:**
    * Kiểm tra `displayableView.getParent() != frameLayout` trước khi remove/add. Tránh recreate SurfaceView không cần thiết.
