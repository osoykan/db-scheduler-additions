package io.github.osoykan.dbscheduler

import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.github.osoykan.scheduler.DocumentDatabase
import org.slf4j.LoggerFactory

data class OtherOptions(
  val concurrency: Int = 5
)

internal val logger = LoggerFactory.getLogger(SchedulerUseCases::class.java)

typealias SchedulerFactory<T> = (
  db: T,
  tasks: List<Task<*>>,
  startupTasks: List<RecurringTask<*>>,
  name: String,
  clock: Clock,
  options: OtherOptions
) -> Scheduler

data class CaseDefinition<T : DocumentDatabase<T>>(
  val db: T,
  val schedulerFactory: SchedulerFactory<T>
)

data class TestTaskData(
  val name: String
)
