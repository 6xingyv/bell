// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.storage

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.domain.repository.LocalMediaLibrary
import com.mocharealm.bell.domain.repository.MusicRepository
import kotlinx.coroutines.*
import org.koin.core.context.GlobalContext

class MetadataRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                val koin = GlobalContext.get()
                koin.get<SessionStore>().load()?.let {
                    koin.get<LocalMediaLibrary>().refresh(koin.get<MusicRepository>(), it)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 0x4a454c

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, MetadataRefreshJob::class.java))
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
