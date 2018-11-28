package eu.slipo.utils

import java.io._

import org.json4s.native.JsonMethods._
import eu.slipo.datatypes.{ Cluster, Clusters, ClusterLabel }
import org.json4s.DefaultFormats

object JsonToCsv {

  private final val CSV_DILEMITER: String = ","

  def readJson(jsonFilePath: String, csvFilePath: String): Unit = {
    val stream = new FileInputStream(jsonFilePath)
    val stringFromJson = stringBuilder(stream)
    println("String from File: " + stringFromJson)
    val json = try { parse(stringFromJson) } finally { stream.close() }
    //print("Json File: " + json)
    implicit val formats = DefaultFormats
    val clustersJson = json.extract[Clusters]
    // clusterToCsv(clustersJson.clusters(4), csvFilePath)
  }

  def clusterToCsv(cluster: Cluster, csvPath: String, clusterlabel: Vector[String]): Unit = {
    try {
      val bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvPath), "UTF-8"))
      // write header to csv
      val headerLine = new StringBuffer()
      //a val label=clusterlabel.mkString(";")
      headerLine.append("clusterlabel")
      headerLine.append(CSV_DILEMITER)
      headerLine.append("id")
      headerLine.append(CSV_DILEMITER)
      headerLine.append("lat")
      headerLine.append(CSV_DILEMITER)
      headerLine.append("long")
      headerLine.append(CSV_DILEMITER)
      headerLine.append("kwds")
      bw.write(headerLine.toString)
      bw.newLine()
      // write for each poin

      /* oneLine.append(clusterlabel.mkString(";"))
         oneLine.append(CSV_DILEMITER)*/
      cluster.poi_in_cluster.foreach(poi => {
        val oneLine = new StringBuffer()
        oneLine.append(clusterlabel.mkString(";"))
        oneLine.append(CSV_DILEMITER)
        oneLine.append(poi.poi_id)
        oneLine.append(CSV_DILEMITER)
        oneLine.append(poi.coordinate.latitude)
        oneLine.append(CSV_DILEMITER)
        oneLine.append(poi.coordinate.longitude)
        oneLine.append(CSV_DILEMITER)
        oneLine.append(poi.categories.categories.mkString(";"))
        bw.write(oneLine.toString)
        bw.newLine()
      })

      bw.flush()
      bw.close()
    } catch {
      case ioe: IOException =>
      case e: Exception     =>
    }
  }

  def stringBuilder(fis: FileInputStream): String = {
    val br = new BufferedReader(new InputStreamReader(fis, "UTF-8"))
    val sb: StringBuilder = new StringBuilder
    var line: String = null
    while ({
      line = br.readLine()
      //println(line)
      line != null
    }) {
      sb.append(line)
      sb.append('\n')
    }
    sb.toString()
  }

  def main(args: Array[String]): Unit = {
    readJson("/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/results/pic_clusters.json", "/home/rajjat/IAIS/Projects/SANSA_POI/sansa-poi/target/CORE/results/pic_csv79.csv")
  }
}
