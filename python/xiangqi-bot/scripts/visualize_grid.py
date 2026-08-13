# /// script
# dependencies = [
#   "opencv-python",
#   "numpy"
# ]
# ///

from pathlib import Path
import cv2
import numpy as np

# ========== 10 行 x 9 列 静态网格中心点坐标矩阵 (NumPy 数组) ==========
GRID_CENTERS_NP = np.array(
    [
        # Row 0 (Y = 680)
        [
            [76.0, 680],
            [192.0, 680],
            [308.0, 680],
            [424.0, 680],
            [540.0, 680],
            [656.0, 680],
            [772.0, 680],
            [888.0, 680],
            [1004.0, 680],
        ],
        # Row 1 (Y = 792)
        [
            [75.0, 792],
            [191.25, 792],
            [307.5, 792],
            [423.75, 792],
            [540.0, 792],
            [656.25, 792],
            [772.5, 792],
            [888.75, 792],
            [1005.0, 792],
        ],
        # Row 2 (Y = 904)
        [
            [74.0, 904],
            [190.5, 904],
            [307.0, 904],
            [423.5, 904],
            [540.0, 904],
            [656.5, 904],
            [773.0, 904],
            [889.5, 904],
            [1006.0, 904],
        ],
        # Row 3 (Y = 1016)
        [
            [73.0, 1016],
            [189.75, 1016],
            [306.5, 1016],
            [423.25, 1016],
            [540.0, 1016],
            [656.75, 1016],
            [773.5, 1016],
            [890.25, 1016],
            [1007.0, 1016],
        ],
        # Row 4 (Y = 1129)
        [
            [72.0, 1129],
            [189.0, 1129],
            [306.0, 1129],
            [423.0, 1129],
            [540.0, 1129],
            [657.0, 1129],
            [774.0, 1129],
            [891.0, 1129],
            [1008.0, 1129],
        ],
        # Row 5 (Y = 1242)
        [
            [71.0, 1242],
            [188.25, 1242],
            [305.5, 1242],
            [422.75, 1242],
            [540.0, 1242],
            [657.25, 1242],
            [774.5, 1242],
            [891.75, 1242],
            [1009.0, 1242],
        ],
        # Row 6 (Y = 1356)
        [
            [70.0, 1356],
            [187.5, 1356],
            [305.0, 1356],
            [422.5, 1356],
            [540.0, 1356],
            [657.5, 1356],
            [775.0, 1356],
            [892.5, 1356],
            [1010.0, 1356],
        ],
        # Row 7 (Y = 1470)
        [
            [69.0, 1470],
            [186.75, 1470],
            [304.5, 1470],
            [422.25, 1470],
            [540.0, 1470],
            [657.75, 1470],
            [775.5, 1470],
            [893.25, 1470],
            [1011.0, 1470],
        ],
        # Row 8 (Y = 1585)
        [
            [68.0, 1585],
            [186.0, 1585],
            [304.0, 1585],
            [422.0, 1585],
            [540.0, 1585],
            [658.0, 1585],
            [776.0, 1585],
            [894.0, 1585],
            [1012.0, 1585],
        ],
        # Row 9 (Y = 1700)
        [
            [67.0, 1700],
            [185.25, 1700],
            [303.5, 1700],
            [421.75, 1700],
            [540.0, 1700],
            [658.25, 1700],
            [776.5, 1700],
            [894.75, 1700],
            [1013.0, 1700],
        ],
    ],
    dtype=np.float32,
)

CROP_SIZE = 100
HALF_CROP = CROP_SIZE / 2.0


def draw_grid_annotations(
    img_path: str = "1.png", output_path: str = "annotated_board.png"
) -> None:
    """在图片上绘制固定 NumPy 坐标矩阵的中心点与切片框并保存"""
    src_img = cv2.imread(img_path)
    if src_img is None:
        raise FileNotFoundError(f"无法读取图片: {img_path}")

    annotated_img = src_img.copy()

    # 直接遍历 10 行 x 9 列 NumPy 矩阵
    for r in range(10):
        for c in range(9):
            cx, cy = GRID_CENTERS_NP[r, c]

            px, py = int(round(cx)), int(round(cy))

            # 1. 绘制切片边框 (绿色方框 100x100)
            x1 = int(round(cx - HALF_CROP))
            y1 = int(round(cy - HALF_CROP))
            x2 = int(round(cx + HALF_CROP))
            y2 = int(round(cy + HALF_CROP))
            cv2.rectangle(annotated_img, (x1, y1), (x2, y2), color=(0, 255, 0), thickness=1)

            # 2. 绘制中心点 (红色实心圆)
            cv2.circle(annotated_img, (px, py), radius=5, color=(0, 0, 255), thickness=-1)

            # 3. 标注行列号 (黄色小字)
            label = f"{r},{c}"
            cv2.putText(
                annotated_img,
                label,
                (px - 15, py - 8),
                fontFace=cv2.FONT_HERSHEY_SIMPLEX,
                fontScale=0.4,
                color=(0, 255, 255),
                thickness=1,
                lineType=cv2.LINE_AA,
            )

    cv2.imwrite(output_path, annotated_img)
    print(f"标注完成！结果已保存至: {Path(output_path).resolve()}")


if __name__ == "__main__":
    draw_grid_annotations()
