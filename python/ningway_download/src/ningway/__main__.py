"""允许通过 python -m ningway 运行。"""

import sys

from ningway.cli import cmd_download, cmd_update_data


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python -m ningway <command>")
        print("Commands: update-data, download")
        sys.exit(1)

    command = sys.argv[1]
    if command == "update-data":
        cmd_update_data()
    elif command == "download":
        cmd_download()
    else:
        print(f"Unknown command: {command}")
        sys.exit(1)


if __name__ == "__main__":
    main()
