package com.hienlh.ppm.app

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * [compose] behind a rule that declares the `ComponentActivity` it starts: this module's unit
 * tests have no merged manifest, and the compose rule starts that activity before a `@Before` runs.
 */
internal fun withComposeActivity(compose: TestRule): RuleChain = RuleChain
    .outerRule(
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val app = ApplicationProvider.getApplicationContext<Application>()
                    shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                    base.evaluate()
                }
            }
        },
    )
    .around(compose)
