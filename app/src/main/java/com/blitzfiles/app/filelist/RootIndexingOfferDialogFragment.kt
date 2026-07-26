/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import com.blitzfiles.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-shot first-launch offer for indexing the Linux filesystem root.
 */
class RootIndexingOfferDialogFragment : AppCompatDialogFragment() {
    private val listener: Listener
        get() = requireActivity() as Listener

    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultDelivered = savedInstanceState?.getBoolean(STATE_RESULT_DELIVERED) ?: false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.initial_indexing_root_offer_title)
            .setMessage(R.string.initial_indexing_root_offer_message)
            .setPositiveButton(R.string.initial_indexing_root_offer_accept) { _, _ ->
                deliverResult(accepted = true)
            }
            .setNegativeButton(R.string.initial_indexing_root_offer_decline) { _, _ ->
                deliverResult(accepted = false)
            }
            .create()

    override fun onCancel(dialog: DialogInterface) {
        deliverResult(accepted = false)
        super.onCancel(dialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RESULT_DELIVERED, resultDelivered)
        super.onSaveInstanceState(outState)
    }

    private fun deliverResult(accepted: Boolean) {
        if (resultDelivered) {
            return
        }
        resultDelivered = true
        listener.onRootIndexingOfferResult(accepted)
    }

    interface Listener {
        fun onRootIndexingOfferResult(accepted: Boolean)
    }

    companion object {
        const val TAG = "root_indexing_offer"

        private const val STATE_RESULT_DELIVERED = "result_delivered"
    }
}
