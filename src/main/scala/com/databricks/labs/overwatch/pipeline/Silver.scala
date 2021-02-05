package com.databricks.labs.overwatch.pipeline

import java.io.StringWriter
import com.databricks.labs.overwatch.env.{Database, Workspace}
import com.databricks.labs.overwatch.utils.{Config, FailedModuleException, IncrementalFilter, Module, ModuleStatusReport, OverwatchScope, SparkSessionWrapper}
import org.apache.spark.sql.functions._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window

import scala.collection.mutable.ArrayBuffer

class Silver(_workspace: Workspace, _database: Database, _config: Config)
  extends Pipeline(_workspace, _database, _config)
    with SilverTransforms {

  envInit()

  import spark.implicits._

  private val logger: Logger = Logger.getLogger(this.getClass)
  private val sw = new StringWriter

  private def getIncrementalAuditLogDFByTimestamp(moduleID: Int): DataFrame = {
    val filters = Seq(
      IncrementalFilter(
        "timestamp", lit(config.fromTime(moduleID).asUnixTimeMilli),
        lit(config.untilTime(moduleID).asUnixTimeMilli)
      ),
      IncrementalFilter(
        "date",
        date_sub(config.fromTime(moduleID).asColumnTS.cast("date"), 2),
        config.untilTime(moduleID).asColumnTS.cast("date")
      )
    )
    BronzeTargets.auditLogsTarget.asIncrementalDF(filters)
  }

  /**
   * Start and End events can be separated across rolled log files. For example, if a job runs for 22 hours, there
   * will be n Overwatch runs that are processed and the start/end events will not be able to be pieced together
   * since Overwatch only processes the new data for Spark Events especially for spark modules due to performance
   *
   * @param moduleId     ETL ModuleID
   * @param event        The spark event by which to filter
   * @param previousRuns Number of previous Overwatch Runs to include in the search for joins (inclusive). Defaulted
   *                     to 3
   * @return
   */
  private def getLaggingStartEvents(module: Module, event: String, previousRuns: Int = 3): DataFrame = {

    Schema.verifyDF(
      TransformFunctions.previousNOverwatchRuns(
        BronzeTargets.sparkEventLogsTarget,
        config.databaseName,
        previousRuns,
        module
      )
        .filter('Downstream_Processed)
        .filter('Event === event),
      module
    )

  }

  lazy private val newSparkEvents = BronzeTargets.sparkEventLogsTarget.asDF.filter(!'Downstream_Processed)

  /**
   * Module sparkEvents
   * Bronze sources for spark events
   */

  // TODO -- Compare all configurations against defaults and notate non-default configs

  //  According to Michael -- don't use rdd cache
  //  private def cacheAuditLogs(auditModuleIDs: Array[Int]): Unit = {
  //    val minAuditTS = config.lastRunDetail.filter(run => auditModuleIDs.contains(run.moduleID)).map(_.untilTS).min
  //    val minAuditColTS = config.createTimeDetail(minAuditTS).asColumnTS
  //    newAuditLogsDF = newAuditLogsDF
  //      .filter('date >= minAuditColTS.cast("date"))
  //      .repartition(getTotalCores).cache
  //    newAuditLogsDF.count()
  //  }

  // Slack Chat
  // https://databricks.slack.com/archives/C04SZU99Q/p1588959876188200
  // Todo -- Only return filenameGroup with specific request
  // Todo -- ODBC/JDBC
  /**
   * ODBC/JDBC Sessions
   */

  //    val serverSessionStartDF: DataFrame = sparkEventsDF
  //      .filter('Event === "org.apache.spark.sql.hive.thriftserver.ui.SparkListenerThriftServerSessionCreated")
  //      .select('SparkContextID, 'ip, 'sessionId, 'startTime, 'userName, 'filenameGroup.alias("startFilenameGroup"))
  //
  //    val serverSessionEndDF: DataFrame = sparkEventsDF
  //      .filter('Event === "org.apache.spark.sql.hive.thriftserver.ui.SparkListenerThriftServerSessionClosed")
  //      .select('SparkContextID, 'sessionId, 'finishTime, 'filenameGroup.alias("endFilenameGroup"))
  //
  //    val serverOperationStartDF: DataFrame = sparkEventsDF
  //      .filter('Event === "org.apache.spark.sql.hive.thriftserver.ui.SparkListenerThriftServerOperationStart")
  //      .select('SparkContextID, 'groupId, 'id, 'sessionId, 'startTime, 'statement, 'userName,
  //        'filenameGroup.alias("startFilenameGroup"))
  //
  //    val serverOperationEndDF: DataFrame = sparkEventsDF
  //      .filter('Event === "org.apache.spark.sql.hive.thriftserver.ui.SparkListenerThriftServerOperationClosed")
  //      .select('SparkContextID, 'id, 'closeTime, 'filenameGroup.alias("endFilenameGroup"))

  //    private val serverSessionDF: DataFrame = serverSessionStartDF
  //      .join(serverSessionEndDF, Seq("SparkContextID", "sessionId"))
  //      .withColumn("ServerSessionRunTime", subtractTime('startTime, 'finishTime))
  //      .drop("startTime", "finishTime")
  //
  //    private val serverOperationDF: DataFrame = serverOperationStartDF
  //      .join(serverOperationEndDF, Seq("SparkContextID", "id"))
  //      .withColumn("ServerOperationRunTime", subtractTime('startTime, 'closeTime))
  //      .drop("startTime", "finishTime")

  /**
   * Executor
   */

  //  lazy private val executorAddedDF: DataFrame = sparkEventsDF
  //    .filter('Event === "SparkListenerExecutorAdded")
  //    .select('SparkContextID, 'ExecutorID, 'ExecutorInfo, 'Timestamp.alias("executorAddedTS"),
  //      'filenameGroup.alias("startFilenameGroup"))
  //
  //  lazy private val executorRemovedDF: DataFrame = sparkEventsDF
  //    .filter('Event === "SparkListenerExecutorRemoved")
  //    .select('SparkContextID, 'ExecutorID, 'RemovedReason, 'Timestamp.alias("executorRemovedTS"),
  //      'filenameGroup.alias("endFilenameGroup"))


  /**
   * Module SparkEvents
   */

  // TODO - -replace lazy val back to lazy private val when done testing
  // Todo -- no data to test yet
  //  lazy val appendJDBCSessionsProcess = EtlDefinition(
  //    getJDBCSession(Sources.serverSessionStartDF, Sources.serverSessionEndDF),
  //    None,
  //    append(Silver.jdbcSessionsTarget),
  //    Module(2001, "SPARK_JDBC_Sessions_Raw")
  //  )
  //
  //  lazy val appendJDBCOperationsProcess = EtlDefinition(
  //    getJDBCOperation(Sources.serverOperationStartDF, Sources.serverOperationEndDF),
  //    None,
  //    append(Silver.jdbcOperationsTarget),
  //    Module(2002, "SPARK_JDBC_Operations_Raw")
  //  )

  private val executorsModule = Module(2003, "SPARK_Executors_Raw")
  lazy private val appendExecutorsProcess = EtlDefinition(
    newSparkEvents,
    Some(Seq(executor(
      getLaggingStartEvents(executorsModule, "SparkListenerExecutorAdded")
    ))),
    append(SilverTargets.executorsTarget),
    executorsModule
  )

  // TODO -- Build Bronze
  //  lazy val appendApplicationsProcess = EtlDefinition(
  //    sparkEventsDF,
  //    Some(Seq(enhanceApplication())),
  //    append(Silver.),
  //    Module(2004, "SPARK_Applications_Raw")
  //  )

  private val executionsModule = Module(2005, "SPARK_Executions_Raw")
  lazy private val appendExecutionsProcess = EtlDefinition(
    newSparkEvents,
    Some(Seq(sqlExecutions(
      getLaggingStartEvents(executionsModule, "org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart")
    ))),
    append(SilverTargets.executionsTarget),
    executionsModule
  )

  private val jobsModule = Module(2006, "SPARK_Jobs_Raw")
  lazy private val appendJobsProcess = EtlDefinition(
    newSparkEvents,
    Some(Seq(sparkJobs(
      getLaggingStartEvents(jobsModule, "SparkListenerJobStart")
    ))),
    append(SilverTargets.jobsTarget),
    jobsModule
  )

  private val stagesModule = Module(2007, "SPARK_Stages_Raw")
  lazy private val appendStagesProcess = EtlDefinition(
    newSparkEvents,
    Some(Seq(sparkStages(
      getLaggingStartEvents(stagesModule, "SparkListenerStageSubmitted")
    ))),
    append(SilverTargets.stagesTarget),
    stagesModule
  )

  private val tasksModule = Module(2008, "SPARK_Tasks_Raw")
  lazy private val appendTasksProcess = EtlDefinition(
    newSparkEvents,
    Some(Seq(sparkTasks(
      getLaggingStartEvents(tasksModule, "SparkListenerTaskStart")
    ))),
    append(SilverTargets.tasksTarget),
    tasksModule
  )

  private val jobStatusModule = Module(2010, "Silver_JobsStatus")
  lazy private val appendJobStatusProcess = EtlDefinition(
    getIncrementalAuditLogDFByTimestamp(jobStatusModule.moduleID),
    Some(Seq(dbJobsStatusSummary())),
    append(SilverTargets.dbJobsStatusTarget),
    jobStatusModule
  )

  private val jobRunsModule = Module(2011, "Silver_JobsRuns")
  lazy private val appendJobRunsProcess = EtlDefinition(
    getIncrementalAuditLogDFByTimestamp(jobRunsModule.moduleID),
    Some(Seq(
      dbJobRunsSummary(
        Schema.verifyDF(BronzeTargets.auditLogsTarget.asDF, jobRunsModule),
        SilverTargets.clustersSpecTarget,
        BronzeTargets.clustersSnapshotTarget,
        SilverTargets.dbJobsStatusTarget,
        BronzeTargets.jobsSnapshotTarget,
        config.databaseName
      )
    )),
    append(SilverTargets.dbJobRunsTarget),
    jobRunsModule
  )

  private val clusterSpecModule = Module(2014, "Silver_ClusterSpec")
  lazy private val appendClusterSpecProcess = EtlDefinition(
    getIncrementalAuditLogDFByTimestamp(clusterSpecModule.moduleID),
    Some(Seq(
      buildClusterSpec(
        BronzeTargets.clustersSnapshotTarget,
        BronzeTargets.auditLogsTarget
      ))),
    append(SilverTargets.clustersSpecTarget),
    clusterSpecModule
  )

  private val accountLoginsModule = Module(2016, "Silver_AccountLogins")
  lazy private val appendAccountLoginsProcess = EtlDefinition(
    getIncrementalAuditLogDFByTimestamp(accountLoginsModule.moduleID),
    Some(Seq(accountLogins())),
    append(SilverTargets.accountLoginTarget),
    accountLoginsModule
  )

  private val modifiedAccountsModule = Module(2017, "Silver_ModifiedAccounts")
  lazy private val appendModifiedAccountsProcess = EtlDefinition(
    getIncrementalAuditLogDFByTimestamp(modifiedAccountsModule.moduleID),
    Some(Seq(accountMods())),
    append(SilverTargets.accountModTarget),
    modifiedAccountsModule
  )

  private val notebookSummaryModule = Module(2018, "Silver_Notebooks")
  lazy private val appendNotebookSummaryProcess = EtlDefinition(
    getIncrementalAuditLogDFByTimestamp(notebookSummaryModule.moduleID),
    Some(Seq(notebookSummary())),
    append(SilverTargets.notebookStatusTarget),
    notebookSummaryModule
  )

  // TODO -- temp until refactor
  private def updateSparkEventsPipelineState(eventLogsBronze: PipelineTable): Unit = {
    val updateSql =
      s"""
         |update ${eventLogsBronze.tableFullName}
         |set Downstream_Processed = true
         |where Downstream_Processed = false
         |""".stripMargin
    spark.sql(updateSql)
  }

  private def processSparkEvents(): Unit = {

    //      appendJDBCSessionsProcess.process(),
    //      appendJDBCOperationsProcess.process(),
    appendExecutorsProcess.process()
    //      appendApplicationsProcess.process(),
    appendExecutionsProcess.process()
    appendJobsProcess.process()
    appendStagesProcess.process()
    appendTasksProcess.process()
    updateSparkEventsPipelineState(BronzeTargets.sparkEventLogsTarget)

  }

  def run(): Boolean = {

    restoreSparkConf()
    // TODO -- see which transforms are possible without audit and rebuild for no-audit
    //  CURRENTLY -- audit is required for silver
    val scope = config.overwatchScope

    if (scope.contains(OverwatchScope.accounts)) {
      try {
        appendAccountLoginsProcess.process()
        appendModifiedAccountsProcess.process()
      } catch {
        case _: FailedModuleException =>
          logger.log(Level.ERROR, "FAILED: Accounts Module")
      }
    }

    if (scope.contains(OverwatchScope.clusters)) {
      try {
        appendClusterSpecProcess.process()
      } catch {
        case _: FailedModuleException =>
          logger.log(Level.ERROR, "FAILED: Clusters Module")
      }
    }

    if (scope.contains(OverwatchScope.jobs)) {
      try {
        appendJobStatusProcess.process()
        appendJobRunsProcess.process()
      } catch {
        case _: FailedModuleException =>
          logger.log(Level.ERROR, "FAILED: Jobs Module")
      }
    }

    if (scope.contains(OverwatchScope.notebooks))
      try {
        appendNotebookSummaryProcess.process()
      } catch {
        case _: FailedModuleException =>
          logger.log(Level.ERROR, "FAILED: Notebooks Module")
      }

    if (scope.contains(OverwatchScope.sparkEvents))
      try {
        processSparkEvents()
      } catch {
        case e: FailedModuleException =>
          logger.log(Level.ERROR, "FAILED: SparkEvents Silver Module", e)
      }

    initiatePostProcessing()
    true // to be used as fail switch later if necessary
  }


}

object Silver {
  def apply(workspace: Workspace): Silver = new Silver(workspace, workspace.database, workspace.getConfig)

  //    .setWorkspace(workspace).setDatabase(workspace.database)

}
