pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()

        // ------------------------------------------------------------------------
        // GOOGLE'S MAVEN — the one cost of PARITY.md row 1.1 that was NOT foreseen.
        //
        // PLAN.md §1 said Compose Multiplatform "pulls in a large dependency tree that
        // complicates offline builds". It does, and it also does something the plan did
        // not predict: it pulls part of that tree from a SECOND REPOSITORY. Compose
        // 1.9.0's `ui-desktop` depends on `org.jetbrains.androidx.lifecycle:*` (which is
        // on Central) and those in turn depend on `androidx.lifecycle:lifecycle-common`
        // and `androidx.savedstate:savedstate`, which are NOT. Measured, not assumed:
        //
        //   repo1.maven.org/.../androidx/lifecycle/lifecycle-common/2.9.2/*.pom  -> 404
        //   dl.google.com/dl/android/maven2/.../lifecycle-common/2.9.2/*.pom     -> 200
        //
        // Without this repository the build fails at RESOLUTION with "Could not find
        // androidx.lifecycle:lifecycle-common:2.9.2" — loudly, which is the good case.
        //
        // It is CONTENT-FILTERED to the androidx groups on purpose. This project's
        // crypto core, BouncyCastle and org.json must keep coming from exactly one
        // place; a second wide-open repository is a supply-chain surface, and a
        // dependency that could be served by either host is a dependency nobody can say
        // where it came from. Anything outside `androidx.*` is still Central-only, and
        // adding a group here is a deliberate act, not a side effect.
        // ------------------------------------------------------------------------
        maven("https://dl.google.com/dl/android/maven2") {
            name = "google-androidx"
            content { includeGroupByRegex("androidx\\..*") }
        }
    }
}

rootProject.name = "OSHI-Desktop"
