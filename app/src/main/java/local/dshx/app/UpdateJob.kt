package local.dshx.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.UUID

/** Android 负责何时运行、停止和重新调度，应用仍负责续传和验证下载内容。 */
class UpdateJob : JobService() {
    private val update get() = (application as DshApp).update

    override fun onStartJob(params: JobParameters): Boolean {
        val request = Request.read(params.extras) ?: return false
        val notified = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        if (notified) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW))
            showProgress(params, null)
        }
        val client = OkHttpClient.Builder().apply {
            if (Build.VERSION.SDK_INT >= 28) params.network?.let { network ->
                socketFactory(network.socketFactory)
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
                })
            }
        }.build()
        return update.startJob(request, client, { percent ->
            if (notified) showProgress(params, percent)
        }, { retry -> jobFinished(params, retry) })
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val request = Request.read(params.extras) ?: return false
        val retry = Build.VERSION.SDK_INT < 31 || params.stopReason !in setOf(
            JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.STOP_REASON_USER)
        return update.stopJob(request, retry)
    }

    @android.annotation.TargetApi(34)
    private fun showProgress(params: JobParameters, percent: Int?) {
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (percent == 100) "正在校验更新" else "正在下载 DSHX 更新")
            .setProgress(100, percent ?: 0, percent == null || percent == 100)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        setNotification(params, NOTIFICATION_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
    }

    internal data class Request(val id: String, val manifest: String) {
        fun extras() = PersistableBundle().apply {
            putString("id", id)
            putString("manifest", manifest)
        }

        companion object {
            fun read(extras: PersistableBundle): Request? {
                val id = extras.getString("id") ?: return null
                val manifest = extras.getString("manifest") ?: return null
                return Request(id, manifest)
            }
        }
    }

    companion object {
        private const val JOB_ID = 4_910
        private const val NOTIFICATION_ID = 4_910
        private const val CHANNEL = "app-update"

        internal fun pending(context: Context): Request? = scheduler(context).getPendingJob(JOB_ID)?.let { Request.read(it.extras) }

        internal fun enqueue(context: Context, release: UpdateStore.Release): Request {
            val request = Request(UUID.randomUUID().toString(), release.raw)
            fun job() = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(request.extras())
                .setPersisted(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 28) setEstimatedNetworkBytes(release.artifact.size, 0)
                }

            val scheduler = scheduler(context)
            if (Build.VERSION.SDK_INT >= 34) {
                // 例如用户发起时页面已经不在前台，系统可能不允许使用专用传输任务。
                val scheduled = try {
                    scheduler.schedule(job().setUserInitiated(true).build())
                } catch (_: SecurityException) { JobScheduler.RESULT_FAILURE }
                if (scheduled == JobScheduler.RESULT_SUCCESS) return request
            }
            check(scheduler.schedule(job().build()) == JobScheduler.RESULT_SUCCESS) { "无法提交系统下载任务，请重试。" }
            return request
        }

        internal fun cancel(context: Context) { scheduler(context).cancel(JOB_ID) }

        private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
    }
}
