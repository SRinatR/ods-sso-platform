import json
from pathlib import Path

from app.main import app

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    destination = ROOT / "openapi.json"
    destination.write_text(
        json.dumps(app.openapi(), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
