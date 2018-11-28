package eu.slipo.datatypes

import eu.slipo.datatypes.dbstatusEnum._


case class DBPOI(val poiId: String,
                 val lon: Double,
                 val lat: Double){

    var dbstatus    = UNDEFINED
    var isDense     = false
    var isBoundary  = false
    var clusterName = ""
}