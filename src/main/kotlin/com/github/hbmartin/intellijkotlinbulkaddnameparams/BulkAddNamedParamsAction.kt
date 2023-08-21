package com.github.hbmartin.intellijkotlinbulkaddnameparams

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState.defaultModalityState
import com.intellij.openapi.command.executeCommand
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.intentions.AddNamesToCallArgumentsIntention
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType

private const val CHOP_ARGS_AFTER_LENGTH = 80

class BulkAddNamedParamsAction : AnAction("Bulk Add Named Params Action") {
    private val addNames = AddNamesToCallArgumentsIntention()

    // Looks like this is getting moved in 231, revisit with subsequent IJ releases
    // https://github.com/JetBrains/intellij-community/commit/6063c6f834a9f3baab1270ddfbb7e4f6a38557ba#diff-4eade27e93683029ca59f5efcd060c539dde97a6de381106fd33d5e8d4b0f1f4
    private val chopArguments =
        org.jetbrains.kotlin.idea.intentions.ChopArgumentListIntention()

    override fun actionPerformed(anActionEvent: AnActionEvent) {
        val (editor, psiFile) = anActionEvent.getDetails()
        if (!(editor to psiFile).isEligible()) {
            return
        }
        val element = editor?.caretModel?.offset?.let { psiFile?.findElementAt(it) } ?: return

        ProgressManager.getInstance().let { progressManager ->
            progressManager.runProcessWithProgressSynchronously(
                { element.findParentAndWriteNames(editor, progressManager.progressIndicator) },
                "Finding usages and adding name labels",
                false,
                element.project,
            )
        }
    }

    private fun PsiElement.findParentAndWriteNames(editor: Editor?, indicator: ProgressIndicator) {
        val app = ApplicationManager.getApplication()
        val elementsReferencingThisElement = app.runReadAction<List<PsiElement>> {
            val parent = this.findFirstEligibleParent()
            val elementsToSearchFor = if (parent is PsiFile) {
                parent.children.filter { it is KtNamedFunction || it is KtClass }
            } else {
                listOf(parent)
            }

            val enumEntries = elementsToSearchFor.mapNotNull { it as? KtClass }.flatMap {
                it.body?.getChildrenOfType<KtEnumEntry>()?.toList().orEmpty()
            }

            return@runReadAction elementsToSearchFor.map { searchElement ->
                @Suppress("AvoidMutableCollections")
                ReferencesSearch.search(searchElement).findAll().map { it.element.parent }
            }.flatten() + enumEntries
        }

        app.invokeLater(
            {
                executeCommand(project = project) {
                    app.runWriteAction {
                        elementsReferencingThisElement.forEach { psiElement ->
                            (psiElement as? KtCallElement)?.run {
                                indicator.text = "Updating " +
                                    "${this.getCallNameExpression()?.getReferencedName()} " +
                                    "in ${this.containingFile.name}"
                                this.writeReferenceNames(editor)
                            }
                            (psiElement as? KtEnumEntry)?.run {
                                indicator.text = "Updating ${this.name} in ${this.containingFile.name}"
                                this.findDescendantOfType<KtSuperTypeCallEntry>()?.writeReferenceNames(editor)
                            }
                        }
                    }
                }
            },
            defaultModalityState(),
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getDetails().isEligible()
    }

    private fun KtCallElement.writeReferenceNames(editor: Editor?) {
        addNames.applicabilityRange(this)?.let { _ ->
            addNames.applyTo(this, editor)
            this.containingFile.commitAndUnblockDocument()
            this.valueArgumentList?.let {
                if (this.textLength > CHOP_ARGS_AFTER_LENGTH) {
                    chopArguments.applyTo(it, editor)
                }
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
