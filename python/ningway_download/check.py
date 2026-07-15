"""代码检查与修复脚本。"""

import subprocess
import sys


def run(cmd: list[str], desc: str) -> bool:
    print(f"\n{'=' * 50}")
    print(f"  {desc}")
    print(f"{'=' * 50}")
    result = subprocess.run(cmd, cwd="src")
    return result.returncode == 0


def main() -> None:
    ok = True

    ok &= run(["uv", "run", "ruff", "check", "--fix", "."], "Ruff Lint (自动修复)")
    ok &= run(["uv", "run", "ruff", "format", "."], "Ruff Format")
    ok &= run(["uv", "run", "ty", "check", "."], "Ty 类型检查")

    print(f"\n{'=' * 50}")
    if ok:
        print("  全部通过")
    else:
        print("  存在问题，请检查上方输出")
    print(f"{'=' * 50}")

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
