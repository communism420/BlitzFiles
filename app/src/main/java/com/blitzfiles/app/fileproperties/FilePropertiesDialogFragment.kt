/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.fileproperties

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import com.blitzfiles.app.R
import com.blitzfiles.app.databinding.FilePropertiesDialogBinding
import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.filelist.name
import com.blitzfiles.app.fileproperties.apk.FilePropertiesApkTabFragment
import com.blitzfiles.app.fileproperties.audio.FilePropertiesAudioTabFragment
import com.blitzfiles.app.fileproperties.basic.FilePropertiesBasicTabFragment
import com.blitzfiles.app.fileproperties.checksum.FilePropertiesChecksumTabFragment
import com.blitzfiles.app.fileproperties.image.FilePropertiesImageTabFragment
import com.blitzfiles.app.fileproperties.permission.FilePropertiesPermissionTabFragment
import com.blitzfiles.app.fileproperties.video.FilePropertiesVideoTabFragment
import com.blitzfiles.app.ui.TabFragmentPagerAdapter
import com.blitzfiles.app.util.ParcelableArgs
import com.blitzfiles.app.util.args
import com.blitzfiles.app.util.layoutInflater
import com.blitzfiles.app.util.putArgs
import com.blitzfiles.app.util.show
import com.blitzfiles.app.util.viewModels

class FilePropertiesDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { FilePropertiesFileViewModel(args.file) } }

    private lateinit var binding: FilePropertiesDialogBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(getString(R.string.file_properties_title_format, args.file.name))
            .apply {
                binding = FilePropertiesDialogBinding.inflate(context.layoutInflater)
                setView(binding.root)
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()

    // HACK: Work around child FragmentManager requiring a view.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = binding.root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // Initialize the shared file view model before child fragments are created.
        viewModel.fileLiveData
        val tabs = mutableListOf<Pair<Int, () -> Fragment>>()
            .apply {
                add(R.string.file_properties_basic to { FilePropertiesBasicTabFragment() })
                if (FilePropertiesPermissionTabFragment.isAvailable(args.file)) {
                    add(
                        R.string.file_properties_permission
                            to { FilePropertiesPermissionTabFragment() }
                    )
                }
                if (FilePropertiesImageTabFragment.isAvailable(args.file)) {
                    add(
                        R.string.file_properties_image to {
                            FilePropertiesImageTabFragment().putArgs(
                                FilePropertiesImageTabFragment.Args(
                                    args.file.path, args.file.mimeType
                                )
                            )
                        }
                    )
                }
                if (FilePropertiesAudioTabFragment.isAvailable(args.file)) {
                    add(
                        R.string.file_properties_audio to {
                            FilePropertiesAudioTabFragment().putArgs(
                                FilePropertiesAudioTabFragment.Args(args.file.path)
                            )
                        }
                    )
                }
                if (FilePropertiesVideoTabFragment.isAvailable(args.file)) {
                    add(
                        R.string.file_properties_video to {
                            FilePropertiesVideoTabFragment().putArgs(
                                FilePropertiesVideoTabFragment.Args(args.file.path)
                            )
                        }
                    )
                }
                if (FilePropertiesApkTabFragment.isAvailable(args.file)) {
                    add(
                        R.string.file_properties_apk to {
                            FilePropertiesApkTabFragment().putArgs(
                                FilePropertiesApkTabFragment.Args(args.file.path)
                            )
                        }
                    )
                }
                if (FilePropertiesChecksumTabFragment.isAvailable(args.file)) {
                    add(
                        R.string.file_properties_checksum to {
                            FilePropertiesChecksumTabFragment().putArgs(
                                FilePropertiesChecksumTabFragment.Args(args.file.path)
                            )
                        }
                    )
                }
            }
            .map { getString(it.first) to it.second }
            .toTypedArray()
        val tabAdapter = TabFragmentPagerAdapter(childFragmentManager, *tabs)
        binding.viewPager.offscreenPageLimit = tabAdapter.count - 1
        binding.viewPager.adapter = tabAdapter
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }

    override fun onStart() {
        super.onStart()

        // AlertDialog (its AlertController) adds FLAG_ALT_FOCUSABLE_IM when the initial custom
        // view doesn't have any view that returns true for onCheckIsTextEditor().
        requireDialog().window!!.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            FilePropertiesDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }

    @Parcelize
    class Args(val file: FileItem): ParcelableArgs
}
