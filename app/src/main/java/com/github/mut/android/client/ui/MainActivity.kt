package com.github.mut.android.client.ui

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.github.mut.android.client.R
import com.github.mut.android.client.data.ConfigStore
import com.github.mut.android.client.data.MutConfig
import com.github.mut.android.client.service.TunnelService

class MainActivity : AppCompatActivity() {
    companion object {
        const val CODE_PREPARE = 0
    }

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.extras?.get("msg")) {
                TunnelService.MSG_RUNNING -> mSwitchState?.isChecked = true
                TunnelService.MSG_STOPPED -> mSwitchState?.isChecked = false
            }
        }
    }
    private var mSwitchState: Switch? = null
    private lateinit var mConfigList: RecyclerView
    private lateinit var mPreferences: SharedPreferences
    private lateinit var mConfigStore: ConfigStore
    private var mSelectedConfig: MutConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.global_settings, GlobalSettingsFragment())
                .commit()
        }
        registerReceiver(mReceiver, IntentFilter(TunnelService.BROADCAST_MESSAGE))

        mPreferences = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
        mConfigStore = ConfigStore(this)
        mConfigList = findViewById(R.id.config_list)
        mConfigList.adapter = ConfigListAdapter()
        reloadConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        mSwitchState = menu!!.findItem(R.id.menu_toggle).actionView.findViewById(R.id.switch_state)
        mSwitchState!!.setOnClickListener {
            if (mSwitchState!!.isChecked) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, CODE_PREPARE)
                } else {
                    onActivityResult(CODE_PREPARE, Activity.RESULT_OK, null)
                }
            } else {
                sendBroadcast(Intent(TunnelService.BROADCAST_REQUEST).putExtra("req", TunnelService.REQ_STOP))
            }
        }
        requestStatusUpdate()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_add_config -> {
                EditConfigDialog(mConfigStore) {
                    mConfigList.adapter!!.notifyDataSetChanged()
                }.show(supportFragmentManager, "dialog_add_config")
                true
            }
            R.id.menu_reload_config -> {
                reloadConfig()
                true
            }
            else -> false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            CODE_PREPARE -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (mSelectedConfig != null) {
                        startService(
                            Intent(this, TunnelService::class.java)
                                .setAction(TunnelService.ACTION_START)
                                .putExtra("outbound", mSelectedConfig!!.outboundUrl)
                                .putExtra("dns_server", mPreferences.getString("dns_server", TunnelService.DEFAULT_DNS))
                                .putExtra("bypass_special", mPreferences.getBoolean("bypass_special", false))
                                .putExtra("allow_remote", mPreferences.getBoolean("allow_remote", false))
                                .putExtra("allow_debug", mPreferences.getBoolean("allow_debug", false))
                                .putExtra("tun_ip", mPreferences.getString("tun_ip", TunnelService.DEFAULT_TUN_IP))
                        )
                    } else {
                        Toast.makeText(this, "No configuration selected", Toast.LENGTH_SHORT).show()
                        mSwitchState?.isChecked = false
                    }
                } else {
                    Toast.makeText(this, "Connection cancelled by user", Toast.LENGTH_SHORT).show()
                    mSwitchState?.isChecked = false
                }
                return
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun requestStatusUpdate() {
        sendBroadcast(Intent(TunnelService.BROADCAST_REQUEST).putExtra("req", TunnelService.REQ_UPDATE))
    }

    private fun reloadConfig() {
        mConfigStore.reload()
        mConfigList.adapter!!.let {
            val pos = mPreferences.getInt("selected_config", 0)
            if (pos < mConfigStore.count()) {
                (it as ConfigListAdapter).selectedPosition = pos
                mSelectedConfig = mConfigStore[pos]
            }
            it.notifyDataSetChanged()
        }
    }

    private inner class ConfigListAdapter : RecyclerView.Adapter<ConfigListAdapter.ViewHolder>() {
        var selectedPosition = -1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val configItem = layoutInflater.inflate(R.layout.config_item, parent, false)
            return ViewHolder(configItem)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.radio.text = mConfigStore[position].displayName
            holder.radio.isChecked = (position == selectedPosition)
        }

        override fun getItemCount(): Int {
            return mConfigStore.count()
        }

        private inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findViewById(R.id.config_item_radio)
            val editButton: ImageButton = v.findViewById(R.id.config_item_edit)
            val removeButton: ImageButton = v.findViewById(R.id.config_item_remove)

            init {
                radio.setOnClickListener {
                    val oldPosition = selectedPosition
                    selectedPosition = adapterPosition
                    notifyItemChanged(oldPosition)
                    mSelectedConfig = mConfigStore[selectedPosition]
                    mPreferences.edit().putInt("selected_config", selectedPosition).apply()
                    notifyItemChanged(selectedPosition)
                }
                editButton.setOnClickListener {
                    EditConfigDialog(mConfigStore, adapterPosition) {
                        if (adapterPosition == selectedPosition) {
                            mSelectedConfig = mConfigStore[selectedPosition]
                        }
                        notifyItemChanged(adapterPosition)
                    }.show(supportFragmentManager, "dialog_edit_config")
                }
                removeButton.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Sure?")
                        .setPositiveButton("Remove") { _, _ ->
                            if (adapterPosition == selectedPosition) {
                                selectedPosition = -1
                                mSelectedConfig = null
                            }
                            mConfigStore.removeAt(adapterPosition)
                            notifyItemRemoved(adapterPosition)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }
}