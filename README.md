# Additions For Db Scheduler

This project aims to add support for additional databases and a Ktor UI plugin for the [Db Scheduler](https://github.com/kagkarlsson/db-scheduler) project. If you find any bug please report it.

![Release](https://img.shields.io/maven-central/v/io.github.osoykan/db-scheduler?versionPrefix=0&label=latest-release&color=blue)

## UI Ktor Plugin

This package uses [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui) and provides a ktor plugin to interact with.

```kotlin
implementation("io.github.osoykan:db-scheduler-ui-ktor:$version")
```

```kotlin
install(DbSchedulerUI) {
  routePath = "/db-scheduler"
  scheduler = { get<Scheduler>() } // assuming Koin is in place, but you can provide your instance to the functions.
  enabled = true
  taskData = true
}
```
> [!WARNING]
> Enabling `taskData` might cause a peak in memory usage. The issue was discussed at https://github.com/bekk/db-scheduler-ui/issues/121

### Execution History

The UI plugin supports tracking task execution history. To enable it, create the configuration first and share the listener with your scheduler:

```kotlin
// 1. Create UI config before scheduler initialization
val uiConfig = DbSchedulerUIConfiguration().apply {
  routePath = "/db-scheduler"
  enabled = true
  historyEnabled = true      // Enable history tracking
  historyMaxSize = 10_000    // Max entries to keep in memory (default: 10000)
}

// 2. Add the listener to your scheduler
Scheduler.create(dataSource, tasks)
  .addSchedulerListener(uiConfig.createListener())  // Creates ExecutionLogListener
  .build()

// Or if using the DSL:
scheduler {
  listeners(uiConfig.createListener())
  // ... other config
}

// 3. Install plugin with the same config
install(DbSchedulerUI) {
  from(uiConfig)  // Shares the same logRepository
  scheduler = { get<Scheduler>() }
}
```

With Koin DI:
```kotlin
val uiConfig = DbSchedulerUIConfiguration().apply {
  historyEnabled = true
}

install(Koin) {
  modules(
    module {
      // Register listener for scheduler to use
      single { uiConfig.createListener() }
    }
  )
}

install(DbSchedulerUI) {
  from(uiConfig)
  scheduler = { get() }
}
```

> [!NOTE]
> The `logRepository` defaults to `InMemoryLogRepository`. You can provide a custom implementation via `uiConfig.logRepository = YourCustomRepository()`.

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

<details>

<summary>Couchbase(DEPRECATED)</summary>


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


</details>



