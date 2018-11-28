package eu.slipo.utils

import org.apache.spark.sql._
import eu.slipo.algorithms.{ Distances, Encoder, Kmeans, PIC }
import eu.slipo.datatypes._
import org.json4s._
import com.typesafe.config.ConfigFactory
import org.json4s.jackson.Serialization
import net.sansa_stack.rdf.spark.io.NTripleReader
import org.apache.jena.graph.Triple
import java.io.{ File, FilenameFilter }
import org.apache.spark.rdd.RDD
import org.apache.jena.graph.Node
import org.apache.commons.lang.StringEscapeUtils
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

object slipotoSANSA {

  def main(args: Array[String]) {
    // System.setProperty("hadoop.home.dir", "C:\\Hadoop") // for Windows system
    implicit val formats = DefaultFormats
    val conf = ConfigFactory.load()
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

    val output1 = "/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/dataMain"
    val output2 = "/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/keywords"
    removePathFiles(Paths.get(output1))
    removePathFiles(Paths.get(output2))

    val temp = "http://example.org/"

    val POI = dataPOI("/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/dataPOI_10000.nt", spark, temp)

    val keywords = sansaKeywords("/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/sansa_keywords.nt", spark, temp)

  }
  def loadNTriple(tripleFilePath: String, spark: SparkSession): RDD[Triple] = {
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

  def dataPOI(path: String, spark: SparkSession, temp: String) {
    val dataRDD: RDD[Triple] = loadNTriple("/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/dataPOI_10000.nt", spark)

    val replaceSlipo = dataRDD.map(f => (
      f.getSubject.toString().replaceAll("http://slipo.eu/", temp),
      f.getPredicate.toString().replaceAll("http://slipo.eu/", temp),
      f.getObject.toString().replaceAll("http://slipo.eu/", temp)))

    // replaceSlipo.take(10).foreach(println)

    val latLong = replaceSlipo.filter(f => f._3.contains("http://www.opengis.net/ont/geosparql#wktLiteral"))

    val removeXSD = latLong.map(f => "<" + f._1 + ">" + " " + "<" + f._2 + ">" + removeXSDtype(f._3))

    val category = replaceSlipo.filter(f => f._2 == "http://example.org/def#nameValue" || f._2 == "http://example.org/def#termValue")

    val categorychnaged = category.map(f => (f._1, f._2, changeCategory(f._3)))

    val removePOINT = replaceSlipo.filter(f => (!f._3.contains("http://www.opengis.net/ont/geosparql#wktLiteral")))
      .filter(f => (f._2 != "http://example.org/def#nameValue")).filter(f => (!f._2.contains("http://example.org/def#accuracyValue"))).
      filter(f => (!f._2.contains("http://example.org/def#termValue"))).filter(f => (!f._2.contains("http://example.org/def#postCode"))).
      filter(f => (!f._2.contains("http://example.org/def#contactValue")))

    val postCode = replaceSlipo.filter(f => f._2 == "http://example.org/def#postCode" || f._2 == "http://example.org/def#contactValue")
    val postcodeChnage = postCode.map(f => (f._1, f._2, postCodeAlter(f._3)))

    val unionOFAll = removePOINT.union(categorychnaged).union(postcodeChnage)
    val replaceBrackets = unionOFAll.map({ f =>
      val subject = "<" + f._1 + ">" + " "
      val predicate = "<" + f._2 + ">"
      var object1: String = null

      if (f._3.contains("http"))
        object1 = "<" + f._3 + ">" + "."
      else
        object1 = f._3 + "."
      (subject, predicate, object1)

    })
    val pointCorrected = replaceBrackets.map({ f =>
      if (f._3.contains("<\"")) {
        val object1 = f._3.replace("<\"", "\"<")
        (f._1, f._2, object1)
      } else
        (f._1, f._2, f._3)

    })

    val replaceb = replaceBrackets.map(f => f._1 + " " + f._2 + " " + f._3)
    val replaceBUnionxsd = replaceb.union(removeXSD)

    replaceBUnionxsd.coalesce(1).saveAsTextFile("/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/dataMain")

  }

  def sansaKeywords(path: String, spark: SparkSession, temp: String) {
    val dataKeywords: RDD[Triple] = loadNTriple(path, spark)
    val chageKeywords = keywordChange(dataKeywords, temp)
    chageKeywords.coalesce(1).saveAsTextFile("/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/data/keywords")

  }

  def removeXSDtype(node: String): String =
    {
      val nodeStr = node.toString()

      val c = nodeStr.indexOf('^')
      val obj = nodeStr.substring(1, c - 1)

      val k = getPOICoordinates1(obj)

      val temp = " " + "\"<http://www.opengis.net/def/crs/EPSG/0/4326>" + " " + "POINT(" + k._1 + " " + k._2 + ")" + "\"" + "^^<http://www.opengis.net/ont/geosparql#wktLiteral>" + "."
      temp
    }

  def getPOICoordinates1(dataRDD: String): (Double, Double) = {
    val pattern = "POINT(.+ .+)".r
    val poiCoordinatesString = dataRDD.replace("http://www.opengis.net/def/crs/EPSG", "")
    val coordinate = pattern.findFirstIn(poiCoordinatesString).head.replace("POINT", "")
    val latLong = coordinate.replace("(", "").replace(")", "").split(" ")
    val r = new scala.util.Random

    val lat = latLong(0).toDouble + +r.nextInt(9) * 0.000001
    val long = latLong(1).toDouble + r.nextInt(9) * 0.000001

    (lat, long)
  }

  def postCodeAlter(postCode: String): String = {
    val r = new scala.util.Random
    val len = postCode.length()

    if (postCode.contains("+")) {

      val number = postCode.replace("+", "").replace("-", "").replace("(", "").replace(")", "").replace("\"", "")

      val numberToString = "\"" + "+" + number.map(f => (f.toInt + 3).toChar).filter(p => p.isDigit) + "\""

      numberToString
    } else {
      val subString = postCode.substring(1, len - 1)
      val post = subString.toInt + r.nextInt(10000)
      val postToString = "\"" + post.toString() + "\""
      postToString
    }
  }

  def changeCategory(cat: String): String = {

    val a = "" + cat.map(f => (f.toInt + 3).toChar) + ""
    val b = StringEscapeUtils.escapeJava(a)
    val c = "\"" + b + "\""
    val d = c.replace("/", "")
    val e = d.replace("#", "")
    val f = e.replace("%", "")
    val h = f.replace("\\", "")
    val g = h.replace(".", "").replace("{", "").replace("}", "").replace("(", "").
      replace(")", "").replace("|", "").replace(":", "").replace("=", "").replace("[", "").replace("]", "")
    /* .replace("\\I", "").
      replace("\\G", "").replace("\\S", "").replace("\\O", "").replace("\\V", "").replace("\\F", "")*/
    //println("a="+a)
    g
  }

  def keywordChange(triple: RDD[Triple], temp: String): RDD[String] = {

    val replaceSlipo = triple.map(f => (
      f.getSubject.toString().replaceAll("http://slipo.eu/", temp),
      f.getPredicate.toString().replaceAll("http://slipo.eu/", temp),
      f.getObject.toString().replaceAll("http://slipo.eu/", temp)))

    //  val objectChnage = triple.map(f => (f.getSubject.toString(), f.getPredicate.toString(), f.getObject.toString()))
    val removeInt = replaceSlipo.filter(f => (!f._2.contains("http://example.org/hasNoReviews")))
    val removeInt1 = removeInt.filter(f => (!f._2.contains("http://example.org/hasRating")))
    val removeInt2 = removeInt1.filter(f => (!f._2.contains("http://example.org/hasSentiment")))

    val mapObject = removeInt2.map(f => (f._1, f._2, changeCategory(f._3)))

    val replaceBrackets = mapObject.map({ f =>
      val subject = "<" + f._1 + ">" + " "
      val predicate = "<" + f._2 + ">"
      val object1 = f._3 + "."
      (subject, predicate, object1)

    })
    val addInt = replaceSlipo.filter(f => f._2 == "http://example.org/hasNoReviews" ||
      f._2 == "http://example.org/hasRating" || f._2 == "http://example.org/hasSentiment")
    addInt.foreach(println)
    val replaceb = replaceBrackets.map(f => f._1 + " " + f._2 + " " + f._3)
    val removeXSD = addInt.map(f => "<" + f._1 + ">" + " " + "<" + f._2 + ">" + " " + f._3 + ".")

    val replaceBUnionxsd = replaceb.union(removeXSD)

    replaceBUnionxsd

  }

  def removePathFiles(root: Path): Unit = {
    if (Files.exists(root)) {
      Files.walkFileTree(root, new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      })
    }
  }

}