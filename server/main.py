"""GC product image matching API: upload jobs, progress, cache and staff approvals."""
from __future__ import annotations
import csv, hashlib, io, json, logging, os, uuid
from pathlib import Path
from typing import Any

from fastapi import BackgroundTasks, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from openpyxl import load_workbook
from pydantic import BaseModel, Field

import cache
from search import MEDIA_DIR, search_product

logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))
logger=logging.getLogger("gc.api")
app=FastAPI(title="GC Verified Product Image Matcher",version="3.0.0")
MEDIA_DIR.mkdir(parents=True,exist_ok=True)
app.mount("/media",StaticFiles(directory=str(MEDIA_DIR)),name="media")

ALIASES={
 "ean":{"ean","barcode","bar code","upc","gtin","ean code"},
 "article":{"article","article number","article no","artical","sku","item code","material"},
 "description":{"short text","description","product","product name","name","material description","item description"},
 "brand":{"brand","manufacturer","make"},
}

def _norm_header(value:Any)->str:
 return " ".join(str(value or "").strip().lower().replace("_"," ").split())

def _map_headers(headers:list[Any])->dict[str,int]:
 result={}
 for index,value in enumerate(headers):
  name=_norm_header(value)
  for key,aliases in ALIASES.items():
   if name in aliases and key not in result: result[key]=index
 return result

def _read_rows(filename:str,data:bytes)->list[dict]:
 suffix=Path(filename).suffix.lower()
 if suffix in {".xlsx",".xlsm"}:
  workbook=load_workbook(io.BytesIO(data),read_only=True,data_only=True)
  sheet=workbook.active
  iterator=sheet.iter_rows(values_only=True)
  headers=next(iterator,None)
  if not headers: return []
  mapping=_map_headers(list(headers))
  rows=[]
  for cells in iterator:
   if not cells or not any(v is not None and str(v).strip() for v in cells): continue
   row={key:str(cells[idx]).strip() if idx<len(cells) and cells[idx] is not None else "" for key,idx in mapping.items()}
   if row.get("description") or row.get("ean") or row.get("article"): rows.append(row)
  return rows
 # UTF-8 BOM, UTF-16 exports, comma/semicolon/tab separated.
 text=data.decode("utf-8-sig",errors="replace")
 sample=text[:8192]
 try: dialect=csv.Sniffer().sniff(sample,delimiters=",;\t|")
 except csv.Error: dialect=csv.excel
 reader=csv.reader(io.StringIO(text),dialect)
 headers=next(reader,None)
 if not headers: return []
 mapping=_map_headers(headers)
 rows=[]
 for cells in reader:
  if not cells or not any(str(v).strip() for v in cells): continue
  row={key:str(cells[idx]).strip() if idx<len(cells) else "" for key,idx in mapping.items()}
  if row.get("description") or row.get("ean") or row.get("article"): rows.append(row)
 return rows

def _product_id(row:dict)->str:
 basis="|".join([row.get("ean","").strip(),row.get("article","").strip(),row.get("description","").strip().lower()])
 return hashlib.sha256(basis.encode("utf-8")).hexdigest()[:24]

def _public_result(product_id:str,row:dict,result:dict)->dict:
 return {"product_id":product_id,"ean":row.get("ean",""),"article":row.get("article",""),
         "original_description":row.get("description",""),"clean_name":result.get("clean_name",""),
         "images":result.get("images",[]),"status":result.get("status","no_reliable_photo"),
         "rejected_count":result.get("rejected_count",0),
         "normalization":result.get("normalization",{})}

def _process_job(job_id:str,filename:str,rows:list[dict],base_url:str,force_research:bool):
 cache.update_job(job_id,status="running",total=len(rows),done=0)
 for index,row in enumerate(rows):
  product_id=_product_id(row)
  try:
   cached=cache.get_cached(product_id)
   if cached and cached.get("verified_by")=="staff" and not force_research:
    images=json.loads(cached.get("images_json") or "[]")
    result={"clean_name":cached.get("clean_name",""),"images":images,"status":"verified",
            "normalization":{},"rejected_count":0}
   elif cached and cached.get("status")=="verified" and not force_research:
    images=json.loads(cached.get("images_json") or "[]")
    result={"clean_name":cached.get("clean_name",""),"images":images,"status":"verified",
            "normalization":{},"rejected_count":0}
   else:
    result=search_product(row,base_url=base_url,
      rejection_logger=lambda item,pid=product_id:cache.log_rejection(pid,item))
    cache.save_product(product_id,row,result)
   public=_public_result(product_id,row,result)
   cache.save_result(job_id,product_id,public)
  except Exception as exc:
   logger.exception("product matching failed product_id=%s",product_id)
   public={"product_id":product_id,"ean":row.get("ean",""),"article":row.get("article",""),
           "original_description":row.get("description",""),"clean_name":row.get("description",""),
           "images":[],"status":"error","error":str(exc)}
   cache.save_result(job_id,product_id,public)
  cache.update_job(job_id,done=index+1)
 cache.update_job(job_id,status="completed")

@app.on_event("startup")
def startup():
 cache.init_db()

@app.get("/health")
def health():
 return {"status":"ok","service":"gc-product-image-matcher","engine":"ddgs+bing-icrawler"}

@app.post("/jobs",status_code=202)
async def create_job(background_tasks:BackgroundTasks,request:Request,
                     file:UploadFile=File(...),force_research:bool=Form(False)):
 filename=file.filename or "inventory.csv"
 if Path(filename).suffix.lower() not in {".csv",".txt",".xlsx",".xlsm"}:
  raise HTTPException(415,"Upload a CSV or Excel (.xlsx) file.")
 data=await file.read()
 if len(data)>20*1024*1024: raise HTTPException(413,"Maximum upload size is 20 MB.")
 try: rows=_read_rows(filename,data)
 except Exception as exc: raise HTTPException(400,f"Could not parse inventory file: {exc}")
 if not rows: raise HTTPException(400,"No product rows found. Expected columns include EAN, Article and Short Text.")
 job_id=str(uuid.uuid4())
 cache.create_job(job_id,filename,len(rows))
 background_tasks.add_task(_process_job,job_id,filename,rows,str(request.base_url).rstrip("/"),force_research)
 return {"job_id":job_id,"status":"queued","total":len(rows)}

@app.get("/jobs/{job_id}")
def job_status(job_id:str):
 value=cache.get_job(job_id)
 if not value: raise HTTPException(404,"Job not found.")
 return {"job_id":value["job_id"],"filename":value["filename"],"status":value["status"],
         "done":value["done"],"total":value["total"],"progress":round(value["done"]/max(1,value["total"])*100,1),
         "error":value.get("error"),"results":value["results"]}

@app.get("/api/search-images")
def legacy_search(request:Request,name:str="",ean:str="",article:str="",brand:str="",force:bool=False):
 row={"description":name,"ean":ean,"article":article,"brand":brand}
 product_id=_product_id(row)
 cached=cache.get_cached(product_id)
 if cached and cached.get("status")=="verified" and not force:
  images=json.loads(cached.get("images_json") or "[]")
  return {"product_id":product_id,"query":name,"count":len(images),
          "results":[{"image":x["url"],"thumbnail":x["url"],"url":x["source"],
                      "title":x.get("title",""),"source":x["source"],"score":x["score"],
                      "needs_review":False} for x in images],
          "status":"verified","clean_name":cached.get("clean_name",""),"cached":True}
 result=search_product(row,base_url=str(request.base_url).rstrip("/"))
 cache.save_product(product_id,row,result)
 return {"product_id":product_id,"query":name,"count":len(result["images"]),
         "results":[{"image":x["url"],"thumbnail":x["url"],"url":x["source"],
                     "title":x.get("title",""),"source":x["source"],"score":x["score"],
                     "needs_review":x["needs_review"]} for x in result["images"]],
         "status":result["status"],"clean_name":result["clean_name"],"cached":False}

@app.get("/review-queue")
def get_review_queue(limit:int=100):
 return {"items":cache.review_queue(max(1,min(limit,500)))}

class ImageApproval(BaseModel):
 images:list[dict[str,Any]]=Field(min_length=1,max_length=5)
 staff_id:str=Field(min_length=1,max_length=120)

@app.put("/products/{product_id}/images")
def approve_product_images(product_id:str,payload:ImageApproval,x_staff_key:str|None=Header(default=None)):
 required=os.getenv("STAFF_API_KEY")
 if required and x_staff_key!=required: raise HTTPException(401,"Invalid staff approval key.")
 try:
  product=cache.approve_images(product_id,payload.images,payload.staff_id)
 except KeyError: raise HTTPException(404,"Product not found.")
 except ValueError as exc: raise HTTPException(400,str(exc))
 return {"product_id":product_id,"status":"verified","verified_by":"staff","images":json.loads(product["images_json"])}

@app.get("/products/{product_id}/rejections")
def get_rejections(product_id:str,limit:int=100):
 return {"product_id":product_id,"rejections":cache.rejection_log(product_id,max(1,min(limit,500)))}

@app.get("/products/{product_id}")
def get_product(product_id:str):
 product=cache.get_cached(product_id)
 if not product: raise HTTPException(404,"Product not found.")
 product["images"]=json.loads(product.pop("images_json","[]"))
 return product
