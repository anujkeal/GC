import re
from typing import Any

from ddgs import DDGS
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="GC Product Image Search", version="2.0.0")
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

def score(name: str, brand: str, title: str, source_url: str, ean: str, article: str) -> int:
    haystack = f"{title} {source_url}".lower()
    if ean and ean in haystack:
        return 160
    if article and article in haystack:
        return 145
    wanted = tokens(f"{brand} {name}")
    found = tokens(f"{title} {source_url}")
    if not wanted or not found:
        return 0
    overlap = wanted & found
    distinctive = sum(1 for t in overlap if len(t) >= 4 or any(c.isdigit() for c in t))
    ratio = len(overlap) / len(wanted)
    if distinctive >= 1 and ratio >= 0.70:
        value = 100
    elif distinctive >= 2 and ratio >= 0.50:
        value = 85
    elif distinctive >= 3 and ratio >= 0.35:
        value = 70
    else:
        return 0
    if "hamleys." in source_url.lower():
        value += 18
    return value

def build_queries(name: str, ean: str, article: str, brand: str) -> list[str]:
    clean = normalize(name)
    core = [t for t in clean.split() if t not in STOP and not re.fullmatch(r"[a-z]*\d+[a-z0-9]*", t)]
    queries: list[str] = []
    if ean.strip():
        queries.append(f'"{ean.strip()}"')
    if article.strip():
        queries.append(f'"{article.strip()}"')
    if clean:
        queries.extend([
            f'site:hamleys.in "{clean}"',
            f'site:hamleys.com "{clean}"',
            f'"{brand} {clean}" toy'.strip(),
            f'{brand} {" ".join(core)} product photo'.strip(),
        ])
        if len(core) > 2:
            queries.append(" ".join(core[:5]))
    return list(dict.fromkeys(q for q in queries if q))

def ddgs_images(query: str) -> list[dict[str, Any]]:
    # Current ddgs image search backend is DuckDuckGo.
    return DDGS(timeout=15).images(
        query=query,
        region="in-en",
        safesearch="moderate",
        max_results=30,
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
    for query in build_queries(name, ean, article, brand):
        try:
            results = ddgs_images(query)
        except Exception as exc:
            errors.append(type(exc).__name__)
            continue

        for item in results:
            image = str(item.get("image") or "").strip()
            thumb = str(item.get("thumbnail") or image).strip()
            title = str(item.get("title") or "").strip()
            page = str(item.get("url") or "").strip()
            if not image.startswith("https://") or not thumb.startswith("https://"):
                continue
            relevance = score(name, brand, title, page, ean.strip(), article.strip())
            if relevance < 70:
                continue
            candidates[image] = {
                "image": image,
                "thumbnail": thumb,
                "title": title or normalize(name),
                "url": page,
                "source": page,
                "score": relevance,
            }
        if len(candidates) >= 5:
            break

    ordered = sorted(candidates.values(), key=lambda x: x["score"], reverse=True)[:5]
    if not ordered and errors:
        raise HTTPException(
            status_code=502,
            detail="DDGS image search failed: " + ", ".join(errors[:3]),
        )
    return {"query": name, "count": len(ordered), "results": ordered}
