/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexingsettings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.blitzfiles.app.databinding.IndexingSettingsFragmentBinding

class IndexingSettingsFragment : Fragment() {
    private var binding: IndexingSettingsFragmentBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        IndexingSettingsFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(requireNotNull(binding).toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
