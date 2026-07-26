/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.fileproperties.apk

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import com.blitzfiles.app.R
import com.blitzfiles.app.databinding.PermissionListDialogBinding
import com.blitzfiles.app.util.Failure
import com.blitzfiles.app.util.Loading
import com.blitzfiles.app.util.ParcelableArgs
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.Success
import com.blitzfiles.app.util.args
import com.blitzfiles.app.util.fadeInUnsafe
import com.blitzfiles.app.util.fadeOutUnsafe
import com.blitzfiles.app.util.fadeToVisibilityUnsafe
import com.blitzfiles.app.util.getQuantityString
import com.blitzfiles.app.util.layoutInflater
import com.blitzfiles.app.util.putArgs
import com.blitzfiles.app.util.show
import com.blitzfiles.app.util.viewModels

class PermissionListDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { PermissionListViewModel(args.permissionNames) } }

    private lateinit var binding: PermissionListDialogBinding

    private lateinit var adapter: PermissionListAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .apply {
                val permissionsSize = args.permissionNames.size
                setTitle(
                    getQuantityString(
                        R.plurals.file_properties_apk_requested_permissions_positive_format,
                        permissionsSize, permissionsSize
                    )
                )

                binding = PermissionListDialogBinding.inflate(context.layoutInflater)
                binding.recyclerView.layoutManager = LinearLayoutManager(context)
                adapter = PermissionListAdapter()
                binding.recyclerView.adapter = adapter
                setView(binding.root)

                viewModel.permissionListLiveData.observe(this@PermissionListDialogFragment) {
                    onPermissionListChanged(it)
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()

    private fun onPermissionListChanged(stateful: Stateful<List<PermissionItem>>) {
        when (stateful) {
            is Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.emptyView.fadeOutUnsafe()
                adapter.clear()
            }
            is Failure -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = stateful.throwable.toString()
                binding.emptyView.fadeOutUnsafe()
                adapter.clear()
            }
            is Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.emptyView.fadeToVisibilityUnsafe(stateful.value.isEmpty())
                adapter.replace(stateful.value)
            }
        }
    }

    companion object {
        fun show(permissionNames: Array<String>, fragment: Fragment) {
            PermissionListDialogFragment().putArgs(Args(permissionNames)).show(fragment)
        }
    }

    @Parcelize
    class Args(val permissionNames: Array<String>) : ParcelableArgs
}
