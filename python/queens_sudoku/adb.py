import io
import sys
import time
import traceback

import numpy as np
from PIL import Image
from ppadb.client import Client as AdbClient


def print_exception_with_line():
    """自定义异常处理，打印完整行号"""
    exc_type, exc_value, exc_tb = sys.exc_info()
    print("\n" + "=" * 50)
    print("错误详情：")
    print("=" * 50)
    traceback.print_exception(exc_type, exc_value, exc_tb)
    print("=" * 50)


class QueensSudokuHelper:
    def __init__(self, serial="adb-LJSKJJZPU4UWCMSC-chVoIz._adb-tls-connect._tcp"):
        self.serial = serial
        self.device = None
        self.screenshot = None
        self.game_area = None  # [x1, y1, x2, y2]
        self.grid_colors = []  # 地图格子 => 颜色
        self.grid_coords = []  # 地图格子 => 坐标
        self.color_map = {}  # {color_code: (r, g, b)}
        self.solution = None

    def find_device(self):
        """查找移动端"""
        client = AdbClient(host="127.0.0.1", port=5037)
        devices = client.devices()
        match len(devices):
            case 0:
                raise Exception("无已连接的移动端")
            case 1:  # 只有一个设备，默认使用此设备
                self.device = devices[0]
                return self.device
            case _:
                for device in devices:
                    if device.serial == self.serial:
                        self.device = device
                        return self.device
        raise Exception(f"未找到移动端: {self.serial}")

    def capture_screenshot(self):
        """使用 adb 获取手机截图"""
        screencap = self.device.screencap()  # 截图 PNG
        image = Image.open(io.BytesIO(screencap))  # 用 PIL 解码 PNG 二进制数据
        # 转换为 numpy 数组 (H, W, C) 格式
        self.screenshot = np.array(image)
        if self.screenshot.shape[2] == 4:
            self.screenshot = self.screenshot[:, :, :3]  # 只保留 RGB
        return self.screenshot

    def find_game_area(self):
        """定位游戏地图操作区"""
        height, width, _ = self.screenshot.shape
        print(f"窗口尺寸: {width}, {height}")

        # 忽略四边10像素
        start_x, end_x = 10, width - 10
        start_y, end_y = 10, height - 10

        for y in range(start_y, end_y):
            if self.game_area is None:  # 第一次匹配到为游戏的起始行
                row = self.screenshot[y, start_x:end_x]
                # 从左向右扫描，找到第一个非 边框 的像素
                for x_offset, pixel in enumerate(row):
                    if not np.all((pixel == 0xFE) | (pixel == 0xFF)):
                        # 跳过第一个非 FF 的像素（因为不同 n*n 棋盘的边界颜色不同）
                        x1 = start_x + x_offset + 1
                        if x1 >= width * 0.2:  # 到达棋盘列的 1/5 位置
                            break

                        # 条件：第一个非 F0 右侧像素点颜色为 游戏背景
                        if not np.all(
                            (self.screenshot[y, x1] == 0xEE)
                            | (self.screenshot[y, x1] == 0xEF)
                            | (self.screenshot[y, x1] == 0xF0)
                        ):
                            # print(f"非 F0 右侧像素: {pixel_tuple} ({x1}, {y})")
                            break

                        x2 = width - x1
                        # 条件：本行排除左右 边框 后，全部为 游戏背景
                        row_f0 = self.screenshot[y, x1:x2]
                        if not np.all(
                            (row_f0 == 0xEE) | (row_f0 == 0xEF) | (row_f0 == 0xF0)
                        ):
                            # print(f"排除左右: {pixel_tuple} ({x1}, {y}) ({x2}, {y})")
                            break

                        # 所有条件满足，第一次匹配到为游戏的起始行，最后一次匹配到为游戏的结束行
                        print(f"游戏起始行匹配成功: ({x1}, {y}) ({x2}, {y})")
                        self.game_area = [x1, y, x2, 0]
                        break
            else:
                row_f0 = self.screenshot[y, self.game_area[0] : self.game_area[2]]
                if np.all((row_f0 == 0xEE) | (row_f0 == 0xEF) | (row_f0 == 0xF0)):
                    # print(f"条件4成功: {y} 更新 y2")
                    self.game_area[3] = y

        if self.game_area is not None:
            print(f"游戏区域坐标: {self.game_area}")
            return self.game_area

        raise Exception("未找到游戏操作区")

    def extract_grid_colors(self):
        """提取棋盘色块信息"""
        x1, y1, x2, y2 = self.game_area
        game_img = self.screenshot[y1:y2, x1:x2]
        height, width, _ = game_img.shape

        # 收集所有色块颜色
        color_dict = {}

        # 记录当前行状态：0-背景行，1-棋盘行
        row_stat = 0
        # 逐行扫描
        for y in range(height):
            row = game_img[y]
            if row_stat == 1:  # 棋盘行，跳过色块，需要等待背景行出现
                if np.all((row == 0xEE) | (row == 0xEF) | (row == 0xF0)):  # 背景行出现
                    row_stat = 0  # 切换到背景行，下次循环开始等待色块
            else:  # 背景行，跳过背景，需要等待色块出现
                if np.all((row == 0xEE) | (row == 0xEF) | (row == 0xF0)):  # 跳过背景行
                    continue

                # 记录当前列状态：0-背景列(边框)，1-棋盘列(色块)
                col_stat = 0
                # 记录当前行格子 颜色code
                row_colors = []
                # 记录当前行格子 坐标
                row_coords = []
                x = 0
                while x < width:
                    pixel = row[x]
                    if col_stat == 1:  # 棋盘列，跳过色块，需要等待背景列出现
                        if np.all((pixel == 0xEE) | (pixel == 0xEF) | (pixel == 0xF0)):
                            # 连续 5 个 f0，判断是否为背景列
                            if x + 5 >= width:  # 超出边界，跳过
                                break
                            # 检查是否为背景列
                            if np.all(
                                (row[x : x + 5] == 0xEE)
                                | (row[x : x + 5] == 0xEF)
                                | (row[x : x + 5] == 0xF0)
                            ):
                                col_stat = 0  # 切换到背景列，下次循环开始等待色块
                                x += 5
                                continue
                        x += 1
                    else:  # 背景列，跳过背景，需要等待色块出现
                        # 跳过 f0
                        if np.all((pixel == 0xEE) | (pixel == 0xEF) | (pixel == 0xF0)):
                            x += 1
                            continue

                        # 跳过圆角，原理：检查下一行同列色块颜色是否一致
                        # row_colors 为空时才进行检查，避免重复检查
                        # if not row_colors:
                        #     if y + 1 >= height:  # 超出边界，跳过
                        #         break
                        #     next_row = game_img[y + 1]
                        #     next_row_pixel_tuple = tuple(next_row[x])
                        #     if not (pixel_tuple == next_row_pixel_tuple):
                        #         break

                        # 获取色块颜色，原理：跳过10个像素边框过度，检测第11-20个像素的颜色是否相同
                        if x + 20 >= width:  # 超出边界，跳过
                            break
                        # pixels_piece = row[x + 11 : x + 20]
                        # # pixels_piece[0] 不能为 f0
                        # current_color = tuple(pixels_piece[0])
                        # if current_color == COLOR_F0F0F0:
                        #     x += 1
                        #     continue
                        # if not np.all(pixels_piece == pixels_piece[0]):
                        #     x += 1
                        #     continue
                        if y + 2 >= height:  # 超出边界，跳过
                            break
                        next_row = game_img[y + 2]
                        next_pixels_piece = next_row[x + 11 : x + 20]
                        # if y1 + y == 1205:
                        #     print(f"坐标: ({x1 + x}, {y1 + y})")
                        #     print(next_pixels_piece)
                        current_color = tuple(next_pixels_piece[0])
                        if np.all(
                            (next_pixels_piece[0] == 0xEE)
                            | (next_pixels_piece[0] == 0xEF)
                            | (next_pixels_piece[0] == 0xF0)
                        ):
                            x += 1
                            continue
                        if not np.all(next_pixels_piece == next_pixels_piece[0]):
                            x += 1
                            continue

                        # 找到连续色块
                        col_stat = 1  # 切换到棋盘列，下次循环开始等待色块
                        x += 21

                        # 获取 color_code 并记录到 row_colors
                        color_code = 0
                        if current_color in color_dict:  # 已经存在
                            color_code = color_dict[current_color]
                        else:  # 新不存在的颜色
                            color_code = len(color_dict)
                            color_dict[current_color] = color_code
                            self.color_map[color_code] = current_color
                        row_colors.append(color_code)
                        # 记录格子坐标（加上游戏区域偏移）
                        row_coords.append({"x": x1 + x, "y": y1 + y + 20})

                # 棋盘最小为 4x4
                if row_colors and len(row_colors) >= 4:
                    self.grid_colors.append(row_colors)  # 记录当前行格子 颜色code
                    self.grid_coords.append(row_coords)  # 记录当前行格子 坐标
                    row_stat = 1  # 切换到棋盘行，下次循环开始等待色块

        return self.grid_colors, self.color_map

    def check_grid(self):
        """检查棋盘是否有效"""
        # color_map 数量应该与 grid_colors 数量一致
        if len(self.color_map) != len(self.grid_colors):
            print(
                f"color 与 grid 数量不一致! color_map={len(self.color_map)} grid_colors={len(self.grid_colors)}"
            )
            return False
        # 每一行 grid_colors 的数量都应该与 grid_colors 的数量一致
        for row in self.grid_colors:
            if len(row) != len(self.grid_colors):
                print(
                    f"grid 行与列数量不一致! 行={len(row)} 列={len(self.grid_colors)}"
                )
                return False
        return True

    def print_grid(self):
        """打印棋盘"""

        print("颜色映射：")
        for code, rgb in self.color_map.items():
            print(f"{code}:\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end=" ")
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
                print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end=" ")
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
        """打印 solve_sudoku2 的解（每行2头牛）"""
        # 文字形式
        print("解（文字形式）：")
        for row_idx, col_item in enumerate(self.solution):
            row_code = len(self.color_map) - row_idx
            print(f"行:{row_code:2d}", end=" ")
            # 兼容处理 单牛/双牛 模式 解格式
            col_list = col_item if isinstance(col_item, list) else [col_item]
            for col_idx in col_list:
                color_code = self.grid_colors[row_idx][col_idx]
                rgb = self.color_map[color_code]
                col_code = col_idx + 1
                print(
                    f"列:{col_code:2d} \033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m",
                    end=" ",
                )
            print()

        # 棋盘形式
        print("解（棋盘形式）：")
        for row_idx, row in enumerate(self.grid_colors):
            row_code = len(self.color_map) - row_idx
            print(f"{row_code:2d}", end=" ")
            for col_idx, color_code in enumerate(row):
                rgb = self.color_map[color_code]
                # 兼容处理 单牛/双牛 模式 解格式
                solution_list = (
                    self.solution[row_idx]
                    if isinstance(self.solution[row_idx], list)  # 双牛模式
                    else [self.solution[row_idx]]  # 单牛模式
                )
                # 检查当前位置是否在 solution 中
                if col_idx in solution_list:
                    print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m🐮\033[0m", end=" ")
                else:
                    print(f"\033[48;2;{rgb[0]};{rgb[1]};{rgb[2]}m  \033[0m", end=" ")
            print()
            print()
        for col_idx in range(len(self.grid_colors) + 1):
            print(f"{col_idx:2d}", end=" ")
        print()

    def mouse_doubleClick(self, x, y):
        """使用 adb 双击"""
        self.device.shell(f"input tap {x} {y}")
        time.sleep(0.1)
        self.device.shell(f"input tap {x} {y}")
        time.sleep(0.1)
        self.device.shell(f"input tap {x} {y}")

    def exec_solution(self):
        """执行数独游戏（每行2头牛） 支持后台窗口"""
        for row_idx, col_item in enumerate(self.solution):
            # 兼容处理 单牛/双牛 模式 解格式
            col_list = col_item if isinstance(col_item, list) else [col_item]
            for col_idx in col_list:
                coord = self.grid_coords[row_idx][col_idx]
                self.mouse_doubleClick(coord["x"], coord["y"])

    def run(
        self,
        mode="1",
        exec_solution=False,
    ):
        """运行完整流程"""
        try:
            print("查找移动端...")
            self.find_device()
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
                valid = self.solve_sudoku2()
            else:
                print("求解中（单牛模式）...")
                valid = self.solve_sudoku()
            if not valid:
                print("无解")
                return
            print("打印解...")
            self.print_solution()
            # 执行解
            if exec_solution:
                print("执行解...")
                self.exec_solution()
            print("解完成")

        except Exception:
            print_exception_with_line()


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--mode",
        "-m",
        default="1",
        choices=["1", "2"],
        help="模式: 1-单牛[默认], 2-双牛",
    )
    parser.add_argument(
        "--exec_solution",
        "-e",
        default="0",
        choices=["0", "1"],
        help="是否执行解: 0-否[默认], 1-是",
    )
    args = parser.parse_args()

    mode = args.mode
    exec_solution = args.exec_solution == "1"

    # 正常运行模式
    try:
        helper = QueensSudokuHelper()
        helper.run(mode=mode, exec_solution=exec_solution)
    except Exception as e:
        print(f"错误：{e}")
