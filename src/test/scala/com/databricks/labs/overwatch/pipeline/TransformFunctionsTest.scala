package com.databricks.labs.overwatch.pipeline

import com.databricks.labs.overwatch.SparkSessionTestWrapper
import com.github.mrpowers.spark.fast.tests.DataFrameComparer
import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.{col, lit, struct}
import org.apache.spark.sql.types._
import org.scalatest.funspec.AnyFunSpec
import TransformFunctions._
import org.scalatest.GivenWhenThen

import java.sql.{Date, Timestamp}
import java.time.{Instant, LocalDate}

class TransformFunctionsTest extends AnyFunSpec
  with DataFrameComparer
  with SparkSessionTestWrapper
  with GivenWhenThen {

  import spark.implicits._
  spark.conf.set("spark.sql.session.timeZone", "UTC")

  describe("TransformFunctions.fillFromLookupsByTS") {
    it("should fillFromLookupsByTS") {

    }
  }

  describe("TransformFunctions.subtractTime") {
    it("should subtractTime") {
      val schema = StructType(
        Seq(StructField("start", LongType, false),
          StructField("end", LongType, false),
          StructField("RunTime",
            StructType(Seq(
              StructField("startEpochMS", LongType, false),
              StructField("startTS", TimestampType, true),
              StructField("endEpochMS", LongType, false),
              StructField("endTS", TimestampType, true),
              StructField("runTimeMS", LongType, false),
              StructField("runTimeS", DoubleType, true),
              StructField("runTimeM", DoubleType, true),
              StructField("runTimeH", DoubleType, true)
            )),false)))

      val sourceStartTS = 123456789000L
      val sourceEndTS = 223256289000L
      val sourceDF = Seq((sourceStartTS, sourceEndTS)).toDF("start", "end")

      val expectedData = sc.parallelize(Seq(
        Row(sourceStartTS, sourceEndTS,
          Row(sourceStartTS, Timestamp.from(Instant.ofEpochMilli(sourceStartTS)),
            sourceEndTS, Timestamp.from(Instant.ofEpochMilli(sourceEndTS)),
            99799500000L, 99799500.0, 1663325.0, 27722.083333333333333))))
      val expectedDF = spark.createDataFrame(expectedData, schema)

      val actualDF = sourceDF.withColumn("RunTime",
        TransformFunctions.subtractTime($"start", $"end"))
      assertResult("`start` BIGINT,`end` BIGINT,`RunTime` STRUCT<`startEpochMS`: BIGINT, `startTS`: TIMESTAMP, `endEpochMS`: BIGINT, `endTS`: TIMESTAMP, `runTimeMS`: BIGINT, `runTimeS`: DOUBLE, `runTimeM`: DOUBLE, `runTimeH`: DOUBLE>")(actualDF.schema.toDDL)
      assertResult(1)(actualDF.count())
      assertApproximateDataFrameEquality(actualDF, expectedDF, 0.001)
    }

  }
  describe("TransformFunctions.removeNullCols") {
    case class BaseStruct(par1: Option[String], par2: Option[Int])
    val myDate = Date.valueOf(LocalDate.of(2020,1,1))
    val myTS = new Timestamp(System.currentTimeMillis)
    val nullDate: Option[Date] = None
    val nullTS: Option[Timestamp] = None
    val nullMap: Option[Map[String, Int]] = None
    val nullArray: Option[Array[Int]] = None
    val nullBoolean: Option[Boolean] = None
    val nullString: Option[String] = None

    val structDef = StructType(Seq(
      StructField("sc1", StringType, true),
      StructField("sc2", LongType, true),
    ))
    val nullStructCol = lit(null).cast(structDef).alias("myStruct")
    val nonNullStructCol = struct(
      lit("abc").alias("sc1"),
      lit(0L).alias("sc2")
    ).alias("myStruct")

    val allNulls = Seq(
      (nullString, nullMap, nullArray, nullBoolean, nullDate, nullTS)
    ).toDF("myString", "myMap", "myArray", "myBoolean", "myDate", "myTimestamp")
      .withColumn("myStruct", nullStructCol)

    val noNulls = Seq(
      ("someString", Map("a" -> 1), Array(1,2,3), false, myDate, myTS)
    ).toDF("myString", "myMap", "myArray", "myBoolean", "myDate", "myTimestamp")
      .withColumn("myStruct", nonNullStructCol)

    it("should maintain columns with at least one non-null record") {
      val noColumnsRemovedDDL = "`myMap` MAP<STRING, INT>,`myString` STRING,`myStruct` STRUCT<`sc1`: STRING, `sc2`: BIGINT>,`myBoolean` BOOLEAN,`myDate` DATE,`myTimestamp` TIMESTAMP,`myArray` ARRAY<INT>"

      When("df is fully populated")
      Then("removes no columns")
      assertResult(noColumnsRemovedDDL)(noNulls.cullNull().schema.toDDL)

      When("all df columns have at least one non-null")
      Then("removes no columns")
      assertResult(noColumnsRemovedDDL)(noNulls.unionByName(allNulls).cullNull().schema.toDDL)

    }

    it("should remove only the null columns and leave the others"){

      val onlyMyStringDDL = "`myString` STRING"
      val structMapsDDL = "`myMap` MAP<STRING, INT>,`myStruct` STRUCT<`sc1`: STRING, `sc2`: BIGINT>"

      When("df has boolean with all nulls and string with values")
      Then("remove only the boolean field")
      assertResult(onlyMyStringDDL)(
        Seq(("someString", nullBoolean)).toDF("myString", "myBoolean")
          .cullNull().schema.toDDL
      )

      When("df has struct with all nulls and string with values")
      Then("remove only the struct field")
      assertResult(onlyMyStringDDL)(
        Seq(("someString")).toDF("myString")
          .withColumn("myStruct", nullStructCol)
          .cullNull().schema.toDDL
      )

      When("df has struct and map with no nulls but array with all nulls")
      Then("remove only the array field")
      assertResult(structMapsDDL)(
        Seq((Map("a" -> 1), nullArray)).toDF("myMap", "myArray")
          .withColumn("myStruct", nonNullStructCol)
          .cullNull().schema.toDDL
      )



    }
  }

  describe("TransformFunctions.toTS") {
    it("should work for TimestampType") {
      val sourceTS = 123456789L
      val sourceDF = Seq((sourceTS)).toDF("long")
      val actualDF = sourceDF.withColumn("tsmilli", TransformFunctions.toTS(col("long")))
        .withColumn("tssec", TransformFunctions.toTS(col("long"), inputResolution = "sec"))
      assertResult("`long` BIGINT,`tsmilli` TIMESTAMP,`tssec` TIMESTAMP")(actualDF.schema.toDDL)
      assertResult(1)(actualDF.count())
      val first = actualDF.first()
      assertResult(sourceTS)(first.getLong(0))
      assertResult(Instant.parse("1970-01-02T10:17:36Z"))(first.getTimestamp(1).toInstant)
      assertResult(Instant.parse("1973-11-29T21:33:09Z"))(first.getTimestamp(2).toInstant)
    }

    it("should work for DateType") {
      val sourceTS = 123456789L
      val sourceDF = Seq((sourceTS)).toDF("long")
      val actualDF = sourceDF.withColumn("tsmilli",
        TransformFunctions.toTS(col("long"), outputResultType = DateType))
        .withColumn("tssec",
          TransformFunctions.toTS(col("long"), inputResolution = "sec", outputResultType = DateType))
      assertResult("`long` BIGINT,`tsmilli` DATE,`tssec` DATE")(actualDF.schema.toDDL)
      assertResult(1)(actualDF.count())
      val first = actualDF.first()
      assertResult(sourceTS)(first.getLong(0))
      assertResult(Date.valueOf("1970-01-02"))(first.getDate(1))
      assertResult(Date.valueOf("1973-11-29"))(first.getDate(2))
    }

    it("shouldn't work for LongType") {
      assertThrows[IllegalArgumentException](TransformFunctions.toTS(col("abc"), outputResultType = LongType))
    }
  }

//  TOMES -- removed this test as the getJobsBase function does not belong in TransformFunctions
//  describe("TransformFunctions.getJobsBase") {
//    val schema = StructType(
//      Seq(StructField("serviceName", StringType, true),
//        StructField("requestParams",
//          StructType(Seq(
//            StructField("par1", StringType, true),
//            StructField("par2", IntegerType, true)
//          )),true)))
//
//    it("should get job base") {
//      val sourceData = sc.parallelize(Seq(Row("jobs", Row("t1", 1)),Row("non-jobs", Row("t2", 2))))
//      val sourceDF = spark.createDataFrame(sourceData, schema)
//      val df = TransformFunctions.getJobsBase(sourceDF)
//      assertResult("`serviceName` STRING,`par1` STRING,`par2` INT")(df.schema.toDDL)
//      assertResult(1)(df.count())
//      val first = df.first()
//      assertResult("jobs")(first.getAs[String]("serviceName"))
//      assertResult("t1")(first.getAs[String]("par1"))
//      assertResult(1)(first.getAs[Int]("par2"))
//    }
//
//    it("should not get job base") {
//      val sourceData = sc.parallelize(Seq(Row("no-jobs2", Row("t1", 1)),Row("non-jobs", Row("t2", 2))))
//      val sourceDF = spark.createDataFrame(sourceData, schema)
//      val df = TransformFunctions.getJobsBase(sourceDF)
//      assertResult("`serviceName` STRING,`par1` STRING,`par2` INT")(df.schema.toDDL)
//      assertResult(0)(df.count())
//    }
//
//  }

  describe("TransformFunctions.moveColumnsToFront") {
    it("should moveColumnsToFront") {
      assertResult(Seq("field3", "field2", "field1")){
        val df = spark.createDataFrame(Seq((1,2,3))).toDF("field1", "field2", "field3")
        df.moveColumnsToFront(Array("field3", "field2")).schema.names.toSeq
      }
    }
  }

  describe("TransformFunctions.stringTsToUnixMillis") {
    it("should convert timestamp string to milliseconds") {
      val df = Seq(("2020-11-06T08:10:12.123Z")).toDF("col1")
      val expectedDF = Seq((1604650212123L)).toDF("col1")
      val actualDF = df.withColumn("col1", TransformFunctions.stringTsToUnixMillis($"col1"))
      assertSmallDataFrameEquality(actualDF, expectedDF, ignoreNullable = true)
    }

    it("should not convert timestamp string to milliseconds") {
      val df = Seq(("2020-11-06T08:10:12Z")).toDF("col1")
      val expectedDF = Seq((1L)).toDF("col1").select(lit(null).cast("long").as("col1"))
      val actualDF = df.withColumn("col1", TransformFunctions.stringTsToUnixMillis($"col1"))
      assertSmallDataFrameEquality(actualDF, expectedDF)
    }
  }
}
