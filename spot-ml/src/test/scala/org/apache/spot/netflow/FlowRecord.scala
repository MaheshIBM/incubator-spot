package org.apache.spot.netflow

/**
  * Created by nlsegerl on 5/16/17.
  */
case class FlowRecord(treceived: String,
                      tryear: Int,
                      trmonth: Int,
                      trday: Int,
                      trhour: Int,
                      trminute: Int,
                      trsec: Int,
                      tdur: Float,
                      sip: String,
                      dip: String,
                      sport: Int,
                      dport: Int,
                      proto: String,
                      ipkt: Int,
                      ibyt: Int,
                      opkt: Int,
                      obyt: Int)
