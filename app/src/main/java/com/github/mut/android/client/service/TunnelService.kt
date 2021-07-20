package com.github.mut.android.client.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.mut.android.client.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

class TunnelService : VpnService() {
    companion object {
        val BROADCAST_REQUEST = "${TunnelService::class.qualifiedName}.REQUEST"
        val BROADCAST_MESSAGE = "${TunnelService::class.qualifiedName}.MESSAGE"
        const val DEFAULT_TUN_IP = "172.31.255.2"
        const val DEFAULT_DNS = "udp://1.1.1.1"
        const val REQ_UPDATE = 1
        const val REQ_STOP = 2
        const val MSG_RUNNING = 1
        const val MSG_STOPPED = 2

        const val ACTION_START = "start"
        const val NOTIFICATION_STATUS = "status"
    }

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.extras?.get("req")) {
                REQ_UPDATE -> sendBroadcastMessage()
                REQ_STOP -> onRevoke()
            }
        }
    }
    private lateinit var mNotificationManager: NotificationManager
    private var mMutProcess: Process? = null
    private var mFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        mNotificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_STATUS, "Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        registerReceiver(mReceiver, IntentFilter(BROADCAST_REQUEST))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val outbound = intent.getStringExtra("outbound")
            val tunAddress = intent.getStringExtra("tun_ip")
            if (outbound.isNullOrBlank() || tunAddress.isNullOrBlank()) {
                return START_NOT_STICKY
            }

            val dns: String
            try {
                val dnsUri = URI(intent.getStringExtra("dns_server"))
                dns = URI(
                    dnsUri.scheme,
                    dnsUri.authority,
                    dnsUri.path,
                    "local_listen=localhost:1053&fake_ip=1",
                    dnsUri.fragment
                ).toASCIIString()
            } catch (e: Exception) {
                return START_NOT_STICKY
            }

            val tunEndpointAddress = tunAddress.substring(0, tunAddress.lastIndexOf('.') + 1) + "1"
            val allowRemote = intent.getBooleanExtra("allow_remote", false)
            val allowDebug = intent.getBooleanExtra("allow_debug", false)

            var rules = "final,default"
            if (intent.getBooleanExtra("bypass_special", false)) {
                val cidrFile = File(filesDir, "directip.txt")
                if (!cidrFile.exists()) {
                    cidrFile.outputStream().use { os ->
                        resources.openRawResource(R.raw.directip).use { ins ->
                            ins.copyTo(os)
                        }
                    }
                }
                rules = "cidr:directip.txt,direct;final,default"
            }

            stopTunnel()
            startForeground(1, NotificationCompat.Builder(this, NOTIFICATION_STATUS).build())

            synchronized(this) {
                try {
                    val builder = Builder()
                        .allowFamily(OsConstants.AF_INET)
                        .addAddress(tunAddress, 30)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer(tunEndpointAddress)
                        .setMtu(1500)
                        .addDisallowedApplication(packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", 1082))
                    }
                    mFd = builder.establish()
                    startMutProcess(outbound, dns, rules, allowRemote, allowDebug)
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopTunnel()
                    return START_NOT_STICKY
                }
            }

            sendBroadcastMessage()
            return START_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        stopTunnel()
    }

    private fun stopTunnel() {
        if (mFd != null || mMutProcess != null) {
            synchronized(this) {
                if (mFd != null || mMutProcess != null) {
                    mMutProcess?.destroy()
                    mMutProcess = null
                    mFd?.close()
                    mFd = null

                    stopForeground(true)
                    sendBroadcastMessage()
                }
            }
        }
    }

    private fun startMutProcess(
        outbound: String,
        dns: String,
        rules: String,
        allowRemote: Boolean,
        allowDebug: Boolean
    ) {
        val fdPath = "${cacheDir}/fd"
        val argsList = mutableListOf(
            "${applicationInfo.nativeLibraryDir}/libmut.so",
            "-in", "mix://${if (allowRemote) "" else "localhost"}:1082/?udp=1",
            "-in", "tun://?fdpath=${fdPath}&dnsgw=localhost:1053",
            "-out", outbound,
            "-rules", rules,
            "-dns", dns
        )
        if (allowDebug) {
            argsList.addAll(arrayOf("-debug", "6061"))
        }

        mMutProcess = ProcessBuilder(argsList).directory(filesDir).start()
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                mMutProcess!!.inputStream.copyTo(System.out, 256)
            }
        }
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                mMutProcess!!.errorStream.copyTo(System.err, 256)
            }
        }

        GlobalScope.launch(Dispatchers.IO) {
            val maxRetries = 10
            for (i in 0..maxRetries) {
                if (i == maxRetries || mMutProcess == null) {
                    break
                }
                try {
                    LocalSocket().use {
                        it.connect(LocalSocketAddress(fdPath, LocalSocketAddress.Namespace.FILESYSTEM))
                        it.setFileDescriptorsForSend(arrayOf(mFd!!.fileDescriptor))
                        it.outputStream.write(0)
                    }
                    break
                } catch (e: Exception) {
                    Thread.sleep(1000)
                }
            }

            mMutProcess?.waitFor()
            println("Mut process stopped")
            stopTunnel()
        }
    }

    private fun sendBroadcastMessage() {
        sendBroadcast(
            Intent(BROADCAST_MESSAGE).putExtra(
                "msg", if (mFd != null) {
                    MSG_RUNNING
                } else {
                    MSG_STOPPED
                }
            )
        )
    }
}