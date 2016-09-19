package org.opennetworkinsight.dns


import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.opennetworkinsight.OniLDACWrapper.{OniLDACInput, OniLDACOutput}
import org.opennetworkinsight.SuspiciousConnectsArgumentParser.SuspiciousConnectsConfig
import org.opennetworkinsight.dns.DNSSchema._
import org.opennetworkinsight.utilities.{CountryCodes, Quantiles, TopDomains}
import org.opennetworkinsight.{OniLDACWrapper, SuspiciousConnectsScoreFunction}
import org.slf4j.Logger


class DNSSuspiciousConnectsModel(_topicCount: Int,
                                 _ipToTopicMix: Map[String, Array[Double]],
                                 _wordToPerTopicProb: Map[String, Array[Double]],
                                 _timeCuts: Array[Double],
                                 _frameLengthCuts: Array[Double],
                                 _subdomainLengthCuts: Array[Double],
                                 _numberPeriodsCuts: Array[Double],
                                 _entropyCuts: Array[Double]) {

  val topicCount = _topicCount
  val ipToTopicMix = _ipToTopicMix
  val wordToPerTopicProb = _wordToPerTopicProb
  val timeCuts = _timeCuts
  val frameLengthCuts = _frameLengthCuts
  val subdomainLengthCuts = _subdomainLengthCuts
  val numberPeriodsCuts = _numberPeriodsCuts
  val entropyCuts = _entropyCuts


  /**
    * Assign scores to a
 *
    * @param sc
    * @param sqlContext
    * @param inDF
    * @return
    */
  def score(sc: SparkContext, sqlContext: SQLContext, inDF: DataFrame): DataFrame = {

    val countryCodesBC = sc.broadcast(CountryCodes.CountryCodes)
    val topDomainsBC = sc.broadcast(TopDomains.TOP_DOMAINS)

    val udfWordCreation = DNSWordCreation.udfWordCreation(frameLengthCuts,
      timeCuts,
      subdomainLengthCuts,
      entropyCuts,
      numberPeriodsCuts,
      countryCodesBC,
      topDomainsBC)


    val ipToTopicMixBC = sc.broadcast(ipToTopicMix)
    val wordToPerTopicProbBC = sc.broadcast(wordToPerTopicProb)

    val scoreFunction = new DNSScoreFunction(frameLengthCuts,
      timeCuts,
      subdomainLengthCuts,
      entropyCuts,
      numberPeriodsCuts,
      topicCount,
      ipToTopicMixBC,
      wordToPerTopicProbBC,
      countryCodesBC,
      topDomainsBC)


    def udfScoreFunction = udf((timeStamp: String,
                                unixTimeStamp: Long,
                                frameLength: Int,
                                clientIP: String,
                                queryName: String,
                                queryClass: String,
                                queryType: Int,
                                queryResponseCode: Int) =>
      scoreFunction.score(timeStamp: String,
        unixTimeStamp: Long,
        frameLength: Int,
        clientIP: String,
        queryName: String,
        queryClass: String,
        queryType: Int,
        queryResponseCode: Int))

    inDF.withColumn(Score, udfScoreFunction(ModelColumns: _*))
  }
}

/**
  * Methods for creating a DNS Suspicious Connects model.
  */
object DNSSuspiciousConnectsModel {

  /**
    * Create a new DNS Suspicious Connects model by training it on a dataframe.
 *
    * @param sparkContext
    * @param sqlContext
    * @param logger
    * @param config Analysis configuration object containing CLI parameters.
    * @param inDF Data used to train the model.
    * @param topicCount Number of topics (traffic profiles) used to build the model.
    * @return DNSSuspiciousConnectsModel
    */
  def trainNewModel(sparkContext: SparkContext,
                    sqlContext: SQLContext,
                    logger: Logger,
                    config: SuspiciousConnectsConfig,
                    inDF: DataFrame,
                    topicCount: Int): DNSSuspiciousConnectsModel = {

    logger.info("DNS pre LDA starts")


    print("Read source data")
    logger.info("Read source data")

    val selectedDF = inDF.select(ModelColumns:_*)

    val totalDataDF = selectedDF.unionAll(DNSFeedback.loadFeedbackDF(sparkContext,
      sqlContext,
      config.scoresFile,
      config.duplicationFactor))

    val countryCodesBC = sparkContext.broadcast(CountryCodes.CountryCodes)
    val topDomainsBC = sparkContext.broadcast(TopDomains.TOP_DOMAINS)

    // add the derived fields

    val df = DNSWordCreation.addDerivedFields(sparkContext, sqlContext, countryCodesBC, topDomainsBC, totalDataDF)

    // adding cuts

    val timeCuts = Quantiles.computeDeciles(df
      .select(UnixTimestamp)
      .rdd
      .map({ case Row(unixTimeStamp: Long) => unixTimeStamp.toDouble }))


    val frameLengthCuts = Quantiles.computeDeciles(df
      .select(FrameLength)
      .rdd
      .map({ case Row(frameLen: Int) => frameLen.toDouble }))


    val subdomainLengthCuts = Quantiles.computeQuintiles(df
      .filter(SubdomainLength + " > 0")
      .select(SubdomainLength)
      .rdd
      .map({ case Row(subdomainLength: Double) => subdomainLength }))


    val entropyCuts = Quantiles.computeQuintiles(df
      .filter(SubdomainEntropy + " > 0")
      .select(SubdomainEntropy)
      .rdd
      .map({ case Row(subdomainEntropy: Double) => subdomainEntropy }))

    val numberPeriodsCuts = Quantiles.computeQuintiles(df
      .filter(NumPeriods + " > 0")
      .select(NumPeriods)
      .rdd
      .map({ case Row(numberPeriods: Double) => numberPeriods }))

    val udfWordCreation = DNSWordCreation.udfWordCreation(frameLengthCuts, timeCuts,
      subdomainLengthCuts, entropyCuts, numberPeriodsCuts, countryCodesBC,
      topDomainsBC)



    val dataWithWordDF = df.withColumn(Word, udfWordCreation(ModelColumns:_*))

    val ipDstWordCounts =
      dataWithWordDF.select(ClientIP, Word).map({ case Row(destIP: String, word: String) => (destIP, word) -> 1 })
        .reduceByKey(_ + _)
        .map({ case ((ipDst, word), count) => OniLDACInput(ipDst, word, count) })

    val OniLDACOutput(ipToTopicMix, wordToPerTopicProb) =
      OniLDACWrapper.runLDA(ipDstWordCounts, config.modelFile, config.topicDocumentFile, config.topicWordFile,
        config.mpiPreparationCmd, config.mpiCmd, config.mpiProcessCount, config.mpiTopicCount, config.localPath,
        config.ldaPath, config.localUser, config.analysis, config.nodes)

    new DNSSuspiciousConnectsModel(topicCount,
      ipToTopicMix,
      wordToPerTopicProb,
      timeCuts,
      frameLengthCuts,
      subdomainLengthCuts,
      numberPeriodsCuts,
      entropyCuts)
  }


}