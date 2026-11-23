package com.keal.gc
import com.keal.gc.engine.CsvReader
import com.keal.gc.engine.NameNormalizer
import org.junit.Assert.*
import org.junit.Test
class CsvReaderTest{
 @Test fun parsesQuotedComma(){assertEquals("Dog, Walking",CsvReader.parse("Name,Qty\n\"Dog, Walking\",2")[1][0])}
 @Test fun preservesEanArticleAndNumbers(){val p=CsvReader.products("EAN No.,Article Code,Article Description,MRP,Qty\n8904391808578,494731136,NE MIR STAR WARS BABY YODA 25CM,899,3").single();assertEquals("8904391808578",p.ean);assertEquals("494731136",p.article);assertEquals(899.0,p.mrp!!,0.0);assertEquals(3,p.quantity)}
 @Test fun parsesSemicolonSeparatedCsv(){val p=CsvReader.products("EAN;Article Code;Article Description;MRP;Qty\n8904391808578;494731136;STAR WARS BABY YODA;899;3").single();assertEquals("8904391808578",p.ean);assertEquals("494731136",p.article);assertEquals(3,p.quantity)}
 @Test fun parsesTabSeparatedCsv(){val p=CsvReader.products("EAN\tArticle Code\tArticle Description\tMRP\tQty\n8904391808578\t494731136\tBABY YODA\t899\t3").single();assertEquals("BABY YODA",p.original)}
 @Test fun expandsKnownBrand(){assertTrue(NameNormalizer.clean("NE E HM MS CHILI WALKING DOG").contains("Hamleys"))}
}
