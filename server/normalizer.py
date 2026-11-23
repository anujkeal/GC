"""Inventory description normalization with preserved model and SKU codes."""
from __future__ import annotations
import json, os, re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any
import httpx

DATA_DIR = Path(__file__).parent
ABBREVIATIONS_PATH = DATA_DIR / "abbreviations.json"
COLORS = {"red","blue","green","grey","gray","black","white","pink","brown","purple","orange","yellow","silver","gold"}
SCALE_RE = re.compile(r"(?<!\d)(\d{1,2}\s*:\s*\d{1,3})(?!\d)", re.I)
CODE_RE = re.compile(r"(?<![A-Za-z0-9])([A-Za-z]{1,5}\d{3,}[A-Za-z0-9]*|\d{5,})(?![A-Za-z0-9])")
SIZE_RE = re.compile(r"\b(\d+(?:\.\d+)?\s*(?:cm|mm|inch|inches|in|ft|pcs|pieces|pc))\b", re.I)
WORD_RE = re.compile(r"[A-Za-z0-9]+(?:[:./-][A-Za-z0-9]+)*")

@dataclass
class NormalizedProduct:
    original: str
    clean_name: str
    brand: str
    key_tokens: dict[str, list[str]] = field(default_factory=dict)
    codes: list[str] = field(default_factory=list)
    code_tokens: list[str] = field(default_factory=list)
    unknown_abbreviations: list[str] = field(default_factory=list)
    confidence: float = 0.0
    normalization_source: str = "dictionary"
    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

def load_abbreviations(path: Path = ABBREVIATIONS_PATH) -> dict[str, str]:
    return {str(k).lower(): str(v) for k,v in json.loads(path.read_text(encoding="utf-8")).items()}

def _llm_fallback(raw: str, unknown: list[str], brand: str) -> dict | None:
    key = os.getenv("OPENAI_API_KEY")
    if not key or not unknown:
        return None
    endpoint = os.getenv("OPENAI_BASE_URL","https://api.openai.com/v1").rstrip("/") + "/chat/completions"
    prompt = ("Normalize retail stock text without inventing facts. Preserve exact model/SKU codes. "
              "Return JSON: clean_name, brand, key_tokens {model,color,scale,size}, confidence 0..1. "
              f"Known brand={brand!r}; unknown abbreviations={unknown!r}; description={raw!r}")
    try:
        r=httpx.post(endpoint,headers={"Authorization":f"Bearer {key}"},
          json={"model":os.getenv("OPENAI_NORMALIZER_MODEL","gpt-4o-mini"),"temperature":0,
                "response_format":{"type":"json_object"},"messages":[{"role":"user","content":prompt}]},timeout=20)
        r.raise_for_status()
        value=json.loads(r.json()["choices"][0]["message"]["content"])
        return value if isinstance(value,dict) and isinstance(value.get("clean_name"),str) else None
    except Exception:
        return None

def normalize_product(description: str, ean: str="", article: str="", brand: str="",
                      abbreviations: dict[str,str] | None=None, use_llm: bool=True) -> NormalizedProduct:
    raw=(description or "").strip()
    dictionary=abbreviations if abbreviations is not None else load_abbreviations()
    codes=list(dict.fromkeys(CODE_RE.findall(raw)))
    scale_match=SCALE_RE.search(raw)
    scale=re.sub(r"\s+","",scale_match.group(1)) if scale_match else ""
    sizes=[re.sub(r"\s+","",m.group(1).lower()) for m in SIZE_RE.finditer(raw)]
    expanded=raw
    for short,full in sorted(dictionary.items(),key=lambda item:len(item[0]),reverse=True):
        expanded=re.sub(r"(?<![A-Za-z0-9])"+re.escape(short)+r"(?![A-Za-z0-9])",full,expanded,flags=re.I)
    for code in codes:
        expanded=re.sub(r"(?<![A-Za-z0-9])"+re.escape(code)+r"(?![A-Za-z0-9])"," ",expanded,flags=re.I)
    clean=re.sub(r"\s+"," ",re.sub(r"[^A-Za-z0-9:./ -]"," ",expanded)).strip(" -")
    clean=re.sub(r"\s*:\s*",":",clean)
    words=WORD_RE.findall(clean)
    colors=list(dict.fromkeys(w.lower() for w in words if w.lower() in COLORS))
    key_tokens={"model":codes.copy(),"color":colors,"scale":[scale] if scale else [],
                "size":list(dict.fromkeys(sizes)),
                "descriptive":[w.lower() for w in words if w.lower() not in COLORS]}
    known=set(dictionary)
    known_words=set("remote control high speed monster truck amphibious climbing with grey bubble ice cream assortment battery friction lights sound green blue black white red yellow pink brown purple orange".split())
    unknown=[]
    for token in re.findall(r"[A-Za-z]{2,8}",raw):
        low=token.lower()
        if len(low)<=5 and low not in known and low not in known_words and low not in unknown:
            unknown.append(low)
    llm=_llm_fallback(raw,unknown,brand) if use_llm else None
    confidence=0.94 if not unknown else 0.72
    source="dictionary"
    if llm:
        clean=llm["clean_name"].strip()
        brand=str(llm.get("brand") or brand)
        extra=llm.get("key_tokens") or {}
        for key_name in ("model","color","scale","size"):
            vals=extra.get(key_name,[])
            if isinstance(vals,list):
                key_tokens[key_name]=list(dict.fromkeys(key_tokens.get(key_name,[])+[str(v) for v in vals]))
        try: confidence=max(0.0,min(1.0,float(llm.get("confidence",confidence))))
        except (ValueError,TypeError): pass
        source="llm_fallback"
    exact_codes=list(dict.fromkeys(codes+([ean.strip()] if ean.strip() else [])+([article.strip()] if article.strip() else [])))
    code_tokens=[]
    for code in exact_codes: code_tokens.extend(re.findall(r"[A-Za-z]+|\d+",code))
    return NormalizedProduct(raw,clean,brand.strip(),key_tokens,exact_codes,list(dict.fromkeys(code_tokens)),
                             unknown,confidence,source)
