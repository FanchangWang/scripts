import os
import sys
from pathlib import Path

import tinify
from dotenv import load_dotenv


def main() -> None:
    load_dotenv()

    api_key = os.getenv("TINIFY_API_KEY")
    if not api_key:
        print("Error: TINIFY_API_KEY not found in .env file", file=sys.stderr)
        sys.exit(1)

    tinify.key = api_key  # type: ignore

    src_dir = Path(input("Enter source PNG directory: ").strip())
    dst_dir = Path(input("Enter output directory: ").strip())

    if not src_dir.is_dir():
        print(f"Error: {src_dir} is not a valid directory", file=sys.stderr)
        sys.exit(1)

    dst_dir.mkdir(parents=True, exist_ok=True)

    png_files = list(src_dir.rglob("*.png"))
    if not png_files:
        print("No PNG files found.")
        return

    print(f"Found {len(png_files)} PNG file(s). Starting compression...")

    for png_path in png_files:
        rel_path = png_path.relative_to(src_dir)
        out_path = dst_dir / rel_path
        out_path.parent.mkdir(parents=True, exist_ok=True)

        try:
            source = tinify.from_file(str(png_path))
            source.to_file(str(out_path))
            print(f"  Compressed: {rel_path}")
        except tinify.Error as e:
            print(f"  API error compressing {rel_path}: {e}", file=sys.stderr)
        except OSError as e:
            print(f"  File error compressing {rel_path}: {e}", file=sys.stderr)

    print("Done.")


if __name__ == "__main__":
    main()
