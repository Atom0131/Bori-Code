package com.bori.core.project.scaffold

import com.bori.core.project.model.ProjectTemplate

object TemplateFiles {

    fun mainActivity(packageName: String, template: ProjectTemplate): String = when (template) {
        ProjectTemplate.EMPTY_ACTIVITY -> """
package $packageName;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
""".trimIndent()

        ProjectTemplate.BASIC_ACTIVITY_XML -> """
package $packageName;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    private TextView textView;
    private int count = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textView = findViewById(R.id.textView);
        Button button = findViewById(R.id.button);

        button.setOnClickListener(v -> {
            count++;
            textView.setText("Count: " + count);
        });
    }
}
""".trimIndent()

        else -> ""
    }

    fun mainKotlinActivity(packageName: String, template: ProjectTemplate): String = when (template) {
        ProjectTemplate.EMPTY_ACTIVITY_KOTLIN -> """
package $packageName

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
""".trimIndent()

        ProjectTemplate.BASIC_ACTIVITY_XML_KOTLIN -> """
package $packageName

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private var count = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.textView)
        val button = findViewById<Button>(R.id.button)

        button.setOnClickListener {
            count++
            textView.text = "Count: ${"$"}{count}"
        }
    }
}
""".trimIndent()

        else -> ""
    }

    fun activityMainXml(template: ProjectTemplate): String = when (template) {
        ProjectTemplate.EMPTY_ACTIVITY, ProjectTemplate.EMPTY_ACTIVITY_KOTLIN -> """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Hello World!" />

</LinearLayout>
""".trimIndent()

        ProjectTemplate.BASIC_ACTIVITY_XML, ProjectTemplate.BASIC_ACTIVITY_XML_KOTLIN -> """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:id="@+id/textView"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Count: 0"
        android:textSize="24sp" />

    <Button
        android:id="@+id/button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="Increment" />

</LinearLayout>
""".trimIndent()
    }

    fun stringsXml(appName: String) = """
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$appName</string>
</resources>
""".trimIndent()

    fun themesXml(appName: String) = """
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="android:colorPrimary">#6200EE</item>
        <item name="android:colorPrimaryDark">#3700B3</item>
        <item name="android:colorAccent">#03DAC5</item>
    </style>
</resources>
""".trimIndent()

    fun androidManifest(packageName: String) = """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$packageName">

    <application
        android:label="@string/app_name"
        android:theme="@style/AppTheme">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
""".trimIndent()

    /** Written to app/build.gradle.kts — read by BuildEngine for namespace/minSdk and by
     *  EditorViewModel for dependency management. */
    fun appBuildGradleKts(packageName: String, minSdk: Int) = """
plugins {
    id("com.android.application")
}

android {
    namespace = "$packageName"
    compileSdk = 35

    defaultConfig {
        applicationId = "$packageName"
        minSdk = $minSdk
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // AppCompat, Material, ConstraintLayout, RecyclerView, Fragment, Lifecycle
    // are pre-bundled by Bori Code — no need to add them here.
    // Add extra dependencies below with implementation(...), e.g. the coordinate
    // com.squareup.retrofit2:retrofit:2.11.0 in quotes. (Written this way because the
    // on-device build reads implementation lines even inside comments.)
}
""".trimIndent()

    /** Written to the root build.gradle.kts (informational; not executed by Bori Code). */
    fun rootBuildGradleKts() = """
// Root build file — module-specific config is in app/build.gradle.kts.
plugins {
    id("com.android.application") version "8.7.3" apply false
}
""".trimIndent()

    fun proguardRules() = """
# Add project-specific ProGuard rules here.
# Bori Code automatically applies standard Android keep rules for release builds
# (Activities, Application, Parcelable, Serializable, enums, native methods).
# Add any extra rules your code needs below, for example:
#
# -keep class com.example.MyClass { *; }
# -dontwarn com.example.SomeThirdParty
""".trimIndent()

    fun gitignore() = """
build/
.gradle/
*.apk
local.properties
""".trimIndent()

    fun settingsGradleKts(appName: String) = """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "$appName"
include(":app")
""".trimIndent()
}
