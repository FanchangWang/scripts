import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from stock_save.cli import main

main()
