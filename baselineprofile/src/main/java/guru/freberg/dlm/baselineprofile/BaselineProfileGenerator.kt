// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates the app's Baseline Profile: launches the app cold and lets the first
 * screen settle, capturing the classes/methods on the startup hot path so they are
 * AOT-compiled at install time instead of JIT-warmed on first run.
 *
 * Run on the connected device with:
 *   ./gradlew :app:generateReleaseBaselineProfile
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "guru.freberg.dlm",
        // Also emit a startup profile so cold-start classes are laid out together in
        // the dex for faster loading, not just AOT-compiled.
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // Let the initial composition / first frame settle before the profile is cut.
        device.waitForIdle()
    }
}
