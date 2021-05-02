package com.github.mut.android.client.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.github.mut.android.client.R

class GlobalSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }
}