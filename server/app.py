import os
import re
from functools import lru_cache
from typing import Any

from ddgs import DDGS
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="GC DDGS Image Search", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET"],
    allow_headers=["*"],
)

STOP = {
    "the", "and", "for", "with", "toy", "toys", "product", "kids",
    "boys", "girls", "age", "years", "new", "assorted", "remote",
    "control", "car", "vehicle", "color", "colour", "red", "blue",
    "green", "grey", "gray", "black", "white", "pink", "ghz", "mhz",
}

EXPANSIONS = [
    (r"\br\s*/?\s*c\b", "remote control"),
    (r"\brc\b", "remote control"),
    (r"\btrck\b", "truck"),
    (r"\bmnstr\b", "monster"),
    (r"\bhighspd\b", "high speed"),
    (r"\bamphi\b", "amphibious"),
    (r"\bclimbin\b", "climbing"),
    (r"\bwtch\b", "with"),
    (r"\brmt\b", "remote"),
    (r"\bgry\b", "grey"),
    (r"\bblmg\b", "bubble"),
    (r"\bicecream\b", "ice cream"),
    (r"\b2\.4\s*g\b", "2.4 ghz"),
    (r"\b2\.4ghz\b", "2.4 ghz"),
]

def normalize(value: str) -> str:
    text = (value or "").lower()
    for pattern, replacement in EXPANSIONS:
        text = re.sub(pattern, replacement, text)
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", text)).strip()

def tokens(value: str) -> set[str]:
    return {t for t in normalize(value).split() if len(t) >= 3 and t not in STOP}

def relevance(product_name: str, brand: str, title: str, source_url: str) -> int:
    wanted = tokens(f"{brand} {product_name}")
    found = tokens(f"{title} {source_url}")
    if not wanted or not found:
        return 0
    overlap = wanted & found
    distinctive = sum(1 for token in overlap if len(token) >= 4 or any(c.isdigit() for c in token))
    if distinctive == 0:
        return 0
    ratio = len(overlap) / len(wanted)
    if ratio >= 0.70:
        return 100
    if ratio >= 0.50 and distinctive >= 2:
        return 85
    if ratio >= 0.35 and distinctive >= 3:
        return 70
    return 0

def make_queries(name: str, ean: str, article: str, brand: str) -> list[str]:
    clean = normalize(name)
    core = [
        t for t in clean.split()
        if t not in STOP and not re.fullmatch(r"[a-z]*\d+[a-z0-9]*", t)
    ]
    queries: list[str] = []
    if ean.strip():
        queries.append(f'"{ean.strip()}" {brand} {clean}'.strip())
    if article.strip():
        queries.append(f'"{article.strip()}" {brand} {clean}'.strip())
    if clean:
        queries.extend([
            f'site:hamleys.in {brand} {clean}'.strip(),
            f'site:hamleys.com {brand} {clean}'.strip(),
            f'{brand} {" ".join(core)} toy product'.strip(),
        ])
        if len(core) > 3:
            queries.append(" ".join(core[:4]))
    return list(dict.fromkeys(q for q in queries if q))

def run_ddgs(query: str, backend: str) -> list[dict[str, Any]]:
    # DDGS is instantiated per call to avoid sharing mutable session state across requests.
    return DDGS(timeout=12).images(
        query=query,
        region="in-en",
        safesearch="moderate",
        max_results=20,
        backend=backend,
        type_image="photo",
    )

@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "engine": "ddgs"}

@app.get("/api/search-images")
def search_images(
    name: str = Query(default="", max_length=240),
    ean: str = Query(default="", max_length=40),
    article: str = Query(default="", max_length=40),
    brand: str = Query(default="", max_length=100),
) -> dict[str, Any]:
    if not any(x.strip() for x in (name, ean, article)):
        raise HTTPException(status_code=400, detail="Product name, EAN or article is required.")

    candidates: dict[str, dict[str, Any]] = {}
    errors: list[str] = []
    queries = make_queries(name, ean, article, brand)

    # DuckDuckGo is primary. Bing is a DDGS image backend fallback, not a separate API key.
    for backend in ("duckduckgo", "bing"):
        for query in queries:
            try:
                results = run_ddgs(query, backend)
            except Exception as exc:
                errors.append(f"{backend}: {type(exc).__name__}")
                continue

            for item in results:
                image = str(item.get("image") or item.get("thumbnail") or "").strip()
                thumb = str(item.get("thumbnail") or image).strip()
                title = str(item.get("title") or "").strip()
                page_url = str(item.get("url") or "").strip()
                if not image.startswith("https://") or not thumb.startswith("https://"):
                    continue
                score = relevance(name, brand, title, page_url)
                if "hamleys." in page_url.lower():
                    score += 18
                if ean and ean in f"{title} {page_url}":
                    score += 60
                if article and article in f"{title} {page_url}":
                    score += 45
                # Keep the bar deliberately high: never fill five slots with unrelated pictures.
                if score < 70:
                    continue
                entry = {
                    "image": image,
                    "thumbnail": thumb,
                    "title": title or normalize(name),
                    "url": page_url,
                    "source": str(item.get("source") or backend),
                    "score": score,
                }
                previous = candidates.get(image)
                if previous is None or score > previous["score"]:
                    candidates[image] = entry
            if len(candidates) >= 5:
                break
        if len(candidates) >= 5:
            break

    ordered = sorted(candidates.values(), key=lambda x: x["score"], reverse=True)[:5]
    if not ordered and errors:
        raise HTTPException(
            status_code=502,
            detail="DuckDuckGo image search could not complete. " + "; ".join(errors[:2]),
        )
    return {"query": name, "count": len(ordered), "results": ordered}
