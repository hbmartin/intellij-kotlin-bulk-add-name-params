package com.github.hbmartin.intellijkotlinbulkaddnameparams

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.intentions.AddNamesToCallArgumentsIntention
import org.jetbrains.kotlin.idea.intentions.ChopArgumentListIntention
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class BulkAddNamedParamsAction : AnAction("Bulk Add Named Params Action") {
    private val addNames = AddNamesToCallArgumentsIntention()
    private val chopArguments = ChopArgumentListIntention()

    override fun actionPerformed(anActionEvent: AnActionEvent) {
        val (editor, psiFile) = anActionEvent.getDetails()
        if (!(editor to psiFile).isEligible()) { return }
        val element = editor?.caretModel?.offset?.let { psiFile?.findElementAt(it) } ?: return

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                ProgressManager.getInstance().progressIndicator?.isIndeterminate = true
                element.findParentAndWriteNames(editor)
            },
            "Finding usages and adding name labels",
            false,
            element.project
        )
    }

    private fun PsiElement.findParentAndWriteNames(editor: Editor?) {
        val app = ApplicationManager.getApplication()
        val searchResultsForParentElementReferencesToThisElement = app.runReadAction<List<PsiElement>> {
            val parent = this.findFirstEligibleParent()
            val elementsToSearchFor = if (parent is PsiFile) {
                parent.children.filter { it is KtNamedFunction || it is KtClass }
            } else {
                listOf(parent)
            }
            return@runReadAction elementsToSearchFor.map { searchElement ->
                ReferencesSearch.search(searchElement).findAll().map { it.element.parent }
            }.flatten()
        }


        app.runWriteAction {
            searchResultsForParentElementReferencesToThisElement.forEach {
                (it as? KtCallElement)?.writeReferenceNames(editor)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getDetails().isEligible()
    }

    private fun KtCallElement.writeReferenceNames(editor: Editor?) {
        addNames.applicabilityRange(this)?.let { _ ->
            addNames.applyTo(this, editor)
            this.containingFile.commitAndUnblockDocument()
            this.valueArgumentList?.let {
                chopArguments.applyTo(it, editor)
                this.containingFile.commitAndUnblockDocument()
            }
        }
    }
}

private fun PsiElement.findFirstEligibleParent(): PsiElement {
    return if (parent is KtNamedFunction || parent is KtClass || parent is KtFile) {
        parent
    } else {
        parent.findFirstEligibleParent()
    }
}

private fun Pair<Editor?, PsiFile?>.isEligible(): Boolean =
    first != null && second != null && second?.fileType is KotlinFileType

private fun AnActionEvent.getDetails(): Pair<Editor?, PsiFile?> =
    getData(CommonDataKeys.EDITOR) to getData(CommonDataKeys.PSI_FILE)
