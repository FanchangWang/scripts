uv run ruff format .
uv run ruff check .
uv run ty check .
uv run pytest tests/ -v --tb=short -n auto
