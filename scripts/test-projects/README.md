# Builder test projects

Small Android projects that each exercise part of the on-device builder. Each app logs one line
to logcat with the tag `BORI_TEST` when it starts: `<name> OK …` when every check passed.

| Project | Covers |
|---|---|
| `t1-kotlin-views` | Kotlin + Views with no dependencies (the Kotlin stdlib gets packaged) |
| `t2-compose` | Compose 1.4 from a version catalog (`module =`, bundles, `alias(libs.plugins…)`) |
| `t3-app-features` | `src/main/kotlin`, assets, a prebuilt `.so` in jniLibs, BuildConfig + `buildConfigField`, applicationId ≠ namespace, versionCode/versionName, a mipmap icon, a library's merged `<provider>`, a commented-out dependency |
| `t5-multi-module` | Groovy DSL, `include ':app', ':core'`, a library module with resources, a service in its manifest and an `api` dependency (OkHttp) |

`t3-app-features/native/t3.c` is the source of the `.so` files, built with NDK 28 for API 24
with `-Wl,-z,max-page-size=16384`.

## Running one

Push the project into the debug app's project folder, restart the app (it registers new
folders on launch), then start a build over adb. Only the shell can send this broadcast (it
needs the DUMP permission):

```
adb push t3-app-features /sdcard/Android/data/com.bori.code.debug/files/Bori/
adb shell am broadcast -a com.bori.app.debug.BUILD \
    -n com.bori.code.debug/com.bori.app.debug.DebugBuildReceiver --es project t3-app-features
# release: add --ez release true --es ks <keystore on the device> --es alias <alias> --es pass <password>
```

The log is written to `/sdcard/Android/data/com.bori.code.debug/files/build-out/<project>.log`
(the last line is `RESULT OK <apk>` or `RESULT FAIL <message>`), with the APK next to it.
