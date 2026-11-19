# TunnelGo MVP (Android/Kotlin + Xray)

## Tính năng

- VLESS URI / Reality profile parsing, kiểm tra qua upstream libXray và lưu profile mã hóa cục bộ bằng AES-GCM/Android Keystore.
- Android `VpnService` foreground service, system VPN consent, TUN fd hand-off tới Xray, protected core sockets, start/stop lifecycle và HTTPS health check.
- Android VPN cấp DNS Cloudflare và Google dự phòng; TUN descriptor gốc được đóng sau khi libXray dừng bản sao nội bộ.
- Nhập `.ovpn` để lưu profile. **OpenVPN core chưa được tích hợp** nên profile `.ovpn` chưa thể kết nối.

## Build APK

Cần JDK 17+, Android SDK Platform 35, Build Tools, Android NDK và Go toolchain tương thích upstream libXray. Core AAR phải được tạo trước:

```bash
# Install Go 1.27.3 and Android NDK 28.2.13676358 first.
git clone https://github.com/XTLS/libXray.git
cd libXray
git checkout 5a0f7d8d1c28b34afb5727b04b26a10f6dbac7e3
python3 build/main.py android

# Copy its generated AAR into the TunnelGo project:
mkdir -p /path/to/TunnelGo/app/libs
cp libXray.aar /path/to/TunnelGo/app/libs/libXray.aar

# Build app and unit tests:
cd /path/to/TunnelGo
./gradlew testDebugUnitTest assembleDebug
```

The pinned libXray `go.mod` pins the Xray-core Go module.

Build output: `app/build/outputs/apk/debug/app-debug.apk`. Bản phát hành kèm theo chỉ đóng gói ABI `arm64-v8a` để giảm dung lượng; dùng điện thoại Android 64-bit ARM. APK debug ký bằng debug keystore, chỉ dành cho thử nghiệm.

## Thử VPN

1. Cài APK và mở ứng dụng.
2. Thêm URI `vless://...` từ nhà cung cấp VLESS/Reality mà bạn tin cậy. Không gửi URI riêng cho người khác.
3. Chạm profile → **Kết nối VPN** và chấp nhận hộp thoại quyền VPN Android.
4. Ứng dụng báo **Đã kết nối** chỉ sau khi Xray báo đang chạy và HTTPS health check thành công qua route VPN. Nếu Reality server trả TLS certificate thường, hoặc khóa/SNI/short ID không khớp, profile/server cần nhà cung cấp sửa; đổi DNS trong app không thể khắc phục lỗi xác thực Reality.
5. Chạm profile → **Ngắt VPN** để dừng tunnel.

Profile/server do người dùng cung cấp là bắt buộc; APK không chứa server credentials. Kiểm tra HTTPS yêu cầu mạng và máy chủ có thể truy cập các endpoint health check. Hỗ trợ VLESS là tùy theo URI/config mà phiên bản libXray đi kèm chấp nhận.
