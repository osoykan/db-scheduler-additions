import org.gradle.api.*
import org.gradle.kotlin.dsl.invoke

fun Collection<Project>.of(
  vararg parentProjects: String,
  filter: (Project) -> Boolean,
  action: Action<Project>
): Unit = this.filter { parentProjects.contains(it.parent?.name) && filter(it) }.forEach { action(it) }

fun Project.getProperty(
  projectKey: String,
  environmentKey: String
): String? = if (this.hasProperty(projectKey)) {
  this.property(projectKey) as? String?
} else {
  System.getenv(environmentKey)
}

val runningOnCI: Boolean
  get() = System.getenv("CI") != null
    || System.getenv("GITHUB_ACTIONS") != null
    || System.getenv("GITLAB_CI") != null
    || System.getenv("CIRCLECI") != null
    || System.getenv("TRAVIS") != null
    || System.getenv("TEAMCITY_VERSION") != null
    || System.getenv("JENKINS_URL") != null
