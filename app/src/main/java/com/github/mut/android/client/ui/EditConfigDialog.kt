package com.github.mut.android.client.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import com.github.mut.android.client.R
import com.github.mut.android.client.data.ConfigStore
import com.github.mut.android.client.data.MutConfig
import com.google.android.material.textfield.TextInputEditText
import java.net.URI

class EditConfigDialog : AppCompatDialogFragment {
    private val mConfigStore: ConfigStore
    private val mIndex: Int
    private val mConfig: MutConfig
    private val mOnSave: () -> Unit

    constructor(configStore: ConfigStore, onSave: () -> Unit) {
        mConfigStore = configStore
        mIndex = -1
        mConfig = MutConfig("", "direct://")
        mOnSave = onSave
    }

    constructor(configStore: ConfigStore, index: Int, onSave: () -> Unit) {
        mConfigStore = configStore
        mIndex = index
        mConfig = mConfigStore[index].copy()
        mOnSave = onSave
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_config, null)
        val editName = view.findViewById<TextInputEditText>(R.id.edit_name)
        val editOutbound = view.findViewById<TextInputEditText>(R.id.edit_outbound)
        editName.setText(mConfig.displayName)
        editOutbound.setText(mConfig.outboundUrl)
        return AlertDialog.Builder(requireContext())
            .setTitle("Edit configuration")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                mConfig.displayName = editName.text.toString()
                mConfig.outboundUrl = editOutbound.text.toString()
                if (mConfig.outboundUrl.isBlank()) {
                    Toast.makeText(context, "Outbound URL cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    val uri = URI(mConfig.outboundUrl)
                    if (mConfig.displayName.isBlank()) {
                        mConfig.displayName = "${uri.scheme}-${uri.host}-${uri.port}"
                    }
                } catch (e: Exception) {
//                    Toast.makeText(context, "Outbound URL is malformed", Toast.LENGTH_SHORT).show()
//                    return@setPositiveButton
                }

                if (mIndex >= 0) {
                    mConfigStore[mIndex] = mConfig
                } else {
                    mConfigStore += mConfig
                }
                mOnSave()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}