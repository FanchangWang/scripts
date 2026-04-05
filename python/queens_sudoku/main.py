import win32gui
import win32ui
import win32con
import numpy as np
from PIL import Image
import pyautogui
import os
import sys
from ctypes import windll
import traceback

def print_exception_with_line():
    """自定义异常处理，打印完整行号"""
    exc_type, exc_value, exc_tb = sys.exc_info()
    print("\n" + "="*50)
    print("错误详情：")
    print("="*50)
    traceback.print_exception(exc_type, exc_value, exc_tb)
    print("="*50)

# 启用DPI感知
if sys.platform == 'win32':
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # PROCESS_PER_MONITOR_DPI_AWARE
    except:
        pass

class QueensSudokuHelper:
    def __init__(self, window_title="智商不够别点"):
        self.window_title = window_title
        self.hwnd = None
        self.screenshot = None
        self.game_area = None  # [x1, y1, x2, y2]
        self.grid_colors = None
        self.color_map = {}  # {color_code: (r, g, b)}
        self.solution = None

    def find_window(self):
        """查找窗口句柄"""
        self.hwnd = win32gui.FindWindow(None, self.window_title)
        print(f"窗口句柄: {self.hwnd}")
        if not self.hwnd:
            raise Exception(f"未找到窗口: {self.window_title}")
        return self.hwnd

    def capture_screenshot(self):
        """使用 PrintWindow 截图，支持后台/最小化窗口"""
        if not self.hwnd:
            self.find_window()

        # 获取客户区尺寸
        left, top, right, bottom = win32gui.GetClientRect(self.hwnd)
        width = right - left
        height = bottom - top

        # 创建设备上下文
        hwndDC = win32gui.GetWindowDC(self.hwnd)
        mfcDC = win32ui.CreateDCFromHandle(hwndDC)
        saveDC = mfcDC.CreateCompatibleDC()

        # 创建位图
        saveBitMap = win32ui.CreateBitmap()
        saveBitMap.CreateCompatibleBitmap(mfcDC, width, height)
        saveDC.SelectObject(saveBitMap)

        # ========== 关键修改：使用 PrintWindow 替代 BitBlt ==========
        # 参数3的含义：
        # 0 = 仅客户区
        # 1 = 整个窗口（含标题栏边框）
        # 2 = 客户区（Win8+，更可靠）
        # 3 = 整个窗口（Win8+，推荐）

        PW_CLIENTONLY = 0
        PW_WINDOW = 1
        PW_RENDERFULLCONTENT = 2  # Win8+
        PW_RENDERFULLCONTENT_WINDOW = 3  # Win8+，整个窗口

        result = windll.user32.PrintWindow(self.hwnd, saveDC.GetSafeHdc(), 3)

        if result == 0:
            print("PrintWindow 失败，尝试 BitBlt 备用...")
            saveDC.BitBlt((0, 0), (width, height), mfcDC, (0, 0), win32con.SRCCOPY)

        # 获取位图数据
        bmpinfo = saveBitMap.GetInfo()
        bmpstr = saveBitMap.GetBitmapBits(True)

        # 转换为 numpy（BGRX 格式）
        img = Image.frombuffer(
            'RGB',
            (bmpinfo['bmWidth'], bmpinfo['bmHeight']),
            bmpstr,
            'raw',
            'BGRX',
            0,
            1
        )

        # 清理资源
        win32gui.DeleteObject(saveBitMap.GetHandle())
        saveDC.DeleteDC()
        mfcDC.DeleteDC()
        win32gui.ReleaseDC(self.hwnd, hwndDC)

        self.screenshot = np.array(img)
        return self.screenshot

    def find_game_area(self):
        """定位游戏地图操作区"""
        if self.screenshot is None:
            self.capture_screenshot()

        height, width, _ = self.screenshot.shape
        print(f"游戏窗口尺寸: {width}, {height}")

        # 忽略四边10像素
        start_x, end_x = 10, width - 10
        start_y, end_y = 10, height - 10

        COLOR_FF = (0xFF, 0xFF, 0xFF)
        COLOR_F0 = (0xF0, 0xF0, 0xF0)

        for y in range(start_y, end_y):
            if self.game_area is None: # 第一次匹配到为游戏的起始行
                row = self.screenshot[y, start_x:end_x]
                # 从左向右扫描，找到第一个非 COLOR_FFFFFF 的像素
                for x_offset, pixel in enumerate(row):
                    pixel_tuple = tuple(pixel)
                    if pixel_tuple != COLOR_FF:
                        x1 = start_x + x_offset + 1 # 跳过第一个非 FF 的像素（因为不同 n*n 棋盘的边界颜色不同）
                        if x1 >= width: # 到达列末
                            break

                        # 条件：第一个非 F0 右侧像素点颜色为 COLOR_F0
                        if tuple(self.screenshot[y, x1]) != COLOR_F0:
                            # print(f"条件2失败: {pixel_tuple} ({x1}, {y})")
                            break

                        x2 = width - x1
                        # 条件：本行排除左右 FF F2 全部为 COLOR_F0
                        row_f0 = self.screenshot[y, x1:x2]
                        if not np.all(row_f0 == np.array(COLOR_F0)):
                            # print(f"条件3失败: {pixel_tuple} ({x1}, {y}) ({x2}, {y})")
                            break

                        # 所有条件满足，第一次匹配到为游戏的起始行，最后一次匹配到为游戏的结束行
                        print(f"游戏起始行匹配成功: ({x1}, {y}) ({x2}, {y})")
                        self.game_area = [x1, y, x2, 0]
                        break
            else:
                row_f0 = self.screenshot[y, self.game_area[0]:self.game_area[2]]
                if np.all(row_f0 == np.array(COLOR_F0)):
                    # print(f"条件4成功: {y} 更新 y2")
                    self.game_area[3] = y

        if not self.game_area is None:
            print(f"游戏区域坐标: {self.game_area}")
            return self.game_area

        raise Exception("未找到游戏操作区")

    def extract_grid_colors(self):
        """提取棋盘色块信息"""
        x1, y1, x2, y2 = self.game_area
        game_img = self.screenshot[y1:y2, x1:x2]
        height, width, _ = game_img.shape

        COLOR_F0F0F0 = (0xF0, 0xF0, 0xF0)

        # 收集所有色块颜色
        color_dict = {}
        grid_data = []

        # 记录当前行状态：0-背景行，1-棋盘行
        row_stat = 0
        # 逐行扫描
        for y in range(height):
            row = game_img[y]
            if row_stat == 1: # 棋盘行，跳过色块，需要等待背景行出现
                if np.all(row == COLOR_F0F0F0): # 背景行出现
                    row_stat = 0 # 切换到背景行，下次循环开始等待色块
            else: # 背景行，跳过背景，需要等待色块出现
                if np.all(row == COLOR_F0F0F0): # 跳过背景行
                    continue

                # 记录当前列状态：0-背景列(边框)，1-棋盘列(色块)
                col_stat = 0
                # 检测该行色块
                row_colors = []
                i = 0
                while i < width:
                    pixel_tuple = tuple(row[i])
                    if col_stat == 1: # 棋盘列，跳过色块，需要等待背景列出现
                        if pixel_tuple == COLOR_F0F0F0:
                            # 连续 5 个 f0，判断是否为背景列
                            if i + 5 >= width: # 超出边界，跳过
                                break
                            # 检查是否为背景列
                            if np.all(row[i:i+5] == COLOR_F0F0F0):
                                col_stat = 0 # 切换到背景列，下次循环开始等待色块
                                i += 5
                                continue
                        i += 1
                    else: # 背景列，跳过背景，需要等待色块出现
                        # 跳过 f0
                        if pixel_tuple == COLOR_F0F0F0:
                            i += 1
                            continue

                        # 跳过圆角，原理：检查下一行同列色块颜色是否一致
                        # row_colors 为空时才进行检查，避免重复检查
                        if not row_colors:
                            if y + 1 >= height: # 超出边界，跳过
                                break
                            next_row = game_img[y + 1]
                            next_row_pixel_tuple = tuple(next_row[i])
                            if not (pixel_tuple == next_row_pixel_tuple):
                                break

                        # 获取色块颜色，原理：跳过10个像素边框过度，检测第11-20个像素的颜色是否相同
                        if i + 20 >= width: # 超出边界，跳过
                            break
                        pixels_piece = row[i+11:i+20]
                        # pixels_piece[0] 不能为 f0
                        current_color = tuple(pixels_piece[0])
                        if current_color == COLOR_F0F0F0:
                            i += 1
                            continue

                        if not np.all(pixels_piece == pixels_piece[0]):
                            i += 1
                            continue

                        # 找到连续色块
                        col_stat = 1 # 切换到棋盘列，下次循环开始等待色块
                        i += 21
                        if current_color not in color_dict:
                            color_dict[current_color] = len(color_dict)
                        row_colors.append(current_color)

                # 棋盘最小为 4x4
                if row_colors and len(row_colors) >= 4:
                    grid_data.append(row_colors)
                    row_stat = 1 # 切换到棋盘行，下次循环开始等待色块

        # 转换为颜色代码的二维数组
        self.grid_colors = []
        for row in grid_data:
            color_row = []
            for color in row:
                for rgb, code in color_dict.items():
                    if rgb == color:
                        color_row.append(code)
                        break
            self.grid_colors.append(color_row)

        # 构建颜色映射
        self.color_map = {i: color for i, color in enumerate(color_dict)}

        return self.grid_colors, self.color_map

    def check_grid(self):
        """检查棋盘是否有效"""
        if self.grid_colors is None:
            return False
        # color_map 数量应该与 grid_colors 数量一致
        if len(self.color_map) != len(self.grid_colors):
            print(f"color 与 grid 数量不一致! color_map={len(self.color_map)} grid_colors={len(self.grid_colors)}")
            return False
        # 每一行 grid_colors 的数量都应该与 grid_colors 的数量一致
        for row in self.grid_colors:
            if len(row) != len(self.grid_colors):
                print(f"grid 行与列数量不一致! 行={len(row)} 列={len(self.grid_colors)}")
                return False
        return True

    def print_grid(self):
        """打印棋盘"""

        print("颜色映射：")
        for code, rgb in self.color_map.items():
            print(f"{code}:\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end="")
            print(f" ", end="")
        print()

        print("棋盘数字：")
        for row in self.grid_colors:
            for code in row:
                print(f"{code:2d}", end=" ")
            print()

        print("棋盘颜色：")
        for row_idx, row in enumerate(self.grid_colors):
            row_code = len(self.color_map) - row_idx
            print(f"{row_code:2d}", end=" ")
            for color_code in row:
                rgb = self.color_map[color_code]
                print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end="")
                print(f" ", end="")
            print()
            print()
        for col_idx in range(len(self.grid_colors) + 1):
            print(f"{col_idx:2d}", end=" ")
        print()

    def solve_sudoku(self):
        """使用回溯法求解数独游戏"""

        grid = np.array(self.grid_colors)
        n = len(grid)

        solution = []
        used_cols = set()
        used_colors = set()

        def backtrack(row):
            if row == n:
                return True

            for col in range(n):
                # 检查列是否已使用
                if col in used_cols:
                    continue

                color = grid[row][col]

                # 检查颜色是否已使用
                if color in used_colors:
                    continue

                # 检查是否与已放置的小牛相邻
                valid = True
                for r, c in enumerate(solution):
                    if abs(r - row) <= 1 and abs(c - col) <= 1:
                        valid = False
                        break

                if not valid:
                    continue

                # 放置小牛
                solution.append(col)
                used_cols.add(col)
                used_colors.add(color)

                if backtrack(row + 1):
                    return True

                # 回溯
                solution.pop()
                used_cols.remove(col)
                used_colors.remove(color)

            return False

        if backtrack(0):
            self.solution = solution
            return self.solution
        return None

    def solve_sudoku2(self):
        """使用回溯法求解数独游戏（每行每列每颜色各2头牛）"""
        grid = np.array(self.grid_colors)
        n = len(grid)

        solution = []
        col_count = [0] * n
        color_count = {}
        placed_positions = []

        def backtrack(row):
            # 如果当前行已经放了2个，进入下一行
            if len(solution) > row and len(solution[row]) == 2:
                if row + 1 == n:
                    # 检查是否所有列和颜色都恰好有2个
                    for i in range(n):
                        if col_count[i] != 2:
                            return False
                    for c in range(n):
                        if color_count.get(c, 0) != 2:
                            return False
                    return True
                return backtrack(row + 1)

            # 确保 solution 列表长度足够
            while len(solution) <= row:
                solution.append([])

            for col in range(n):
                # 检查该列是否已经有2个小牛
                if col_count[col] >= 2:
                    continue

                color = grid[row][col]

                # 检查该颜色是否已经有2个小牛
                if color_count.get(color, 0) >= 2:
                    continue

                # [删除] 原来在这里的"检查当前行是否已经放过这个颜色"代码块已移除

                # 检查是否与已放置的小牛相邻（周围8格）
                valid = True
                for r, c in placed_positions:
                    if abs(r - row) <= 1 and abs(c - col) <= 1:
                        valid = False
                        break

                if not valid:
                    continue

                # 放置小牛
                solution[row].append(col)
                col_count[col] += 1
                color_count[color] = color_count.get(color, 0) + 1
                placed_positions.append((row, col))

                if backtrack(row):
                    return True

                # 回溯
                solution[row].pop()
                col_count[col] -= 1
                color_count[color] -= 1
                placed_positions.pop()

            return False

        if backtrack(0):
            self.solution = solution
            return self.solution
        return None

    def print_solution(self):
        """打印 solve_sudoku 的解（文字形式+棋盘形式）"""
        if not self.solution:
            print("无解")
            return

        # 文字形式
        print("解（文字形式）：")
        for row, col in enumerate(self.solution):
            color_code = self.grid_colors[row][col]
            rgb = self.color_map[color_code]
            row_code = len(self.color_map) - row
            col_code = col + 1
            print(f"行:{row_code:2d} 列:{col_code:2d} \033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m")

        # 棋盘形式
        print("\n解（棋盘形式）：")
        for row_idx, row in enumerate(self.grid_colors):
            row_code = len(self.color_map) - row_idx
            print(f"{row_code:2d}", end=" ")
            for col_idx, color_code in enumerate(row):
                rgb = self.color_map[color_code]
                if self.solution[row_idx] == col_idx:
                    print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m🐮\033[0m", end=" ")
                else:
                    print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end=" ")
            print()
            print()
        for col_idx in range(len(self.grid_colors) + 1):
            print(f"{col_idx:2d}", end=" ")
        print()

    def print_solution2(self):
        """打印 solve_sudoku2 的解（每行2头牛）"""
        if not self.solution:
            print("无解")
            return

        # 文字形式
        print("解（文字形式）：")
        for row_idx, cols in enumerate(self.solution):
            for col in cols:
                color_code = self.grid_colors[row_idx][col]
                rgb = self.color_map[color_code]
                row_code = len(self.color_map) - row_idx
                col_code = col + 1
                print(f"行:{row_code:2d} 列:{col_code:2d} \033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m")

        # 棋盘形式
        print("\n解（棋盘形式）：")
        for row_idx, row in enumerate(self.grid_colors):
            row_code = len(self.color_map) - row_idx
            print(f"{row_code:2d}", end=" ")
            for col_idx, color_code in enumerate(row):
                rgb = self.color_map[color_code]
                # 检查当前位置是否在 solution 中
                if col_idx in self.solution[row_idx]:
                    print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m🐮\033[0m", end="")
                else:
                    print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end="")
                print(f" ", end="")
            print()
            print()
        for col_idx in range(len(self.grid_colors) + 1):
            print(f"{col_idx:2d}", end=" ")
        print()

    def run(self, mode="1"):
        """运行完整流程"""
        try:
            print("查找窗口...")
            self.find_window()
            print("截图...")
            self.capture_screenshot()
            print("定位游戏区域...")
            self.find_game_area()
            print("提取棋盘信息...")
            self.extract_grid_colors()
            print("打印棋盘...")
            self.print_grid()
            print("检查棋盘...")
            valid = self.check_grid()
            if not valid:
                print("棋盘无效")
                return
            print("棋盘有效")

            # 根据模式求解
            if mode == "2":
                print("求解中（双牛模式）...")
                self.solve_sudoku2()
                self.print_solution2()
            else:
                print("求解中（单牛模式）...")
                self.solve_sudoku()
                self.print_solution()

        except Exception as e:
            print_exception_with_line()


if __name__ == "__main__":
    import sys

    # 解析命令行参数
    mode = "1"

    for arg in sys.argv[1:]:
        if arg == "--2":
            mode = "2"

    # 正常运行模式
    try:
        helper = QueensSudokuHelper()
        helper.run(mode)
    except Exception as e:
        print(f"错误：{e}")
        print("请确保游戏窗口'智商不够别点'已打开")
