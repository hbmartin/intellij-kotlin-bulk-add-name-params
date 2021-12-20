package com.github.hbmartin.intellijkotlinbulkaddnameparams

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinFileType
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
        val actionParent = element.findFirstEligibleParent()

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

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getDetails().isEligible()
    }

    // TODO: all writes for PsiFile in one command, throws otherwise
    private fun PsiElement.writeReferenceNames(editor: Editor?) {
        ReferencesSearch.search(this).findAll().forEach { reference ->
            val parent = reference.element.parent
            if (parent is KtCallElement) {
                addNames.applicabilityRange(parent)?.let { _ ->
                    WriteCommandAction.runWriteCommandAction(parent.project) {
                        addNames.applyTo(parent, editor)
                        parent.valueArgumentList?.let {
                            chopArguments.applyTo(it, editor)
                        }
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
