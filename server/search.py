"""Tiered free image discovery with optional Google Lens reverse-search enrichment."""
from __future__ import annotations
import hashlib, logging, os, time
from pathlib import Path
from urllib.parse import quote_plus

import httpx
from ddgs import DDGS
from icrawler.builtin import BingImageCrawler

from normalizer import normalize_product
from sources import commons_candidates, open_food_facts_candidates
from verifier import score_metadata, verify_candidate

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
        "alt":str(item.get("alt") or item.get("title") or ""),
        "url":str(item.get("url") or ""),
        "source":str(item.get("source") or "DuckDuckGo Images"),
        "query":query,"tier":tier
    }


def _google_lens_candidates(seed_image:str,query:str)->list[dict]:
    """Use a separately hosted, self-managed Google Lens Scraper instance if configured."""
    base=os.getenv("GOOGLE_LENS_BASE_URL","").strip().rstrip("/")
    if not base or not seed_image.startswith("https://"):
        return []
    try:
        response=httpx.get(base+"/search",params={"image":seed_image},
            headers={"User-Agent":"GC-Product-Photo-Finder/1.0"},timeout=75)
        response.raise_for_status()
        payload=response.json()
        matches=payload.get("visual_matches") or payload.get("matches") or []
        found=[]
        for item in matches:
            image_data=item.get("image") or {}
            image=(image_data.get("link") if isinstance(image_data,dict) else None) or item.get("thumbnail")
            if not isinstance(image,str) or not image.startswith("https://"):
                continue
            title=str(item.get("title") or "")
            source=str(item.get("source") or item.get("domain") or "Google Lens")
            found.append({
                "image":image,"thumbnail":str(item.get("thumbnail") or image),
                "title":title,"alt":title,
                "url":str(item.get("link") or ""),
                "source":"Google Lens — "+source,
                "query":query,"tier":3
            })
        return found
    except Exception as exc:
        log.info("Google Lens enrichment unavailable: %s",type(exc).__name__)
        return []


def _bing_icrawler(query:str,base_url:str,max_num:int=12)->list[dict]:
    """Bing fallback. Retry transient crawler failures and serve cached local copies."""
    safe=hashlib.sha1(query.encode("utf-8")).hexdigest()[:12]
    target=MEDIA_DIR/safe
    target.mkdir(parents=True,exist_ok=True)
    last_error=None
    for attempt in range(3):
        try:
            crawler=BingImageCrawler(storage={"root_dir":str(target)},log_level=logging.ERROR)
            crawler.crawl(keyword=query,max_num=max_num,min_size=(300,300),file_idx_offset="auto")
            last_error=None
            break
        except Exception as exc:
            last_error=exc
            log.warning("icrawler attempt=%s failed query=%r error=%s",attempt+1,query,type(exc).__name__)
            if attempt<2: time.sleep(2**attempt)
    if last_error and not any(target.glob("*")):
        return []
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


def search_product(row:dict,base_url:str="",rejection_logger=None)->dict:
    normalized=normalize_product(
        row.get("description",""),row.get("ean",""),row.get("article",""),
        row.get("brand",""),use_llm=True
    ).to_dict()
    product={**normalized,"ean":str(row.get("ean","")).strip(),
             "article":str(row.get("article","")).strip()}
    seen_hashes=[]
    evaluated={}
    rejected_by_url={}
    candidate_map={}
    query_errors=[]

    def evaluate_candidates(candidate_list=None):
        prelim=[]
        source_candidates=candidate_list if candidate_list is not None else candidate_map.values()
        for candidate in source_candidates:
            meta_score,meta_reasons,_=score_metadata(product,candidate)
            if meta_score>=30:
                prelim.append((meta_score,candidate))
            else:
                rejected_by_url[candidate["image"]]={"candidate":candidate,
                    "reasons":meta_reasons+["rejected: metadata score below 30"]}
        shortlist=sorted(prelim,key=lambda x:x[0],reverse=True)[:8]
        for _,candidate in shortlist:
            url=candidate["image"]
            if url in evaluated:
                continue
            check=verify_candidate(product,candidate,seen_hashes=seen_hashes,run_vision=True)
            record={"url":url,"score":check.score,"source":candidate["url"] or candidate["source"],
                    "source_name":candidate["source"],
                    "title":candidate["title"],"needs_review":check.needs_review,
                    "reasons":check.reasons,"vision":check.vision}
            evaluated[url]=record if not check.rejected else None
            if check.rejected:
                rejected_by_url[url]={"candidate":candidate,"reasons":check.reasons}

    # Exact barcode database lookup is the highest-priority free source.
    for candidate in open_food_facts_candidates(product.get("ean","")):
        candidate_map.setdefault(candidate["image"],candidate)

    # Wikimedia Commons is a supplementary open-image source, not proof of product identity.
    commons_query=" ".join([product.get("brand",""),product.get("clean_name","")]).strip()
    for candidate in commons_candidates(commons_query,max_results=8):
        candidate_map.setdefault(candidate["image"],candidate)
    evaluate_candidates()

    lens_attempted=False
    for tier,query in _queries(product):
        try:
            raw=_retry_ddgs(query)
            for item in raw:
                candidate=_candidate(item,query,tier)
                if candidate:
                    candidate_map.setdefault(candidate["image"],candidate)
        except Exception as exc:
            query_errors.append(f"tier {tier}: {type(exc).__name__}")
        evaluate_candidates()

        # Reverse-search only a metadata-supported seed image. This avoids feeding
        # an unrelated thumbnail to Lens and limits each product to one Lens call.
        if not lens_attempted and os.getenv("GOOGLE_LENS_BASE_URL","").strip():
            seeds=sorted(
                ((score_metadata(product,c)[0],c) for c in candidate_map.values()),
                key=lambda pair:pair[0],reverse=True
            )
            seed=next((c for score,c in seeds if score>=40),None)
            if seed:
                lens_attempted=True
                for candidate in _google_lens_candidates(seed["image"],query):
                    candidate_map.setdefault(candidate["image"],candidate)
                evaluate_candidates()
        verified=[x for x in evaluated.values() if x and not x["needs_review"]]
        if len(verified)>=5:
            break
        time.sleep(1.2)

    verified=[x for x in evaluated.values() if x and not x["needs_review"]]
    if len(verified)<5 and product.get("clean_name"):
        bing_query=" ".join([product.get("brand",""),product["clean_name"]," ".join(product.get("codes",[])[:2])]).strip()
        bing=_bing_icrawler(bing_query,base_url,max_num=12)
        for candidate in bing:
            candidate_map.setdefault(candidate["image"],candidate)
        evaluate_candidates(bing)
        verified=[x for x in evaluated.values() if x and not x["needs_review"]]

    review=[x for x in evaluated.values() if x and x["needs_review"]]
    final=verified+review
    unique=[]; urls=set()
    for item in sorted(final,key=lambda x:x["score"],reverse=True):
        if item["url"] not in urls:
            unique.append(item);urls.add(item["url"])
        if len(unique)>=5:
            break
    if rejection_logger:
        for item in rejected_by_url.values():
            rejection_logger(item)
    status="verified" if any(not x["needs_review"] for x in unique) else ("needs_review" if unique else "no_reliable_photo")
    return {
        "clean_name":product["clean_name"],"brand":product["brand"],
        "normalization":normalized,"images":unique,"status":status,
        "rejected_count":len(rejected_by_url),"rejection_reasons":query_errors,
    }
