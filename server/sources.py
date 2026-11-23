"""Free product-image discovery providers used by GC."""
from __future__ import annotations

import logging
import re
from urllib.parse import quote

import httpx

log = logging.getLogger("gc.sources")
USER_AGENT = "GC-Product-Photo-Finder/1.0 (inventory image discovery)"


def open_food_facts_candidates(ean: str) -> list[dict]:
    """Return an exact-barcode Open Food Facts photo, when one exists."""
    code = re.sub(r"\D", "", str(ean or ""))
    if len(code) not in (8, 12, 13, 14):
        return []
    endpoint = f"https://world.openfoodfacts.org/api/v2/product/{quote(code)}.json"
    try:
        response = httpx.get(
            endpoint,
            params={"fields": "code,product_name,brands,image_front_url,image_url,url"},
            headers={"User-Agent": USER_AGENT},
            timeout=12,
        )
        response.raise_for_status()
        payload = response.json()
        product = payload.get("product") or {}
        # Never accept a neighbouring or fuzzy barcode result.
        if payload.get("status") != 1 or re.sub(r"\D", "", str(product.get("code", ""))) != code:
            return []
        image = product.get("image_front_url") or product.get("image_url")
        if not isinstance(image, str) or not image.startswith("https://"):
            return []
        name = str(product.get("product_name") or "").strip()
        brand = str(product.get("brands") or "").strip()
        title = " ".join(part for part in (brand, name, code) if part)
        return [{
            "image": image,
            "thumbnail": image,
            "title": title,
            "alt": title,
            "url": str(product.get("url") or f"https://world.openfoodfacts.org/product/{code}"),
            "source": "Open Food Facts",
            "query": code,
            "tier": 1,
        }]
    except Exception as exc:
        log.info("Open Food Facts lookup unavailable for barcode %s: %s", code, type(exc).__name__)
        return []


def commons_candidates(query: str, max_results: int = 8) -> list[dict]:
    """Search Wikimedia Commons for openly hosted image candidates."""
    query = " ".join(str(query or "").split())[:180]
    if len(query) < 3:
        return []
    try:
        response = httpx.get(
            "https://commons.wikimedia.org/w/api.php",
            params={
                "action": "query",
                "generator": "search",
                "gsrsearch": query,
                "gsrnamespace": 6,
                "gsrlimit": max(1, min(max_results, 10)),
                "prop": "imageinfo",
                "iiprop": "url",
                "iiurlwidth": 1200,
                "format": "json",
            },
            headers={"User-Agent": USER_AGENT},
            timeout=15,
        )
        response.raise_for_status()
        pages = (response.json().get("query") or {}).get("pages") or {}
        found = []
        for page in pages.values():
            info = (page.get("imageinfo") or [{}])[0]
            image = info.get("thumburl") or info.get("url")
            if not isinstance(image, str) or not image.startswith("https://"):
                continue
            title = str(page.get("title") or "").replace("File:", "").strip()
            found.append({
                "image": image,
                "thumbnail": image,
                "title": title,
                "alt": title,
                "url": str(info.get("descriptionurl") or "https://commons.wikimedia.org/"),
                "source": "Wikimedia Commons",
                "query": query,
                "tier": 4,
            })
        return found
    except Exception as exc:
        log.info("Wikimedia Commons search unavailable: %s", type(exc).__name__)
        return []
