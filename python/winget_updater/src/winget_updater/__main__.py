"""支持 python -m winget_updater 运行。"""

import sys

from winget_updater.cli import main

if __name__ == "__main__":
    sys.exit(main())
