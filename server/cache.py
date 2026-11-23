"""SQLite job, verified-image cache, rejection audit and manual review queue."""
from __future__ import annotations
import json, os, sqlite3, threading, time
from pathlib import Path
from typing import Any

DB_PATH=Path(os.getenv("GC_DB_PATH",Path(__file__).parent/"data"/"gc.sqlite3"))
DB_PATH.parent.mkdir(parents=True,exist_ok=True)
_LOCK=threading.RLock()

def connect():
    db=sqlite3.connect(DB_PATH,timeout=30,check_same_thread=False)
    db.row_factory=sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    return db

def init_db():
    with _LOCK,connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS jobs(
          job_id TEXT PRIMARY KEY, filename TEXT NOT NULL, status TEXT NOT NULL,
          total INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0,
          created_at REAL NOT NULL, updated_at REAL NOT NULL, error TEXT
        );
        CREATE TABLE IF NOT EXISTS products(
          product_id TEXT PRIMARY KEY, ean TEXT, article TEXT, description TEXT,
          clean_name TEXT, images_json TEXT NOT NULL DEFAULT '[]',
          status TEXT NOT NULL, verified_by TEXT NOT NULL DEFAULT 'auto',
          verified_at REAL, updated_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS job_results(
          job_id TEXT NOT NULL, product_id TEXT NOT NULL, result_json TEXT NOT NULL,
          PRIMARY KEY(job_id,product_id), FOREIGN KEY(job_id) REFERENCES jobs(job_id)
        );
        CREATE TABLE IF NOT EXISTS rejected_candidates(
          id INTEGER PRIMARY KEY AUTOINCREMENT, product_id TEXT NOT NULL,
          candidate_json TEXT NOT NULL, reasons_json TEXT NOT NULL, created_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS manual_review(
          product_id TEXT PRIMARY KEY, product_json TEXT NOT NULL,
          candidates_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
          created_at REAL NOT NULL, updated_at REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_rejected_product ON rejected_candidates(product_id);
        """)
        db.commit()

def create_job(job_id:str,filename:str,total:int):
    now=time.time()
    with _LOCK,connect() as db:
        db.execute("INSERT INTO jobs(job_id,filename,status,total,done,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                   (job_id,filename,"queued",total,0,now,now))
        db.commit()

def update_job(job_id:str,**fields):
    allowed={"status","total","done","error"}
    values={k:v for k,v in fields.items() if k in allowed}
    values["updated_at"]=time.time()
    sql=", ".join(f"{k}=?" for k in values)
    with _LOCK,connect() as db:
        db.execute(f"UPDATE jobs SET {sql} WHERE job_id=?",(*values.values(),job_id))
        db.commit()

def get_job(job_id:str)->dict|None:
    with _LOCK,connect() as db:
        row=db.execute("SELECT * FROM jobs WHERE job_id=?",(job_id,)).fetchone()
        if not row:return None
        results=db.execute("SELECT result_json FROM job_results WHERE job_id=?",(job_id,)).fetchall()
        value=dict(row);value["results"]=[json.loads(r["result_json"]) for r in results]
        return value

def get_cached(product_id:str)->dict|None:
    with _LOCK,connect() as db:
        row=db.execute("SELECT * FROM products WHERE product_id=?",(product_id,)).fetchone()
        return dict(row) if row else None

def save_product(product_id:str,row:dict,result:dict):
    now=time.time()
    images=result.get("images",[])
    status=result.get("status","no_reliable_photo")
    verified=any(not image.get("needs_review") for image in images)
    verified_by="auto" if verified else "auto"
    with _LOCK,connect() as db:
        db.execute("""INSERT INTO products(product_id,ean,article,description,clean_name,images_json,status,verified_by,verified_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(product_id) DO UPDATE SET
        ean=excluded.ean,article=excluded.article,description=excluded.description,
        clean_name=excluded.clean_name,images_json=CASE WHEN products.verified_by='staff' THEN products.images_json ELSE excluded.images_json END,
        status=CASE WHEN products.verified_by='staff' THEN products.status ELSE excluded.status END,
        verified_by=CASE WHEN products.verified_by='staff' THEN 'staff' ELSE excluded.verified_by END,
        verified_at=CASE WHEN products.verified_by='staff' THEN products.verified_at ELSE excluded.verified_at END,
        updated_at=excluded.updated_at""",
        (product_id,row.get("ean",""),row.get("article",""),row.get("description",""),
         result.get("clean_name",""),json.dumps(images),status,verified_by,now if verified else None,now))
        if status=="no_reliable_photo" or any(x.get("needs_review") for x in images):
            db.execute("""INSERT INTO manual_review(product_id,product_json,candidates_json,status,created_at,updated_at)
              VALUES(?,?,?,?,?,?) ON CONFLICT(product_id) DO UPDATE SET
              product_json=excluded.product_json,candidates_json=excluded.candidates_json,
              status=CASE WHEN manual_review.status='approved' THEN 'approved' ELSE 'pending' END,
              updated_at=excluded.updated_at""",
              (product_id,json.dumps(row),json.dumps(images),"pending",now,now))
        db.commit()

def save_result(job_id:str,product_id:str,result:dict):
    with _LOCK,connect() as db:
        db.execute("INSERT OR REPLACE INTO job_results(job_id,product_id,result_json) VALUES(?,?,?)",
                   (job_id,product_id,json.dumps(result)))
        db.commit()

def log_rejection(product_id:str,candidate:dict):
    with _LOCK,connect() as db:
        db.execute("INSERT INTO rejected_candidates(product_id,candidate_json,reasons_json,created_at) VALUES(?,?,?,?)",
          (product_id,json.dumps(candidate.get("candidate",{})),json.dumps(candidate.get("reasons",[])),time.time()))
        db.commit()

def rejection_log(product_id:str,limit:int=100)->list[dict]:
    with _LOCK,connect() as db:
        rows=db.execute("SELECT * FROM rejected_candidates WHERE product_id=? ORDER BY id DESC LIMIT ?",
                        (product_id,limit)).fetchall()
        return [{"candidate":json.loads(r["candidate_json"]),"reasons":json.loads(r["reasons_json"]),
                 "created_at":r["created_at"]} for r in rows]

def review_queue(limit:int=100)->list[dict]:
    with _LOCK,connect() as db:
        rows=db.execute("SELECT * FROM manual_review WHERE status='pending' ORDER BY updated_at DESC LIMIT ?",(limit,)).fetchall()
        return [{"product_id":r["product_id"],"product":json.loads(r["product_json"]),
                 "candidates":json.loads(r["candidates_json"]),"status":r["status"]} for r in rows]

def approve_images(product_id:str,images:list[dict],staff_id:str):
    now=time.time()
    for item in images:
        if not str(item.get("url","")).startswith("https://"):
            raise ValueError("Approved image URLs must use HTTPS.")
        if not item.get("source"): raise ValueError("Every approved image needs a source.")
    with _LOCK,connect() as db:
        row=db.execute("SELECT product_id FROM products WHERE product_id=?",(product_id,)).fetchone()
        if not row: raise KeyError(product_id)
        db.execute("UPDATE products SET images_json=?,status='verified',verified_by='staff',verified_at=?,updated_at=? WHERE product_id=?",
                   (json.dumps([{**x,"needs_review":False,"verified_by":"staff","staff_id":staff_id} for x in images[:5]]),now,now,product_id))
        db.execute("UPDATE manual_review SET status='approved',updated_at=? WHERE product_id=?",(now,product_id))
        db.commit()
    return get_cached(product_id)

def force_research_allowed(product_id:str)->bool:
    row=get_cached(product_id)
    return not row or row.get("verified_by")!="staff"
