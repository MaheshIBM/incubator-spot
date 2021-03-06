/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spot.dns

import org.apache.log4j.Logger
import org.apache.spark.SparkContext
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spot.SuspiciousConnects.SuspiciousConnectsAnalysisResults
import org.apache.spot.SuspiciousConnectsArgumentParser.SuspiciousConnectsConfig
import org.apache.spot.dns.DNSSchema._
import org.apache.spot.dns.model.DNSSuspiciousConnectsModel
import org.apache.spot.dns.model.DNSSuspiciousConnectsModel.ModelSchema
import org.apache.spot.proxy.ProxySchema.Score
import org.apache.spot.utilities.data.validation.{InvalidDataHandler => dataValidation}

/**
  * The suspicious connections analysis of DNS log data develops a probabilistic model the DNS queries
  * made by each client IP and flags those assigned a low probability as "suspicious"
  */

object DNSSuspiciousConnectsAnalysis {



  /**
    * Run suspicious connections analysis on DNS log data.
    * Saves the most suspicious connections to a CSV file on HDFS.
    *
    * @param config Object encapsulating runtime parameters and CLI options.
    * @param sparkContext
    * @param sqlContext
    * @param logger
    */
  def run(config: SuspiciousConnectsConfig, sparkContext: SparkContext, sqlContext: SQLContext, logger: Logger,
          inputDNSRecords: DataFrame): SuspiciousConnectsAnalysisResults = {


    logger.info("Starting DNS suspicious connects analysis.")

    val dnsRecords = filterRecords(inputDNSRecords)
      .select(InSchema: _*)
      .na.fill(DefaultQueryClass, Seq(QueryClass))
      .na.fill(DefaultQueryType, Seq(QueryType))
      .na.fill(DefaultQueryResponseCode, Seq(QueryResponseCode))

    logger.info("Fitting probabilistic model to data")
    val model =
      DNSSuspiciousConnectsModel.trainModel(sparkContext, sqlContext, logger, config, dnsRecords)

    logger.info("Identifying outliers")
    val scoredDNSRecords = model.score(sparkContext, sqlContext, dnsRecords, config.userDomain)

    val filteredScored = filterScoredRecords(scoredDNSRecords, config.threshold)

    val orderedDNSRecords = filteredScored.orderBy(Score)

    val mostSuspiciousDNSRecords =
      if (config.maxResults > 0) orderedDNSRecords.limit(config.maxResults)
      else orderedDNSRecords

    val outputDNSRecords = mostSuspiciousDNSRecords.select(OutSchema: _*)

    val invalidDNSRecords = filterInvalidRecords(inputDNSRecords).select(InSchema: _*)

    SuspiciousConnectsAnalysisResults(outputDNSRecords, invalidDNSRecords)

  }


  /**
    *
    * @param inputDNSRecords raw DNS records.
    * @return
    */
  def filterRecords(inputDNSRecords: DataFrame): DataFrame = {

    val cleanDNSRecordsFilter = inputDNSRecords(Timestamp).isNotNull &&
      inputDNSRecords(Timestamp).notEqual("") &&
      inputDNSRecords(Timestamp).notEqual("-") &&
      inputDNSRecords(UnixTimestamp).geq(0) &&
      inputDNSRecords(FrameLength).geq(0) &&
      inputDNSRecords(QueryName).isNotNull &&
      inputDNSRecords(QueryName).notEqual("") &&
      inputDNSRecords(QueryName).notEqual("-") &&
      inputDNSRecords(QueryName).notEqual("(empty)") &&
      inputDNSRecords(ClientIP).isNotNull &&
      inputDNSRecords(ClientIP).notEqual("") &&
      inputDNSRecords(ClientIP).notEqual("-") &&
      ((inputDNSRecords(QueryClass).isNotNull &&
        inputDNSRecords(QueryClass).notEqual("") &&
        inputDNSRecords(QueryClass).notEqual("-")) ||
        inputDNSRecords(QueryType).isNotNull ||
        inputDNSRecords(QueryResponseCode).geq(0))

    inputDNSRecords
      .filter(cleanDNSRecordsFilter)
  }


  /**
    *
    * @param inputDNSRecords raw DNS records.
    * @return
    */
  def filterInvalidRecords(inputDNSRecords: DataFrame): DataFrame = {

    val invalidDNSRecordsFilter = inputDNSRecords(Timestamp).isNull ||
      inputDNSRecords(Timestamp).equalTo("") ||
      inputDNSRecords(Timestamp).equalTo("-") ||
      inputDNSRecords(UnixTimestamp).isNull ||
      inputDNSRecords(FrameLength).isNull ||
      inputDNSRecords(QueryName).isNull ||
      inputDNSRecords(QueryName).equalTo("") ||
      inputDNSRecords(QueryName).equalTo("-") ||
      inputDNSRecords(QueryName).equalTo("(empty)") ||
      inputDNSRecords(ClientIP).isNull ||
      inputDNSRecords(ClientIP).equalTo("") ||
      inputDNSRecords(ClientIP).equalTo("-") ||
      ((inputDNSRecords(QueryClass).isNull ||
        inputDNSRecords(QueryClass).equalTo("") ||
        inputDNSRecords(QueryClass).equalTo("-")) &&
        inputDNSRecords(QueryType).isNull &&
        inputDNSRecords(QueryResponseCode).isNull)

    inputDNSRecords
      .filter(invalidDNSRecordsFilter)
  }


  /**
    *
    * @param scoredDNSRecords scored DNS records.
    * @param threshold score tolerance.
    * @return
    */
  def filterScoredRecords(scoredDNSRecords: DataFrame, threshold: Double): DataFrame = {


    val filteredDNSRecordsFilter = scoredDNSRecords(Score).leq(threshold) &&
      scoredDNSRecords(Score).gt(dataValidation.ScoreError)

    scoredDNSRecords.filter(filteredDNSRecordsFilter)
  }

  val DefaultQueryClass = "unknown"
  val DefaultQueryType = -1
  val DefaultQueryResponseCode = -1

  val InStructType = StructType(List(TimestampField, UnixTimestampField, FrameLengthField, ClientIPField,
    QueryNameField, QueryClassField, QueryTypeField, QueryResponseCodeField))

  val InSchema = InStructType.fieldNames.map(col)

  assert(ModelSchema.fields.forall(InStructType.fields.contains(_)))

  val OutSchema = StructType(
    List(TimestampField,
      UnixTimestampField,
      FrameLengthField,
      ClientIPField,
      QueryNameField,
      QueryClassField,
      QueryTypeField,
      QueryResponseCodeField,
      ScoreField)).fieldNames.map(col)

}