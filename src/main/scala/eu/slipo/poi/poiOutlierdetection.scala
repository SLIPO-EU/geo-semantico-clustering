package eu.slipo.poi

import java.io.PrintWriter

import org.apache.spark.sql._
import eu.slipo.utils.Common
import org.json4s._
import com.typesafe.config.ConfigFactory
import org.json4s.jackson.Serialization
import net.sansa_stack.rdf.spark.io.NTripleReader
import org.apache.jena.graph.Triple
import org.apache.spark.rdd.RDD
import java.io.{ File, FilenameFilter }
import org.apache.jena.graph.Node
import com.typesafe.config.Config
import eu.slipo.algorithms.outDetection

object poiOutlierdetection {

  /**
   * main function
   */
  def main(args: Array[String]) {
    implicit val formats = DefaultFormats
    val conf = ConfigFactory.load()
    // System.setProperty("hadoop.home.dir", "C:\\Hadoop") // for Windows system
    val spark = SparkSession.builder
      .master(conf.getString("slipo.spark.master"))
      .config("spark.serializer", conf.getString("slipo.spark.serializer"))
      .config("spark.executor.memory", conf.getString("slipo.spark.executor.memory"))
      .config("spark.driver.memory", conf.getString("slipo.spark.driver.memory"))
      .config("spark.driver.maxResultSize", conf.getString("slipo.spark.driver.maxResultSize"))
      //.config("spark.memory.fraction", conf.getString("spark.memory.fraction"))
      .appName(conf.getString("slipo.spark.app.name"))
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")

    println(spark.conf.getAll.mkString("\n"))

    val dataRDD: RDD[Triple] = loadNTriple(spark, conf.getString("slipo.data.input"))
    val anomalyDetection = new outDetection(spark, dataRDD)
    val output = anomalyDetection.run()
    val triple = output.map({ iter =>
      val a = iter._1
      val b = iter._2.map(f => (f._1, a, f._2))
      b
    })
    val oulierTriple = triple.flatMap(f => f)
    oulierTriple.coalesce(1).saveAsTextFile(conf.getString("slipo.anomaly.detection.output"))
  }

  def loadNTriple(spark: SparkSession, tripleFilePath: String): RDD[Triple] = {
    val tripleFile = new File(tripleFilePath)
    if (tripleFile.isDirectory) {
      val files = tripleFile.listFiles(new FilenameFilter() {
        def accept(tripleFile: File, name: String): Boolean = {
          !(name.toString.contains("SUCCESS") || name.toLowerCase.endsWith(".crc"))
        }
      })
      var i = 0
      var triple_0 = NTripleReader.load(spark, files(0).getAbsolutePath)
      for (file <- files) {
        if (i != 0) {
          triple_0 = triple_0.union(NTripleReader.load(spark, file.getAbsolutePath))
        }
        i += 1
      }
      triple_0
    } else {
      NTripleReader.load(spark, tripleFile.getAbsolutePath)
    }
  }
}
    
