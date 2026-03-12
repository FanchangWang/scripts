import logging;
import os
import sys

# 检查并提示安装依赖
try:
    from androguard.misc import AnalyzeAPK
    from androguard.core import androconf
    androconf.DEBUG = False
except ImportError:
    print("❌ 错误: 缺少androguard库")
    print("请使用以下命令安装依赖:")
    print("pip install androguard")
    sys.exit(1)

logging.getLogger("androguard").setLevel(logging.INFO)

class APKAnalyzer:
    def __init__(self, apk_path):
        self.apk_path = apk_path
        self.apk = None
        self.dex = None
        self.vm = None
        self.load_apk()

    def load_apk(self):
        """加载APK文件并初始化分析环境"""
        if not os.path.exists(self.apk_path):
            raise FileNotFoundError(f"APK文件不存在: {self.apk_path}")

        if not self.apk_path.lower().endswith('.apk'):
            raise ValueError("请选择有效的APK文件")

        try:
            self.apk, self.dex, self.vm = AnalyzeAPK(self.apk_path)
            print(f"✓ 成功加载APK: {os.path.basename(self.apk_path)}")
        except Exception as e:
            raise RuntimeError(f"加载APK失败: {str(e)}")

    def get_apk_info(self):
        """获取APK基本信息：包名、文字版本号、数字版本号"""
        if not self.apk:
            raise RuntimeError("APK未加载")

        package_name = self.apk.get_package()
        version_name = self.apk.get_androidversion_name()
        version_code = self.apk.get_androidversion_code()

        return {
            "包名": package_name,
            "文字版本号": version_name,
            "数字版本号": version_code
        }

    def search_strings(self, search_patterns):
        """根据自定义字符串查找类和方法"""
        if not self.dex or not self.vm:
            raise RuntimeError("APK未加载")

        results = {}

        for function_name, string_pattern in search_patterns.items():
            found = []

            # 确保 string_pattern 是列表形式
            patterns = string_pattern if isinstance(string_pattern, list) else [string_pattern]

            # 遍历所有 dex文件
            for dex in self.dex:
                # 获取该 DEX 的所有字符串
                all_strings = dex.get_strings()

                # 遍历所有类
                for clazz in dex.get_classes():
                    class_name = clazz.get_name()

                    # 遍历类中的所有方法
                    for method in clazz.get_methods():
                        method_name = method.get_name()

                        # 获取方法的字节码
                        code = method.get_code()
                        if not code:
                            continue

                        # 获取方法的 Dalvik 字节码
                        bc = code.get_bc()
                        if not bc:
                            continue

                        # 收集该方法中所有的字符串
                        method_strings = set()
                        for instruction in bc.get_instructions():
                            # 检查是否是字符串加载指令 (const-string 或 const-string/jumbo)
                            opcode = instruction.get_op_value()
                            if opcode in (0x1a, 0x1b):  # const-string / const-string/jumbo
                                # 获取字符串索引
                                ref_idx = instruction.get_ref_kind()
                                if ref_idx < len(all_strings):
                                    string = all_strings[ref_idx]
                                    method_strings.add(string)

                        # 检查该方法是否包含所有需要的字符串模式
                        match_count = 0
                        for pattern in patterns:
                            for string in method_strings:
                                if pattern in string:
                                    match_count += 1
                                    break

                        # 如果所有模式都匹配，则添加到结果
                        if match_count == len(patterns):
                            found.append((class_name, method_name))

            results[function_name] = found

        return results


def get_apk_path():
    """获取APK路径，支持命令行参数和拖放"""
    if len(sys.argv) > 1:
        # 从命令行参数获取
        apk_path = sys.argv[1]
    else:
        # 提示用户输入
        apk_path = input("请输入CARWITH APK完整路径（支持拖放）: ").strip()

    # 处理拖放可能带来的引号
    if (apk_path.startswith('"') and apk_path.endswith('"')) or \
       (apk_path.startswith("'") and apk_path.endswith("'")):
        apk_path = apk_path[1:-1]

    return apk_path


def main():
    print("🚗 CARWITH DEXKIT APK分析工具")
    print("=" * 40)

    try:
        # 1. 获取APK路径
        # apk_path = "D:\硬件\车机导航相关 CarWith CarLife CarLink 高德导航\CarWith 测试版\signed_PLATFORM_CarWith_3.8.4-20260306_release_公测到3.26（横屏地图位置可调）.apk"
        apk_path = get_apk_path()
        if not apk_path:
            print("❌ 错误: 未输入APK路径")
            sys.exit(1)

        # 2. 初始化分析器
        analyzer = APKAnalyzer(apk_path)

        # 3. 获取并显示APK基本信息
        print("\n📋 APK基本信息:")
        print("-" * 20)
        apk_info = analyzer.get_apk_info()
        for key, value in apk_info.items():
            print(f"{key}: {value}")

        # 4. 获取搜索模式
        search_patterns = {}
        search_patterns["锁屏设置"] = "setCastingState fail"
        search_patterns["触摸事件"] = "synergy_mode is 0"
        search_patterns["地图缩放"] = ["CastingApplication", "createNotification"]
        search_patterns["版本到期"] = "CarWith内测版本已到期"
        search_patterns["内测版本"] = "您不在此次CarWith内测版本名单中"

        if not search_patterns:
            print("\n没有输入搜索模式，程序结束")
            return

        # 5. 执行字符串搜索
        print("\n🔍 开始搜索字符串...")
        results = analyzer.search_strings(search_patterns)

        # 6. 显示搜索结果
        print("\n📊 搜索结果:")
        print("=" * 40)
        for function_name, found_items in results.items():
            print(f"\n功能名: {function_name}")
            print(f"搜索字符串: {search_patterns[function_name]}")
            print(f"匹配数量: {len(found_items)}")
            print("-" * 20)

            if found_items:
                for class_name, method_name in found_items:
                    print(f"类名: {class_name}")
                    print(f"方法名: {method_name}")
                    print("---")
            else:
                print("未找到匹配项")

        print("\n✅ 分析完成！")

    except KeyboardInterrupt:
        print("\n\n程序已终止")
    except Exception as e:
        print(f"\n❌ 错误: {str(e)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
