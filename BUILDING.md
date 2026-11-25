# Building

Needs: JDK 17, Android SDK platform 34 and build-tools 34
(Gradle 8.7 via the wrapper; Android Gradle Plugin 8.5.2, Kotlin 1.9.24; versions are pinned in
`build.gradle`).

```
# 1. point Gradle at your Android SDK (this file is git-ignored)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. build the debug APK and run the unit tests
sh gradlew assembleDebug testDebugUnitTest  # on Windows: gradlew.bat assembleDebug testDebugUnitTest

# result
app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) is included and pinned to Gradle 8.7. The first run downloads that Gradle version from services.gradle.org. Needs JDK 17 on the PATH and the Android SDK (platform 34, build-tools 34) installed. `sh gradlew` is used above because some upload tools drop the executable bit of the script.

The debug APK is signed with the standard Android debug key. For your own
release, create your own keystore and keep it out of the repository (`*.jks` and
`*.keystore` are git-ignored). No secrets or server settings are needed to
build or run this app.

Reproducibility: dependencies are fixed to exact versions in `app/build.gradle`.
Byte-identical APKs across machines are not guaranteed (debug signing and build
paths), so compare behaviour, not hashes, across builders.
