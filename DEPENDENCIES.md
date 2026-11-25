# Dependencies and licenses

Checked against each project's published license. Re-check before a release.

| Library | Version | Use | License |
|---|---|---|---|
| androidx.core:core-ktx | 1.13.1 | app | Apache-2.0 |
| androidx.appcompat:appcompat | 1.7.0 | app | Apache-2.0 |
| com.google.android.material:material | 1.12.0 | app | Apache-2.0 |
| org.bouncycastle:bcprov-jdk18on | 1.78.1 | X25519 key exchange | Bouncy Castle License (MIT style) |
| junit:junit | 4.13.2 | tests only | EPL-1.0 |
| org.robolectric:robolectric | 4.12.2 | tests only | MIT |
| androidx.test:core | 1.5.0 | tests only | Apache-2.0 |
| Kotlin stdlib (via plugin) | 1.9.24 | app | Apache-2.0 |

Build tools: Android Gradle Plugin 8.5.2 (Apache-2.0), Gradle 8.7 (Apache-2.0).
All direct app and test dependencies are under OSI-approved licenses (JUnit's
EPL-1.0 is OSI-approved). They are test-only except where listed as app. Robolectric
downloads Android framework jars (Apache-2.0, from the AOSP) from Maven Central the
first time the unit tests run. Transitive dependencies of Robolectric and the
androidx libraries were not individually license-audited; run a license report
(for example the Gradle license plugin) before a release if that matters.
The earlier non-OSI test library (org.json, "JSON License") is not used. Robolectric
supplies the JSON and storage classes for the tests. The shipped APK is unaffected.
