# Công cụ lồng tiếng TikTok — Windows

Ứng dụng nhận link TikTok hoặc MP4, nhận diện lời nói, dịch sang tiếng Việt, tạo giọng Việt và xuất MP4. Mặc định toàn bộ AI chạy trên máy, không cần `OPENAI_API_KEY` và không phát sinh phí API.

## Cài lần đầu

Mở PowerShell và chạy từng lệnh:

```powershell
Set-Location D:\tooltiktok\demo
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-tools.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local-ai.ps1
```

Lần đầu cần tải khoảng 2 GB gồm Whisper.cpp, mô hình nhận diện, Ollama, `qwen3:1.7b` và Piper. Các file được lưu trong `tools\` để những lần sau dùng lại.

## Chạy ứng dụng

```powershell
Set-Location D:\tooltiktok\demo
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build
```

Đợi dòng `Started DemoApplication`, rồi mở **http://127.0.0.1:8080**. Không cần điền khóa truy cập cục bộ hoặc khóa OpenAI. Giữ cửa sổ PowerShell đang chạy; nhấn `Ctrl+C` để dừng.

Trên giao diện:

1. Dán đúng một link video TikTok hoặc chọn một file MP4.
2. Chọn một trong 65 giọng Việt và tích xác nhận bạn có quyền sử dụng video.
3. Bấm **Bắt đầu lồng tiếng** và đợi trạng thái **Hoàn tất MP4**.
4. Bấm **Xem trước** hoặc **Tải MP4**; kết quả có phụ đề tiếng Việt.

Nên thử trước với video 10–30 giây, lời nói rõ. Máy không có GPU vẫn chạy được nhưng sẽ chậm. Âm thanh đầu ra thay toàn bộ audio gốc bằng giọng Việt. Với link TikTok, ứng dụng ưu tiên luồng không watermark khi TikTok cung cấp; không thể bảo đảm mọi video đều có luồng này. Chữ tiếng Anh đã dính sẵn vào hình ảnh nguồn không được xóa tự động.

## Chạy kiểm thử

```powershell
Set-Location D:\tooltiktok\demo
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
mvn clean test
```

Kiểm tra nhanh khi ứng dụng đang chạy:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

Kết quả cần có `aiProvider` bằng `local` và `aiConfigured` bằng `True`.

## Thành phần và giới hạn

- Whisper.cpp nhận diện và tạo mốc thời gian.
- Ollama với `qwen3:1.7b` dịch sang tiếng Việt.
- Piper với giọng `vi_VN-vivos-x_low` tạo giọng Việt.
- FFmpeg ghép giọng vào video; giới hạn mặc định là 100 MB, 180 giây và 60 đoạn lời nói.
- Giọng Piper `vivos` có giấy phép CC BY-NC-SA 4.0, phù hợp mô hình học tập phi thương mại. Cần chọn giọng có giấy phép phù hợp trước khi dùng thương mại.
- Server chỉ nghe trên `127.0.0.1`; phần truy cập từ iPhone/LAN để giai đoạn sau.

Dữ liệu và MP4 kết quả nằm trong `data\`. Video lỗi được giữ để có thể thử lại.

## Kết nối và đăng trực tiếp lên TikTok

TikTok yêu cầu một ứng dụng Developer riêng; tài khoản TikTok thông thường không cung cấp Client key/secret. Trên [TikTok for Developers](https://developers.tiktok.com/apps/), tạo ứng dụng Desktop, thêm **Login Kit** và **Content Posting API**, bật **Direct Post**, xin scope `video.publish`, rồi đăng ký Redirect URI chính xác:

```text
http://127.0.0.1:8080/api/tiktok/callback
```

Chạy trình cấu hình và dán Client key/secret khi được hỏi. Client secret được nhập ẩn và các giá trị được lưu cho tài khoản Windows hiện tại:

```powershell
Set-Location D:\tooltiktok\demo
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-tiktok.ps1
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build
```

Trên giao diện, bấm **Kết nối tài khoản**, đồng ý quyền đăng video, rồi dùng nút **Đăng lên TikTok** ở video hoàn tất. TikTok bắt buộc chọn quyền riêng tư và xác nhận cho từng lần đăng. Ứng dụng Developer chưa qua audit chỉ đăng ở chế độ `SELF_ONLY`; TikTok kiểm duyệt ứng dụng trước khi cho đăng công khai.

## Tùy chọn dùng OpenAI

Chỉ dùng khi bạn chủ động muốn gọi API có tính phí:

```powershell
$env:AI_PROVIDER = 'openai'
$env:OPENAI_API_KEY = 'khóa-mới-của-bạn'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build
```

Tài liệu: [Whisper.cpp](https://github.com/ggml-org/whisper.cpp), [Ollama trên Windows](https://ollama.readthedocs.io/en/windows/), [Piper](https://github.com/OHF-Voice/piper1-gpl), [FFmpeg](https://ffmpeg.org/ffmpeg.html), [TikTok Login Kit](https://developers.tiktok.com/docs/en/login-kit-desktop), [TikTok Content Posting API](https://developers.tiktok.com/docs/en/content-posting-api-get-started).
