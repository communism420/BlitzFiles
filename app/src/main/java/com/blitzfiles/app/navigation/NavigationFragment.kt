/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.navigation

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import java8.nio.file.Path
import com.blitzfiles.app.databinding.NavigationFragmentBinding
import com.blitzfiles.app.util.startActivitySafe

class NavigationFragment : Fragment(), NavigationItem.Listener {
    private lateinit var binding: NavigationFragmentBinding

    private lateinit var adapter: NavigationListAdapter

    lateinit var listener: Listener

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        NavigationFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        binding.recyclerView.setHasFixedSize(true)
        // TODO: Needed?
        //binding.recyclerView.setItemAnimator(new NoChangeAnimationItemAnimator())
        val context = requireContext()
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = NavigationListAdapter(this, context)
        binding.recyclerView.adapter = adapter

        val viewLifecycleOwner = viewLifecycleOwner
        NavigationItemListLiveData.observe(viewLifecycleOwner) { onNavigationItemsChanged(it) }
        listener.observeCurrentPath(viewLifecycleOwner) { onCurrentPathChanged(it) }
        listener.observeFileManagerPlusHomeVisibility(viewLifecycleOwner) {
            adapter.notifyCheckedChanged()
        }
    }

    private fun onNavigationItemsChanged(navigationItems: List<NavigationItem?>) {
        val visibleItems = mutableListOf<NavigationItem?>()
        for (item in navigationItems) {
            if (item == null) {
                if (visibleItems.isNotEmpty() && visibleItems.last() != null) {
                    visibleItems += null
                }
            } else if (item.isVisible(this)) {
                visibleItems += item
            }
        }
        if (visibleItems.lastOrNull() == null) {
            visibleItems.removeLastOrNull()
        }
        adapter.replace(visibleItems)
    }

    private fun onCurrentPathChanged(path: Path) {
        adapter.notifyCheckedChanged()
    }

    override val currentPath: Path
        get() = listener.currentPath

    override val isFileManagerPlusHomeAvailable: Boolean
        get() = listener.isFileManagerPlusHomeAvailable

    override val isFileManagerPlusHomeVisible: Boolean
        get() = listener.isFileManagerPlusHomeVisible

    override fun navigateHome() {
        listener.navigateHome()
    }

    override fun navigateTo(path: Path) {
        listener.navigateTo(path)
    }

    override fun navigateToRoot(path: Path) {
        listener.navigateToRoot(path)
    }

    override fun launchIntent(intent: Intent) {
        startActivitySafe(intent)
    }

    override fun closeNavigationDrawer() {
        listener.closeNavigationDrawer()
    }

    interface Listener {
        val currentPath: Path
        val isFileManagerPlusHomeAvailable: Boolean
        val isFileManagerPlusHomeVisible: Boolean
        fun navigateHome()
        fun navigateTo(path: Path)
        fun navigateToRoot(path: Path)
        fun navigateToDefaultRoot()
        fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit)
        fun observeFileManagerPlusHomeVisibility(
            owner: LifecycleOwner,
            observer: (Boolean) -> Unit
        )
        fun closeNavigationDrawer()
    }
}
