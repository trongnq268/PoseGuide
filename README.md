# Pose Guide

App Android gợi ý tạo dáng và bố cục khi chụp ảnh. Chạy hoàn toàn offline.

- Nhận diện cảnh (biển, thiên nhiên, phố, quán cà phê) bằng ML Kit Image Labeling
- Hiện **bóng người trắng mờ** (dáng mẫu) đặt ở điểm 1/3 phù hợp với cảnh
- Nhận diện khung xương người thật (ML Kit Pose) và chấm điểm mức khớp với dáng mẫu
- Bóng mờ chuyển xanh khi khớp ≥ 85%, có chế độ **Tự chụp**
- Lưới 1/3 và thước cân bằng đường chân trời
- Ảnh lưu vào `Pictures/PoseGuide`, không có hình mờ

## Cách dùng
- ◀ ▶ : đổi dáng mẫu
- Đổi bên ⇆ : đưa bóng mờ sang 1/3 trái hoặc phải
- Chạm vào dòng "Cảnh: ..." : tự chọn cảnh (khoá, không tự đổi nữa)

## Build
Mỗi lần push, GitHub Actions tự build. Vào tab **Actions**, mở lần chạy mới nhất và tải **PoseGuide-apk** ở mục Artifacts.
