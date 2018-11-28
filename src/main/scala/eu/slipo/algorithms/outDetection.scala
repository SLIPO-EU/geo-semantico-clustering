package eu.slipo.algorithms

import org.apache.spark.sql._
import com.typesafe.config.ConfigFactory
import net.sansa_stack.rdf.spark.io.NTripleReader
import org.apache.jena.graph.Triple
import org.apache.spark.rdd.RDD
import java.io.{ File, FilenameFilter }
import org.apache.jena.graph.Node
import eu.slipo.datatypes.CoordinatePOI
import com.typesafe.config.Config
import org.apache.commons.math3.stat.descriptive._

class outDetection(spark: SparkSession, dataRDD: RDD[Triple]) extends Serializable {
  def run(): RDD[(String, Iterable[(Node, Double)])] = {

    spark.sparkContext.setLogLevel("ERROR")

    println(spark.conf.getAll.mkString("\n"))

    val objectLiteral = dataRDD.filter(f => f.getObject.isLiteral())

    val dataTriple = objectLiteral.map(f => (f.getSubject, f.getPredicate, f.getObject))
    val latLongFilter = latLongcleaning(dataTriple)

    val coordinateRemoved = dataTriple.filter(f => !f._2.toString().contains("http://www.opengis.net/ont/geosparql#asWKT"))

    val numberPropertyRemoved = coordinateRemoved.filter(f => !f._2.toString().contains("http://slipo.eu/def#number"))

    val phoneNumberRemoved = numberPropertyRemoved.filter(f => !f._2.toString().contains("http://slipo.eu/def#contactValue"))

    val removeXSD = phoneNumberRemoved.map(f => (f._1, f._2, removeXSDtype(f._3)))

    val numberFilter = removeXSD.filter(f => isNumeric(f._3))

    val groupbyPredicate = numberFilter.map(f => (f._2, (f._1, f._3))) //.groupByKey()

    val groupBy = groupbyPredicate.groupByKey()

    val removeComma = groupBy.map(f => (f._1, f._2.map(f => (f._1, f._2.replaceAll("\"", "")))))
    val phoneLiterals = removeComma.map(f => (f._1.toString(), f._2.map(f => (f._1, removeExtraLiteral(f._2)))))
    val finalRDD = latLongFilter.union(phoneLiterals)
    val longAnomaly = finalRDD.map(f => (f._1, iqr2(f._2)))

    longAnomaly

  }
  def isNumeric(x: String): Boolean =
    {
      if (x.contains("^")) {
        val c = x.indexOf('^')
        val subject = x.substring(1, c - 2)

        isAllDigits(subject)
      } else
        isAllDigits(x)
    }

  def isAllDigits(x: String): Boolean = {
    var found = false
    for (ch <- x) {
      if (ch.isDigit || ch == '.' || ch == '+' || ch == '-')
        found = true
      else if (ch.isLetter) {

        found = false
        return found
      }
    }

    found
  }

  def removeXSDtype(node: Node): String =
    {
      val nodeStr = node.toString()
      if (nodeStr.contains("^")) {
        val index = nodeStr.indexOf('^')
        val obj = nodeStr.substring(1, index - 1)
        obj
      } else
        nodeStr

    }
  def getPOICoordinates1(dataRDD: String): String = {
    val pattern = "POINT(.+ .+)".r
    val poiCoordinatesString = dataRDD.replace("http://www.opengis.net/def/crs/EPSG", "")
    val coordinate = pattern.findFirstIn(poiCoordinatesString).head.replace("POINT", "")
    coordinate
  }

  def removeExtraLiteral(dataRDD: String): Double = {
    val removeSpecialCharcter = dataRDD.replaceAll("\\+", "").replaceAll("-", "").
      replaceAll("\\(", "").replaceAll("\\)", "").toDouble
    removeSpecialCharcter
  }
  def coordinateAnomaly(coordinate: RDD[(Node, Iterable[(Node, CoordinatePOI)])]): RDD[(String, Iterable[(Node, Double)])] =
    {
      val lat = coordinate.map(f => (f._1.toString() + "/lat", f._2.map(f => (f._1, f._2.latitude))))

      val long = coordinate.map(f => (f._1.toString() + "/long", f._2.map(f => (f._1, f._2.longitude))))

      val coordinateAnomaly = lat.union(long)
      coordinateAnomaly
    }

  def iqr2(cluster: Iterable[(org.apache.jena.graph.Node, Double)]): Iterable[(Node, Double)] = {

    val stringToDuble = cluster.map(b => (b._2.toString()).toDouble).toArray
    val arrMean = new DescriptiveStatistics()
    genericArrayOps(stringToDuble).foreach(v => arrMean.addValue(v))

    val Q1 = arrMean.getPercentile(25)

    val Q3 = arrMean.getPercentile(75)

    val IQR = Q3 - Q1

    val lowerRange = Q1 - 2.5 * IQR

    val upperRange = Q3 + 2.5 * IQR

    val filterOutlier = stringToDuble.filter(p => (p < lowerRange || p > upperRange))

    val outliers = cluster.filter(f => search(f._2.toString().toDouble, filterOutlier))

    outliers

  }

  def search(a: Double, b: Array[Double]): Boolean = {
    if (b.contains(a)) true
    else false
  }

  def latLongcleaning(latlong: RDD[(Node, Node, Node)]): RDD[(String, Iterable[(Node, Double)])] = {
    //  val dataTriple=latlong.map(f=>(f.getSubject,f.getPredicate,f.getObject))
    val filterPOI = latlong.filter(f => f._2.toString() == "http://www.opengis.net/ont/geosparql#asWKT")
    val removeExtraLiteral = filterPOI.map(f => (f._1, f._2, getPOICoordinates1(f._3.toString())))
    val groupbyPredicate = removeExtraLiteral.map(f => (f._2, (f._1, f._3.toString()))).groupByKey()

    val latLong = groupbyPredicate.map(f => (f._1, (f._2.map(f => (f._1, f._2.replace("\"^^http://www.opengis.net/ont/geosparql#wktLiteral", "").replace("(", "").replace(")", "").replace("\"", "").split(" "))))))

    val coordinate = latLong.map(f => (f._1, (f._2.map(f => (f._1, CoordinatePOI(f._2(0).toDouble, f._2(1).toDouble))))))

    val latLongSeparated = coordinateAnomaly(coordinate)
    latLongSeparated

  }

}

object outDetection {
  def apply(sparkSession: SparkSession, nTriplesRDD: RDD[Triple]) = new outDetection(sparkSession, nTriplesRDD)
}