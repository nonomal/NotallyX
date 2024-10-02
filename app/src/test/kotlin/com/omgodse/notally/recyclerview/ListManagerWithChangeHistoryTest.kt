package com.omgodse.notally.recyclerview

import com.omgodse.notally.preferences.ListItemSorting
import com.omgodse.notally.test.assertChecked
import com.omgodse.notally.test.assertOrder
import com.omgodse.notally.test.createListItem
import com.omgodse.notally.test.mockPreferences
import org.junit.Test

class ListManagerWithChangeHistoryTest : ListManagerTestBase() {

    @Test
    fun `undo and redo moves`() {
        mockPreferences(preferences)
        listManager.move(0, 4)
        listManager.move(2, 3)
        listManager.move(4, 1)
        listManager.move(0, 5)
        listManager.move(5, 0)
        listManager.move(3, 4)
        listManager.move(1, 5)
        val bodiesAfterMove = items.map { it.body }.toTypedArray()

        while (changeHistory.canUndo()) {
            changeHistory.undo()
        }
        items.assertOrder("A", "B", "C", "D", "E", "F")
        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        items.assertOrder(*bodiesAfterMove)
    }

    @Test
    fun `undo and redo changeChecked`() {
        mockPreferences(preferences)
        listManager.changeChecked(0, true)
        listManager.changeChecked(3, true)
        listManager.changeChecked(0, false)
        listManager.changeChecked(4, true)
        listManager.changeChecked(1, false)
        listManager.changeChecked(2, true)
        val checkedValues = items.map { it.checked }.toBooleanArray()

        while (changeHistory.canUndo()) {
            changeHistory.undo()
        }
        items.assertChecked(false, false, false, false, false, false)
        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        items.assertChecked(*checkedValues)
    }

    @Test
    fun `undo and redo changeChecked if auto-sort enabled`() {
        mockPreferences(preferences, ListItemSorting.autoSortByChecked)
        listManager.changeChecked(0, true)
        listManager.changeChecked(3, true)
        listManager.changeChecked(0, false)
        listManager.changeChecked(4, true)
        listManager.changeChecked(1, false)
        listManager.changeChecked(2, true)
        val bodiesAfterMove = items.map { it.body }.toTypedArray()
        val checkedValues = items.map { it.checked }.toBooleanArray()

        while (changeHistory.canUndo()) {
            changeHistory.undo()
        }
        items.assertChecked(false, false, false, false, false, false)
        items.assertOrder("A", "B", "C", "D", "E", "F")
        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        items.assertChecked(*checkedValues)
        items.assertOrder(*bodiesAfterMove)
    }

    @Test
    fun `undo and redo changeIsChild`() {
        mockPreferences(preferences)
        listManager.changeIsChild(1, true)
        listManager.changeIsChild(2, true)
        listManager.changeIsChild(4, true)
        listManager.changeIsChild(1, false)
        listManager.changeIsChild(3, true)
        listManager.changeIsChild(4, false)
        listManager.changeIsChild(4, true)
        // Afterwards: B has children C,D,E

        while (changeHistory.canUndo()) {
            changeHistory.undo()
        }
        listOf("A", "B", "C", "D", "E", "F").forEach { it.assertChildren() }
        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        "A".assertChildren()
        "B".assertChildren("C", "D", "E")
        "F".assertChildren()
    }

    @Test
    fun `undo and redo add parents with children`() {
        mockPreferences(preferences)
        val child1 = createListItem("Child1", isChild = true)
        val child2 = createListItem("Child2", isChild = true)
        val child3 = createListItem("Child3", isChild = true)
        val child4 = createListItem("Child4", isChild = true)
        listManager.add(0, createListItem("Parent1", children = mutableListOf(child1)))
        listManager.add(4, createListItem("Parent2"))
        listManager.add(0, createListItem("Parent3"))
        listManager.add(3, createListItem("Parent4", children = mutableListOf(child2)))
        listManager.add(item = createListItem("Parent5"))
        listManager.add(
            items.lastIndex,
            createListItem("Parent6", children = mutableListOf(child3, child4)),
        )
        val bodiesAfterAdd = items.map { it.body }.toTypedArray()

        while (changeHistory.canUndo()) {
            changeHistory.undo()
        }
        items.assertOrder("A", "B", "C", "D", "E", "F")
        listOf("A", "B", "C", "D", "E", "F").forEach { it.assertChildren() }
        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        items.assertOrder(*bodiesAfterAdd)
        "Parent1".assertChildren("Child1")
        "Parent2".assertChildren()
        "Parent3".assertChildren()
        "Parent4".assertChildren("Child2")
        "Parent5".assertChildren()
        "Parent6".assertChildren("Child3", "Child4")
        listOf("A", "B", "C", "D", "E", "F").forEach { it.assertChildren() }
    }

    @Test
    fun `undo and redo delete parents with children`() {
        mockPreferences(preferences)
        listManager.changeIsChild(1, true)
        listManager.changeIsChild(3, true)
        listManager.changeIsChild(4, true)
        changeHistory.reset()
        listManager.delete(0, true)
        listManager.delete(items.lastIndex, true)
        listManager.delete(0, true)
        items.assertSize(0)

        while (changeHistory.canUndo()) {
            changeHistory.undo()
        }
        items.assertOrder("A", "B", "C", "D", "E", "F")
        "A".assertChildren("B")
        "C".assertChildren("D", "E")
        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        items.assertSize(0)
    }

    @Test
    fun `undo and redo check all with auto-sort enabled`() {
        mockPreferences(preferences, ListItemSorting.autoSortByChecked)
        listManager.changeIsChild(3, true)
        listManager.changeChecked(2, true)
        listManager.changeChecked(0, true)
        listManager.changeCheckedForAll(true)

        changeHistory.undo()
        items.assertOrder("B", "E", "F", "A", "C", "D")
        items.assertChecked(false, false, false, true, true, true)

        changeHistory.redo()
        items.assertOrder("A", "B", "C", "D", "E", "F")
        items.assertChecked(true, true, true, true, true, true)
    }

    @Test
    fun `undo and redo uncheck all with auto-sort enabled`() {
        mockPreferences(preferences, ListItemSorting.autoSortByChecked)
        listManager.changeIsChild(3, true)
        listManager.changeChecked(2, true)
        listManager.changeChecked(0, true)
        listManager.changeCheckedForAll(false)

        changeHistory.undo()
        items.assertOrder("B", "E", "F", "A", "C", "D")
        items.assertChecked(false, false, false, true, true, true)

        changeHistory.redo()
        items.assertOrder("A", "B", "C", "D", "E", "F")
        items.assertChecked(false, false, false, false, false, false)
    }

    @Test
    fun `undo and redo delete checked with auto-sort enabled`() {
        mockPreferences(preferences, ListItemSorting.autoSortByChecked)
        listManager.changeIsChild(3, true)
        listManager.changeChecked(2, true)
        listManager.changeChecked(0, true)
        listManager.deleteCheckedItems()

        changeHistory.undo()
        items.assertOrder("B", "E", "F", "A", "C", "D")
        items.assertChecked(false, false, false, true, true, true)

        changeHistory.redo()
        items.assertOrder("B", "E", "F")
        items.assertChecked(false, false, false)
    }

    @Test
    fun `undo and redo various changes with auto-sort enabled`() {
        mockPreferences(preferences, ListItemSorting.autoSortByChecked)
        listManager.changeIsChild(1, true)
        listManager.changeIsChild(3, true)
        listManager.changeIsChild(4, true)
        listManager.changeChecked(0, true)
        listManager.changeChecked(3, true)
        listManager.changeChecked(0, false)
        listManager.delete(0, true)
        listManager.addWithChildren(0, "Parent", "Child1")
        listManager.delete(4, true)
        listManager.changeIsChild(2, true)
        listManager.changeIsChild(1, false)
        listManager.addWithChildren(3, "Parent4", "Child2", "Child3")
        listManager.changeCheckedForAll(true)
        //        listManager.deleteCheckedItems()
        //        changeHistory.undo()
        listManager.changeChecked(4, false)
        listManager.delete(0, true)
        listManager.addWithChildren(6, "Parent6", "Child4")
        //        listManager.changeCheckedForAll(false)
        //        listManager.deleteCheckedItems()
        val bodiesAfterAdd = items.map { it.body }.toTypedArray()
        val checkedValues = items.map { it.checked }.toBooleanArray()
        items.assertOrder(*bodiesAfterAdd)
        items.assertChecked(*checkedValues)
        "Parent6".assertChildren("Child4")
        "Parent4".assertChildren("Child2", "Child3")

        while (changeHistory.canUndo()) {
            println(changeHistory.lookUp().toString())
            changeHistory.undo()
        }
        items.assertOrder("A", "B", "C", "D", "E", "F")
        listOf("A", "B", "C", "D", "E", "F").forEach { it.assertChildren() }
        items.assertChecked(false, false, false, false, false, false)

        while (changeHistory.canRedo()) {
            changeHistory.redo()
        }
        items.assertOrder(*bodiesAfterAdd)
        items.assertChecked(*checkedValues)
        "Parent6".assertChildren("Child4")
        "Parent4".assertChildren("Child2", "Child3")
    }

    private fun ListManager.addWithChildren(
        position: Int,
        parentBody: String,
        vararg childrenBodies: String,
    ) {
        this.add(
            position,
            createListItem(
                body = parentBody,
                children =
                    childrenBodies.map { createListItem(body = it, isChild = true) }.toMutableList(),
            ),
        )
    }
}
