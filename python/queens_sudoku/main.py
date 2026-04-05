def find_game_area(self):
    """定位游戏地图操作区"""
    if self.screenshot is None:
        self.capture_screenshot()

    height, width, _ = self.screenshot.shape
    # 忽略四边10像素
    start_x, end_x = 10, width - 10
    start_y, end_y = 10, height - 10
    print(f"游戏窗口尺寸: {start_x}, {start_y}, {end_x}, {end_y}")

    COLOR_FFFFFF = (0xFF, 0xFF, 0xFF)
    COLOR_F0F0F0 = (0xF0, 0xF0, 0xF0)

    for y in range(start_y, end_y):
        row = self.screenshot[y, start_x:end_x]
        row_length = len(row)
        threshold_60_percent = int(row_length * 0.6)

        # 从左向右扫描，找到第一个非 COLOR_FFFFFF 的像素
        for x_offset, pixel in enumerate(row):
            pixel_tuple = tuple(pixel)
            if pixel_tuple != COLOR_FFFFFF:
                x1 = start_x + x_offset
                y1 = y

                # 条件1：(x1, y1) 颜色为 COLOR_F0F0F0
                if pixel_tuple != COLOR_F0F0F0:
                    break

                # 条件2：(x1, y1 + 1) 颜色为 COLOR_F0F0F0
                if y1 + 1 >= height:
                    break
                if tuple(self.screenshot[y1 + 1, x1]) != COLOR_F0F0F0:
                    break

                # 条件3：接下来本行长度的 60% 个格子全部为 COLOR_F0F0F0
                if x_offset + threshold_60_percent >= row_length:
                    break
                pixels_60_percent = row[x_offset:x_offset + threshold_60_percent]
                if not np.all(pixels_60_percent == np.array(COLOR_F0F0F0)):
                    break

                # 所有条件满足，计算游戏区域坐标
                x2 = end_x - (x1 - start_x)
                y2 = y1 + (x2 - x1)

                self.game_area = (x1, y1, x2, y2)
                print(f"游戏区域坐标: {self.game_area}")
                return self.game_area

    raise Exception("未找到游戏操作区")
