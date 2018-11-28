package eu.slipo.poi

import java.io.PrintWriter

import org.apache.spark.sql._
import eu.slipo.algorithms.{ Distances, Encoder, Kmeans, PIC }
import eu.slipo.datatypes._
import eu.slipo.utils.tomTomDataProcessing
import eu.slipo.utils.Common
import org.json4s._
import com.typesafe.config.ConfigFactory
import org.json4s.jackson.Serialization
import scala.collection.mutable
import eu.slipo.utils.JsonToCsv
object semanticGeoClustering {
  val conf = ConfigFactory.load()
  /**
   * main function
   */
  def main(args: Array[String]) {
    implicit val formats = DefaultFormats

    val profileWriter = new PrintWriter(conf.getString("slipo.clustering.profile"))
    val picFileWriter = new PrintWriter(conf.getString("slipo.clustering.pic.result"))
    val oneHotKMFileWriter = new PrintWriter(conf.getString("slipo.clustering.km.onehot.result"))
    val mdsKMFileWriter = new PrintWriter(conf.getString("slipo.clustering.km.mds.result"))
    val word2VecKMFileWriter = new PrintWriter(conf.getString("slipo.clustering.km.word2vec.result"))
    val picDistanceMatrixWriter = new PrintWriter(conf.getString("slipo.clustering.pic.matrix"))
    val mdsCoordinatesWriter = new PrintWriter(conf.getString("slipo.clustering.km.mds.matrix"))
    val oneHotMatrixWriter = new PrintWriter(conf.getString("slipo.clustering.km.onehot.matrix"))
    val word2VecWriter = new PrintWriter(conf.getString("slipo.clustering.km.word2vec.matrix"))
    val kMeansclusterlabel = new PrintWriter(conf.getString("slipo.clustering.km.clusterlabel.result"))
    val kMeanword2VecClusterlabel = new PrintWriter(conf.getString("slipo.clustering.kmW2V.clusterlabel.result"))
    val picClusterLabel = new PrintWriter(conf.getString("slipo.clustering.pic.clusterlabel.result"))
    val mdsClusterLabel = new PrintWriter(conf.getString("slipo.clustering.mds.clusterlabel.result"))
    // System.setProperty("hadoop.home.dir", "C:\\Hadoop") // for Windows system
    val spark = SparkSession.builder
      .master(conf.getString("slipo.spark.master"))
      .config("spark.serializer", conf.getString("slipo.spark.serializer"))
      .config("spark.executor.memory", conf.getString("slipo.spark.executor.memory"))
      .config("spark.driver.memory", conf.getString("slipo.spark.driver.memory"))
      .config("spark.driver.maxResultSize", conf.getString("slipo.spark.driver.maxResultSize"))
      .config("spark.ui.port", conf.getInt("slipo.spark.ui.port"))
      .config("spark.executor.cores", conf.getInt("slipo.spark.executor.cores"))
      .config("spark.executor.heartbeatInterval", conf.getLong("slipo.spark.executor.heartbeatInterval"))
      .config("spark.network.timeout", conf.getLong("slipo.spark.network.timeout"))
      //.config("spark.memory.fraction", conf.getString("spark.memory.fraction"))
      .appName(conf.getString("slipo.spark.app.name"))
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")

    println(spark.conf.getAll.mkString("\n"))
    val t0 = System.nanoTime()
    val tomTomData = new tomTomDataProcessing(spark = spark, conf = conf)

    val pois = tomTomData.pois
    pois.map(f => (f.poi_id, f.coordinate, f.categories)).take(10).foreach(println)
    println("Number of pois: " + pois.count().toString)
    //val poiCategorySetVienna = tomTomData.poiCategoryId
    val poiCategorySetVienna = pois.map(poi => (poi.poi_id, poi.categories.categories.toSet)).persist()
    profileWriter.println(pois.count())
    profileWriter.println(poiCategorySetVienna.count())
    val t1 = System.nanoTime()
    profileWriter.println("Elapsed time preparing data: " + (t1 - t0) / 1000000000 + "s")

    // one hot encoding
    println("Start one hot encoding km")
    val (oneHotDF, oneHotMatrix) = new Encoder().oneHotEncoding(poiCategorySetVienna, spark)
    Serialization.writePretty(oneHotMatrix, oneHotMatrixWriter)
    val oneHotClusters = new Kmeans().kmClustering(
      numClusters = conf.getInt("slipo.clustering.km.onehot.number_clusters"),
      maxIter = conf.getInt("slipo.clustering.km.onehot.iterations"),
      df = oneHotDF,
      spark = spark)

    val kMeanCluster = Common.writeClusteringResult(spark.sparkContext, oneHotClusters, pois, oneHotKMFileWriter)
    assignClusterLabel(kMeanCluster, kMeansclusterlabel, "kmeanscluster")
    println("End one hot encoding km")

    // word2Vec encoding
    println("Start word2vec encoding km")
    val (avgVectorDF, word2Vec) = new Encoder().wordVectorEncoder(poiCategorySetVienna, spark)
    Serialization.writePretty(word2Vec.collect(), word2VecWriter)
    val avgVectorClusters = new Kmeans().kmClustering(
      numClusters = conf.getInt("slipo.clustering.km.word2vec.number_clusters"),
      maxIter = conf.getInt("slipo.clustering.km.word2vec.iterations"),
      df = avgVectorDF,
      spark = spark)
    val kMeanword2VecCluster = Common.writeClusteringResult(spark.sparkContext, avgVectorClusters, pois, word2VecKMFileWriter)

    assignClusterLabel(kMeanword2VecCluster, kMeanword2VecClusterlabel, "kMeanword2VecClusterlabel")

    println("End one hot encoding km")
    val t3 = System.nanoTime()
    profileWriter.println("Elapsed time word2Vec: " + (t3 - t0) / 1000000000 + "s")
    println("End one hot encoding km")

    println("Start PIC")
   
    println("Start cartesian")
    println(poiCategorySetVienna.count())
    println(poiCategorySetVienna.take(1).mkString(","))
    poiCategorySetVienna.collect().foreach(x => println(x._1))
    poiCategorySetVienna.foreach(f => println(f._2.size))
    val poiCartesian = poiCategorySetVienna.cartesian(poiCategorySetVienna)
    println("Cartesian: " + poiCartesian.count())
    val pairwisePOICategorySet = poiCartesian.filter {
      case (a, b) => {
        println(a._1.toString + "; " + b._1.toString)
        a._1 < b._1
      }
    }
    println(pairwisePOICategorySet.count())
    println("end of cartesian")
    // from ((sid, ()), (did, ())) to (sid, did, similarity)
    val pairwisePOISimilarity = pairwisePOICategorySet.map(x => (x._1._1.toLong, x._2._1.toLong,
      new Distances().jaccardSimilarity(x._1._2, x._2._2))).persist()

    println("get similarity matrix")

    val picDistanceMatrix = pairwisePOISimilarity.map(x => Distance(x._1, x._2, 1 - x._3)).collect()
    Serialization.writePretty(picDistanceMatrix, picDistanceMatrixWriter)
    picDistanceMatrixWriter.close()

    println("start pic clustering")
    val clustersPIC = new PIC().picSparkML(
      pairwisePOISimilarity,
      conf.getInt("slipo.clustering.pic.number_clusters"),
      conf.getInt("slipo.clustering.pic.iterations"),
      spark)
    println("end pic clustering")
    val picCluster = Common.writeClusteringResult(spark.sparkContext, clustersPIC, pois, picFileWriter)
    assignClusterLabel(picCluster, picClusterLabel, "picClusterLabel")

    val t4 = System.nanoTime()
    profileWriter.println("Elapsed time cartesian: " + (t4 - t0) / 1000000000 + "s")
    println("End PIC")
    println("Start MDS")
    //// distance RDD, from (sid, did, similarity) to (sid, did, distance)
    val distancePairs = pairwisePOISimilarity.map(x => (x._1, x._2, 1.0 - x._3)).persist()
    val (mdsDF, coordinates) = new Encoder().mdsEncoding(
      distancePairs = distancePairs,
      poiCategorySetVienna.count().toInt,
      dimension = conf.getInt("slipo.clustering.km.mds.dimension"),
      spark = spark)
    val mdsCoordinates = MdsCoordinates(coordinates.map(f => MdsCoordinate(f._1, f._2)))
    Serialization.writePretty(mdsCoordinates, mdsCoordinatesWriter)
    val mdsClusters = new Kmeans().kmClustering(
      numClusters = conf.getInt("slipo.clustering.km.mds.number_clusters"),
      maxIter = conf.getInt("slipo.clustering.km.mds.iterations"),
      df = mdsDF,
      spark = spark)
    val mdsClsuter = Common.writeClusteringResult(spark.sparkContext, mdsClusters, pois, mdsKMFileWriter)
    assignClusterLabel(mdsClsuter, mdsClusterLabel, "mdsClusterLabel")

    val t5 = System.nanoTime()
    profileWriter.println("Elapsed time mds: " + (t5 - t0) / 1000000000 + "s")
    println("End MDS")
    // dbscan clustering, TODO solve scala version flicts with SANSA
    // dbscanClustering(coordinates, spark)
    picFileWriter.close()
    oneHotKMFileWriter.close()
    mdsKMFileWriter.close()
    word2VecKMFileWriter.close()
    profileWriter.close()
    mdsCoordinatesWriter.close()
    oneHotMatrixWriter.close()
    word2VecWriter.close()
    spark.stop()
  }

  def assignClusterLabel(listofCluster: List[Cluster], clusterlabel: PrintWriter, name: String) {
    val find1 = listofCluster.map(f => (f.cluster_id, LDA(f.poi_in_cluster)))

    val kmeansCat = find1.map({
      case (a, b) => (a, b.flatMap(f => f).toList)
    })

    /*  val countMaxfreqword=temp1.map(f=>wordcount(f))
     countMaxfreqword.take(1)
 */
    val eachwordCount = kmeansCat.map(f => (f._1, wordCount(f._2)))

    val searchKmeansID = eachwordCount.map({ f =>
      val matchID = listofCluster.filter(g => g.cluster_id == f._1)
      val label = ClusterLabel(f._2, matchID)
      label
    })
    var i = 1
    // val convertToCSV=searchKmeansID.map(f=>(JsonToCsv.clusterLabelToCsv(f,"/results/")))
    val convertToCSV = searchKmeansID.map(f => f.cluster.map({ g =>

      (JsonToCsv.clusterToCsv(g, conf.getString("slipo.semantic.geo.output") + name + i + ".csv", f.label))
      i = i + 1
    }))
    implicit val formats = DefaultFormats
    Serialization.writePretty(searchKmeansID, clusterlabel)

  }

  def LDA(b: Array[Poi]): Array[Set[String]] = {
    val cat = b.map(f => f.categories.categories)
    cat
  }
  /* def wordcount(a: List[String]) {
    println("a=" + a)
    val counts: Map[String, Int] = a.foldLeft(Map.empty[String, Int]) { (map, string) =>
      val count: Int = map.getOrElse(string, 0) //get the current count of the string
      map.updated(string, count + 1) //update the map by incrementing string's counter
    }
    val sortedFrequency: Vector[(String, Int)] = counts.toVector.sortWith(_._2 > _._2)
    println(s"sorted frequency${sortedFrequency}")

    println(s"counts = $counts")

  }*/
  def wordCount(a: List[String]): Vector[String] =
    {
      val separateByspace = a.mkString(" ")

      val counts = mutable.Map.empty[String, Int].withDefaultValue(0)
      for (rawWord <- separateByspace.split("\\s+")) {
        var word = rawWord.toLowerCase

        if (word.contains("@de") || word.contains("@en")) {
          val indx = word.indexOf("@")
          word = word.substring(0, indx)
        }
        counts(word) += 1
      }
      val sortedFrequency: Vector[(String, Int)] = counts.toVector.filter(p => (!p._1.contains("&"))).filter(p => (!p._1.contains("und"))).sortWith(_._2 > _._2)

      val topthree = sortedFrequency.take(3)
      val topthreeLabel = topthree.map(f => f._1)

      topthreeLabel

    }
}
