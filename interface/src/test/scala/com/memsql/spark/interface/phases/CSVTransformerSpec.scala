// scalastyle:off magic.number
package com.memsql.spark.interface.phases

import java.math.BigDecimal
import java.sql.Timestamp

import com.memsql.spark.connector.dataframe._
import com.memsql.spark.etl.LocalSparkContext
import com.memsql.spark.etl.utils.ByteUtils._
import com.memsql.spark.interface.TestKitSpec
import com.memsql.spark.interface.util.PipelineLogger
import com.memsql.spark.phases.{CSVTransformerConfig, CSVTransformer, CSVTransformerException}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{SQLContext, Row}
import org.apache.spark.sql.types._
import org.apache.spark.streaming.{Duration, StreamingContext}
import spray.json._

class CSVTransformerSpec extends TestKitSpec("CSVTransformerSpec") with LocalSparkContext{
  val DURATION_CONST = 5000
  val transformer = new CSVTransformer
  var sqlContext: SQLContext = null
  var streamingContext: StreamingContext = null
  var logger: PipelineLogger = null

  override def beforeEach(): Unit = {
    val sparkConfig = new SparkConf().setMaster("local").setAppName("Test")
    sc = new SparkContext(sparkConfig)
    sqlContext = new SQLContext(sc)
    streamingContext = new StreamingContext(sc, new Duration(DURATION_CONST))
    logger = new PipelineLogger(s"Pipeline p1 transform", false)
  }

  "CSVTransformer" should {
    "work on DataFrame with String column" in {
      val config = CSVTransformerConfig(
        delimiter = Some(','),
        escape = Some("\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """[{"name": "id", "column_type": "string"}, {"name": "name", "column_type": "string"}]""".parseJson
      )

      val schema = StructType(StructField("string", StringType, false) :: Nil)
      val sampleData = List(
        "1,hello",
        "2,world",
        "3,foo",
        "4,bar"
      )
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      val df = transformer.transform(sqlContext, dfIn, config, logger)
      val schemaOut = StructType(
        StructField("id", StringType, true) ::
        StructField("name", StringType, true) :: Nil)

      assert(df.schema == schemaOut)
      assert(df.first == Row("1", "hello"))
      assert(df.count == 4)
    }

    "work on DataFrame with Binary column" in {
      val config = CSVTransformerConfig(
        delimiter = Some(','),
        escape = Some("\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """[{"name": "id", "column_type": "string"}, {"name": "name", "column_type": "string"}]""".parseJson
      )

      val schema = StructType(StructField("bytes", BinaryType, false) :: Nil)
      val sampleData = List(
        "1,hello",
        "2,world",
        "3,foo",
        "4,bar"
      ).map(utf8StringToBytes)
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      val df = transformer.transform(sqlContext, dfIn, config, logger)
      val schemaOut = StructType(
        StructField("id", StringType, true) ::
        StructField("name", StringType, true) :: Nil)

      assert(df.schema == schemaOut)
      assert(df.first == Row("1", "hello"))
      assert(df.count == 4)
    }


    "work on DataFrame with extraneous spaces in the input" in {
      val config = CSVTransformerConfig(
        delimiter = Some(','),
        escape = Some("\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """[{"name": "id", "column_type": "string"}, {"name": "name", "column_type": "string"}]""".parseJson
      )

      val schema = StructType(StructField("string", StringType, false) :: Nil)
      val sampleData = List(
        "1,      hello   ",
        "2,  world",
        "3     ,foo   ",
        "4  ,bar   ",
        "5,NULL   ",
        "6,   NULL   "
      )
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      val df = transformer.transform(sqlContext, dfIn, config, logger)
      val schemaOut = StructType(
        StructField("id", StringType, true) ::
        StructField("name", StringType, true) :: Nil)

      assert(df.schema == schemaOut)
      assert(df.collect()(0) == Row("1", "hello"))
      assert(df.collect()(1) == Row("2", "world"))
      assert(df.collect()(2) == Row("3", "foo"))
      assert(df.collect()(3) == Row("4", "bar"))
      assert(df.collect()(4) == Row("5", null))
      assert(df.collect()(5) == Row("6", null))
      assert(df.count == 6)
    }

    "NOT work on DataFrame with column different than String or Binary" in {
      val config = CSVTransformerConfig(
        delimiter = Some(','),
        escape = Some("\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """[{"name": "id", "column_type": "string"}, {"name": "name", "column_type": "string"}]""".parseJson
      )

      val schema = StructType(StructField("data", IntegerType, false) :: Nil)
      val sampleData = List(
        "1,hello",
        "2,world",
        "3,foo",
        "4,bar"
      ).map(utf8StringToBytes)
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      val e = intercept[IllegalArgumentException] {
        transformer.transform(sqlContext, dfIn, config, logger)
      }
      assert(e.getMessage() == "The first column of the input DataFrame should be either StringType or BinaryType")
    }

    "allow the empty character for escape" in {
      val config = CSVTransformerConfig(
        delimiter = Some('/'),
        escape = Some(""),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """[{"name": "sample_column", "column_type": "string"}]""".parseJson
      )

      val schema = StructType(StructField("string", StringType, false) :: Nil)
      val sampleData = List(
        "test\\default"
      )
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      try {
        val df = transformer.transform(sqlContext, dfIn, config, logger)
        assert(df.rdd.collect()(0)(0).toString == "test\\default", "Input not preserved");
      } catch {
        case e: CSVTransformerException => fail("CSVTransformer exception")
        case e: Throwable => fail(s"Unexpected response $e")
      }
    }

    "fail on an invalid column json" in {
      val config = CSVTransformerConfig(
        delimiter = Some('/'),
        escape = Some("\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """{"name": "sample_column", "column_type": "string"}""".parseJson
      )

      val schema = StructType(StructField("string", StringType, false) :: Nil)
      val sampleData = List(
        "test\\default"
      )
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      try {
        transformer.transform(sqlContext, dfIn, config, logger)
      } catch {
        case e: Throwable => assert(e.isInstanceOf[DeserializationException])
      }
    }

    "fail if the escape is longer than 1 character" in {
      val config = CSVTransformerConfig(
        delimiter = Some('/'),
        escape = Some("\\\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns = """[{"name": "sample_column", "column_type": "string"}]""".parseJson
      )

      val schema = StructType(StructField("string", StringType, false) :: Nil)
      val sampleData = List(
        "test\\default"
      )
      val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      try {
        transformer.transform(sqlContext, dfIn, config, logger)
      } catch {
        case e: Throwable => assert(e.isInstanceOf[CSVTransformerException])
      }
    }


    "convert values to the expected type, for all types supported by ops-loader" in {

      // We want to cover every type that could show up in a dropdown in Streamliner Import.
      val columns = """[
          { "name": "bigint_unsigned", "column_type": "bigint unsigned" },
          { "name": "bigint", "column_type": "bigint" },
          { "name": "binary", "column_type": "binary" },
          { "name": "bool", "column_type": "bool" },
          { "name": "boolean", "column_type": "boolean" },
          { "name": "byte", "column_type": "byte" },
          { "name": "date", "column_type": "date" },
          { "name": "datetime", "column_type": "datetime" },
          { "name": "decimal", "column_type": "decimal" },
          { "name": "double", "column_type": "double" },
          { "name": "float", "column_type": "float" },
          { "name": "geography", "column_type": "geography" },
          { "name": "geography_point", "column_type": "geographypoint" },
          { "name": "int", "column_type": "int" },
          { "name": "integer", "column_type": "integer" },
          { "name": "json", "column_type": "json" },
          { "name": "short", "column_type": "short" },
          { "name": "text", "column_type": "text" },
          { "name": "timestamp", "column_type": "timestamp" },
          { "name": "string", "column_type": "string" },
          { "name": "notype" }
        ]""".parseJson

      val config = CSVTransformerConfig(
        delimiter = Some(','),
        escape = Some("\\"),
        quote = Some('\''),
        null_string = Some("NULL"),
        columns
      )

      val schema = StructType(StructField("string", StringType, false) :: Nil)

      // Fields we are sending to CSVTransformer
      val sampleData = List(
        "999999999",
        "10000000",
        "2",
        "true",
        "false",
        "1",
        "2014-02-02",
        "2014-02-02T12:25:35",
        "7.3",
        "4.7",
        "1.2",
        "geo1",
        "geopoint1",
        "2048",
        "2049",
        "[]",
        "256",
        "text",
        "2014-02-02T12:25:36",
        "string",
        "notype"
      )
      val rowRDD = sqlContext.sparkContext.parallelize(List(sampleData.mkString(","))).map(Row(_))
      val dfIn = sqlContext.createDataFrame(rowRDD, schema)

      val transformedDf = transformer.transform(sqlContext, dfIn, config, logger)

      // Fields we get out of CSVTransformer
      val transformedValues = transformedDf.first().toSeq.toList.map {
        // convert arrays for comparison
        case arr: Array[_] => arr.toSeq.toList

        // compare raw value for custom types
        case bigInt: BigIntUnsignedValue => bigInt.value
        case datetime: DatetimeValue => datetime.value
        case json: JsonValue => json.value
        case geo: GeographyValue => geo.value
        case geoPoint: GeographyPointValue => geoPoint.value

        case default => default
      }

      // Fields we expect to get out of CSVTransformer
      val expectedValues = List(
        new BigIntUnsignedValue(999999999).value,
        10000000.toLong,
        List('2'.toByte),
        true,
        false,
        1.toByte,
        new Timestamp(114, 1, 2, 0, 0, 0, 0),
        new DatetimeValue(new Timestamp(114, 1, 2, 12, 25, 35, 0)),
        new BigDecimal(7.3),
        4.7,
        1.2f,
        new GeographyValue("geo1").value,
        new GeographyPointValue("geopoint1").value,
        2048,
        2049,
        new JsonValue("[]").value,
        256.toShort,
        "text",
        new Timestamp(114, 1, 2, 12, 25, 36, 0),
        "string",
        "notype"
      )

      // Compare everything except BigDecimals
      assert(
        expectedValues.filterNot(_.isInstanceOf[BigDecimal])
          == transformedValues.filterNot(_.isInstanceOf[BigDecimal]))

      // Compare BigDecimals without getting precision errors
      assert(
        expectedValues.filter(_.isInstanceOf[BigDecimal]).map(_.asInstanceOf[BigDecimal].doubleValue)
          == transformedValues.filter(_.isInstanceOf[BigDecimal]).map(_.asInstanceOf[BigDecimal].doubleValue)
      )
    }
  }
}
