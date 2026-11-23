package com.keal.gc.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="products")
data class Product(@PrimaryKey(autoGenerate=true) val id:Long=0,val importId:Long=0,val originalDescription:String="",val productName:String="",val brand:String="",val ean:String="",val articleCode:String="",val mrp:Double?=null,val quantity:Int?=null,val imageUrls:String="",val imageSources:String="",val selectedImage:String="",val imageConfidence:String="NONE",val imageStatus:String="NOT_SEARCHED",val duplicateFlag:String="")
@Entity(tableName="imports")
data class ImportRecord(@PrimaryKey(autoGenerate=true) val id:Long=0,val fileName:String,val importedAt:Long=System.currentTimeMillis(),val rowCount:Int)
