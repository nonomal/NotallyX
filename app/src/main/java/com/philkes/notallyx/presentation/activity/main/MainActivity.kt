package com.philkes.notallyx.presentation.activity.main

import android.content.Intent
import android.os.Bundle
import android.transition.TransitionManager
import android.view.Menu
import android.view.Menu.CATEGORY_CONTAINER
import android.view.Menu.CATEGORY_SYSTEM
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialFade
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.data.model.toText
import com.philkes.notallyx.databinding.ActivityMainBinding
import com.philkes.notallyx.presentation.activity.LockedActivity
import com.philkes.notallyx.presentation.activity.main.fragment.DisplayLabelFragment.Companion.EXTRA_DISPLAYED_LABEL
import com.philkes.notallyx.presentation.activity.main.fragment.NotallyFragment
import com.philkes.notallyx.presentation.activity.main.fragment.SearchFragment.Companion.EXTRA_INITIAL_FOLDER
import com.philkes.notallyx.presentation.activity.note.EditListActivity
import com.philkes.notallyx.presentation.activity.note.EditNoteActivity
import com.philkes.notallyx.presentation.add
import com.philkes.notallyx.presentation.applySpans
import com.philkes.notallyx.presentation.getQuantityString
import com.philkes.notallyx.presentation.movedToResId
import com.philkes.notallyx.presentation.setCancelButton
import com.philkes.notallyx.presentation.showColorSelectDialog
import com.philkes.notallyx.presentation.view.misc.NotNullLiveData
import com.philkes.notallyx.presentation.view.misc.tristatecheckbox.TriStateCheckBox
import com.philkes.notallyx.presentation.view.misc.tristatecheckbox.setMultiChoiceTriStateItems
import com.philkes.notallyx.presentation.viewmodel.BaseNoteModel
import com.philkes.notallyx.presentation.viewmodel.ExportMimeType
import com.philkes.notallyx.utils.backup.exportNotes
import com.philkes.notallyx.utils.shareNote
import kotlinx.coroutines.launch

class MainActivity : LockedActivity<ActivityMainBinding>() {

    private lateinit var navController: NavController
    private lateinit var configuration: AppBarConfiguration
    private lateinit var exportFileActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportNotesActivityResultLauncher: ActivityResultLauncher<Intent>

    private val actionModeCancelCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                baseModel.actionMode.close(true)
            }
        }

    var getCurrentFragmentNotes: (() -> Collection<BaseNote>?)? = null

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.Toolbar)
        setupFAB()
        setupMenu()
        setupActionMode()
        setupNavigation()
        setupSearch()

        setupActivityResultLaunchers()
        onBackPressedDispatcher.addCallback(this, actionModeCancelCallback)

        val fragmentIdToLoad = intent.getIntExtra(EXTRA_FRAGMENT_TO_OPEN, -1)
        if (fragmentIdToLoad != -1) {
            val bundle = Bundle()
            navController.navigate(fragmentIdToLoad, bundle)
        }
    }

    private fun setupFAB() {
        binding.TakeNote.setOnClickListener {
            val intent = Intent(this, EditNoteActivity::class.java)
            startActivity(prepareNewNoteIntent(intent))
        }
        binding.MakeList.setOnClickListener {
            val intent = Intent(this, EditListActivity::class.java)
            startActivity(prepareNewNoteIntent(intent))
        }
    }

    private fun prepareNewNoteIntent(intent: Intent): Intent {
        return supportFragmentManager
            .findFragmentById(R.id.NavHostFragment)
            ?.childFragmentManager
            ?.fragments
            ?.firstOrNull()
            ?.let { fragment ->
                return if (fragment is NotallyFragment) {
                    fragment.prepareNewNoteIntent(intent)
                } else intent
            } ?: intent
    }

    private var labelsMenuItems: List<MenuItem> = listOf()
    private var labelsMoreMenuItem: MenuItem? = null
    private var labels: List<String> = listOf()
    private var labelsLiveData: LiveData<List<String>>? = null

    private fun setupMenu() {
        binding.NavigationView.menu.apply {
            add(0, R.id.Notes, 0, R.string.notes).setCheckable(true).setIcon(R.drawable.home)
            NotallyDatabase.getDatabase(application).observe(this@MainActivity) { database ->
                labelsLiveData?.removeObservers(this@MainActivity)
                labelsLiveData =
                    database.getLabelDao().getAll().also {
                        it.observe(this@MainActivity) { labels ->
                            this@MainActivity.labels = labels
                            setupLabelsMenuItems(labels, preferences.maxLabels.value)
                        }
                    }
            }

            add(2, R.id.Deleted, CATEGORY_SYSTEM + 1, R.string.deleted)
                .setCheckable(true)
                .setIcon(R.drawable.delete)
            add(2, R.id.Archived, CATEGORY_SYSTEM + 2, R.string.archived)
                .setCheckable(true)
                .setIcon(R.drawable.archive)
            add(3, R.id.Reminders, CATEGORY_SYSTEM + 3, R.string.reminders)
                .setCheckable(true)
                .setIcon(R.drawable.notifications)
            add(3, R.id.Settings, CATEGORY_SYSTEM + 4, R.string.settings)
                .setCheckable(true)
                .setIcon(R.drawable.settings)
        }
        baseModel.preferences.labelsHiddenInNavigation.observe(this) { hiddenLabels ->
            hideLabelsInNavigation(hiddenLabels, baseModel.preferences.maxLabels.value)
        }
        baseModel.preferences.maxLabels.observe(this) { maxLabels ->
            binding.NavigationView.menu.setupLabelsMenuItems(labels, maxLabels)
        }
    }

    private fun Menu.setupLabelsMenuItems(labels: List<String>, maxLabelsToDisplay: Int) {
        removeGroup(1)
        add(1, R.id.Labels, CATEGORY_CONTAINER + 1, R.string.labels)
            .setCheckable(true)
            .setIcon(R.drawable.label_more)
        labelsMenuItems =
            labels
                .mapIndexed { index, label ->
                    add(1, R.id.DisplayLabel, CATEGORY_CONTAINER + index + 2, label)
                        .setCheckable(true)
                        .setVisible(index < maxLabelsToDisplay)
                        .setIcon(R.drawable.label)
                        .setOnMenuItemClickListener {
                            val bundle = Bundle().apply { putString(EXTRA_DISPLAYED_LABEL, label) }
                            navController.navigate(R.id.DisplayLabel, bundle)
                            false
                        }
                }
                .toList()

        labelsMoreMenuItem =
            if (labelsMenuItems.size > maxLabelsToDisplay) {
                add(
                        1,
                        R.id.Labels,
                        CATEGORY_CONTAINER + labelsMenuItems.size + 2,
                        getString(R.string.more, labelsMenuItems.size - maxLabelsToDisplay),
                    )
                    .setCheckable(true)
                    .setIcon(R.drawable.label)
            } else null
        configuration = AppBarConfiguration(binding.NavigationView.menu, binding.DrawerLayout)
        setupActionBarWithNavController(navController, configuration)
        hideLabelsInNavigation(
            baseModel.preferences.labelsHiddenInNavigation.value,
            maxLabelsToDisplay,
        )
    }

    private fun hideLabelsInNavigation(hiddenLabels: Set<String>, maxLabelsToDisplay: Int) {
        var visibleLabels = 0
        labelsMenuItems.forEach { menuItem ->
            val visible =
                !hiddenLabels.contains(menuItem.title) && visibleLabels < maxLabelsToDisplay
            menuItem.setVisible(visible)
            if (visible) {
                visibleLabels++
            }
        }
        labelsMoreMenuItem?.setTitle(getString(R.string.more, labels.size - visibleLabels))
    }

    private fun setupActionMode() {
        binding.ActionMode.setNavigationOnClickListener { baseModel.actionMode.close(true) }

        val transition =
            MaterialFade().apply {
                secondaryAnimatorProvider = null
                excludeTarget(binding.NavHostFragment, true)
                excludeChildren(binding.NavHostFragment, true)
                excludeTarget(binding.TakeNote, true)
                excludeTarget(binding.MakeList, true)
                excludeTarget(binding.NavigationView, true)
            }

        baseModel.actionMode.enabled.observe(this) { enabled ->
            TransitionManager.beginDelayedTransition(binding.RelativeLayout, transition)
            if (enabled) {
                binding.Toolbar.visibility = View.GONE
                binding.ActionMode.visibility = View.VISIBLE
                binding.DrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            } else {
                binding.Toolbar.visibility = View.VISIBLE
                binding.ActionMode.visibility = View.GONE
                binding.DrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNDEFINED)
            }
            actionModeCancelCallback.isEnabled = enabled
        }

        val menu = binding.ActionMode.menu
        baseModel.folder.observe(this@MainActivity, ModelFolderObserver(menu, baseModel))
    }

    private fun moveNotes(folderTo: Folder) {
        val folderFrom = baseModel.actionMode.getFirstNote().folder
        val ids = baseModel.moveBaseNotes(folderTo)
        Snackbar.make(
                findViewById(R.id.DrawerLayout),
                getQuantityString(folderTo.movedToResId(), ids.size),
                Snackbar.LENGTH_SHORT,
            )
            .apply { setAction(R.string.undo) { baseModel.moveBaseNotes(ids, folderFrom) } }
            .show()
    }

    private fun share() {
        val baseNote = baseModel.actionMode.getFirstNote()
        val body =
            when (baseNote.type) {
                Type.NOTE -> baseNote.body.applySpans(baseNote.spans)
                Type.LIST -> baseNote.items.toText()
            }
        this.shareNote(baseNote.title, body)
    }

    private fun deleteForever() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_selected_notes)
            .setPositiveButton(R.string.delete) { _, _ -> baseModel.deleteSelectedBaseNotes() }
            .setCancelButton()
            .show()
    }

    private fun label() {
        val baseNotes = baseModel.actionMode.selectedNotes.values
        lifecycleScope.launch {
            val labels = baseModel.getAllLabels()
            if (labels.isNotEmpty()) {
                displaySelectLabelsDialog(labels, baseNotes)
            } else {
                baseModel.actionMode.close(true)
                navigateWithAnimation(R.id.Labels)
            }
        }
    }

    private fun displaySelectLabelsDialog(labels: Array<String>, baseNotes: Collection<BaseNote>) {
        val checkedPositions =
            labels
                .map { label ->
                    if (baseNotes.all { it.labels.contains(label) }) {
                        TriStateCheckBox.State.CHECKED
                    } else if (baseNotes.any { it.labels.contains(label) }) {
                        TriStateCheckBox.State.PARTIALLY_CHECKED
                    } else {
                        TriStateCheckBox.State.UNCHECKED
                    }
                }
                .toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.labels)
            .setCancelButton()
            .setMultiChoiceTriStateItems(this, labels, checkedPositions) { idx, state ->
                checkedPositions[idx] = state
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val checkedLabels =
                    checkedPositions.mapIndexedNotNull { index, checked ->
                        if (checked == TriStateCheckBox.State.CHECKED) {
                            labels[index]
                        } else null
                    }
                val uncheckedLabels =
                    checkedPositions.mapIndexedNotNull { index, checked ->
                        if (checked == TriStateCheckBox.State.UNCHECKED) {
                            labels[index]
                        } else null
                    }
                val updatedBaseNotesLabels =
                    baseNotes.map { baseNote ->
                        val noteLabels = baseNote.labels.toMutableList()
                        checkedLabels.forEach { checkedLabel ->
                            if (!noteLabels.contains(checkedLabel)) {
                                noteLabels.add(checkedLabel)
                            }
                        }
                        uncheckedLabels.forEach { uncheckedLabel ->
                            if (noteLabels.contains(uncheckedLabel)) {
                                noteLabels.remove(uncheckedLabel)
                            }
                        }
                        noteLabels
                    }
                baseNotes.zip(updatedBaseNotesLabels).forEach { (baseNote, updatedLabels) ->
                    baseModel.updateBaseNoteLabels(updatedLabels, baseNote.id)
                }
            }
            .show()
    }

    private fun exportSelectedNotes(mimeType: ExportMimeType) {
        exportNotes(
            mimeType,
            baseModel.actionMode.selectedNotes.values,
            exportFileActivityResultLauncher,
            exportNotesActivityResultLauncher,
        )
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        configuration = AppBarConfiguration(binding.NavigationView.menu, binding.DrawerLayout)
        setupActionBarWithNavController(navController, configuration)

        var fragmentIdToLoad: Int? = null
        binding.NavigationView.setNavigationItemSelectedListener { item ->
            fragmentIdToLoad = item.itemId
            binding.DrawerLayout.closeDrawer(GravityCompat.START)
            return@setNavigationItemSelectedListener true
        }

        binding.DrawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {

                override fun onDrawerClosed(drawerView: View) {
                    if (
                        fragmentIdToLoad != null &&
                            navController.currentDestination?.id != fragmentIdToLoad
                    ) {
                        navigateWithAnimation(requireNotNull(fragmentIdToLoad))
                    }
                }
            }
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->
            fragmentIdToLoad = destination.id
            binding.NavigationView.setCheckedItem(destination.id)
            if (destination.id != R.id.Search) {
                binding.EnterSearchKeyword.apply {
                    setText("")
                    clearFocus()
                }
                when (destination.id) {
                    R.id.Notes,
                    R.id.Deleted,
                    R.id.Archived -> binding.EnterSearchKeywordLayout.visibility = VISIBLE
                    else -> binding.EnterSearchKeywordLayout.visibility = GONE
                }
            }
            handleDestinationChange(destination)
        }
    }

    private fun handleDestinationChange(destination: NavDestination) {
        when (destination.id) {
            R.id.Notes,
            R.id.DisplayLabel -> {
                binding.TakeNote.show()
                binding.MakeList.show()
            }
            else -> {
                binding.TakeNote.hide()
                binding.MakeList.hide()
            }
        }

        val inputManager = ContextCompat.getSystemService(this, InputMethodManager::class.java)
        if (destination.id == R.id.Search) {
            binding.EnterSearchKeyword.apply {
                //                setText("")
                visibility = View.VISIBLE
                requestFocus()
                inputManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            binding.EnterSearchKeyword.apply {
                //                visibility = View.GONE
                inputManager?.hideSoftInputFromWindow(this.windowToken, 0)
            }
        }
    }

    private fun navigateWithAnimation(id: Int) {
        val options = navOptions {
            launchSingleTop = true
            anim {
                exit = androidx.navigation.ui.R.anim.nav_default_exit_anim
                enter = androidx.navigation.ui.R.anim.nav_default_enter_anim
                popExit = androidx.navigation.ui.R.anim.nav_default_pop_exit_anim
                popEnter = androidx.navigation.ui.R.anim.nav_default_pop_enter_anim
            }
            popUpTo(navController.graph.startDestination) { inclusive = false }
        }
        navController.navigate(id, null, options)
    }

    private fun setupSearch() {
        binding.EnterSearchKeyword.apply {
            setText(baseModel.keyword)
            doAfterTextChanged { text ->
                baseModel.keyword = requireNotNull(text).trim().toString()
                if (
                    baseModel.keyword.isNotEmpty() &&
                        navController.currentDestination?.id != R.id.Search
                ) {
                    val bundle =
                        Bundle().apply {
                            putSerializable(EXTRA_INITIAL_FOLDER, baseModel.folder.value)
                        }
                    navController.navigate(R.id.Search, bundle)
                }
            }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && navController.currentDestination?.id != R.id.Search) {
                    val bundle =
                        Bundle().apply {
                            putSerializable(EXTRA_INITIAL_FOLDER, baseModel.folder.value)
                        }
                    navController.navigate(R.id.Search, bundle)
                }
            }
        }
    }

    private fun setupActivityResultLaunchers() {
        exportFileActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri -> baseModel.exportSelectedFileToUri(uri) }
                }
            }
        exportNotesActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri -> baseModel.exportSelectedNotesToFolder(uri) }
                }
            }
    }

    private inner class ModelFolderObserver(
        private val menu: Menu,
        private val model: BaseNoteModel,
    ) : Observer<Folder> {
        override fun onChanged(value: Folder) {
            menu.clear()
            model.actionMode.count.removeObservers(this@MainActivity)

            menu.add(
                R.string.select_all,
                R.drawable.select_all,
                showAsAction = MenuItem.SHOW_AS_ACTION_ALWAYS,
            ) {
                getCurrentFragmentNotes?.invoke()?.let { model.actionMode.add(it) }
            }
            when (value) {
                Folder.NOTES -> {
                    val pinned = menu.addPinned(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.addLabels(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.addDelete(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(R.string.archive, R.drawable.archive) { moveNotes(Folder.ARCHIVED) }
                    menu.addChangeColor()
                    val share = menu.addShare()
                    menu.addExportMenu()
                    model.actionMode.count.observeCountAndPinned(this@MainActivity, share, pinned)
                }

                Folder.ARCHIVED -> {
                    menu.add(
                        R.string.unarchive,
                        R.drawable.unarchive,
                        MenuItem.SHOW_AS_ACTION_ALWAYS,
                    ) {
                        moveNotes(Folder.NOTES)
                    }
                    menu.addDelete(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.addExportMenu(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    val pinned = menu.addPinned()
                    menu.addLabels()
                    menu.addChangeColor()
                    val share = menu.addShare()
                    model.actionMode.count.observeCountAndPinned(this@MainActivity, share, pinned)
                }

                Folder.DELETED -> {
                    menu.add(R.string.restore, R.drawable.restore, MenuItem.SHOW_AS_ACTION_ALWAYS) {
                        moveNotes(Folder.NOTES)
                    }
                    menu.add(
                        R.string.delete_forever,
                        R.drawable.delete,
                        MenuItem.SHOW_AS_ACTION_ALWAYS,
                    ) {
                        deleteForever()
                    }
                    menu.addExportMenu()
                    menu.addChangeColor()
                    val share = menu.add(R.string.share, R.drawable.share) { share() }
                    model.actionMode.count.observeCount(this@MainActivity, share)
                }
            }
        }

        private fun Menu.addPinned(showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM): MenuItem {
            return add(R.string.pin, R.drawable.pin, showAsAction) {}
        }

        private fun Menu.addLabels(showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM): MenuItem {
            return add(R.string.labels, R.drawable.label, showAsAction) { label() }
        }

        private fun Menu.addChangeColor(
            showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM
        ): MenuItem {
            return add(R.string.change_color, R.drawable.change_color, showAsAction) {
                showColorSelectDialog(null) { selectedColor -> model.colorBaseNote(selectedColor) }
            }
        }

        private fun Menu.addDelete(showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM): MenuItem {
            return add(R.string.delete, R.drawable.delete, showAsAction) {
                moveNotes(Folder.DELETED)
            }
        }

        private fun Menu.addShare(showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM): MenuItem {
            return add(R.string.share, R.drawable.share, showAsAction) { share() }
        }

        private fun Menu.addExportMenu(
            showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM
        ): MenuItem {
            return addSubMenu(R.string.export)
                .apply {
                    setIcon(R.drawable.export)
                    item.setShowAsAction(showAsAction)
                    ExportMimeType.entries.forEach {
                        add(it.name).onClick { exportSelectedNotes(it) }
                    }
                }
                .item
        }

        fun MenuItem.onClick(function: () -> Unit) {
            setOnMenuItemClickListener {
                function()
                return@setOnMenuItemClickListener false
            }
        }

        private fun NotNullLiveData<Int>.observeCount(
            lifecycleOwner: LifecycleOwner,
            share: MenuItem,
            onCountChange: ((Int) -> Unit)? = null,
        ) {
            observe(lifecycleOwner) { count ->
                binding.ActionMode.title = count.toString()
                onCountChange?.invoke(count)
                share.setVisible(count == 1)
            }
        }

        private fun NotNullLiveData<Int>.observeCountAndPinned(
            lifecycleOwner: LifecycleOwner,
            share: MenuItem,
            pinned: MenuItem,
        ) {
            observeCount(lifecycleOwner, share) {
                val baseNotes = model.actionMode.selectedNotes.values
                if (baseNotes.any { !it.pinned }) {
                    pinned.setTitle(R.string.pin).setIcon(R.drawable.pin).onClick {
                        model.pinBaseNotes(true)
                    }
                } else {
                    pinned.setTitle(R.string.unpin).setIcon(R.drawable.unpin).onClick {
                        model.pinBaseNotes(false)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FRAGMENT_TO_OPEN = "notallyx.intent.extra.FRAGMENT_TO_OPEN"
    }
}
