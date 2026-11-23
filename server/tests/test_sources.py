import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import sources


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


def test_open_food_facts_requires_exact_ean(monkeypatch):
    def fake_get(*args, **kwargs):
        return FakeResponse({
            "status": 1,
            "product": {
                "code": "8901234567890",
                "product_name": "Example cereal",
                "brands": "Example",
                "image_front_url": "https://images.openfoodfacts.org/front.jpg",
                "url": "https://world.openfoodfacts.org/product/8901234567890",
            },
        })

    monkeypatch.setattr(sources.httpx, "get", fake_get)
    result = sources.open_food_facts_candidates("8901234567890")
    assert len(result) == 1
    assert result[0]["image"].endswith("front.jpg")
    assert "8901234567890" in result[0]["url"]
    assert result[0]["source"] == "Open Food Facts"


def test_open_food_facts_rejects_mismatched_barcode(monkeypatch):
    monkeypatch.setattr(
        sources.httpx,
        "get",
        lambda *args, **kwargs: FakeResponse({
            "status": 1,
            "product": {
                "code": "8900000000000",
                "product_name": "Different item",
                "image_front_url": "https://images.openfoodfacts.org/front.jpg",
            },
        }),
    )
    assert sources.open_food_facts_candidates("8901234567890") == []


def test_open_food_facts_skips_invalid_ean():
    assert sources.open_food_facts_candidates("not-a-barcode") == []


def test_commons_candidates_normalizes_image_metadata(monkeypatch):
    monkeypatch.setattr(
        sources.httpx,
        "get",
        lambda *args, **kwargs: FakeResponse({
            "query": {
                "pages": {
                    "1": {
                        "title": "File:Example product.jpg",
                        "imageinfo": [{
                            "thumburl": "https://upload.wikimedia.org/example-thumb.jpg",
                            "descriptionurl": "https://commons.wikimedia.org/wiki/File:Example_product.jpg",
                        }],
                    }
                }
            }
        }),
    )
    result = sources.commons_candidates("Example product")
    assert len(result) == 1
    assert result[0]["image"].endswith("example-thumb.jpg")
    assert result[0]["source"] == "Wikimedia Commons"
