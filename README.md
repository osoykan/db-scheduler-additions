# Additions For Db Scheduler

This project aims to add support for additional databases and a Ktor UI plugin for the [Db Scheduler](https://github.com/kagkarlsson/db-scheduler) project. If you find any bug please report it.

## UI Ktor Plugin

This package uses [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui) and provides a ktor plugin to interact with.

```kotlin
implementation("io.github.osoykan:db-scheduler-ui-ktor:$version")
```

```kotlin
install(DbSchedulerUI) {
  routePath = "/db-scheduler"
  scheduler = { get<Scheduler>() } // assuming Koin is in place, but you can provide your instance to the functions.
  dataSource = { get<DataSource>() }
  enabled = true
  taskData = true
}
```
> [!WARNING]
> Enabling `taskData` might cause a peak in memory usage. The issue was discussed at https://github.com/bekk/db-scheduler-ui/issues/121

## Couchbase

```kotlin
implementation("io.github.osoykan:db-scheduler-couchbase:$version")
```

```kotlin
scheduler {
  val couchbase = Couchbase(cluster, "bucket-name", "collection-name")
  couchbase.ensureCollectionExists()

  database(couchbase)
  knownTasks(*tasks.toTypedArray())
  startupTasks(*startupTasks.toTypedArray())
  name(name)
  clock(clock)
  shutdownMaxWait(1.seconds)
  fixedThreadPoolSize(5)
  corePoolSize(1)
  heartbeatInterval(2.seconds)
  executeDue(2.seconds)
  deleteUnresolvedAfter(1.seconds)
  logLevel(LogLevel.TRACE)
  logStackTrace(true)
  shutdownMaxWait(1.minutes)
  numberOfMissedHeartbeatsBeforeDead(3)
  listeners(emptyList())
  meterRegistry(PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
}.also {
  it.start()
}
```

## Mongo

```kotlin
implementation("io.github.osoykan:db-scheduler-mongo:$version")
```

```kotlin
scheduler {
  val mongo = Mongo(client, "database-name", "collection-name")
  mongo.ensureCollectionExists()

  database(mongo)
  knownTasks(*tasks.toTypedArray())
  startupTasks(*startupTasks.toTypedArray())
  name(name)
  clock(clock)
  shutdownMaxWait(1.seconds)
  fixedThreadPoolSize(5)
  corePoolSize(1)
  heartbeatInterval(2.seconds)
  executeDue(2.seconds)
  deleteUnresolvedAfter(1.seconds)
  logLevel(LogLevel.TRACE)
  logStackTrace(true)
  shutdownMaxWait(1.minutes)
  numberOfMissedHeartbeatsBeforeDead(3)
  listeners(emptyList())
  meterRegistry(PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
}.also {
  it.start()
}
```


