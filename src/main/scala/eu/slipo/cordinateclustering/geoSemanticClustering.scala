package eu.slipo.cordinateclustering

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
import eu.slipo.utils.tomTomDataFiltering
import eu.slipo.datatypes.appConfig
import org.json4s.native.JsonMethods.parse
import eu.slipo.algorithms.DBSCANcoordinateClustering
import eu.slipo.utils.tomTomDataProcessing
import com.vividsolutions.jts.geom.Coordinate
import com.vividsolutions.jts.geom.GeometryFactory

object geoSemanticClustering {

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
    // val conf1 = parse("").extract[appConfig]
    val tomTomData1 = new tomTomDataFiltering(spark, conf)

    val tomTomData = new tomTomDataProcessing(spark = spark, conf = conf)
    tomTomData.dataRDD.take(1).foreach(println)
    val geometryFactory = new GeometryFactory()
    val pois = tomTomData.pois
    val dbParam = pois.map { f =>
      val id = f.poi_id.toString()
      val name = ""
      val lat = f.coordinate.latitude
      val long = f.coordinate.longitude
      val cat = f.categories.categories.mkString(";")
      val point = geometryFactory.createPoint(new Coordinate(long, lat))

      point.setUserData(id)
      point
    }
    val dbscan = new DBSCANcoordinateClustering()
    val clusteredLatLong = dbscan.dbclusters(dbParam, 0.01, 2, spark)

    val clusterIDPOIidPair = clusteredLatLong.map(arr => (arr._1, arr._2.map(f => f._2.poiId.toLong)))

    val broadcastKV = spark.sparkContext.broadcast(clusterIDPOIidPair.collect())
    val clusterIDTripleKV = broadcastKV.value.map(f => (tomTomData1.get_triples(f._1, f._2, tomTomData.dataRDD, spark)))
    clusterIDTripleKV.map(f=>f.take(10)foreach(println))
    dbscan.clear()
    spark.stop()
  }

}
    
