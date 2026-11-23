"""Tiered DDGS and Bing(icrawler) search with retry, verification and rejection audit."""
from __future__ import annotations
import hashlib, logging, os, re, tempfile, time
from pathlib import Path
from urllib.parse import quote_plus

from ddgs import DDGS
from icrawler.builtin import BingImageCrawler

from normalizer import normalize_product
from verifier import verify_candidate

log=logging.getLogger("gc.search")
MEDIA_DIR=Path(os.getenv("GC_MEDIA_DIR",Path(__file__).parent/"data"/"media"))
MEDIA_DIR.mkdir(parents=True,exist_ok=True)

def _retry_ddgs(query:str,max_results:int=30)->list[dict]:
    last=None
    for attempt in range(4):
        try:
            return list(DDGS(timeout=20).images(query=query,region="in-en",safesearch="moderate",max_results=max_results))
        except Exception as exc:
            last=exc
            message=str(exc).lower()
            if any(term in message for term in ("403","429","rate","captcha","blocked")):
                log.warning("DDGS rate limited query=%r attempt=%s error=%s",query,attempt+1,type(exc).__name__)
            if attempt<3:
                time.sleep(min(2**attempt,8))
    raise RuntimeError(f"DDGS retries exhausted: {type(last).__name__ if last else 'unknown'}")

def _queries(product:dict)->list[tuple[int,str]]:
    name=product.get("clean_name","").strip()
    brand=product.get("brand","").strip()
    codes=product.get("codes",[])
    ean=product.get("ean","").strip()
    article=product.get("article","").strip()
    tiers=[]
    if ean: tiers.append((1,f'"{ean}"'))
    if article: tiers.append((2,f'"{article}" {brand}'.strip()))
    if name:
        tiers.append((3,f'site:hamleys.in "{name}"'))
        tiers.append((3,f'site:hamleys.com "{name}"'))
        tiers.append((4,f'"{brand} {name}"'.strip()))
        tiers.append((4," ".join([name]+[str(x) for x in codes[:2]])))
    return list(dict.fromkeys(tiers))

def _candidate(item:dict,query:str,tier:int)->dict|None:
    image=str(item.get("image") or item.get("thumbnail") or "").strip()
    if not image.startswith("http"): return None
    return {
        "image":image,"thumbnail":str(item.get("thumbnail") or image),
        "title":str(item.get("title") or ""),
        "url":str(item.get("url") or ""),
        "source":str(item.get("source") or "DuckDuckGo Images"),
        "query":query,"tier":tier
    }

def _bing_icrawler(query:str,base_url:str,max_num:int=12)->list[dict]:
    """Bing fallback. Downloaded files are served by this API for subsequent verification."""
    try:
        safe=hashlib.sha1(query.encode("utf-8")).hexdigest()[:12]
        target=MEDIA_DIR/safe
        target.mkdir(parents=True,exist_ok=True)
        crawler=BingImageCrawler(storage={"root_dir":str(target)},log_level=logging.ERROR)
        crawler.crawl(keyword=query,max_num=max_num, min_size=(300,300), file_idx_offset="auto")
        found=[]
        for path in sorted(target.glob("*")):
            if path.suffix.lower() not in {".jpg",".jpeg",".png",".webp"}: continue
            name=path.name
            found.append({
                "image":f"{base_url.rstrip('/')}/media/{safe}/{quote_plus(name)}",
                "thumbnail":f"{base_url.rstrip('/')}/media/{safe}/{quote_plus(name)}",
                "title":query,"url":f"https://www.bing.com/images/search?q={quote_plus(query)}",
                "source":"Bing Images (icrawler)","query":query,"tier":4
            })
            if len(found)>=max_num: break
        return found
    except Exception as exc:
        log.warning("icrawler fallback failed query=%r error=%s",query,type(exc).__name__)
        return []

def search_product(row:dict,base_url:str="",rejection_logger=None)->dict:
    normalized=normalize_product(
        row.get("description",""),row.get("ean",""),row.get("article",""),
        row.get("brand",""),use_llm=True
    ).to_dict()
    product={**normalized,"ean":str(row.get("ean","")).strip(),
             "article":str(row.get("article","")).strip()}
    seen_hashes=[]
    verified=[]
    review=[]
    rejected=[]
    candidate_map={}
    query_errors=[]
    for tier,query in _queries(product):
        try:
            raw=_retry_ddgs(query)
            for item in raw:
                candidate=_candidate(item,query,tier)
                if candidate: candidate_map[candidate["image"]]=candidate
        except Exception as exc:
            query_errors.append(f"tier {tier}: {type(exc).__name__}")
        # Verify the growing candidate pool after each tier. Vision only checks the top 8.
        prelim=[]
        for candidate in candidate_map.values():
            check=verify_candidate(product,candidate,seen_hashes=[],run_vision=False)
            if not check.rejected:
                prelim.append((check.score,candidate,check))
            else:
                rejected.append({"candidate":candidate,"reasons":check.reasons})
        shortlist=sorted(prelim,key=lambda x:x[0],reverse=True)[:8]
        verified=[]; review=[]
        for _,candidate,_ in shortlist:
            check=verify_candidate(product,candidate,seen_hashes=seen_hashes,run_vision=True)
            record={"url":candidate["image"],"score":check.score,"source":candidate["source"],
                    "title":candidate["title"],"needs_review":check.needs_review,
                    "reasons":check.reasons,"vision":check.vision}
            if check.accepted: verified.append(record)
            elif check.needs_review: review.append(record)
            else: rejected.append({"candidate":candidate,"reasons":check.reasons})
        if len(verified)>=5: break
        time.sleep(1.2)

    # Only use icrawler if DDGS did not provide five vision-verified photos.
    if len(verified)<5 and product.get("clean_name"):
        bing=_bing_icrawler(product["clean_name"],base_url,max_num=12)
        for candidate in bing:
            candidate_map.setdefault(candidate["image"],candidate)
        for candidate in bing:
            check=verify_candidate(product,candidate,seen_hashes=seen_hashes,run_vision=True)
            record={"url":candidate["image"],"score":check.score,"source":candidate["source"],
                    "title":candidate["title"],"needs_review":check.needs_review,
                    "reasons":check.reasons,"vision":check.vision}
            if check.accepted: verified.append(record)
            elif check.needs_review: review.append(record)
            else: rejected.append({"candidate":candidate,"reasons":check.reasons})
            if len(verified)>=5: break

    # If no vision key is configured, nothing is labeled verified. Scores are capped by verifier.
    final=(verified+review)
    unique=[]; urls=set()
    for item in sorted(final,key=lambda x:x["score"],reverse=True):
        if item["url"] not in urls:
            unique.append(item);urls.add(item["url"])
        if len(unique)>=5:break
    if rejection_logger:
        for item in rejected:
            rejection_logger(item)
    status="verified" if any(not x["needs_review"] for x in unique) else ("needs_review" if unique else "no_reliable_photo")
    return {
        "clean_name":product["clean_name"],"brand":product["brand"],
        "normalization":normalized,"images":unique,"status":status,
        "rejected_count":len(rejected),"rejection_reasons":query_errors,
    }
