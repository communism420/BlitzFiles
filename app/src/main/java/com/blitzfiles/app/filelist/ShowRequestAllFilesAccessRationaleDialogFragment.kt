/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.blitzfiles.app.R

class ShowRequestAllFilesAccessRationaleDialogFragment : AppCompatDialogFragment() {
    private val listener: Listener
        get() = requireParentFragment() as Listener

    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultDelivered = savedInstanceState?.getBoolean(STATE_RESULT_DELIVERED) ?: false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.all_files_access_rationale_title)
            .setMessage(R.string.all_files_access_rationale_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                deliverResult(shouldRequest = true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                deliverResult(shouldRequest = false)
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        deliverResult(shouldRequest = false)
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (parentFragment as? Listener)?.onShowRequestAllFilesAccessRationaleDismissed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RESULT_DELIVERED, resultDelivered)
        super.onSaveInstanceState(outState)
    }

    private fun deliverResult(shouldRequest: Boolean) {
        if (resultDelivered) {
            return
        }
        resultDelivered = true
        listener.onShowRequestAllFilesAccessRationaleResult(shouldRequest)
    }

    companion object {
        fun show(fragment: Fragment) {
            val fragmentManager = fragment.childFragmentManager
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                ShowRequestAllFilesAccessRationaleDialogFragment()
                    .show(fragmentManager, TAG)
            }
        }

        const val TAG = "request_all_files_access_rationale"

        private const val STATE_RESULT_DELIVERED = "result_delivered"
    }

    interface Listener {
        fun onShowRequestAllFilesAccessRationaleResult(shouldRequest: Boolean)
        fun onShowRequestAllFilesAccessRationaleDismissed()
    }
}
