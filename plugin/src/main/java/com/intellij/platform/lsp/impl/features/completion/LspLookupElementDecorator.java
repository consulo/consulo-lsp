package com.intellij.platform.lsp.impl.features.completion;

import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import consulo.application.progress.ProgressManager;
import consulo.language.editor.completion.lookup.InsertHandler;
import consulo.language.editor.completion.lookup.InsertionContext;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.LookupElementDecorator;
import consulo.language.editor.completion.lookup.LookupElementPresentation;
import consulo.language.editor.completion.lookup.LookupElementRenderer;
import org.eclipse.lsp4j.CompletionItem;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class is needed to control LSP-based {@link LookupElement}:
 * - {@link LspCompletionObject} is used as {@link LookupElement#getObject}
 * - {@link LspCompletionItemInsertHandler} controls element insertion
 * - {@link LspCompletionSupport#renderLookupElement} tunes element presentation
 */
public class LspLookupElementDecorator extends LookupElementDecorator<LookupElement> {
    private final LspCompletionObject myLspCompletionObject;

    public LspLookupElementDecorator(
        LspClientImpl lspClient,
        Semaphore requestSemaphore,
        LookupElement lookupElement,
        CompletionItem completionItem
    ) {
        super(lookupElement);
        myLspCompletionObject = new LspCompletionObject(lspClient, requestSemaphore, completionItem);
    }

    @Override
    public LspCompletionObject getObject() {
        return myLspCompletionObject;
    }

    /**
     * {@link LookupElementDecorator} has no decorator insert handler: {@link #handleInsert} calls it explicitly.
     */
    public InsertHandler<LookupElement> getDecoratorInsertHandler() {
        return LspCompletionItemInsertHandler.INSTANCE;
    }

    @Override
    public void handleInsert(InsertionContext context) {
        getDecoratorInsertHandler().handleInsert(context, this);
    }

    @Override
    public void renderElement(LookupElementPresentation presentation) {
        LspInstantRenderer.renderLookupElement(myLspCompletionObject, presentation);
    }

    @Override
    public LookupElementRenderer<LspLookupElementDecorator> getExpensiveRenderer() {
        return LspExpensiveRenderer.INSTANCE;
    }

    private static final class LspInstantRenderer {
        private LspInstantRenderer() {
        }

        static void renderLookupElement(LspCompletionObject completionObject, LookupElementPresentation presentation) {
            if (!(completionObject.getLspClient().getDescriptor().getLspCustomization().getCompletionCustomizer()
                instanceof LspCompletionSupport completionSupport)) {
                return;
            }
            CompletionItem completionItem = completionObject.getCompletionItem();
            completionSupport.renderLookupElement(completionItem, presentation);
        }
    }

    /**
     * Expensive renderers run on a background thread in a cancellable non-blocking read action, so the resolve request and the expensive rendering are awaited there with
     * {@link ProgressManager#checkCanceled} calls.
     */
    private static final class LspExpensiveRenderer extends LookupElementRenderer<LspLookupElementDecorator> {
        private static final LspExpensiveRenderer INSTANCE = new LspExpensiveRenderer();
        private static final long POLL_MS = 10;

        @Override
        public void renderElement(LspLookupElementDecorator element, LookupElementPresentation presentation) {
            LspCompletionObject completionObject = element.myLspCompletionObject;
            if (!(completionObject.getLspClient().getDescriptor().getLspCustomization().getCompletionCustomizer()
                instanceof LspCompletionSupport completionSupport)) {
                return;
            }
            if (completionSupport.shouldResolveCompletionItem(completionObject.getCompletionItem())) {
                completionObject.resolveCompletionItem();
            }
            awaitWithCheckCanceled(completionSupport.expensiveRenderLookupElement(completionObject.getCompletionItem(), presentation));
        }

        private static void awaitWithCheckCanceled(CompletableFuture<?> future) {
            try {
                while (true) {
                    ProgressManager.checkCanceled();
                    try {
                        future.get(POLL_MS, TimeUnit.MILLISECONDS);
                        return;
                    }
                    catch (TimeoutException ignored) {
                        // keep waiting
                    }
                }
            }
            catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
            catch (CancellationException ignored) {
                // the rendering was cancelled; the instant presentation stays
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (RuntimeException e) {
                future.cancel(true);
                throw e;
            }
        }
    }
}
