# Additions For Db Scheduler

This is a WIP project to add support for additional databases and a Ktor UI plugin for the [Db Scheduler](https://github.com/kagkarlsson/db-scheduler) project.

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
}
```

## Couchbase

_In Progress_

## Mongo

_TODO_

