import sys
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))

from normalizer import normalize_product
from verifier import score_metadata

SAMPLES = [
    ("1:16 Rc Highspd Mnstr Trck Red T102024","1:16 Remote Control High Speed Monster Truck Red","T102024","1:16","red"),
    ("2.4g R/c Amphi Snake","2.4 g remote control amphibious snake","","",""),
    ("2.4 Rc Wall Climbin Rc W Wtch Rmt Gry","2.4 Remote Control Wall Climbing Remote With Grey","","","grey"),
    ("Blmg Icecream Bubble Blower","Bubble Ice Cream Bubble Blower","","",""),
    ("Diy Stable Builders Farm Playset 9pcs","Diy Stable Builders Farm Playset 9pcs","","",""),
    ("Drawing Board L Blue","Drawing Board L Blue","","","blue"),
    ("Kop Rp Grn Toaster","Kop Rp Green Toaster","","","green"),
    ("Be Set With Backpack Carrier","Be Set With Backpack Carrier","","",""),
    ("City Assortment 6asst","City Assortment 6asst","","",""),
    ("Clue Cluedo Classic Refresh F6420","Clue Cluedo Classic Refresh","","F6420","",""),
]

def test_ten_stock_descriptions_normalize():
    for sample in SAMPLES:
        raw,expected,code,scale,color,*_ = sample
        result=normalize_product(raw,use_llm=False)
        assert expected.lower() in result.clean_name.lower(), (raw,result.clean_name)
        if code:
            assert code in result.codes
            assert code in result.code_tokens
        if scale:
            assert scale in result.key_tokens["scale"]
        if color:
            assert color in result.key_tokens["color"]

def test_requested_example_keeps_exact_model_code():
    result=normalize_product("1:16 Rc Highspd Mnstr Trck Red T102024",use_llm=False)
    assert result.clean_name=="1:16 Remote Control High Speed Monster Truck Red"
    assert result.codes==["T102024"]
    assert {"T","102024"}.issubset(set(result.code_tokens))
    assert result.key_tokens["model"]==["T102024"]
    assert result.key_tokens["scale"]==["1:16"]
    assert result.key_tokens["color"]==["red"]

def test_identifier_and_trusted_domain_score():
    product={"ean":"8901234567890","article":"494500852","clean_name":"1:16 remote control monster truck red",
             "key_tokens":{"brand":["Hamleys"],"model":["T102024"],"color":["red"],"scale":["1:16"],"size":[]}}
    candidate={"title":"Hamleys 1:16 Remote Control Monster Truck Red T102024 EAN 8901234567890",
               "alt":"red monster truck","url":"https://hamleys.in/products/t102024"}
    score,reasons,conflicts=score_metadata(product,candidate)
    assert score>=85
    assert any("40" in reason for reason in reasons)
    assert any("trusted" in reason for reason in reasons)
    assert not conflicts

def test_wrong_color_gets_conflict_penalty():
    product={"ean":"","article":"","clean_name":"red remote control monster truck",
             "key_tokens":{"brand":[],"model":["T102024"],"color":["red"],"scale":["1:16"],"size":[]}}
    candidate={"title":"Blue 1:16 Remote Control Monster Truck T102024",
               "url":"https://unknown.example/item"}
    score,reasons,conflicts=score_metadata(product,candidate)
    assert any("color mismatch" in reason for reason in conflicts)
    assert any(reason.startswith("-30") for reason in reasons)

def test_bundle_listing_is_flagged():
    product={"ean":"","article":"","clean_name":"toy car","key_tokens":{"brand":[],"model":[],"color":[],"scale":[],"size":[]}}
    candidate={"title":"Toy car set of 4","url":"https://unknown.example/item"}
    _,_,conflicts=score_metadata(product,candidate)
    assert any("bundle mismatch" in reason for reason in conflicts)
