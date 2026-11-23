"""Explainable image quality, duplicate, product and optional vision verification."""
from __future__ import annotations
import base64, io, json, os, re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

import httpx
import imagehash
from PIL import Image

TRUSTED_PATH=Path(__file__).parent/"trusted_domains.json"
COLORS={"red","blue","green","grey","gray","black","white","pink","brown","purple","orange","yellow","silver","gold"}
WATERMARK_TERMS=("shutterstock","gettyimages","alamy","dreamstime","depositphotos","123rf","istockphoto","watermark")

@dataclass
class Verification:
    score:int
    accepted:bool
    needs_review:bool
    rejected:bool
    reasons:list[str]=field(default_factory=list)
    vision:dict|None=None
    width:int=0
    height:int=0
    phash:str=""

def trusted_domains()->list[str]:
    try: return json.loads(TRUSTED_PATH.read_text(encoding="utf-8")).get("domains",[])
    except Exception: return []

def _trusted(url:str)->bool:
    host=(urlparse(url).hostname or "").lower()
    return any(host==d or host.endswith("."+d) for d in trusted_domains())

def _conflicts(product:dict,title:str,page_url:str)->list[str]:
    text=f"{title} {page_url}".lower()
    conflicts=[]
    expected=set(product.get("key_tokens",{}).get("color",[]))
    found=COLORS.intersection(re.findall(r"[a-z]+",text))
    if expected and found and not expected.intersection(found):
        conflicts.append(f"color mismatch: expected {sorted(expected)}, found {sorted(found)}")
    for model in product.get("key_tokens",{}).get("model",[]):
        model=str(model).lower()
        model_tokens=re.findall(r"\b[a-z]{1,5}\d{3,}[a-z0-9]*\b",text)
        if model_tokens and model not in model_tokens:
            conflicts.append(f"model mismatch: expected {model}, listing has {model_tokens}")
            break
    expected_scale=set(product.get("key_tokens",{}).get("scale",[]))
    found_scale=set(re.findall(r"\b\d{1,2}:\d{1,3}\b",text))
    if expected_scale and found_scale and not expected_scale.intersection(found_scale):
        conflicts.append(f"scale mismatch: expected {sorted(expected_scale)}, found {sorted(found_scale)}")
    if re.search(r"\bset of\s+\d+\b",text):
        conflicts.append("possible bundle mismatch: listing says set of")
    expected_size=set(product.get("key_tokens",{}).get("size",[]))
    found_size={re.sub(r"\s+","",x).lower() for x in re.findall(r"\b\d+(?:\.\d+)?\s*(?:cm|mm|inch|inches)\b",text)}
    if expected_size and found_size and not expected_size.intersection(found_size):
        conflicts.append(f"size mismatch: expected {sorted(expected_size)}, found {sorted(found_size)}")
    return conflicts

def _download(url:str)->tuple[bytes,int,int,str]:
    response=httpx.get(url,follow_redirects=True,timeout=20,
        headers={"User-Agent":"Mozilla/5.0 GC-Product-Photo-Finder"})
    response.raise_for_status()
    data=response.content
    if len(data)>15*1024*1024: raise ValueError("image larger than 15 MB")
    image=Image.open(io.BytesIO(data)).convert("RGB")
    width,height=image.size
    if width<300 or height<300: raise ValueError(f"image too small ({width}x{height}); minimum 300x300")
    # OCR image corners where stock-photo watermarks commonly appear.
    try:
        import pytesseract
        w,h=image.size
        corners=[image.crop((0,0,w//3,h//4)),image.crop((2*w//3,0,w,h//4)),
                 image.crop((0,3*h//4,w//3,h)),image.crop((2*w//3,3*h//4,w,h))]
        corner_text=" ".join(pytesseract.image_to_string(crop,config="--psm 11") for crop in corners).lower()
        if any(term in corner_text for term in WATERMARK_TERMS):
            raise ValueError("visible stock-image watermark detected by OCR")
    except ImportError:
        pass
    phash=format(int(str(imagehash.phash(image)),16),"064b")
    output=io.BytesIO()
    image.save(output,format="JPEG",quality=90)
    return output.getvalue(),width,height,phash

def _hamming(a:str,b:str)->int:
    return sum(x!=y for x,y in zip(a,b))

def _vision_check(clean_name:str,image_bytes:bytes)->dict|None:
    api_key=os.getenv("OPENAI_API_KEY")
    if not api_key: return None
    model=os.getenv("OPENAI_VISION_MODEL","gpt-4o-mini")
    data_url="data:image/jpeg;base64,"+base64.b64encode(image_bytes).decode("ascii")
    prompt=(f"Does this image show the exact retail product: {clean_name}? "
      "Do not accept a generic similar category. Compare product type, model, color and scale. "
      'Return JSON only: {"answer":"yes" or "no","reason":"short visual evidence"}.')
    try:
        r=httpx.post(os.getenv("OPENAI_BASE_URL","https://api.openai.com/v1").rstrip("/")+"/chat/completions",
          headers={"Authorization":f"Bearer {api_key}"},
          json={"model":model,"temperature":0,"messages":[{"role":"user","content":[
            {"type":"text","text":prompt},{"type":"image_url","image_url":{"url":data_url}}]}]},timeout=40)
        r.raise_for_status()
        content=r.json()["choices"][0]["message"]["content"]
        match=re.search(r"\{.*\}",content,re.S)
        return json.loads(match.group(0)) if match else {"answer":"unknown","reason":"vision response was not JSON"}
    except Exception as exc:
        return {"answer":"unknown","reason":f"vision check error: {type(exc).__name__}"}

def score_metadata(product:dict,candidate:dict)->tuple[int,list[str],list[str]]:
    """Score metadata evidence only; useful for deterministic offline unit tests."""
    title=str(candidate.get("title") or "")
    page=str(candidate.get("url") or candidate.get("source") or "")
    alt=str(candidate.get("alt") or "")
    text=f"{title} {alt} {page}".lower()
    reasons=[]
    score=0
    ean=str(product.get("ean") or "").strip()
    article=str(product.get("article") or "").strip()
    if any(code and re.search(r"(?<![a-z0-9])"+re.escape(code.lower())+r"(?![a-z0-9])",text) for code in (ean,article)):
        score+=40
        reasons.append("+40 exact EAN/article found in title, alt text or source URL")
    buckets=product.get("key_tokens") or {}
    important=[]
    for key in ("brand","model","color","scale","size"):
        values=buckets.get(key,[])
        if isinstance(values,str): values=[values]
        important.extend(str(v).lower() for v in values if str(v).strip())
    important=list(dict.fromkeys(important))
    if not important:
        important=[x.lower() for x in re.findall(r"[a-z0-9]+",product.get("clean_name","")) if len(x)>=3]
    matched=[token for token in important
             if re.search(r"(?<![a-z0-9])"+re.escape(token)+r"(?![a-z0-9])",text)]
    points=round(30*len(set(matched))/max(1,len(set(important))))
    score+=points
    reasons.append(f"+{points} key-token coverage ({len(set(matched))}/{len(set(important))})")
    if _trusted(page):
        score+=15
        reasons.append("+15 trusted retailer/manufacturer domain")
    conflicts=_conflicts(product,title+" "+alt,page)
    if conflicts:
        score-=30
        reasons.extend("-30 "+reason for reason in conflicts)
    return max(0,min(100,score)),reasons,conflicts

def verify_candidate(product:dict,candidate:dict,seen_hashes:list[str]|None=None,
                     run_vision:bool=True)->Verification:
    seen_hashes=seen_hashes if seen_hashes is not None else []
    title=str(candidate.get("title") or "")
    page=str(candidate.get("url") or candidate.get("source") or "")
    image_url=str(candidate.get("image") or candidate.get("thumbnail") or "")
    searchable=f"{title} {page} {image_url}".lower()
    if any(term in searchable for term in WATERMARK_TERMS):
        return Verification(0,False,False,True,["rejected: stock-watermark source or URL"])
    try: image_bytes,width,height,phash=_download(image_url)
    except Exception as exc:
        return Verification(0,False,False,True,[f"rejected: image validation failed: {exc}"])
    if any(_hamming(phash,old)<=4 for old in seen_hashes):
        return Verification(0,False,False,True,["rejected: perceptual duplicate (pHash distance <= 4)"],width=width,height=height,phash=phash)
    seen_hashes.append(phash)

    score,reasons,conflicts=score_metadata(product,candidate)
    vision=_vision_check(product.get("clean_name",""),image_bytes) if run_vision else None
    vision_yes=bool(vision and str(vision.get("answer","")).lower()=="yes")
    if vision_yes:
        score+=20
        reasons.append("+20 vision model confirms exact product appearance")
    elif vision and str(vision.get("answer","")).lower()=="no":
        score-=40
        reasons.append("-40 vision model rejects visual product identity")
        reasons.append("vision: "+str(vision.get("reason","")))
    else:
        reasons.append("vision confirmation unavailable; staff review required")
    score=max(0,min(100,score))
    if not vision_yes: score=min(score,79)
    accepted=score>=80 and vision_yes
    needs_review=50<=score<80 or (score>=80 and not vision_yes)
    rejected=score<50
    if accepted: reasons.append("auto-accepted: score >=80 with positive vision confirmation")
    elif needs_review: reasons.append("manual review: score 50-79 or no positive vision confirmation")
    else: reasons.append("rejected: score below 50")
    return Verification(score,accepted,needs_review,rejected,reasons,vision,width,height,phash)
