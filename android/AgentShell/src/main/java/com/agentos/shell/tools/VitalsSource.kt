package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Health Connect: one integration that reads Whoop, Garmin, Fitbit and Samsung Health, because they
 * all write into it.
 *
 * Chosen over talking to each vendor directly, and the reasoning matters. Whoop's own API needs an
 * OAuth client secret, and a secret compiled into an APK is not a secret. Garmin's is a partner
 * programme measured in weeks of waiting that no amount of work here shortens. Health Connect needs
 * neither — and because SlyOS ships from its own site rather than the Play Store, it also skips the
 * health-data review that would otherwise gate this entirely.
 *
 * What it costs: whatever the vendor app chooses to write. Whoop's recovery score and day strain do
 * not come through, which is the one real argument for adding Whoop directly later.
 */
object VitalsSource {

    /** Only what the page actually shows. Asking for more than you display is how trust is lost. */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    enum class State {
        /** Ready to read. */
        READY,
        /** Installed, but the owner has not granted the reads. */
        NEEDS_PERMISSION,
        /** Health Connect itself is not on this phone. */
        NOT_INSTALLED,
        /** Too old an Android, or the vendor removed it. */
        UNAVAILABLE
    }

    fun availability(ctx: Context): State = try {
        when (HealthConnectClient.getSdkStatus(ctx)) {
            HealthConnectClient.SDK_AVAILABLE -> State.READY
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> State.NOT_INSTALLED
            else -> State.UNAVAILABLE
        }
    } catch (e: Exception) { State.UNAVAILABLE }

    fun client(ctx: Context): HealthConnectClient? = try {
        if (availability(ctx) == State.READY) HealthConnectClient.getOrCreate(ctx) else null
    } catch (e: Exception) { null }

    suspend fun granted(ctx: Context): Boolean = try {
        val c = client(ctx) ?: return false
        c.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    } catch (e: Exception) { false }

    /** Whatever has been granted so far — a partial grant is normal and should still be read. */
    suspend fun grantedAny(ctx: Context): Boolean = try {
        val c = client(ctx) ?: return false
        c.permissionController.getGrantedPermissions().any { it in PERMISSIONS }
    } catch (e: Exception) { false }

    /**
     * Pull the last [days] days into [VitalsStore].
     *
     * Every record type is read inside its own try: one vendor writing a malformed sleep session
     * must not cost the person their heart-rate history. Returns how many samples landed, so the
     * screen can distinguish "connected and empty" from "connected and working" — the commonest
     * real failure is a granted permission with nothing behind it, and that must not read as our bug.
     */
    suspend fun sync(ctx: Context, days: Int = 90): Int {
        val c = client(ctx) ?: return 0
        val since = Instant.now().minusSeconds(days * 86_400L)
        val range = TimeRangeFilter.between(since, Instant.now())
        val out = ArrayList<VitalsStore.Sample>()

        suspend fun <T : androidx.health.connect.client.records.Record> pull(
            klass: kotlin.reflect.KClass<T>, take: (T) -> List<VitalsStore.Sample>
        ) {
            try {
                c.readRecords(ReadRecordsRequest(klass, range)).records.forEach { out.addAll(take(it)) }
            } catch (e: Exception) { Log.w("SlyOS", "vitals/${klass.simpleName}: ${e.message}") }
        }

        fun origin(r: androidx.health.connect.client.records.Record): String =
            try { r.metadata.dataOrigin.packageName.substringAfterLast('.') } catch (e: Exception) { "" }

        pull(HeartRateVariabilityRmssdRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.HRV, it.heartRateVariabilityMillis,
                it.time.toEpochMilli(), it.time.toEpochMilli(), origin(it)))
        }
        pull(RestingHeartRateRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.RHR, it.beatsPerMinute.toDouble(),
                it.time.toEpochMilli(), it.time.toEpochMilli(), origin(it)))
        }
        pull(SleepSessionRecord::class) {
            val mins = (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60000.0
            // A sleep session is filed under the day it ENDED — last night's sleep belongs to this
            // morning, which is when someone asks about it.
            listOf(VitalsStore.Sample(VitalsStore.M.SLEEP, mins,
                it.endTime.toEpochMilli(), it.endTime.toEpochMilli(), origin(it)))
        }
        pull(StepsRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.STEPS, it.count.toDouble(),
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), origin(it)))
        }
        pull(RespiratoryRateRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.RESP, it.rate,
                it.time.toEpochMilli(), it.time.toEpochMilli(), origin(it)))
        }
        pull(OxygenSaturationRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.SPO2, it.percentage.value,
                it.time.toEpochMilli(), it.time.toEpochMilli(), origin(it)))
        }
        pull(WeightRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.WEIGHT, it.weight.inKilograms,
                it.time.toEpochMilli(), it.time.toEpochMilli(), origin(it)))
        }
        pull(TotalCaloriesBurnedRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.CALORIES, it.energy.inKilocalories,
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), origin(it)))
        }
        pull(Vo2MaxRecord::class) {
            listOf(VitalsStore.Sample(VitalsStore.M.VO2, it.vo2MillilitersPerMinuteKilogram,
                it.time.toEpochMilli(), it.time.toEpochMilli(), origin(it)))
        }
        pull(ExerciseSessionRecord::class) {
            val mins = (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60000.0
            listOf(VitalsStore.Sample(VitalsStore.M.EXERCISE, mins,
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), origin(it)))
        }

        VitalsStore.put(ctx, out)
        try { ctx.getSharedPreferences("slyos_vitals_prefs", Context.MODE_PRIVATE)
            .edit().putLong("synced_at", System.currentTimeMillis()).apply() } catch (e: Exception) {}
        return out.size
    }

    fun lastSync(ctx: Context): Long = try {
        ctx.getSharedPreferences("slyos_vitals_prefs", Context.MODE_PRIVATE).getLong("synced_at", 0L)
    } catch (e: Exception) { 0L }

    /**
     * Where to send someone whose vendor app is not writing into Health Connect yet — naming the app
     * they actually have.
     *
     * The generic version listed four vendors and left the owner to work out which sentence was
     * theirs. Worse, it read as SlyOS explaining its own failure, when the true state is that Health
     * Connect is empty and every app reading it would show exactly the same thing.
     */
    fun writerHint(ctx: Context? = null): String {
        val installed = ctx?.let { c ->
            listOf(
                "com.whoop.android" to "WHOOP: open the app, let it sync with your strap, then " +
                    "Menu → Settings → Integrations → Health Connect",
                "com.garmin.android.apps.connectmobile" to "Garmin Connect: More → Settings → " +
                    "Connected Apps → Health Connect",
                "com.fitbit.FitbitMobile" to "Fitbit: Account → Fitbit app settings → Health Connect",
                "com.sec.android.app.shealth" to "Samsung Health: Settings → Health Connect"
            ).filter { (pkg, _) ->
                try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }.map { it.second }
        }.orEmpty()
        return if (installed.isNotEmpty()) installed.joinToString(". ") + "."
        else "Whoop, Garmin, Fitbit and Samsung Health each have a Health Connect switch in their " +
             "own settings — turn it on there and the data appears here."
    }

    /**
     * Whether Health Connect holds ANY data at all, from any app.
     *
     * The distinction that matters when a page comes up empty: "SlyOS cannot read" and "there is
     * nothing there to read" look identical to the owner and have completely different fixes. Read
     * across the record types rather than trusting a count, because a partial permission grant means
     * some types are simply invisible to us.
     */
    suspend fun anyDataAtAll(ctx: Context): Boolean {
        val c = client(ctx) ?: return false
        val range = TimeRangeFilter.between(Instant.now().minusSeconds(365 * 86_400L), Instant.now())
        suspend fun <T : androidx.health.connect.client.records.Record> has(k: kotlin.reflect.KClass<T>) =
            try { c.readRecords(ReadRecordsRequest(k, range, pageSize = 1)).records.isNotEmpty() }
            catch (e: Exception) { false }
        return has(StepsRecord::class) || has(SleepSessionRecord::class) ||
               has(RestingHeartRateRecord::class) || has(HeartRateVariabilityRmssdRecord::class) ||
               has(ExerciseSessionRecord::class) || has(WeightRecord::class)
    }
}
