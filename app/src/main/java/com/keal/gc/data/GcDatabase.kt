package com.keal.gc.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao interface ProductDao {
 @Query("SELECT * FROM products ORDER BY productName COLLATE NOCASE") fun observeAll():Flow<List<Product>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertAll(items:List<Product>)
 @Update suspend fun update(item:Product)
 @Query("SELECT * FROM products WHERE ean != '' GROUP BY ean HAVING COUNT(*) > 1") suspend fun duplicateEans():List<Product>
 @Query("SELECT * FROM products WHERE articleCode != '' GROUP BY articleCode HAVING COUNT(*) > 1") suspend fun duplicateArticles():List<Product>
}
@Dao interface ImportDao {
 @Insert suspend fun insert(record:ImportRecord):Long
 @Query("SELECT * FROM imports ORDER BY importedAt DESC") fun observeAll():Flow<List<ImportRecord>>
}
@Database(entities=[Product::class,ImportRecord::class],version=1,exportSchema=false)
abstract class GcDatabase:RoomDatabase(){ abstract fun products():ProductDao; abstract fun imports():ImportDao }
