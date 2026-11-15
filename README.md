# Pose Guide

App Android gợi ý tạo dáng, góc máy và bố cục khi chụp ảnh. Chạy hoàn toàn offline.

## Tính năng
- **Quét bối cảnh mỗi 500 ms** (ML Kit Image Labeling): biển, thiên nhiên, phố, quán cà phê, cầu thang, tường trơn
- **Nhận diện vật thể xung quanh** (ghế, bậc thang, lan can, cửa sổ, cây) → ưu tiên gợi ý dáng tương tác (ngồi bậc thang, tựa lan can, vịn cây…)
- **Phân tích ánh sáng & nền** từ khung hình: nền tối giản, ánh sáng ngang trái/phải, thiếu sáng
- **3–5 thẻ gợi ý dáng**, mỗi thẻ kèm hình minh hoạ và góc máy đề xuất
- **Hướng dẫn góc máy** bằng cảm biến trọng lực: thước bên phải màn hình + lời nhắc "ngửa máy lên thêm 8°" (góc thấp kéo dài chân, ngang ngực, góc cao)
- **Bóng người trắng mờ** đặt ở điểm 1/3; tự đặt về phía nền ít chi tiết hơn để chủ thể nổi bật
- **So khớp khung xương** (ML Kit Pose) → bóng mờ chuyển xanh khi khớp ≥ 85%
- **Tự chụp**: khớp dáng + đúng góc máy ~1,2 giây → đếm ngược 3‑2‑1 → chụp; lệch dáng thì huỷ
- Camera trước/sau, lưới 1/3, thước cân bằng đường chân trời
- Ảnh lưu vào `Pictures/PoseGuide`

## Cách dùng
- Chạm thẻ dáng ở dưới để chọn
- Đổi bên ⇆ : đưa bóng mờ sang 1/3 trái hoặc phải (tắt chế độ tự chọn bên)
- Cam ⟲ : đổi camera trước/sau
- Chạm vào dòng "Cảnh: ..." : tự chọn cảnh (khoá, không tự đổi nữa)

## Kiến trúc
| File | Vai trò |
|---|---|
| `FrameAnalyzer.kt` | CameraX ImageAnalysis: pose mỗi khung, nhãn cảnh + thống kê mỗi 500 ms |
| `FrameStats.kt` | Độ sáng / độ chi tiết theo 3 cột từ kênh Y (ánh sáng ngang, nền trơn, phía nền sạch) |
| `PoseLibrary.kt` | Dáng mẫu, góc máy, vật thể, phân loại cảnh, xếp hạng gợi ý |
| `OverlayView.kt` | Vẽ lưới, thước cân bằng, thước góc máy, bóng mờ, khung xương, đếm ngược; chấm điểm khớp |
| `PoseThumbView.kt` | Hình minh hoạ dáng trong thẻ |
| `MainActivity.kt` | Camera, cảm biến, thẻ gợi ý, logic tự chụp |

## Build
Mỗi lần push, GitHub Actions tự build. Vào tab **Actions**, mở lần chạy mới nhất và tải **PoseGuide-apk** ở mục Artifacts.
