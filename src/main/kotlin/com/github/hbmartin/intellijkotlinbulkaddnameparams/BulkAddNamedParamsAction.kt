package com.github.hbmartin.intellijkotlinbulkaddnameparams

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
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
        val actionParent = ApplicationManager.getApplication().runReadAction<PsiElement> {
            return@runReadAction this.findFirstEligibleParent()
        }

        WriteCommandAction.runWriteCommandAction(this.project) {
            if (actionParent is PsiFile) {
                actionParent.children.forEach {
                    if (it is KtNamedFunction || it is KtClass) {
                        it.writeReferenceNames(editor)
                    }
                }
            } else {
                actionParent.writeReferenceNames(editor)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getDetails().isEligible()
    }

    private fun PsiElement.writeReferenceNames(editor: Editor?) {
        ReferencesSearch.search(this).findAll().forEach { reference ->
            val parent = reference.element.parent
            if (parent is KtCallElement) {
                addNames.applicabilityRange(parent)?.let { _ ->
                    addNames.applyTo(parent, editor)
                    parent.containingFile.commitAndUnblockDocument()
                    parent.valueArgumentList?.let {
                        chopArguments.applyTo(it, editor)
                        parent.containingFile.commitAndUnblockDocument()
                    }
                }
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
