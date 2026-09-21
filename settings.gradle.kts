pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Aicodemax"
include(
    ":app",
    ":core:common",
    ":core:state",
    ":core:resources",
    ":ai:core",
    ":ai:tasks",
    ":ai:agents",
    ":ai:models",
    ":tools:registry",
    ":tools:gateway",
    ":tools:files",
    ":tools:editor",
    ":tools:terminal",
    ":tools:terminal_runtime",
    ":tools:builder",
    ":tools:git",
    ":tools:browser",
    ":data:checkpoint",
    ":data:audit",
    ":data:memory",
    ":data:conversations",
    ":data:settings",
    ":ui:designsystem",
    ":ui:chat",
    ":ui:workspace",
    ":ui:settings",
)
